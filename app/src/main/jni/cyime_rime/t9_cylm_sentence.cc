#include "t9_cylm_sentence.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <functional>
#include <map>
#include <mutex>
#include <set>
#include <sstream>
#include <thread>
#include <utility>

#include <rime/dict/dictionary.h>
#include <rime/gear/translator_commons.h>
#include <utf8.h>

#include "t9_script_options.h"

#ifdef __ANDROID__
#include <android/log.h>
#include <malloc.h>
#include <sys/system_properties.h>
#endif

namespace rime {

namespace {
// Per-key diagnosis on a device (adb logcat -s CyLM): the first keys, then a summary every 50 keys of how many got
// CyLM's sentence first, how many it missed the budget for, how many found it already decoded, and the median /
// p95 of how long the key waited for CyLM and how long CyLM decoded. Never the keys or the text.
std::atomic<long> learned_texts{0};  // committed texts CyLM learned (cylm_learn), for the log

struct CylmStats {
  std::mutex mutex;
  long calls = 0, replaced = 0, late = 0, ready = 0;
  vector<float> waits, decodes;
  static float Quantile(vector<float> v, double q) {
    if (v.empty()) return 0;
    std::sort(v.begin(), v.end());
    return v[std::min(v.size() - 1, static_cast<size_t>(v.size() * q))];
  }
  void Add(bool was_replaced, bool was_late, bool was_ready, double wait_ms, double decode_ms) {
    std::lock_guard<std::mutex> lock(mutex);
    ++calls; replaced += was_replaced; late += was_late; ready += was_ready;
    waits.push_back(static_cast<float>(wait_ms));
    if (decode_ms > 0) decodes.push_back(static_cast<float>(decode_ms));
#ifdef __ANDROID__
    if (calls <= 3 || calls % 50 == 0)
      __android_log_print(ANDROID_LOG_INFO, "CyLM",
                          "decode calls=%ld replaced=%ld late=%ld ready_at_ask=%ld wait_ms p50=%.2f p95=%.2f "
                          "decode_ms p50=%.2f p95=%.2f heap_mb=%.1f learned=%ld", calls, replaced, late, ready,
                          Quantile(waits, 0.5), Quantile(waits, 0.95), Quantile(decodes, 0.5),
                          Quantile(decodes, 0.95), mallinfo().uordblks / 1048576.0, learned_texts.load());
#endif
    if (calls % 50 == 0) { waits.clear(); decodes.clear(); }
  }
};
CylmStats& Stats() { static auto* stats = new CylmStats(); return *stats; }

std::atomic<int> budget_ms{12};

// Everything that touches a model's state (decode, learning, user words, the replace gate) runs under this lock, so
// the hook can score with the gate (cylm_gate_score must not overlap a decode) without its own thread.
std::timed_mutex& ModelMutex() { static auto* m = new std::timed_mutex(); return *m; }

// Models whose replace gate loaded (T9CylmGate); the hook then uses the gate's rule.
std::mutex gate_mutex;
std::set<const CylmModel*>& GateModels() { static auto* s = new std::set<const CylmModel*>(); return *s; }
bool HasGate(const CylmModel* model) {
  std::lock_guard<std::mutex> lock(gate_mutex);
  return GateModels().count(model) > 0;
}
std::atomic<bool> guard_unknown{false};  // schema cylm/guard_unknown_words

// Keep Rime's sentence when one of its words (2+ characters) is unknown to CyLM: CyLM would only spell that word out
// of single characters (自训模型攻坚 47badd7). `setprop debug.cyime.cylm_guard 1|0` overrides the schema for tests.
bool GuardUnknownWords() {
#ifdef __ANDROID__
  char value[PROP_VALUE_MAX] = {0};
  if (__system_property_get("debug.cyime.cylm_guard", value) > 0 && (value[0] == '0' || value[0] == '1'))
    return value[0] == '1';
#endif
  return guard_unknown.load();
}

// Developer switch for A/B on one install: `adb shell setprop debug.cyime.cylm 0` turns CyLM off from the next
// key, `setprop debug.cyime.cylm 1` turns it back on.
// Developer override of the budget for measuring quality apart from timing: `setprop debug.cyime.cylm_budget 2000`
// (ms); unset or 0 keeps the schema's cylm/budget_ms.
int CylmBudgetMs() {
#ifdef __ANDROID__
  char value[PROP_VALUE_MAX] = {0};
  if (__system_property_get("debug.cyime.cylm_budget", value) > 0) {
    const int ms = std::atoi(value);
    if (ms > 0) return std::min(ms, 5000);
  }
#endif
  return budget_ms.load();
}

bool CylmSwitchedOff() {
#ifdef __ANDROID__
  char value[PROP_VALUE_MAX] = {0};
  return __system_property_get("debug.cyime.cylm", value) > 0 && value[0] == '0';
#else
  return false;
#endif
}

// CyLM decodes on its own thread. Each key starts it as soon as Rime creates the translation (T9CylmPrefetch, from
// LoadT9Options), so CyLM runs while Rime makes its own sentence; when Rime's sentence is ready the key waits at
// most the budget for CyLM's (user 2026-10-26: typing lagged while CyLM ran after Rime, one after the other).
// A result later than the budget is dropped: the candidates shown for a key never change afterwards.
// One worker serves every session and always decodes the newest request, so skipped keys cost nothing.
class CylmWorker {
 public:
  static CylmWorker& Instance() {
    static auto* worker = new CylmWorker();  // lives for the process, like the sessions
    return *worker;
  }
  // A committed text for the model's user layer (cylm_learn), applied on the worker between decodes; every 20th
  // learned text the layer is written to `file` (set by LearnInto), so a restart keeps it.
  void Learn(CylmModel* model, const string& text) {
    std::lock_guard<std::mutex> lock(mutex_);
    learners_.insert(model);
    learn_.emplace_back(model, text);
    wake_.notify_one();
  }
  // Any other model change (cylm_add_word), run on the worker between decodes like learning.
  void Run(std::function<void()> task) {
    std::lock_guard<std::mutex> lock(mutex_);
    tasks_.push_back(std::move(task));
    wake_.notify_one();
  }
  void LearnInto(CylmModel* model, const string& file) {
    std::lock_guard<std::mutex> lock(mutex_);
    learn_file_[model] = file;
    learners_.insert(model);
  }
  // Long-press 忘记, on the calling (Rime) thread: a commit of `text` not learned yet is dropped from the queue, and
  // each model that learns forgets it under the model lock (no decode runs meanwhile), its layer saved at once.
  int Forget(const string& text, int wait_ms) {
    std::set<CylmModel*> models;
    std::map<CylmModel*, string> files;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      learn_.erase(std::remove_if(learn_.begin(), learn_.end(), [&](const auto& item) { return item.second == text; }),
                   learn_.end());
      models = learners_;
      files = learn_file_;
    }
    if (models.empty()) return 0;
    std::unique_lock<std::timed_mutex> model_lock(ModelMutex(), std::chrono::milliseconds(wait_ms));
    if (!model_lock.owns_lock()) return -1;
    int removed = 0;
    for (auto* model : models) {
      const int n = cylm_forget(model, text.c_str());
      if (n <= 0) continue;
      removed += n;
      const auto file = files.find(model);
      if (file != files.end()) cylm_adapt_save(model, file->second.c_str());
    }
    return removed;
  }
  // Starts decoding (session, input, context) unless that is already the newest request.
  void Prefetch(CylmSession* session, const string& in, bool digits, const string& context) {
    std::lock_guard<std::mutex> lock(mutex_);
    Want(session, in, digits, context);
  }
  // The record for (session, input, context), waiting at most `budget`; empty when not ready in time.
  string Decode(CylmSession* session, const string& in, bool digits, const string& context,
                std::chrono::milliseconds budget, double* wait_ms, double* decode_ms, bool* late, bool* ready) {
    const auto asked = std::chrono::steady_clock::now();
    std::unique_lock<std::mutex> lock(mutex_);
    const uint64_t id = Want(session, in, digits, context);
    *ready = finished_ >= id;
    const bool done = *ready || done_.wait_for(lock, budget, [&] { return finished_ >= id; });
    *wait_ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - asked).count();
    *late = !done;
    if (!done || done_session_ != session || done_in_ != in || done_context_ != context) return string();
    *decode_ms = done_ms_;
    return done_record_;
  }

