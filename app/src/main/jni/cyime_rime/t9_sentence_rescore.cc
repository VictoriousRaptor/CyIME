#include "t9_sentence_rescore.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <unordered_map>
#include <vector>

#include <rime/gear/poet.h>
#include <rime/gear/script_translator.h>
#include <rime/gear/translator_commons.h>
#include <t9_ngram_model.h>

namespace rime {

void T9RescoreSentences(const T9ScriptQuery& q, const T9NgramModel* ngram, bool ngram_sentence_start,
                        size_t keys_per_syllable, double rime_weight, Poet* poet,
                        const WordGraph* sentence_graph, int max_sentences, double cutoff,
                        const an<Sentence>& exact_sentence, const an<Sentence>& rime_best,
                        deque<an<Sentence>>* sentences) {
  // translator/max_sentences gave the n-best to choose from; the menu keeps one sentence, as with
  // max_sentences: 1 (followed by Rime's best when that one is a correction).
  auto corrected = [&](const an<Sentence>& sentence) {
    return q.enable_correction && q.IsCorrection(sentence->code(), sentence->end() - q.start);
  };
  an<Sentence> chosen = sentences->front();
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
  // Initials and short abbreviations (zjqa, zdky) keep Rime's dictionary order.
  if (ngram && !consonants_only && groups >= 2) {
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
      struct State { double value; int32_t prev2, prev1; bool started; };
      constexpr size_t kStates = 4;
      auto log_add = [](double a, double b) {
        if (!std::isfinite(a)) return b;
        if (!std::isfinite(b)) return a;
        return std::max(a, b) + std::log1p(std::exp(-std::fabs(a - b)));
      };
      const int32_t root = ngram_sentence_start ? ngram->Bos() : ::T9NgramModel::kUnseen;
      vector<vector<State>> states(n + 1);
      states[0].push_back({0, root, root, false});
      for (size_t end = 1; end <= n; ++end) {
        auto& here = states[end];
        for (size_t start = end > 8 ? end - 8 : 0; start < end; ++start) {
          if (states[start].empty()) continue;
          const int32_t id = word_id(text.substr(at[start], at[end] - at[start]));
          if (id == ::T9NgramModel::kUnseen && end - start > 1) continue;
          for (const State& from : states[start]) {
            const double value = from.value + (from.started
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
    // A typo correction never wins on the word model alone. Corrected sentences would also take
    // n-best places from typed readings (qunawanle lost 你去哪玩了 to 你去哪完了 once swapped letters
    // were corrections), so the choice is among sentences made from the same graph without
    // corrected words: the typed n-best, made again when corrections left two typed sentences or
    // fewer (rebuilding whenever one place was taken cost a per-key p95 of +3.5%, this +1.6%).
    deque<an<Sentence>> pool;
    for (const auto& sentence : *sentences)
      if (!corrected(sentence)) pool.push_back(sentence);
    if (pool.size() <= 2 && pool.size() < sentences->size() && sentence_graph && poet) {
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
    for (const auto& sentence : pool) {
      const double value = score(*sentence) + rime_weight * sentence->weight();
      if (value > chosen_score) { chosen_score = value; chosen = sentence; }
    }
    // With stray initials (Syllabifier::MarkStrayInitials) the typed reading stands only through initials weighed as
    // slips, so the corrected n-best is chosen by the word model too and leads (清晨 over 清真 in 是清晨的一杯).
    if (q.syllables.cyime_stray_initials && q.enable_correction) {
      an<Sentence> fixed;
      double fixed_score = -std::numeric_limits<double>::infinity();
      for (const auto& sentence : *sentences) {
        if (!corrected(sentence)) continue;
        const double value = score(*sentence) + rime_weight * sentence->weight();
        if (value > fixed_score) { fixed_score = value; fixed = sentence; }
      }
      if (fixed) {
        sentences->clear();
        sentences->push_back(fixed);
        sentences->push_back(chosen);
        return;
      }
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
