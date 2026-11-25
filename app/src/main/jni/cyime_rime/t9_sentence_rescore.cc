#include "t9_sentence_rescore.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <set>
#include <unordered_map>
#include <vector>

#include <rime/gear/poet.h>
#include <rime/gear/script_translator.h>
#include <rime/gear/translator_commons.h>
#include <t9_ngram_model.h>

namespace rime {

namespace {
// Whether the typed letters read in full syllables without any correction, the last syllable included
// (aniu reads a'niu, so the corrected 按钮 never leads 阿牛). Abbreviated or unreadable letters do not count,
// except a mixed word's syllable typed whole (bzhan for B站), which is spelled as an abbreviation.
bool TypedReadsInFull(const SyllableGraph& graph) {
  const size_t end = graph.interpreted_length;
  if (end == 0) return false;
  std::set<size_t> reached{0};
  for (const auto& from : graph.edges) {
    if (!reached.count(from.first)) continue;
    for (const auto& to : from.second)
      for (const auto& spelling : to.second)
        if (!spelling.second.is_correction &&
            (spelling.second.type <= kFuzzySpelling ||
             spelling.second.cyime_whole_mixed)) {
          reached.insert(to.first);
          break;
        }
  }
  return reached.count(end) > 0;
}

// Whether the sentence's only corrections lie in its last word and that word spans at least three letters
// (nihso: 你好 through hso -> hao; jintisn: 今天; xiexir: 谢谢). A one- or two-letter tail is still being typed
// and a correction there only makes particles (henhaok -> 很好哦), so it never counts.
bool CorrectedOnlyAtTail(const T9ScriptQuery& q, const Sentence& sentence) {
  const auto& words = sentence.components();
  const auto& lengths = sentence.word_lengths();
  if (words.empty() || lengths.size() != words.size()) return false;
  const size_t end = sentence.end() - q.start;
  const size_t last_length = lengths.back();
  if (last_length < 3 || last_length > end) return false;
  const size_t last_start = end - last_length;
  if (!q.IsCorrection(words.back().code, end, last_start)) return false;
  if (last_start == 0) return true;
  const Code& code = sentence.code();
  const size_t last_syllables = words.back().code.size();
  if (last_syllables > code.size()) return false;
  Code prefix;
  prefix.assign(code.begin(), code.end() - static_cast<std::ptrdiff_t>(last_syllables));
  return !q.IsCorrection(prefix, last_start);
}
}  // namespace

bool T9InitialsOnly(const string& input) {
  if (input.size() < 2) return false;
  for (char ch : input)
    if (ch < 'a' || ch > 'z' || std::strchr("aeiouv", ch)) return false;
  return true;
}

void T9RescoreSentences(const T9ScriptQuery& q, const T9NgramModel* ngram, bool ngram_sentence_start,
                        size_t keys_per_syllable, double rime_weight, double tail_correction_margin,
                        double slip_margin, int initials_choices, double initials_margin,
                        const string& initials_first, double near_penalty, Poet* poet,
                        const WordGraph* sentence_graph, int max_sentences, double cutoff,
                        const an<Sentence>& exact_sentence, const an<Sentence>& rime_best,
                        deque<an<Sentence>>* sentences, bool slip_only,
                        vector<std::pair<double, an<Sentence>>>* runners_up,
                        const deque<an<Sentence>>* built, double built_rime_weight, bool* stray_decided,
                        const vector<int>* built_fuzzy, double fuzzy_penalty) {
  // translator/max_sentences gave the n-best to choose from; the menu keeps one sentence, as with
  // max_sentences: 1 (followed by Rime's best when that one is a correction).
  auto corrected = [&](const an<Sentence>& sentence) {
    return q.enable_correction && q.IsCorrection(sentence->code(), sentence->end() - q.start);
  };
  an<Sentence> chosen = sentences->front();
  if (runners_up) {
    runners_up->clear();
    for (const auto& sentence : *sentences)
      if (!corrected(sentence)) runners_up->emplace_back(sentences->front()->weight() - sentence->weight(), sentence);
  }
  const string& in = q.input;
  size_t groups = 0;
  bool vowel = false, consonants_only = !in.empty();
  for (char ch : in) {
    // 14-key merged keys are capitals; E/U/O/A carry the vowels (er, ui, op, as).
    const bool v = ch && std::strchr("aeiouvEUOA", ch);
    if (v && !vowel) ++groups;
    vowel = v;
    if (ch < 'a' || ch > 'z' || v) consonants_only = false;
  }
  if (keys_per_syllable) {
    // Double pinyin: every key pair is a whole syllable (tkgjxp is ting gan xie), and the vowels sit on
    // any key, so 挺感谢 never reached the word model. Syllables are counted by keys instead.
    const size_t keys = static_cast<size_t>(std::count_if(in.begin(), in.end(), [](char ch) { return ch != '\''; }));
    groups = keys / keys_per_syllable;
    consonants_only = false;
  }
  // Initials and short abbreviations (zjqa, zdky) keep Rime's dictionary order, unless t9/initials_choices asks for
  // the computed score there too.
  const bool initials = consonants_only && !keys_per_syllable && initials_choices > 0 && T9InitialsOnly(in);
  if (ngram && (initials || (!consonants_only && groups >= 2))) {
    // Substring -> model word id, kept across keys (consecutive n-bests share most substrings).
    thread_local std::unordered_map<string, int32_t> ids;
    thread_local const T9NgramModel* ids_model = nullptr;
    if (ids_model != ngram || ids.size() > 65536) { ids.clear(); ids_model = ngram; }
    auto word_id = [&](const string& word) {
      auto found = ids.find(word);
      if (found != ids.end()) return found->second;
      const int32_t id = ngram->Id(word);
      ids.emplace(word, id);
      return id;
    };
    // ln P(text): summed over its splits into model words (words up to 8 characters) under the
    // bigram/trigram chain, so 被嫌弃了 and 被掀起了 compare as text whatever words Rime used; plus the
    // sentence-position rules Rime applied while making it (吧 not first, 被 not last). A small beam of
    // last-word states per position carries the chain. A single unigram-chosen split read 他也会来的 as
    // 他也|会|来|的 (-26.4) against 他也|回来|的 (-25.4); its best split 他|也会|来|的 (-20.75) still trails
    // 他也|回来的 (-20.69), but the text's other readings add up (-20.56 against -20.67).
    auto score = [&](const Sentence& sentence) {
      const string& text = sentence.text();
      vector<size_t> at{0};
      for (size_t i = 0; i < text.size();) {
        const unsigned char c = static_cast<unsigned char>(text[i]);
        i += c < 0x80 ? 1 : c < 0xE0 ? 2 : c < 0xF0 ? 3 : 4;
        at.push_back(std::min(i, text.size()));
      }
      const size_t n = at.size() - 1;
      // The word model has no Latin letters, so a mixed word (B站, C盘, AI) would be scored as an unseen
      // letter and the sentence lose to any Han reading (不站上面的视频). Such a word is scored once whole, by
      // its dictionary weight (ln of its frequency, as Rime weighs it) brought to the model's scale, besides the
      // splits of its text. The two scales differ by about this much: over the 305,851 base.dict words (weight
      // ≥ 20) the model has, ln P(word) − Rime's ln(weight / 1e8) has median −3.75 (p25 −4.67, p75 −2.41).
      // That is a word's frequency alone; the Han words around it are scored after the words before them, which
      // in chat raises a word by about kInContext over its frequency (ln P(word | the two words before) − ln P(word)
      // over the fixture chat texts and sentences, 393 words: median 2.10, mean 2.25). Without it a mixed word lost
      // to readings the model knows only as pieces (我上班站看了 over 我上B站看了, 帮我拍一下这张图 over 帮我P一下这张图).
      constexpr double kInContext = 2.1;
      constexpr double kDictionaryToModel = -3.75 + kInContext;
      struct Whole { size_t start, end; double weight; };
      vector<Whole> wholes;
      {
        size_t byte = 0;
        for (const auto& word : sentence.components()) {
          const size_t from = byte;
          byte += word.text.size();
          if (std::none_of(word.text.begin(), word.text.end(),
                           [](char ch) { return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z'); }))
            continue;
          const auto first = std::lower_bound(at.begin(), at.end(), from);
          const auto last = std::lower_bound(at.begin(), at.end(), byte);
          if (first == at.end() || last == at.end() || *first != from || *last != byte) continue;
          wholes.push_back({static_cast<size_t>(first - at.begin()), static_cast<size_t>(last - at.begin()),
                            word.weight + kDictionaryToModel});
        }
      }
      struct State { double value; int32_t prev2, prev1; bool started; };
      constexpr size_t kStates = 4;
      auto log_add = [](double a, double b) {
        if (!std::isfinite(a)) return b;
        if (!std::isfinite(b)) return a;
        return std::max(a, b) + std::log1p(std::exp(-std::fabs(a - b)));
      };
      const int32_t root = ngram_sentence_start ? ngram->Bos() : ::T9NgramModel::kUnseen;
      // The states after a character depend only on the text up to it (and on the model and the start rule),
      // and the sentences of one n-best, and of the next key's, mostly begin alike. They are kept by prefix and
      // a sentence starts from its longest prefix kept: the same states and the same sum, without working them
      // out again for every sentence. A sentence with a mixed word scored whole is worked out on its own.
      thread_local std::unordered_map<string, vector<State>> kept;
      thread_local const T9NgramModel* kept_model = nullptr;
      thread_local bool kept_start = false;
      if (kept_model != ngram || kept_start != ngram_sentence_start || kept.size() > 2048) {
        kept.clear();
        kept_model = ngram;
        kept_start = ngram_sentence_start;
      }
      const bool keep = wholes.empty();
      vector<vector<State>> states(n + 1);
      states[0].push_back({0, root, root, false});
      size_t first = 1;
      string prefix;
      for (; keep && first <= n; ++first) {
        prefix.assign(text, 0, at[first]);
        const auto known = kept.find(prefix);
        if (known == kept.end()) break;
        states[first] = known->second;
      }
      for (size_t end = first; end <= n; ++end) {
        auto& here = states[end];
        for (size_t start = end > 8 ? end - 8 : 0; start < end; ++start) {
          if (states[start].empty()) continue;
          const int32_t id = word_id(text.substr(at[start], at[end] - at[start]));
          const auto whole = id == ::T9NgramModel::kUnseen
              ? std::find_if(wholes.begin(), wholes.end(), [&](const Whole& w) { return w.start == start && w.end == end; })
              : wholes.end();
          if (id == ::T9NgramModel::kUnseen && end - start > 1 && whole == wholes.end()) continue;
          for (const State& from : states[start]) {
            const double value = from.value + (whole != wholes.end() ? whole->weight : from.started
                ? ngram->LogProb3(from.prev2, from.prev1, id) : ngram->Start(id, ngram_sentence_start));
            const State next{value, from.started ? from.prev1 : root, id, true};
            // One state per last word (it decides the next transition), the best kStates kept.
            auto same = std::find_if(here.begin(), here.end(), [&](const State& s) { return s.prev1 == id; });
            if (same != here.end()) {
              const double sum = log_add(same->value, value);
              if (value > same->value) *same = next;
              same->value = sum;
              continue;
            }
            if (here.size() < kStates) { here.push_back(next); continue; }
            auto worst = std::min_element(here.begin(), here.end(),
                                          [](const State& a, const State& b) { return a.value < b.value; });
            if (value > worst->value) *worst = next;
          }
        }
        if (keep) {
          prefix.assign(text, 0, at[end]);
          kept.emplace(prefix, here);
        }
      }
      double total = -std::numeric_limits<double>::infinity();
      for (const State& s : states[n]) total = log_add(total, s.value);
      const auto& words = sentence.components();
      if (!words.empty() && poet) {
        total += poet->PositionAdjust(words.front().text, true, words.size() == 1);
        if (words.size() > 1) total += poet->PositionAdjust(words.back().text, false, true);
      }
      return total;
    };
    if (initials) {
      // Initials only (2026-11-13): whole dictionary words and sentences made of words are ordered by the same
      // computed score as full input, so a common combination (wmjs 我们就是, xydj 需要多久) leads a rarer dictionary
      // word (文明建设, 需要大家) by its frequency and nothing else. The translator added the leading whole words as
      // one-word sentences. Corrected readings stay out, as for full input.
      deque<an<Sentence>> pool;
      std::set<string> seen;
      for (const auto& sentence : *sentences)
        if (!corrected(sentence) && seen.insert(sentence->text()).second) pool.push_back(sentence);
      vector<std::pair<double, an<Sentence>>> ranked;
      for (const auto& sentence : pool)
        ranked.emplace_back(score(*sentence) + rime_weight * sentence->weight(), sentence);
      if (ranked.empty()) { sentences->clear(); sentences->push_back(chosen); return; }
      // The first choice of the old order (the dictionary's first word, or Rime's first sentence when no word has
      // these initials) keeps its place unless another candidate's score is higher by the margin: near a tie the
      // dictionary count and the word model disagree both ways (jyb 进一步 / 加油吧), and a word that was right
      // must not move for that.
      const string kept = initials_first.empty() ? ranked.front().second->text() : initials_first;
      std::stable_sort(ranked.begin(), ranked.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
      if (initials_margin > 0 && ranked.front().second->text() != kept) {
        const auto first = std::find_if(ranked.begin(), ranked.end(),
                                        [&](const auto& item) { return item.second->text() == kept; });
        if (first != ranked.end() && ranked.front().first - first->first < initials_margin)
          std::rotate(ranked.begin(), first, first + 1);
      }
      sentences->clear();
      for (const auto& item : ranked) {
        if (sentences->size() >= static_cast<size_t>(initials_choices)) break;
        sentences->push_back(item.second);
      }
      return;
    }
    // A typo correction never wins on the word model alone. Corrected sentences would also take
    // n-best places from typed readings (qunawanle lost 你去哪玩了 to 你去哪完了 once swapped letters
    // were corrections), so the choice is among sentences made from the same graph without
    // corrected words: the typed n-best, made again when corrections left two typed sentences or
    // fewer (rebuilding whenever one place was taken cost a per-key p95 of +3.5%, this +1.6%).
    // A sentence pays `near_penalty` for each word that is only the second dictionary word of its letters
    // (translator/sentence_near_homophone_margin lets those into the sentence graph: 充了 beside 冲了, 我妈 beside
    // 我吗). The dictionary's first word keeps its place unless the word model prefers the other by more than that.
    auto near_words = [](const Sentence& sentence) {
      const auto& words = sentence.components();
      return std::count_if(words.begin(), words.end(), [](const DictEntry& word) { return word.near_homophone; });
    };
    auto total = [&](const Sentence& sentence) {
      return score(sentence) + rime_weight * sentence.weight() - near_penalty * near_words(sentence);
    };
    deque<an<Sentence>> pool;
    for (const auto& sentence : *sentences)
      if (!corrected(sentence)) pool.push_back(sentence);
    // Sentences through a second dictionary word come on top of the n-best, so they do not count here.
    const auto typed = std::count_if(pool.begin(), pool.end(), [&](const auto& s) { return !near_words(*s); });
    if (typed <= 2 && pool.size() < sentences->size() && sentence_graph && poet) {
      // Spans holding no corrected syllable keep all their words without looking at each one.
      vector<std::pair<size_t, size_t>> corrected_edges;
      for (const auto& from : q.syllables.edges)
        for (const auto& to : from.second)
          for (const auto& spelling : to.second)
            if (spelling.second.is_correction) { corrected_edges.emplace_back(from.first, to.first); break; }
      WordGraph typed;
      for (const auto& from : *sentence_graph)
        for (const auto& to : from.second) {
          const bool clean = std::none_of(corrected_edges.begin(), corrected_edges.end(), [&](const auto& edge) {
            return edge.first >= from.first && edge.second <= to.first;
          });
          auto& bucket = typed[from.first][to.first];
          if (clean) { bucket = to.second; continue; }
          for (const auto& entry : to.second)
            if (!q.IsCorrection(entry->code, to.first, from.first)) bucket.push_back(entry);
        }
      auto remade = poet->MakeSentences(typed, q.syllables.interpreted_length,
                                        q.translator->GetPrecedingText(q.start), max_sentences, cutoff);
      for (auto& sentence : remade) {
        sentence->Offset(q.start);
        sentence->set_syllabifier(q.syllabifier);
      }
      if (exact_sentence && !corrected(exact_sentence)) remade.push_front(exact_sentence);
      if (!remade.empty()) pool = std::move(remade);
    }
    double chosen_score = -std::numeric_limits<double>::infinity();
    // The typed choice is made without the sentences through a second dictionary word, and the corrections below
    // are judged against it as before; the best of those sentences takes the first place only at the end, when
    // no correction did and its score, penalty paid, is still the higher one.
    an<Sentence> near_choice;
    double near_score = -std::numeric_limits<double>::infinity();
    vector<std::pair<double, an<Sentence>>> ranked;
    for (const auto& sentence : pool) {
      const double value = total(*sentence);
      if (runners_up) ranked.emplace_back(value, sentence);
      if (near_words(*sentence)) {
        if (value > near_score) { near_score = value; near_choice = sentence; }
        continue;
      }
      if (value > chosen_score) { chosen_score = value; chosen = sentence; }
    }
    // `built` (t9/model_sentences): whole sentences the caller built with the word model. They are not made from
    // this graph, so they stand beside the pool as the sentences through a second dictionary word do: each word the
    // caller marked pays `near_penalty`, and the best one leads only at the end, the typed choice after it.
    // `built_rime_weight` weighs Rime's own sentence weights into this comparison alone (the typed choice above is
    // made without it).
    // A sentence through a second dictionary word that led while keys arrived (it beats the typed choice) and loses
    // the first place to a built one follows it, before the typed choice: what was first before the pause stays in
    // the menu, second. Not when the built one (or the typed choice) is the same text in other words.
    an<Sentence> near_led;
    // `built_fuzzy` (beside `built`, t9/model_sentences_fuzzy_penalty): how many syllables of each built sentence are
    // read only through a fuzzy spelling (模糊音); each costs `fuzzy_penalty`. Such a sentence guesses that the letters
    // were spelt with an accent: when one leads, the sentence that leads without them (what the comparison chose before
    // they joined) follows it, so a sentence typed right stays in the menu.
    an<Sentence> typed_led;
    if (built) {
      if (near_choice && near_score > chosen_score) near_led = near_choice;
      an<Sentence> typed_choice = near_choice;
      double typed_score = near_score;
      for (size_t i = 0; i < built->size(); ++i) {
        const auto& sentence = (*built)[i];
        const int fuzzy = built_fuzzy && i < built_fuzzy->size() ? (*built_fuzzy)[i] : 0;
        const double value = total(*sentence) + built_rime_weight * (sentence->weight() - chosen->weight()) -
                             fuzzy * fuzzy_penalty;
        if (runners_up) ranked.emplace_back(value, sentence);
        if (value > near_score) { near_score = value; near_choice = sentence; }
        if (!fuzzy && value > typed_score) { typed_score = value; typed_choice = sentence; }
      }
      if (typed_choice && typed_choice != near_choice && typed_score > chosen_score &&
          typed_choice->text() != near_choice->text() && typed_choice->text() != chosen->text())
        typed_led = typed_choice;
      if (near_led && (near_led == near_choice || near_led->text() == near_choice->text() ||
                       near_led->text() == chosen->text() || (typed_led && near_led->text() == typed_led->text())))
        near_led.reset();
    }
    if (runners_up) {
      std::stable_sort(ranked.begin(), ranked.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
      runners_up->clear();
      for (const auto& item : ranked) runners_up->emplace_back(ranked.front().first - item.first, item.second);
    }
    // With stray initials (Syllabifier::MarkStrayInitials) the typed reading stands only through initials weighed as
    // slips, so the corrected n-best is chosen by the word model too and leads (清晨 over 清真 in 是清晨的一杯).
    if (q.syllables.cyime_stray_initials && q.enable_correction && !slip_only) {
      // t9/extra_corrections_pay_margin: every correction after a sentence's first costs `slip_margin` here too.
      // Without it a sentence that also left out a letter pressed twice led for being a word shorter
      // (nizhdjia...butinghuaa: 不听话 over 不听话啊; burannixanle: 不然闲了 over 不然你惨了).
      const bool pay = slip_margin > 0 && q.translator && q.translator->extra_corrections_pay_margin();
      an<Sentence> fixed;
      double fixed_score = -std::numeric_limits<double>::infinity();
      for (const auto& sentence : *sentences) {
        if (!corrected(sentence)) continue;
        double value = total(*sentence);
        if (pay) {
          const string marks = sentence->correction_marks();
          value -= slip_margin * std::max<std::ptrdiff_t>(0, std::count(marks.begin(), marks.end(), '=') - 1);
        }
        if (value > fixed_score) { fixed_score = value; fixed = sentence; }
      }
      // t9/stray_correction_margin (ln units, on the scheme's own translator): the corrected sentence leads only when
      // its score beats the typed choice's by this much. A word typed by its initials inside a sentence of full pinyin
      // also leaves stray initials, and the corrected reading of them led it whatever the word model said. Unset: the
      // corrected sentence always leads, as before.
      const double margin = q.translator ? q.translator->stray_correction_margin()
                                         : -std::numeric_limits<double>::infinity();
      if (stray_decided && std::isfinite(margin)) *stray_decided = true;
      if (fixed && fixed_score > chosen_score + margin) {
        sentences->clear();
        sentences->push_back(fixed);
        sentences->push_back(chosen);
        return;
      }
    }
    // A slip in the last syllable (nihso, jintisn, xiexir, nihap): the typed letters only read as abbreviations
    // (你受, 近体是你, 歇息人), which MarkStrayInitials leaves alone because the last syllable may still be typed.
    // The corrected sentence leads when the typed letters cannot be read in full, its corrections are all in a
    // last word of three letters or more, and it beats the typed choice by `tail_correction_margin`
    // (t9/tail_correction_margin, ln units; 0 keeps the typed reading first as before). The typed choice follows.
    if (tail_correction_margin > 0 && q.enable_correction && std::isfinite(chosen_score) && !corrected(chosen) &&
        !TypedReadsInFull(q.syllables)) {
      an<Sentence> tail;
      double tail_score = -std::numeric_limits<double>::infinity();
      for (const auto& sentence : *sentences) {
        if (!corrected(sentence) || !CorrectedOnlyAtTail(q, *sentence)) continue;
        const double value = total(*sentence);
        if (value > tail_score) { tail_score = value; tail = sentence; }
      }
      if (tail && tail_score > chosen_score + tail_correction_margin) {
        sentences->clear();
        sentences->push_back(tail);
        sentences->push_back(chosen);
        return;
      }
    }
    // A slip anywhere in a sentence (2026-11-13, nichifanoema: 你吃饭哦饿吗 led 你吃饭了吗). Pinyin is dense, so the
    // letters of a slip nearly always still read as something and the typed reading kept the first place whatever it
    // said. A corrected sentence among Rime's n-best now leads when the word model finds it more likely than the
    // typed choice by `slip_margin` for every corrected syllable (t9/slip_correction_margin, ln units; 0: off).
    // The typed choice follows.
    if (slip_margin > 0 && q.enable_correction && std::isfinite(chosen_score) && !corrected(chosen)) {
      an<Sentence> fixed;
      double fixed_score = chosen_score;
      for (const auto& sentence : *sentences) {
        if (!corrected(sentence)) continue;
        const string marks = sentence->correction_marks();
        const auto slips = std::max<std::ptrdiff_t>(1, std::count(marks.begin(), marks.end(), '='));
        const double value = total(*sentence) - slip_margin * slips;
        if (value > fixed_score) { fixed_score = value; fixed = sentence; }
      }
      if (fixed) {
        sentences->clear();
        sentences->push_back(fixed);
        sentences->push_back(chosen);
        return;
      }
    }
    if (near_choice && near_score > chosen_score) {
      sentences->clear();
      sentences->push_back(near_choice);
      if (typed_led) sentences->push_back(typed_led);
      if (near_led) sentences->push_back(near_led);
      sentences->push_back(chosen);
      return;
    }
  }
  sentences->clear();
  sentences->push_back(chosen);
  if (rime_best && rime_best != chosen && corrected(rime_best)) sentences->push_back(rime_best);
}

namespace {
// ln P(text) under the word model, over its best split into model words (up to 8 characters); the first word
// is not scored as a sentence start (the 14-key schema does not pass the committed text).
double T9TextLogProb(const T9NgramModel* ngram, const string& text) {
  vector<size_t> at{0};
  for (size_t i = 0; i < text.size();) {
    const unsigned char c = static_cast<unsigned char>(text[i]);
    i += c < 0x80 ? 1 : c < 0xE0 ? 2 : c < 0xF0 ? 3 : 4;
    at.push_back(std::min(i, text.size()));
  }
  const size_t n = at.size() - 1;
  struct State { double value; int32_t prev2, prev1; };
  vector<vector<State>> states(n + 1);
  constexpr size_t kStates = 4;
  for (size_t end = 1; end <= n; ++end) {
    auto& here = states[end];
    for (size_t start = end > 8 ? end - 8 : 0; start < end; ++start) {
      if (start && states[start].empty()) continue;
      const int32_t id = ngram->Id(text.substr(at[start], at[end] - at[start]));
      if (id == ::T9NgramModel::kUnseen && end - start > 1) continue;
      vector<State> from = start ? states[start] : vector<State>{{0, ::T9NgramModel::kUnseen, ::T9NgramModel::kUnseen}};
      for (const State& s : from) {
        const double value = s.value + (start ? ngram->LogProb3(s.prev2, s.prev1, id) : ngram->Start(id, false));
        const State next{value, s.prev1, id};
        auto same = std::find_if(here.begin(), here.end(), [&](const State& o) { return o.prev1 == id; });
        if (same != here.end()) { if (value > same->value) *same = next; continue; }
        if (here.size() < kStates) { here.push_back(next); continue; }
        auto worst = std::min_element(here.begin(), here.end(),
                                      [](const State& a, const State& b) { return a.value < b.value; });
        if (value > worst->value) *worst = next;
      }
    }
  }
  double best = -std::numeric_limits<double>::infinity();
  for (const State& s : states[n]) best = std::max(best, s.value);
  return best;
}
}  // namespace

void T9PreferMergedTailWord(const T9ScriptQuery& q, const T9NgramModel* ngram, double margin,
                            const an<DictEntry>& word, deque<an<Sentence>>* sentences) {
  if (!ngram || margin <= 0 || !word || sentences->empty()) return;
  const auto& sentence = sentences->front();
  if (sentence->text() == word->text) return;
  // 都是 -7.3 against 都啊 -15.5 goes first; 真的是 -8.2 against 真的啊 -9.6 does not (margin ln 20, the gate
  // used for other readings the letters only partly spell), nor 猪上树 against 智商啊.
  if (T9TextLogProb(ngram, word->text) <= T9TextLogProb(ngram, sentence->text()) + margin) return;
  auto first = New<Sentence>(q.translator->language());
  first->Extend(*word, q.end_of_input - q.start, word->weight);
  first->Offset(q.start);
  first->set_syllabifier(q.syllabifier);
  first->set_weight(sentence->weight());
  first->set_quality(sentence->quality());
  sentences->push_front(first);
}

}  // namespace rime