 private:
  CylmWorker() { std::thread([this] { Run(); }).detach(); }
  // The request id for (session, input, context): the newest one when it matches, else a new one. Under mutex_.
  uint64_t Want(CylmSession* session, const string& in, bool digits, const string& context) {
    if (wanted_ > 0 && session_ == session && in_ == in && context_ == context) return wanted_;
    // A decode still running for an older key is stopped (it keeps the incremental state of the common prefix),
    // so the newest key does not queue behind it.
    if (started_ > finished_ && running_) cylm_cancel(running_);
    session_ = session; in_ = in; digits_ = digits; context_ = context;
    wake_.notify_one();
    return ++wanted_;
  }
  void Run() {
    vector<char> out(16384);
    std::unique_lock<std::mutex> lock(mutex_);
    for (;;) {
      wake_.wait(lock, [&] { return wanted_ > started_ || !learn_.empty() || !tasks_.empty(); });
      if (!tasks_.empty()) {
        vector<std::function<void()>> tasks;
        tasks.swap(tasks_);
        lock.unlock();
        for (auto& task : tasks) {
          std::lock_guard<std::timed_mutex> model_lock(ModelMutex());
          task();
        }
        lock.lock();
        continue;
      }
      if (!learn_.empty()) {
        // Learning never overlaps a decode (cylm.h): it runs here, on the decoding thread, before the next key.
        vector<std::pair<CylmModel*, string>> texts;
        texts.swap(learn_);
        const auto files = learn_file_;
        lock.unlock();
        for (const auto& [model, text] : texts) {
          std::lock_guard<std::timed_mutex> model_lock(ModelMutex());
          if (cylm_learn(model, text.c_str(), 1.0) <= 0) continue;
          ++learned_texts;
          const auto file = files.find(model);
          if (++learned_ % 20 == 0 && file != files.end()) cylm_adapt_save(model, file->second.c_str());
        }
        lock.lock();
        continue;
      }
      const uint64_t id = started_ = wanted_;
      CylmSession* session = session_;
      const string in = in_, context = context_;
      const bool digits = digits_;
      running_ = session;
      lock.unlock();
      const auto t0 = std::chrono::steady_clock::now();
      int n;
      {
        std::lock_guard<std::timed_mutex> model_lock(ModelMutex());
        n = cylm_decode2(session, in.c_str(), digits ? 1 : 0, context.c_str(), out.data(), static_cast<int>(out.size()));
      }
      const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
      lock.lock();
      running_ = nullptr;
      done_session_ = session; done_in_ = in; done_context_ = context; done_ms_ = ms;
      // All n-best records ("text\x1f pinyin\x1f spans\x1f score", each ended by '\0'), best first.
      size_t used = 0;
      for (int i = 0; i < n; ++i) used += std::char_traits<char>::length(out.data() + used) + 1;
      done_record_ = n > 0 && out[0] ? string(out.data(), used) : string();
      finished_ = id;
      done_.notify_all();
    }
  }
  std::mutex mutex_;
  std::condition_variable wake_, done_;
  uint64_t wanted_ = 0, started_ = 0, finished_ = 0;
  CylmSession* session_ = nullptr;
  CylmSession* running_ = nullptr;  // the session the worker is decoding now, for cylm_cancel
  vector<std::pair<CylmModel*, string>> learn_;
  vector<std::function<void()>> tasks_;
  std::map<CylmModel*, string> learn_file_;
  std::set<CylmModel*> learners_;  // models that learn (cylm_learn): 忘记 reaches each; models live for the process
  long learned_ = 0;  // texts learned, on the worker only
  string in_, context_;
  bool digits_ = false;
  CylmSession* done_session_ = nullptr;
  string done_in_, done_context_, done_record_;
  double done_ms_ = 0;
};

