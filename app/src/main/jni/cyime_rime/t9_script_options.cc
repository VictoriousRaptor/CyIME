#include "t9_script_options.h"
#include "t9_cylm_sentence.h"

#include <algorithm>
#include <cstdlib>
#include <fstream>
#include <map>
#include <mutex>

#include <gram_db.h>
#include <octagram.h>
#include <rime/config.h>
#include <rime/context.h>
#include <rime/dict/dictionary.h>
#include <rime/gear/grammar.h>
#include <rime/resource.h>
#include <rime/service.h>
#include <t9_ngram_model.h>
#include <t9_sentence_scorer.h>

#ifdef __ANDROID__
#include <android/log.h>
#include <malloc.h>
#include <sys/system_properties.h>
#endif

namespace rime {

T9ScriptOptions::T9ScriptOptions() = default;
T9ScriptOptions::~T9ScriptOptions() = default;

void T9ScriptOptions::LoadT9Options(Config* config, Context* context) {
  // Text committed before the composition, set by the app (RimeEngine.setT9LeftContext);
  // `context` is the engine context parameter added by PinyinSyllableSelection.cmake.
  if (context) t9_left_context_ = context->get_property("cyime_left_context");
  if (context) t9_neighbors_ = context->get_property("cyime_neighbors");
  if (!config) return;
  config->GetBool("t9/joint_decoder", &t9_joint_);
  config->GetBool("t9/decoded_cache", &t9_decoded_cache_);
  config->GetBool("t9/sentence_rescore", &t9_sentence_rescore_);
  {
    // CyLM fills the sentence slot when the schema names its files (cylm/lexicon, cylm/model, optional
    // cylm/model2 and cylm/params). Nothing names them by default, so nothing is loaded.
    // Optional cylm/reranker + cylm/vocab: the ONNX reranker runs inside the same keystroke.
    // Optional cylm/prefer: "P<TAB>D" rows, P moves above its homophone D after ranking (cylm_prefer).
    // Optional cylm/adapt: the user's own trigram counts (cylm_adapt), only in packages for his own devices.
    string lexicon, model, model2, reranker, vocab, params, prefer, adapt, gate, gate_vocab;
    if (config->GetString("cylm/lexicon", &lexicon) && config->GetString("cylm/model", &model) &&
        !lexicon.empty() && !model.empty() && T9CylmEnabled()) {
      config->GetString("cylm/model2", &model2);
      config->GetString("cylm/reranker", &reranker);
      config->GetString("cylm/vocab", &vocab);
      config->GetString("cylm/params", &params);
      config->GetString("cylm/prefer", &prefer);
      config->GetString("cylm/adapt", &adapt);
      config->GetString("cylm/gate", &gate);
      config->GetString("cylm/gate_vocab", &gate_vocab);
      int budget = 0;
      if (config->GetInt("cylm/budget_ms", &budget) && budget > 0) T9CylmSetBudget(budget);
      bool guard = false;
      config->GetBool("cylm/guard_unknown_words", &guard);
      T9CylmSetGuardUnknownWords(guard);
      auto resolve = [](const string& name) { return name.empty() ? string() : T9ResolveOnce("cylm", "", name); };
      t9_cylm_ = T9CylmOpen(resolve(lexicon), resolve(model), resolve(model2),
                            vocab.empty() ? string() : resolve(reranker), resolve(vocab), params, resolve(prefer),
                            resolve(adapt));
      cylm_long_alternative_ = t9_cylm_ && context && !t9_joint_ && context->get_option("cyime_cylm_alternative");
      // A translation is created for every key: start CyLM on it now, so it decodes while Rime makes its own
      // sentence instead of after it.
      // The replace gate loads once per model (T9CylmGate keeps the set); only when the schema names it.
      static std::once_flag gate_once;
      if (t9_cylm_ && !gate.empty())
        std::call_once(gate_once, [&] { T9CylmGate(t9_cylm_.get(), resolve(gate), resolve(gate_vocab)); });
      if (t9_cylm_ && context) T9CylmPrefetch(t9_cylm_.get(), context->input(), t9_left_context_);
      if (t9_cylm_) T9CylmSyncWords(t9_cylm_.get(), Service::instance().deployer().user_data_dir.string());
      // Every commit of this input context teaches CyLM; connected once per context (marked by a property).
      if (t9_cylm_ && context && context->get_property("cyime_cylm_learn").empty()) {
        context->set_property("cyime_cylm_learn", "1");
        auto learner = t9_cylm_;
        // Incognito (the _cyime_incognito option, as Memory::OnCommit) teaches it nothing.
        context->commit_notifier().connect([learner](Context* ctx) {
          if (!ctx->get_option("_cyime_incognito")) T9CylmLearn(learner.get(), ctx->GetCommitText());
        });
      }
    }
  }
  if (t9_sentence_rescore_ && !t9_joint_) {
    // 26-key: Rime makes translator/max_sentences sentences as usual; the word model only picks
    // which one comes first (pinyin26-word-model-20261018.md, 2026-10-20).
    string ngram_model;
    if (config->GetString("t9/ngram_model", &ngram_model) && !ngram_model.empty())
      t9_ngram_ = T9NgramModel::Open(T9ResolveOnce("t9_ngram", ".lm", ngram_model));
    config->GetBool("t9/ngram_sentence_start", &t9_ngram_sentence_start_);
    config->GetDouble("t9/merged_tail_word_margin", &t9_merged_tail_word_margin_);
    config->GetDouble("t9/rescore_rime_weight", &t9_rescore_rime_weight_);
    config->GetDouble("t9/tail_correction_margin", &t9_tail_correction_margin_);
    config->GetDouble("t9/slip_correction_margin", &t9_slip_correction_margin_);
    config->GetInt("t9/initials_choices", &t9_initials_choices_);
    config->GetDouble("t9/initials_lead_margin", &t9_initials_lead_margin_);
    config->GetDouble("t9/final_g_word_margin", &t9_final_g_word_margin_);
    int keys_per_syllable = 0;
    config->GetInt("t9/rescore_keys_per_syllable", &keys_per_syllable);
    t9_rescore_keys_per_syllable_ = static_cast<size_t>(std::max(0, keys_per_syllable));
  }
  {
    // t9/reading_table: ln(count / count of the text's main reading) for minority readings
    // (的 di, 了 liao). The word model scores words, not readings, so without this 默默的 beat
    // 默默地 for momodi because 的 is also read di (目的).
    string readings;
    if (config->GetString("t9/reading_table", &readings) && !readings.empty()) {
      t9_readings_ = T9LoadReadings(T9ResolveOnce("t9_readings", ".txt", readings));
    }
  }
  if (t9_joint_) {
    auto budget = [&](const char* key, size_t& value, int minimum, int maximum) {
      int configured = static_cast<int>(value);
      config->GetInt(key, &configured);
      value = static_cast<size_t>(std::max(minimum, std::min(maximum, configured)));
    };
    budget("t9/source_budget", t9_source_budget_, 8, 128);
    budget("t9/pool_budget", t9_pool_budget_, 8, 64);
    budget("t9/dictionary_beam", t9_dictionary_beam_, 8, 64);
    budget("t9/sentence_beam", t9_sentence_beam_, 8, 128);
    budget("t9/result_budget", t9_result_budget_, 3, 64);
    int display = static_cast<int>(t9_display_sentences_);
    config->GetInt("t9/display_sentences", &display);
    { int keys = 0; config->GetInt("t9/more_sentences_min_keys", &keys);
      t9_more_sentences_min_keys_ = static_cast<size_t>(std::max(0, keys)); }
    config->GetBool("t9/cache_with_correction", &t9_cache_with_correction_);
    t9_display_sentences_ = static_cast<size_t>(std::max(0, display));
    config->GetDouble("t9/spelling_penalty", &t9_spelling_penalty_);
    config->GetDouble("t9/word_penalty", &t9_word_penalty_);
    config->GetString("t9/sentence_model", &t9_sentence_model_);
    config->GetDouble("t9/sentence_weight", &t9_sentence_weight_);
    string ngram_model;
    if (config->GetString("t9/ngram_model", &ngram_model) && !ngram_model.empty()) {
      // Not .bin: Rime cleanup moves *.bin in the user directory to trash.
      t9_ngram_ = T9NgramModel::Open(T9ResolveOnce("t9_ngram", ".lm", ngram_model));
    }
    config->GetDouble("t9/ngram_user_weight", &t9_ngram_user_weight_);
    config->GetDouble("t9/ngram_user_input_weight", &t9_ngram_user_input_weight_);
    config->GetDouble("t9/ngram_dictionary_prior", &t9_ngram_dictionary_prior_);
    config->GetBool("t9/ngram_sentence_start", &t9_ngram_sentence_start_);
    config->GetDouble("t9/ngram_eos_weight", &t9_ngram_eos_weight_);
    config->GetDouble("t9/particle_eos_weight", &t9_particle_eos_weight_);
    config->GetBool("t9/particle_bigram_after_start", &t9_particle_bigram_after_start_);
    config->GetDouble("t9/particle_alternative_margin", &t9_particle_alternative_margin_);
    if (auto list = config->GetList("t9/final_particles")) {
      auto particles = std::make_shared<std::set<string>>();
      for (size_t i = 0; i < list->size(); ++i)
        if (auto value = list->GetValueAt(i)) particles->insert(value->str());
      if (!particles->empty()) t9_final_particles_ = std::move(particles);
    }
    config->GetBool("t9/ngram_merge_segmentations", &t9_ngram_merge_segmentations_);
    config->GetDouble("t9/single_key_initial_margin", &t9_single_key_initial_margin_);
    if (auto list = config->GetList("t9/ngram_typo_bigrams"); list && t9_ngram_) {
      auto pairs = std::make_shared<T9NgramModel::Pairs>();
      for (size_t i = 0; i < list->size(); ++i) {
        auto value = list->GetValueAt(i);
        if (!value) continue;
        const string& text = value->str();
        const size_t space = text.find(' ');
        if (space == string::npos) continue;
        const int32_t first = t9_ngram_->Id(text.substr(0, space));
        const int32_t second = t9_ngram_->Id(text.substr(space + 1));
        if (first != T9NgramModel::kUnseen && second != T9NgramModel::kUnseen) pairs->emplace_back(first, second);
      }
      if (!pairs->empty()) t9_ngram_typo_bigrams_ = std::move(pairs);
    }
    config->GetDouble("t9/correction_penalty", &t9_correction_penalty_);
    config->GetBool("t9/prefer_user_entries", &t9_prefer_user_entries_);
    { int whole = 0; config->GetInt("t9/whole_input_phrases", &whole); t9_whole_input_phrases_ = static_cast<size_t>(std::max(0, whole)); }
    // 26-key uses the decoder only with the word model; without the model file it keeps
    // the ordinary Rime phrase/sentence path rather than dictionary weights + word penalty.
    bool requires_ngram = false;
    config->GetBool("t9/joint_requires_ngram", &requires_ngram);
    if (requires_ngram && !t9_ngram_) t9_joint_ = false;
    // Without the word n-gram model, keep dictionary weight + grammar + word penalty.
    if (!t9_ngram_) {
      if (auto* component = Grammar::Require("grammar"))
        t9_grammar_.reset(component->Create(config));
      string language;
      if (config->GetString("grammar/language", &language)) {
        if (auto* component = dynamic_cast<OctagramComponent*>(Grammar::Require("grammar")))
          t9_gram_db_ = component->GetDb(language);
      }
    }
    T9SetGrammarStatus(t9_gram_db_ != nullptr || t9_ngram_ != nullptr);
  }
}

string T9ScriptOptions::T9ResolveOnce(const char* type_name, const char* suffix, const string& name) {
  static std::mutex mutex;
  static std::map<string, string> resolved;
  std::lock_guard<std::mutex> lock(mutex);
  auto& path = resolved[Service::instance().deployer().user_data_dir.string() + '\n' + type_name + '\n' + name];
  if (path.empty()) {
    const ResourceType type{type_name, "", suffix};
    std::unique_ptr<ResourceResolver> resolver(Service::instance().CreateResourceResolver(type));
    path = resolver->ResolvePath(name).string();
  }
  return path;
}

std::shared_ptr<const T9ScriptOptions::T9Readings> T9ScriptOptions::T9LoadReadings(const string& path) {
  static std::mutex mutex;
  static std::map<string, std::shared_ptr<const T9Readings>> loaded;
  std::lock_guard<std::mutex> lock(mutex);
  if (auto kept = loaded[path]) return kept;
  std::ifstream in(path);
  if (!in) return nullptr;
  auto table = std::make_shared<T9Readings>();
  string line;
  while (std::getline(in, line)) {
    const auto a = line.find('\t');
    const auto b = a == string::npos ? a : line.find('\t', a + 1);
    if (b == string::npos || line[0] == '#') continue;
    (*table)[line.substr(0, a)].emplace_back(line.substr(a + 1, b - a - 1), std::atof(line.c_str() + b + 1));
  }
  loaded[path] = table;
  return table;
}

const std::unordered_map<string, SyllableId>& T9ScriptOptions::T9SyllableIds(Dictionary* dict) {
  static std::mutex mutex;
  static std::map<const void*, std::unordered_map<string, SyllableId>> tables;
  std::lock_guard<std::mutex> lock(mutex);
  auto& ids = tables[dict->primary_table().get()];
  if (ids.empty()) {
    for (SyllableId id = 0;; ++id) {
      const string syllable = dict->primary_table()->GetSyllableById(id);
      if (syllable.empty()) break;
      ids.emplace(syllable, id);
    }
  }
  return ids;
}

std::shared_ptr<CylmModel> T9ScriptOptions::T9CylmOpen(const string& lexicon, const string& model,
                                                       const string& model2, const string& reranker,
                                                       const string& vocab, const string& params,
                                                       const string& prefer, const string& adapt) {
  static std::mutex mutex;
  static std::map<string, std::shared_ptr<CylmModel>> opened;
  std::lock_guard<std::mutex> lock(mutex);
  const string key = lexicon + '\n' + model + '\n' + model2 + '\n' + reranker + '\n' + vocab + '\n' + params +
                     '\n' + prefer + '\n' + adapt;
  auto found = opened.find(key);
  if (found != opened.end()) return found->second;
  const char* second = model2.empty() ? nullptr : model2.c_str();
  CylmModel* raw = reranker.empty()
                       ? cylm_open(lexicon.c_str(), model.c_str(), second, params.c_str())
                       : cylm_open2(lexicon.c_str(), model.c_str(), second, reranker.c_str(), vocab.c_str(), params.c_str());
  // Without the reranker CyLM still decodes (only its rescoring is lost), so a reranker that fails to load
  // does not take the whole model down.
  const bool without_reranker = !raw && !reranker.empty() &&
                                (raw = cylm_open(lexicon.c_str(), model.c_str(), second, params.c_str())) != nullptr;
  std::shared_ptr<CylmModel> handle(raw, [](CylmModel* m) { if (m) cylm_close(m); });
  if (!handle.get()) handle.reset();
  else {
    if (!prefer.empty()) cylm_prefer(handle.get(), prefer.c_str());
    // Before any session: sessions read the layer when they are created. A missing file (public packages) is
    // simply no layer. What the user taught it on this device is saved beside it (cylm_learned.tsv, the same
    // layer plus the learned counts) and read instead when present.
#ifdef __ANDROID__
    // Memory diagnosis (adb logcat -s CyLM): heap before and after the user layer; `setprop debug.cyime.cylm_adapt 0`
    // opens without it, to measure its share.
    char no_adapt[PROP_VALUE_MAX] = {0};
    const bool skip_adapt = __system_property_get("debug.cyime.cylm_adapt", no_adapt) > 0 && no_adapt[0] == '0';
    const double heap_before = mallinfo().uordblks / 1048576.0;
#else
    const bool skip_adapt = false;
#endif
    if (!adapt.empty() && !skip_adapt) {
      const string learned = adapt.substr(0, adapt.find_last_of('/') + 1) + "cylm_learned.tsv";
      if (cylm_adapt(handle.get(), learned.c_str()) < 0) cylm_adapt(handle.get(), adapt.c_str());
      T9CylmLearnInto(handle.get(), learned);
    }
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "CyLM", "user layer %s: heap %.1f -> %.1f MB", skip_adapt ? "skipped" : "loaded",
                        heap_before, mallinfo().uordblks / 1048576.0);
#endif
    T9CylmWarm(handle.get());
  }
#ifdef __ANDROID__
  // Once per file set and process (adb logcat -s CyLM): whether the schema's CyLM files opened, and if not,
  // which file is missing and whether the settings alone are the problem.
  auto size = [](const string& path) -> long long {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    return in ? static_cast<long long>(in.tellg()) : -1;
  };
  __android_log_print(handle ? ANDROID_LOG_INFO : ANDROID_LOG_WARN, "CyLM",
                      "%s lexicon=%s(%lld) model=%s(%lld) reranker=%s(%lld) vocab=%lld",
                      !handle ? "failed to open" : without_reranker ? "opened without reranker (reranker failed)" : "opened",
                      lexicon.c_str(), size(lexicon), model.c_str(), size(model),
                      reranker.empty() ? "none" : reranker.c_str(), reranker.empty() ? 0LL : size(reranker),
                      vocab.empty() ? 0LL : size(vocab));
  if (!handle) {
    CylmModel* bare = cylm_open(lexicon.c_str(), model.c_str(), second, "");
    __android_log_print(ANDROID_LOG_WARN, "CyLM", "same files with default settings: %s", bare ? "open" : "fail");
    if (bare) cylm_close(bare);
  }
#endif
  return opened[key] = handle;
}

}  // namespace rime