// One incremental session per model (one text field at a time), kept for the process.
CylmSession* SessionFor(CylmModel* model) {
  static std::mutex mutex;
  static std::map<const CylmModel*, CylmSession*> sessions;
  std::lock_guard<std::mutex> lock(mutex);
  auto& slot = sessions[model];
  if (!slot) slot = cylm_session(model);
  return slot;
}

// The keys CyLM decodes: all nine-key digits or all pinyin letters; anything else is left to Rime.
bool CylmInput(const string& in, bool* digits) {
  if (in.empty()) return false;
  *digits = std::all_of(in.begin(), in.end(), [](char c) { return c >= '2' && c <= '9'; });
  return *digits || std::all_of(in.begin(), in.end(), [](char c) { return (c >= 'a' && c <= 'z') || c == '\''; });
}

string CylmContext(const string& left_context) {
  return !left_context.empty() && left_context[0] == '\x1e' ? left_context.substr(1) : string();
}
// An extra candidate must account for every typed letter and explicit syllable boundary,
// so selecting it can consume and learn the same full-pinyin composition.
an<Sentence> CylmSentence(const string& best_record, size_t unit, const string& text,
                          const an<Sentence>& rime, Dictionary* dict, const Language* translator_language,
                          size_t start, const an<PhraseSyllabifier>& syllabifier, const string* exact_input) {
  DictEntry entry;
  entry.text = text;
  entry.weight = rime->weight();
  const Language* language = nullptr;
  if (unit != string::npos && dict) {
    const auto end = best_record.find('\x1f', unit + 1);
    std::istringstream pinyin(best_record.substr(unit + 1, end == string::npos ? string::npos : end - unit - 1));
    const auto& ids = T9ScriptOptions::T9SyllableIds(dict);
    string syllable;
    Code code;
    size_t key = 0;
    while (pinyin >> syllable) {
      if (exact_input) {
        if (key && key < exact_input->size() && (*exact_input)[key] == '\'') ++key;
        if (exact_input->compare(key, syllable.size(), syllable) != 0) return nullptr;
        key += syllable.size();
      }
      const auto found = ids.find(syllable);
      if (found == ids.end()) { code.clear(); break; }
      code.push_back(found->second);
    }
    if (exact_input && (key != exact_input->size() || code.size() < 6)) return nullptr;
    if (!code.empty() && code.size() == static_cast<size_t>(utf8::unchecked::distance(text.begin(), text.end()))) {
      entry.code = code;
      language = translator_language;
    }
  }
  if (exact_input && !language) return nullptr;
  auto sentence = New<Sentence>(language);
  sentence->Extend(entry, rime->end() - start, rime->weight());
  sentence->Offset(start);
  sentence->set_syllabifier(syllabifier);
  sentence->set_quality(rime->quality());
  return sentence;
}
}  // namespace

void T9CylmSetBudget(int ms) { budget_ms = std::max(1, std::min(200, ms)); }

void T9CylmSetGuardUnknownWords(bool on) { guard_unknown = on; }

bool T9CylmGate(CylmModel* model, const string& gate, const string& vocab) {
  if (!model || gate.empty() || vocab.empty()) return false;
#ifdef __ANDROID__
  // `setprop debug.cyime.cylm_gate 0` opens without the gate (to measure its memory and effect).
  char value[PROP_VALUE_MAX] = {0};
  if (__system_property_get("debug.cyime.cylm_gate", value) > 0 && value[0] == '0') return false;
  const double heap_before = mallinfo().uordblks / 1048576.0;
#endif
  bool loaded;
  {
    std::lock_guard<std::timed_mutex> model_lock(ModelMutex());
    loaded = cylm_gate_load(model, gate.c_str(), vocab.c_str()) >= 0;
  }
#ifdef __ANDROID__
  __android_log_print(ANDROID_LOG_INFO, "CyLM", "replace gate %s: heap %.1f -> %.1f MB", loaded ? "loaded" : "failed",
                      heap_before, mallinfo().uordblks / 1048576.0);
#endif
  if (loaded) {
    std::lock_guard<std::mutex> lock(gate_mutex);
    GateModels().insert(model);
  }
  return loaded;
}

bool T9CylmEnabled() { return !CylmSwitchedOff(); }

void T9CylmWarm(CylmModel* model) {
  // The first decodes read model pages from storage (about 0.6 s, then slower keys for the first sentences on the
  // tablet); read the common ones on the worker while the keyboard opens: one everyday sentence in full pinyin
  // and the same in nine-key digits (the worker runs the first and then the newest pending request).
  if (CylmSession* session = model ? SessionFor(model) : nullptr) {
    CylmWorker::Instance().Prefetch(session, "womenjintianqunalichifanzhegeshishenmeyisi", false, string());
    CylmWorker::Instance().Prefetch(session, "966365468426786254244326", true, string());
  }
}

void T9CylmLearn(CylmModel* model, const string& text) {
  // Only text with Han characters teaches the model (letters committed raw or punctuation do not).
  if (model && !CylmSwitchedOff() && std::any_of(text.begin(), text.end(), [](char c) { return c & 0x80; }))
    CylmWorker::Instance().Learn(model, text);
}

void T9CylmLearnInto(CylmModel* model, const string& file) { CylmWorker::Instance().LearnInto(model, file); }

int T9CylmForget(const string& text, int wait_ms) {
  if (text.empty() || !std::any_of(text.begin(), text.end(), [](char c) { return c & 0x80; })) return 0;
  return CylmWorker::Instance().Forget(text, wait_ms);
}

void T9CylmSyncWords(CylmModel* model, const string& dir) {
  // The user's own words (个人词库 user_*.dict.yaml, 我的短语 custom_phrase*.txt in the Rime user directory) are
  // given to CyLM (cylm_add_word) when it opens and again when one of those files changes. The key's translation
  // only checks the clock (at most every 3 s); the directory is read on the CyLM thread, so a phrase added in
  // settings counts from the next sentence without touching the key path.
  static std::mutex mutex;
  static std::chrono::steady_clock::time_point checked;
  {
    std::lock_guard<std::mutex> lock(mutex);
    const auto now = std::chrono::steady_clock::now();
    if (!model || dir.empty() || (checked.time_since_epoch().count() && now - checked < std::chrono::seconds(3))) return;
    checked = now;
  }
#ifdef __ANDROID__
  // `setprop debug.cyime.cylm_cells 0` leaves out the cell dictionaries (user_cyime_cells_*), to measure their share.
  char cells_value[PROP_VALUE_MAX] = {0};
  const bool skip_cells = __system_property_get("debug.cyime.cylm_cells", cells_value) > 0 && cells_value[0] == '0';
#else
  const bool skip_cells = false;
#endif
  CylmWorker::Instance().Run([model, dir, skip_cells] {
    namespace fs = std::filesystem;
#ifdef __ANDROID__
    const double heap_before = mallinfo().uordblks / 1048576.0;
    int words = 0;
#endif
    static std::map<string, fs::file_time_type> seen;  // the CyLM thread only
    std::error_code ec;
    for (fs::directory_iterator it(dir, ec), end; !ec && it != end; it.increment(ec)) {
      const string name = it->path().filename().string();
      const bool pack = name.rfind("user_", 0) == 0 && name.size() > 10 && name.compare(name.size() - 10, 10, ".dict.yaml") == 0;
      const bool phrases = name.rfind("custom_phrase", 0) == 0 && name.size() > 4 && name.compare(name.size() - 4, 4, ".txt") == 0;
      if (!pack && !phrases) continue;
      if (skip_cells && name.rfind("user_cyime_cells_", 0) == 0) continue;
      const auto time = it->last_write_time(ec);
      if (ec || seen[name] == time) continue;
      seen[name] = time;
      // Rows "word<TAB>reading[<TAB>weight]"; a dictionary pack's rows start after its "..." line. A phrase whose
      // code is not pinyin (an abbreviation) is refused by cylm_add_word and stays Rime's alone.
      std::ifstream in(it->path());
      string line;
      bool rows = !pack;
      while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        if (!rows) { rows = line == "..."; continue; }
        if (line.empty() || line[0] == '#') continue;
        const auto tab = line.find('\t');
        if (tab == string::npos) continue;
        const auto tab2 = line.find('\t', tab + 1);
        const string reading = line.substr(tab + 1, tab2 == string::npos ? string::npos : tab2 - tab - 1);
#ifdef __ANDROID__
        words += cylm_add_word(model, line.substr(0, tab).c_str(), reading.c_str(), 0) == 0;
#else
        cylm_add_word(model, line.substr(0, tab).c_str(), reading.c_str(), 0);
#endif
      }
    }
#ifdef __ANDROID__
    if (words)
      __android_log_print(ANDROID_LOG_INFO, "CyLM", "user words %d%s: heap %.1f -> %.1f MB", words,
                          skip_cells ? " (cells skipped)" : "", heap_before, mallinfo().uordblks / 1048576.0);
#endif
  });
}

void T9CylmPrefetch(CylmModel* model, const string& input, const string& left_context) {
  bool digits = false;
  if (!model || CylmSwitchedOff() || !CylmInput(input, &digits)) return;
  if (CylmSession* session = SessionFor(model))
    CylmWorker::Instance().Prefetch(session, input, digits, CylmContext(left_context));
}

void T9ApplyCylmSentence(CylmModel* model, bool offer_alternative, const string& in, const string& left_context,
                         Dictionary* dict, const Language* translator_language, size_t start,
                         const an<PhraseSyllabifier>& syllabifier, deque<an<Sentence>>* sentences) {
  bool digits = false;
  if (CylmSwitchedOff() || !CylmInput(in, &digits)) return;
  CylmSession* session = SessionFor(model);
  if (!session) return;
  double wait_ms = 0, ms = 0;
  bool late = false, ready = false;
  const string record = CylmWorker::Instance().Decode(session, in, digits, CylmContext(left_context),
                                                      std::chrono::milliseconds(CylmBudgetMs()), &wait_ms, &ms,
                                                      &late, &ready);
  if (record.empty()) return Stats().Add(false, late, ready, wait_ms, ms);
  // records: text \x1f pinyin \x1f spans \x1f score, '\0' after each, best first.
  const string best_record(record.c_str());
  const auto unit = best_record.find('\x1f');
  const string text = best_record.substr(0, unit);
  const auto& rime = sentences->front();
  if (text.empty() || text == rime->text()) return Stats().Add(false, false, ready, wait_ms, ms);
  // CyLM replaces Rime's sentence only when a learned rule says it is right (CyIME-Windows 65e22c9,
  // tools/cylm/replace_model.py, fit on tune sets, threshold at precision 0.9 per column; the earlier "margin > 1"
  // rule broke far more correct sentences). Rime's text must be in CyLM's n-best; features are what the hook sees.
  auto score_of = [](const string& one) {
    const auto at = one.rfind('\x1f');
    return at == string::npos ? 0.0 : std::atof(one.c_str() + at + 1);
  };
  vector<double> scores;
  int rime_rank = -1;
  for (size_t at = 0; at < record.size();) {
    const string one(record.c_str() + at);
    at += one.size() + 1;
    if (rime_rank < 0 && one.compare(0, one.find('\x1f'), rime->text()) == 0) rime_rank = static_cast<int>(scores.size());
    scores.push_back(score_of(one));
  }
  // The optional CyLM can disagree with the first choice without having enough evidence to replace it.
  // Keep all existing sentence choices ahead of one exact long spelling. No extra decode or wait.
  auto keep_first = [&] {
    if (offer_alternative && !digits && rime->end() - start == in.size() && in.size() >= 6 && unit != string::npos &&
        utf8::unchecked::distance(text.begin(), text.end()) >= 6 &&
        std::none_of(sentences->begin(), sentences->end(), [&](const auto& s) { return s->text() == text; })) {
      if (auto extra = CylmSentence(best_record, unit, text, rime, dict, translator_language, start, syllabifier, &in))
        sentences->push_back(extra);
    }
    Stats().Add(false, false, ready, wait_ms, ms);
  };
  if (rime_rank < 0) return keep_first();
  {
    auto chars = [](const string& t) {
      vector<uint32_t> out;
      for (auto it = t.begin(); it != t.end();) out.push_back(utf8::unchecked::next(it));
      return out;
    };
    const auto a = chars(rime->text()), b = chars(text);
    const bool same_len = a.size() == b.size();
    double diff = same_len ? 0 : std::fabs(double(a.size()) - double(b.size()));
    for (size_t i = 0; i < std::min(a.size(), b.size()); ++i) diff += a[i] != b[i];
    const double len = double(a.size());
    const double margin = scores[0] - scores[rime_rank];
    const double gap12 = scores.size() > 1 ? scores[0] - scores[1] : 0.0;
    double z;
    if (HasGate(model)) {
      // With the gate's character model (CyIME-Windows 102ad9a): its log-probability difference between CyLM's best
      // and Rime's sentence joins the features. Scoring must not overlap a decode; if the CyLM thread is busy for
      // longer than a few ms the key keeps Rime's sentence.
      std::unique_lock<std::timed_mutex> model_lock(ModelMutex(), std::chrono::milliseconds(4));
      if (!model_lock.owns_lock()) return Stats().Add(false, true, ready, wait_ms, ms);
      const double delta = cylm_gate_score(model, text.c_str()) - cylm_gate_score(model, rime->text().c_str());
      z = 0.664 * margin - 0.912 * std::min(rime_rank, 8) / 8.0 + 0.868 * std::min(gap12, 10.0) / 10.0 +
          0.083 * len / 10.0 + 0.484 * diff / std::max(1.0, len) + 0.673 * (same_len ? 1 : 0) -
          0.166 * (diff == 1 ? 1 : 0) - 0.372 * margin / std::max(1.0, diff) + 1.057 * delta / 10.0 +
          0.774 * delta / std::max(1.0, diff) / 5.0 - 1.229;
    } else {
      // Coefficients refit for the 500k lexicon (CyIME-Windows 1b141e6).
      z = 0.725 * margin - 0.654 * std::min(rime_rank, 8) / 8.0 + 0.866 * std::min(gap12, 10.0) / 10.0 +
          0.067 * len / 10.0 + 0.475 * diff / std::max(1.0, len) + 0.52 * (same_len ? 1 : 0) -
          0.086 * (diff == 1 ? 1 : 0) - 0.377 * margin / std::max(1.0, diff) - 1.309;
    }
    if (z <= 3.2) return keep_first();
  }
  // CyLM knows nothing of what the user picked before: a Rime sentence built from a learned word (这篇 picked once,
  // then zhepianbiji) stays, or CyLM's 这片笔记 replaced it and learning never reached whole sentences.
  for (const auto& part : rime->components())
    if (part.commit_count > 0) return Stats().Add(false, false, ready, wait_ms, ms);
  if (GuardUnknownWords())
    for (const auto& part : rime->components())
      if (utf8::unchecked::distance(part.text.begin(), part.text.end()) >= 2 && !cylm_has_word(model, part.text.c_str()))
        return Stats().Add(false, false, ready, wait_ms, ms);
  auto sentence = CylmSentence(best_record, unit, text, rime, dict, translator_language, start, syllabifier, nullptr);
  sentences->front() = sentence;
  Stats().Add(true, false, ready, wait_ms, ms);
}

}  // namespace rime
