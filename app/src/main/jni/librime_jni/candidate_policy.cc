#include "candidate_policy.h"
#include "candidate_pins.h"
#include "basic_particle_choices.h"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <functional>
#include <map>
#include <set>
#include <sstream>
#include <utility>
#include <utf8.h>
#include <rime/candidate.h>
#include <rime/config.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/filter.h>
#include <rime/menu.h>
#include <rime/schema.h>
#include <rime/translator.h>
#include <rime/dict/dictionary.h>
#include <rime/gear/memory.h>
#include <rime/gear/translator_commons.h>
#include <rime/language.h>
#include <rime/registry.h>
#include <rime/resource.h>
#include <rime/service.h>
#include <rime/translation.h>
#include <t9_ngram_model.h>
#include <t9_pinyin_map.h>
#include <t9_sentence_scorer.h>

namespace cyime {
namespace {
using namespace rime;
// All access belongs to the serialized Rime engine, including initialization.
std::set<std::string> hidden;
std::string hidden_path;
thread_local Context* omission_context = nullptr;
thread_local bool omission_inserted = false;
thread_local an<Candidate> pending_digit_edit;

std::vector<std::string> HanCharacters(const std::string& text) {
  std::vector<std::string> chars;
  auto it = text.begin();
  while (it != text.end()) {
    const auto begin = it;
    const auto cp = utf8::next(it, text.end());
    if (!((cp >= 0x3400 && cp <= 0x9fff) || (cp >= 0x20000 && cp <= 0x323af)))
      return {};
    chars.emplace_back(begin, it);
  }
  return chars;
}

std::vector<size_t> SyllableEnds(const an<Phrase>& phrase) {
  std::vector<size_t> ends;
  const auto spans = phrase->spans();
  for (size_t position = phrase->start(); position < phrase->end();) {
    const auto next = spans.NextStop(position);
    if (next <= position) return {};
    ends.push_back(next);
    position = next;
  }
  return ends;
}

// Two sentences that end in the same word over the same keys (济南吗 and 礼貌吗): they differ only in the
// words before it, so the second is an alternative to the first, not another segmentation of the input.
bool SameLastWord(const an<Phrase>& phrase, const an<Phrase>& best) {
  const auto a = As<Sentence>(phrase);
  const auto b = As<Sentence>(best);
  if (!a || !b || a->components().size() < 2 || b->components().size() < 2 ||
      a->word_lengths().empty() || b->word_lengths().empty())
    return false;
  return a->components().back().text == b->components().back().text &&
         a->word_lengths().back() == b->word_lengths().back();
}

// A word reached through a spelling correction (neighbour key, swapped letters…): some syllable reads "typed=correct".
bool Corrected(const an<Phrase>& phrase) {
  return phrase && phrase->correction_marks().find('=') != std::string::npos;
}

// Every corrected syllable is the typed letters with one key replaced, same length (wp=wo, ni for nu): a slip of one
// key on a whole syllable. Not a reading that grows the letters into a longer syllable (ok read as kong).
bool OneKeySubstitution(const std::string& marks) {
  std::istringstream syllables(marks);
  bool corrected = false;
  for (std::string syllable; syllables >> syllable;) {
    const auto eq = syllable.find('=');
    if (eq == std::string::npos) continue;
    const auto typed = syllable.substr(0, eq), correct = syllable.substr(eq + 1);
    if (typed.size() != correct.size()) return false;
    size_t changed = 0;
    for (size_t i = 0; i < typed.size(); ++i) changed += typed[i] != correct[i];
    if (changed != 1) return false;
    corrected = true;
  }
  return corrected;
}

// A whole-input candidate that is the typed letters in other capitals (OK when ok was typed).
bool SameWordOtherCase(const an<Candidate>& c, const std::string& input, size_t end) {
  const auto& text = c->text();
  if (c->start() != 0 || c->end() != end || text.size() != input.size() || text == input) return false;
  for (size_t i = 0; i < text.size(); ++i)
    if (std::tolower(static_cast<unsigned char>(text[i])) != std::tolower(static_cast<unsigned char>(input[i])))
      return false;
  return true;
}

double SpellingCoverage(const an<Phrase>& phrase) {
  double complete = phrase->entry().quality_len;
  if (const auto sentence = As<Sentence>(phrase)) {
    complete = 0;
    for (const auto& part : sentence->components()) complete += part.quality_len;
  }
  return complete / std::max<size_t>(1, phrase->end() - phrase->start());
}

// The candidate reads every key of the input with full syllables (no initials-only part).
bool WholeAndSpelt(const an<Candidate>& candidate, size_t end) {
  const auto phrase = As<Phrase>(Candidate::GetGenuineCandidate(candidate));
  return phrase && candidate->start() == 0 && candidate->end() == end && SpellingCoverage(phrase) >= 1.0;
}

// Kinds of single edit a nine-key lookup may offer (the keyboard's 拼音纠错 switches, see RefineOmission).
enum : int { kEditOmission = 1, kEditSwap = 2, kEditTap = 4, kEditAll = 7, kEditExtra = 8 };

// The auxiliary prism can match an edit in several syllables. Only offer a
// whole reading that differs from the actual input by one edit in total.
bool IsSingleSpellingEdit(const string& input, const string& pinyin, bool digits, Config* tap_grid = nullptr,
                          int kinds = kEditAll) {
  string expected;
  for (char ch : pinyin) {
    if (ch == ' ') continue;
    if (ch < 'a' || ch > 'z') return false;
    expected += digits ? T9PinyinMap::LetterToDigit(ch) : ch;
  }
  size_t pos = 0;
  while (pos < input.size() && pos < expected.size() && input[pos] == expected[pos]) ++pos;
  if (tap_grid) {
    string adjacent;
    return expected.size() == input.size() && pos < input.size() &&
           input.compare(pos + 1, string::npos, expected, pos + 1, string::npos) == 0 &&
           tap_grid->GetString("cyime_tap_neighbors/" + string(1, input[pos]), &adjacent) &&
           adjacent.find(expected[pos]) != string::npos;
  }
  if (digits && expected.size() == input.size() + 1)
    return (kinds & kEditOmission) && expected.compare(pos + 1, string::npos, input, pos, string::npos) == 0;
  return (kinds & kEditSwap) && expected.size() == input.size() && pos + 1 < input.size() &&
         input[pos] == expected[pos + 1] && input[pos + 1] == expected[pos] &&
         input.compare(pos + 2, string::npos, expected, pos + 2, string::npos) == 0;
}

// ScriptTranslator consults its engine's schema and current input when querying.
// Use the small edit prism's own engine so it cannot start the active keyboard's
// joint decoder, sentence model, or learning callbacks a second time.
// Double pinyin (every syllable is two keys): the one-key slip a reading from the pause prism assumes, by the keys
// the whole input has for its characters. One short: a key was left out. One over: a key was pressed twice. Even:
// two keys are swapped. (Not by each syllable's span: the search for spans takes the longest spelling first, and
// reads vege typed veeg as vee + g, two slips, where it is ve + eg, one.)
int KeyEdit(size_t keys, size_t expected) {
  return keys + 1 == expected ? kEditOmission : keys == expected + 1 ? kEditExtra : keys == expected ? kEditSwap : 0;
}

int TwoKeyEdit(const std::string& text, size_t keys) { return KeyEdit(keys, 2 * HanCharacters(text).size()); }

// One key a letter (14 keys, cyime_pause_correction/letters): the slip kind from the reading's syllables spelt with the
// merged keys (qw -> Q ..., l and m alone) against the keys typed. Exactly one key left out, one key pressed twice
// (next to itself) or two neighbouring keys swapped; a reading spelt as typed is no slip (0). Counting letters alone
// took every other reading of the same length for a swap (2026-11-20 PC check: 已经用得很失望了 for 已经用得很爽了).
int LetterKeyEdit(const an<Candidate>& candidate, Dictionary* dict, const std::string& input) {
  const auto phrase = As<Phrase>(Candidate::GetGenuineCandidate(candidate));
  std::vector<std::string> syllables;
  if (!phrase || !dict || !dict->Decode(phrase->code(), &syllables) || syllables.empty()) return 0;
  static const std::string kFrom = "qwertyuiopasdfghjkzxcvbn", kTo = "QQEETTUUOOAADDGGJJZZCCBB";
  std::string spelt, keys;
  for (const auto& syllable : syllables)
    for (char c : syllable) {
      const auto at = kFrom.find(c);
      spelt.push_back(at == std::string::npos ? c : kTo[at]);
    }
  for (char c : input) if (c != '\'' && c != ' ') keys.push_back(c);
  if (spelt == keys) return 0;
  auto without = [](const std::string& value, size_t at) { return value.substr(0, at) + value.substr(at + 1); };
  if (keys.size() + 1 == spelt.size()) {
    for (size_t i = 0; i < spelt.size(); ++i) if (without(spelt, i) == keys) return kEditOmission;
  } else if (keys.size() == spelt.size() + 1) {
    for (size_t i = 0; i + 1 < keys.size(); ++i) if (keys[i] == keys[i + 1] && without(keys, i) == spelt) return kEditExtra;
  } else if (keys.size() == spelt.size()) {
    size_t first = 0;
    while (first < keys.size() && keys[first] == spelt[first]) ++first;
    if (first + 1 < keys.size() && keys[first] == spelt[first + 1] && keys[first + 1] == spelt[first] &&
        keys.compare(first + 2, std::string::npos, spelt, first + 2, std::string::npos) == 0) return kEditSwap;
  }
  return 0;
}

class EditLookup : public Engine {
 public:
  EditLookup(const string& id, const string& active_dictionary) {
    schema_.reset(new Schema(id));
    string prism;
    auto component = Translator::Require("script_translator");
    if (component && schema_->config()->GetString("translator/prism", &prism) && prism == id &&
        schema_->config()->GetString("translator/dictionary", &dictionary_) && dictionary_ == active_dictionary) {
      translator_.reset(component->Create(Ticket(this, "translator")));
      schema_->config()->GetString("cyime_tap_schema", &tap_schema_);
    }
  }

  an<Candidate> Best(const string& input, bool digits, const string& original_first, int kinds = kEditAll) {
    auto hit = Lookup(input);
    if (hit && IsSingleSpellingEdit(input, hit->comment(), digits, nullptr, kinds)) return hit;
    // Two independent decodings agree with the original reading. Looking for
    // a substituted key here mostly introduces alternatives to correctly typed text.
    if (!digits || tap_schema_.empty() || !(kinds & kEditTap) || (hit && hit->text() == original_first)) return nullptr;
    if (!taps_) taps_.reset(new EditLookup(tap_schema_, dictionary_));
    auto neighbor = taps_->Lookup(input);
    return neighbor && IsSingleSpellingEdit(input, neighbor->comment(), true, taps_->schema()->config()) ?
        neighbor : nullptr;
  }

 private:
  an<Candidate> Lookup(const string& input) {
    if (!translator_) return nullptr;
    context_->set_input(input);
    Segment segment(0, input.size());
    segment.tags.insert("abc");
    auto matches = translator_->Query(input, segment);
    auto candidate = matches && !matches->exhausted() ? matches->Peek() : nullptr;
    return candidate && candidate->start() == 0 && candidate->end() == input.size() ? candidate : nullptr;
  }

  string dictionary_, tap_schema_;
  the<Translator> translator_;
  the<EditLookup> taps_;
};

class PolicyTranslation : public Translation {
 public:
  PolicyTranslation(an<Translation> source, const std::string& input, bool numeric, Translator* omission,
                    EditLookup* edits, an<Candidate> edit, BasicParticleChoices* particles, size_t particle_start,
                    bool drop_corrections = false, double correction_lead_bias = 0.0, int pause_kinds = -1,
                    const std::function<bool(const an<Candidate>&, const an<Candidate>&, bool, int, bool)>& pause_likely = nullptr,
                    Dictionary* pause_letters = nullptr, bool merged_corrections = false)
      : source_(source), end_(input.size()), drop_corrections_(drop_corrections),
        merged_corrections_(merged_corrections) {
    // Bound the work independently of dictionary size. This is native candidate
    // identity-preserving ordering, not a separate UI list with stale indices.
    for (size_t i = 0; i < 64 && !source_->exhausted(); ++i) {
      auto cand = Prepare(source_->Peek());
      source_->Next();
      if (cand) head_.push_back(cand);
    }
    // Chinese candidates use native matching evidence and native weights only.
    // Preserve the slots of English, dates, commands and pinned non-Phrase items.
    struct Ranked { an<Candidate> candidate; int group; double coverage; };
    std::vector<size_t> slots;
    std::vector<Ranked> ranked;
    an<Phrase> best_sentence;
    for (const auto& c : head_) {
      auto p = As<Sentence>(Candidate::GetGenuineCandidate(c));
      if (p && (!best_sentence || p->weight() > best_sentence->weight())) best_sentence = p;
    }
    const auto best_ends = best_sentence ? SyllableEnds(best_sentence) : std::vector<size_t>();
    // 26 keys: after the whole-input words, the shorter words that break the input where the first one does come
    // before those that read its syllables another way (2026-11-06, kejishuip: 科技 and 客机 before 科技社会, which
    // reads shui as s + hui, and 科技树, which stops inside shui). Both stay; only their order changes.
    std::vector<size_t> whole_ends;
    if (!numeric) {
      for (const auto& c : head_) {
        const auto p = As<Phrase>(Candidate::GetGenuineCandidate(c));
        if (p && p->end() == end_ && !HanCharacters(p->text()).empty()) { whole_ends = SyllableEnds(p); break; }
      }
    }
    for (size_t i = 0; i < head_.size(); ++i) {
      const auto p = As<Phrase>(Candidate::GetGenuineCandidate(head_[i]));
      if (!p || HanCharacters(p->text()).empty()) continue;
      int group = p->end() == end_ ? 0 : 1;
      // Alternative T9 segmentations remain accessible, after dictionary prefixes.
      // Words on the best-scoring segmentation (including ambiguous final initials)
      // keep competing by existing weights; no text blacklist or external model.
      // A sentence ending in the best sentence's last word stays with it (the T9 decoder shows one such
      // sentence after one ending in a particle: 直播吗, then 遗憾吗).
      if (numeric && As<Sentence>(p) && !best_ends.empty() && SyllableEnds(p) != best_ends &&
          !SameLastWord(p, best_sentence))
        group = 2;
      if (group == 1 && !whole_ends.empty()) {
        const auto ends = SyllableEnds(p);
        if (ends.empty() || std::any_of(ends.begin(), ends.end(), [&](size_t e) {
              return std::find(whole_ends.begin(), whole_ends.end(), e) == whole_ends.end();
            }))
          group = 2;
      }
      slots.push_back(i);
      ranked.push_back({head_[i], group, numeric ? SpellingCoverage(p) : 0.0});
    }
    std::stable_sort(ranked.begin(), ranked.end(), [](const auto& a, const auto& b) {
      if (a.group != b.group) return a.group < b.group;
      // Native translation already orders by word weights and explicit learned
      // preference. Keep that order instead of overriding user-history priority.
      return false;
    });
    // Do not reorder ordinary dictionary words by spelling coverage: a single
    // 3 must retain frequent d-initial words ahead of rare full-syllable e words.
    // Only the best full-spelling composition may precede an abbreviated whole
    // word. This repairs the sentence's missing native quality without promoting
    // every alternative sentence or assigning an arbitrary insertion position.
    if (numeric && best_sentence && SpellingCoverage(best_sentence) >= 1.0) {
      const auto sentence = std::find_if(ranked.begin(), ranked.end(), [&](const auto& r) {
        return Candidate::GetGenuineCandidate(r.candidate) == best_sentence;
      });
      const auto weaker = std::find_if(ranked.begin(), sentence, [](const auto& r) {
        return r.group == 0 && r.coverage < 1.0;
      });
      if (sentence != ranked.end() && weaker != sentence)
        std::rotate(weaker, sentence, sentence + 1);
    }
    for (size_t i = 0; i < slots.size(); ++i) head_[slots[i]] = ranked[i].candidate;
    KeepFixedOrder();
    // Fuzzy pinyin (模糊音) only adds readings: the first word is always one the typed letters spell (2026-11-05:
    // with n/l on, ne gave 了 before 呢 and neng 冷 before 能, the frequent fuzzy word winning by weight). The best
    // such word moves up to the first fuzzy word's place; the fuzzy words keep their order after it. Sentences
    // stay where they are, and nine-key digits keep their own order.
    if (!numeric) {
      auto whole_word = [&](const an<Candidate>& c) -> an<Phrase> {
        const auto p = As<Phrase>(Candidate::GetGenuineCandidate(c));
        return p && !As<Sentence>(p) && p->end() == end_ && !HanCharacters(p->text()).empty() ? p : nullptr;
      };
      const auto first = std::find_if(head_.begin(), head_.end(), [&](const auto& c) { return whole_word(c) != nullptr; });
      if (first != head_.end() && whole_word(*first)->fuzzy_spelling()) {
        const auto typed = std::find_if(first + 1, head_.end(), [&](const auto& c) {
          const auto p = whole_word(c);
          return p && !p->fuzzy_spelling();
        });
        if (typed != head_.end()) std::rotate(first, typed, typed + 1);
      }
    }
    // A whole English word should not lose to guessed Chinese abbreviations.
    // Fully spelled Chinese (e.g. you -> 有) and explicit non-dictionary entries
    // retain their native priority. No word list or fixed candidate is injected.
    // Nor does it lose to a word reached only through a spelling correction (2026-11-11: with 邻键纠错 on, a
    // corrected 空 "kong" in second place kept ok below it).
    // The same word written another way (OK for ok, US for us: the English dictionary has both, with the same
    // code) does not stop it either: before, ok stopped right behind OK, which came after the guessed 偶看 欧卡
    // 藕块 欧坑, so ok was 6th with the switch off too (2026-11-11, tablet probe). The variant keeps its place
    // after the Chinese words.
    if (!numeric && !input.empty() && input.find_first_not_of("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ") == std::string::npos) {
      const auto exact = std::find_if(head_.begin(), head_.end(), [&](const auto& c) {
        return c->start() == 0 && c->end() == end_ && c->text() == input && c->type() == "table";
      });
      if (exact != head_.end()) {
        auto insertion = exact;
        while (insertion != head_.begin()) {
          auto previous = insertion - 1;
          const auto phrase = As<Phrase>(Candidate::GetGenuineCandidate(*previous));
          if (SameWordOtherCase(*previous, input, end_)) { insertion = previous; continue; }
          if (!phrase || HanCharacters(phrase->text()).empty() ||
              (SpellingCoverage(phrase) >= 1.0 && !Corrected(phrase))) break;
          insertion = previous;
        }
        std::rotate(insertion, exact, exact + 1);
      }
    }
    // Imported English words (CellDictionaryEnglish) come right after the first Chinese
    // candidate on nine keys, where short digit codes collide with common Chinese: they
    // never take the first place from it.
    if (!input.empty() && input.find_first_not_of("0123456789' ") == std::string::npos) {
      const auto chinese = std::find_if(head_.begin(), head_.end(), [](const auto& c) {
        const auto p = As<Phrase>(Candidate::GetGenuineCandidate(c));
        return !p || !p->language() || p->language()->name().rfind("user_cyime_cells_en", 0) != 0;
      });
      if (chinese != head_.begin() && chinese != head_.end()) std::rotate(head_.begin(), chinese, chinese + 1);
    }
    // 2026-11-08「九键打不出来 fuck、B站」: the bundled English and mixed words (B站, T恤) of nine keys and 14 keys
    // (t9_english_translator, exact_quality) come right after the Chinese candidates that spell every key of the
    // input in full (nine-key bang: 帮 棒 绑 … 傍), and at the earliest in the 6th place; 26 keys' mixed words
    // (cn_en) move up to that place when they come later. Before, they came after every Chinese word (shit 87th
    // on nine keys, B站 118th on 14 keys and 45th on 26 keys). A first version put them 6th outright, which moved
    // a fully spelled 傍 from 20th to 21st (nine-key replay gate, 2026-11-08). A learned one is left where its
    // learned quality puts it (first after one pick); 26 keys' English words (melt_eng) keep their rule above.
    // 2026-11-11「都按权重算」: mixed words (B站, 哆啦A梦, C罗) are no longer placed here. Typed in full they sat 6th,
    // off the candidate bar; they now carry public-corpus weights on the Chinese scale (data/cyime_mixed_word_counts.tsv)
    // and stand where Rime's quality puts them. Only pure English words keep this rule.
    {
      constexpr size_t kSlot = 5;
      auto bundled = [&](const an<Candidate>& c, bool* pull_only) {
        const auto p = As<Phrase>(Candidate::GetGenuineCandidate(c));
        if (!p || !p->language() || c->end() != end_ || p->entry().commit_count > 0 ||
            (p->type() != "table" && p->type() != "user_table")) return false;
        const auto& name = p->language()->name();
        *pull_only = false;
        if (name != "cyime_t9_english" && name != "cyime_k14_english") return false;
        // Four keys or more: shorter English words (go, the) stay after the Chinese ones. Mixed words: by weight.
        return c->end() - c->start() >= 4 && HanCharacters(p->text()).empty();
      };
      // A Chinese candidate reading every key of the input as a full letter of its pinyin (one letter per key on
      // 9/14/26 keys), not an abbreviation: what a typed word is, at the end of a syllable.
      auto fully_spelled = [&](const an<Candidate>& c) {
        const auto p = As<Phrase>(Candidate::GetGenuineCandidate(c));
        if (!p || c->end() != end_ || HanCharacters(p->text()).empty()) return false;
        size_t keys = 0, letters = 0;
        for (size_t i = c->start(); i < end_ && i < input.size(); ++i) keys += input[i] != ' ' && input[i] != '\'';
        for (unsigned char ch : c->comment())
          letters += (ch & 0xC0) != 0x80 && ch != ' ' && ch != '\'' && !(ch >= '0' && ch <= '9');
        return letters > 0 && letters == keys;
      };
      // Positions count the other candidates only. 26 keys' mixed words only move up: one already within the
      // place stays where it is.
      size_t slot = kSlot, others = 0;
      std::vector<int> move(head_.size(), 0);  // 1: placed at the slot, -1: stays
      for (size_t i = 0; i < head_.size(); ++i) {
        bool pull_only = false;
        if (bundled(head_[i], &pull_only)) {
          move[i] = pull_only ? -2 - static_cast<int>(others) : 1;
        } else {
          if (fully_spelled(head_[i])) slot = std::max(slot, others + 1);
          ++others;
        }
      }
      for (auto& m : move) if (m <= -2) m = static_cast<size_t>(-2 - m) <= slot ? -1 : 1;
      CandidateList moved, result;
      for (size_t i = 0; i < head_.size(); ++i) if (move[i] == 1) moved.push_back(head_[i]);
      if (!moved.empty()) {
        size_t emitted = 0;
        bool inserted = false;
        for (size_t i = 0; i < head_.size(); ++i) {
          if (move[i] == 1) continue;
          if (move[i] == 0) {
            if (!inserted && emitted == slot) { result.insert(result.end(), moved.begin(), moved.end()); inserted = true; }
            ++emitted;
          }
          result.push_back(head_[i]);
        }
        if (!inserted) result.insert(result.end(), moved.begin(), moved.end());
        head_.swap(result);
      }
      // (2026-11-09's「a learned mixed word comes first」was removed on 2026-11-11: a pick raises its weight in the
      // user dictionary like any learned word, and the weight decides.)
    }
    // A corrected whole word goes first when it is far more common than the typed reading's word (2026-11-05,
    // replacing the 10-21 rule that kept the typed reading first: fandu gave 贩毒 before 单独). The corrected word's
    // own frequency (its weight without the log 0.01 correction penalty per corrected syllable) must be at least
    // 2000 times the typed word's (2026-11-08, was 50: genshou gave 分手 before 跟手, which is not a slip; at 50 times
    // 18 of 3000 correctly typed dictionary words lost first place to a correction, at 2000 none, and keyifuzhi keeps
    // 可以复制, which is just 1000 times rarer than 可以辅助). Words in the user's own dictionary are never compared:
    // their weights are on another scale (canting gave 反映 over 餐厅 once both were learned), and a learned typed word
    // is the user's own choice; a typed reading that is a sentence keeps its place (promoting over sentences
    // changed 35 of 3158 correctly typed comment clauses, 牛逼 → 就比), and so does a typed word read mostly
    // through initials (ld 老大, not 了; wyjyx 我研究一下, not 网易邮箱). A slip often leaves one letter read as an
    // initial, so a word with at least half of its letters fully spelled still counts as typed out. A single
    // syllable keeps its typed character: one slipped letter there is a different syllable (ga 尬, not 他; ao 奥, not 我;
    // den 扽, not 人, though 的呢 read as de + n comes first here and only cyime_single_syllable moves it down).
    if (!numeric && head_.size() >= 2) {
      auto corrections = [](const an<Phrase>& p) {
        const auto marks = p->correction_marks();
        return static_cast<int>(std::count(marks.begin(), marks.end(), '='));
      };
      const bool syllable = std::any_of(head_.begin(), head_.end(), [&](const auto& c) {
        const auto p = As<Phrase>(Candidate::GetGenuineCandidate(c));
        return p && p->end() == end_ && HanCharacters(p->text()).size() == 1 && corrections(p) == 0;
      });
      const auto top = As<Phrase>(Candidate::GetGenuineCandidate(head_.front()));
      if (!syllable && top && !As<Sentence>(top) && top->end() == end_ && HanCharacters(top->text()).size() >= 2 &&
          SpellingCoverage(top) >= 0.5) {
        // A corrected whole word is first listed by its weight among the typed words (2026-11-12): the translator puts
        // every typed word of the full length before any corrected one, so 已经 for yijin came after ten words read
        // yi'jin, off the first page (of the 1500 most common words, 600 end in ng; with the g left off 484 were not
        // in the first five). Its weight carries the correction penalty (0.01 per corrected syllable), so it only
        // passes typed words at least 100 times rarer, and never takes the first place here: that is decided below.
        // Learned words keep their places (their weights are on another scale).
        {
          size_t best = 0;
          double best_weight = 0;
          for (size_t i = 2; i < head_.size(); ++i) {
            const auto p = As<Phrase>(Candidate::GetGenuineCandidate(head_[i]));
            if (!p || As<Sentence>(p) || p->end() != end_ || head_[i]->start() != 0 || p->type() == "user_phrase" ||
                corrections(p) == 0 || HanCharacters(p->text()).size() < 2) continue;
            if (!best || p->weight() > best_weight) { best = i; best_weight = p->weight(); }
          }
          for (size_t i = 1; best && i < best; ++i) {
            const auto p = As<Phrase>(Candidate::GetGenuineCandidate(head_[i]));
            if (!p || As<Sentence>(p) || p->end() != end_ || p->type() == "user_phrase" || corrections(p) > 0 ||
                HanCharacters(p->text()).empty() || p->weight() >= best_weight) continue;
            std::rotate(head_.begin() + i, head_.begin() + best, head_.begin() + best + 1);
            break;
          }
        }
        const auto limit = head_.begin() + std::min<size_t>(5, head_.size());
        int count = 0;
        const auto corrected = std::find_if(head_.begin() + 1, limit, [&](const auto& c) {
          const auto p = As<Phrase>(Candidate::GetGenuineCandidate(c));
          return p && !As<Sentence>(p) && p->end() == end_ && !HanCharacters(p->text()).empty() &&
                 (count = corrections(p)) > 0;
        });
        // t9/correction_lead_bias (the keyboard's correction strength, ln units) is added to the 2000.
        static const double kPenalty = std::log(100.0);
        const double kMargin = std::log(2000.0) + correction_lead_bias;
        if (corrected != limit && corrections(top) == 0) {
          const auto p = As<Phrase>(Candidate::GetGenuineCandidate(*corrected));
          // Only the first corrected syllable's penalty is given back (2026-11-12): a word that needs two or three
          // slips at once must be 100 times more common again for each further one. With every penalty given back,
          // paofuchan (剖腹产 as commonly misread) gave 排土场 first once a dropped final g counted as a slip too:
          // o for i, f for t and the g, three in one word. That entry has no count in the dictionary at all (the
          // misreadings of others.dict), which reads as rarer than anything: a typed word counts as seen once at least
          // (a dictionary weight is ln count - ln 1e8, Dictionary's kS).
          static const double kOnce = -std::log(1e8);
          if (top->type() != "user_phrase" && p->type() != "user_phrase" && count > 0 &&
              p->weight() + kPenalty >= std::max(top->weight(), kOnce) + kMargin)
            std::rotate(head_.begin(), corrected, corrected + 1);
        }
      }
    }
    // 2026-11-09「打完前面有 B站，顿一下被挤到后面」: the readings added after a pause (omission, swapped letters)
    // never push down a 26-key mixed word whose code is the whole typed input (bzhan B站, txu T恤): it keeps its
    // place among the first ten and the added readings go before or after it. Chinese words, learned ones too, still
    // make room as before (most typed words are learned, so they would keep every correction off the first page).
    std::vector<std::pair<size_t, an<Candidate>>> kept;
    if (!numeric && (omission || edits)) {
      for (size_t i = 2; i < std::min<size_t>(10, head_.size()); ++i) {
        const auto p = As<Phrase>(Candidate::GetGenuineCandidate(head_[i]));
        if (p && p->language() && head_[i]->start() == 0 && head_[i]->end() == end_ && p->type() != "completion" &&
            (p->language()->name() == "cyime_cn_en" || p->language()->name() == "en_dicts/cn_en"))
          kept.emplace_back(i, head_[i]);
      }
    }
    auto weight = [](const an<Candidate>& item) {
      const auto p = As<Phrase>(Candidate::GetGenuineCandidate(item));
      return p ? p->weight() : 0.0;
    };
    auto corrected = [&](const an<Candidate>& item) {
      if (numeric || item->type() == "cyime_omission" || item->end() != end_) return false;
      const auto p = As<Phrase>(Candidate::GetGenuineCandidate(item));
      return p && p->correction_marks().find('=') != std::string::npos;
    };
    size_t inserted = 0;
    if (omission && head_.size() >= 2) {
      Segment segment(0, input.size());
      segment.tags.insert("abc");
      auto alternatives = omission->Query(input, segment);
      // 14 keys (pause_letters): readings spelt as typed come first in the pause prism and are passed over one by one
      // (LetterKeyEdit), so its first reading being the first candidate says nothing (PC check: 时候 for shi huo, whose
      // first reading 失火 is spelt as typed).
      if (alternatives && !alternatives->exhausted() && alternatives->Peek() &&
          (pause_letters || alternatives->Peek()->text() != head_.front()->text())) {
        const auto original = head_;
        // Letter-input corrections the typing already found (neighbour key, swapped, doubled or extra letter)
        // are not pushed down by a rarer omission reading: an omission reading goes above such a correction in the
        // first five only when its word weight is higher (2026-11-03: a pause moved 今天 for jintiab from 3rd to
        // 6th; jntian still gets 今天 above 纪念日). The two translators' qualities are on different scales.
        // Only corrections of the whole input count: a shorter word's weight is not comparable with a sentence's.
        for (size_t i = 0; i < (pause_letters ? 64 : 10) && inserted < 2 && !alternatives->exhausted(); ++i) {
          auto c = alternatives->Peek();
          alternatives->Next();
          if (!c || c->start() != 0 || c->end() != input.size() ||
              hidden.count(c->text()) || HanCharacters(c->text()).empty()) continue;
          // Double pinyin (cyime_pause_correction): only a reading with one key left out, doubled or swapped, of a
          // kind whose switch is on, and only when the word model finds it more likely than the first candidate by
          // the scheme's margin. Two keys a syllable leave so many such readings that correctly typed clauses got
          // one too (22 of 670: 你到家了没 gained 你到家了吗我).
          // 14 keys (pause_letters): one key a letter, the kind read from the reading's syllables.
          const int kind = pause_letters ? LetterKeyEdit(c, pause_letters, input) : TwoKeyEdit(c->text(), input.size());
          // 14 keys, a key left out while every key as typed is already spelt out by the first candidate (whole
          // input, full syllables): the shorter syllable is a real one too (yan for yuan, ang for wang), so the
          // model alone cannot tell a slip (2026-11-20 PC check: 语言键 gained 医院见 and 以演讲, 已经用得很爽了
          // gained 很失望了). A dictionary word typed out key by key (语言键) is not traded for a sentence pieced
          // together from shorter words, only for another dictionary word (新栽 for xin zai: 现在); a sentence is not
          // traded for a reading of a different number of characters (one syllable read as two, 爽 as 失望).
          // Doubled and swapped keys keep only the model gate (九百元 for jiin tian, 西瓜刀 for zih dao: 今天, 知道).
          bool spelt_word = false;
          if (pause_letters && kind == kEditOmission && WholeAndSpelt(head_.front(), end_)) {
            spelt_word = !As<Sentence>(Candidate::GetGenuineCandidate(head_.front()));
            if (spelt_word ? static_cast<bool>(As<Sentence>(Candidate::GetGenuineCandidate(c)))
                           : HanCharacters(c->text()).size() != HanCharacters(head_.front()->text()).size()) continue;
          }
          if (pause_kinds >= 0 && (!(kind & pause_kinds) ||
                                   (pause_likely && !pause_likely(c, head_.front(), head_.front()->end() == end_, kind,
                                                                  spelt_word)))) continue;
          auto same = [&](const auto& item) { return item->text() == c->text(); };
          auto first_five = original.begin() + std::min<size_t>(5, original.size());
          // 14 keys: a word in the first five that reads only part of the keys does not count as offered (choosing it
          // leaves keys behind); one in the first two stays where it is.
          auto offered = [&](const auto& item) { return same(item) && (!pause_letters || item->end() == end_); };
          if (std::any_of(original.begin(), original.begin() + std::min<size_t>(2, original.size()), same) ||
              std::any_of(original.begin(), first_five, offered) ||
              std::any_of(head_.begin() + 2, head_.begin() + std::min<size_t>(7, head_.size()), [&](const auto& item) {
                return item->type() == "cyime_omission" && same(item);
              })) continue;
          const double own = weight(c);
          auto old = std::find_if(head_.begin(), head_.end(), same);
          // 14 keys: the same word already further down may read only part of the keys (我们 for QOm of QOmBE); the
          // pause reading of all of them goes in instead, so choosing it takes every key.
          if (old != head_.end()) {
            if (!pause_letters || (*old)->end() == end_) c = *old;
            head_.erase(old);
          }
          seen_.insert(c->text());
          size_t at = 2;
          while (at < std::min<size_t>(5 + inserted, head_.size()) &&
                 (head_[at]->type() == "cyime_omission" || (corrected(head_[at]) && weight(head_[at]) >= own)))
            ++at;
          head_.insert(head_.begin() + std::min(at, head_.size()),
                       New<ShadowCandidate>(c, "cyime_omission", c->text(), "纠错", false));
          ++inserted;
          omission_inserted = true;
        }
      }
    }
    if (edits && head_.size() >= 2) edit = edits->Best(input, false, head_.front()->text());
    if (edit && head_.size() >= 2 && !hidden.count(edit->text()) && !HanCharacters(edit->text()).empty()) {
      auto same = [&](const auto& c) { return c->text() == edit->text(); };
      auto original = std::find_if(head_.begin(), head_.end(), same);
      if (original >= head_.begin() + std::min<size_t>(5, head_.size())) {
        if (original != head_.end()) head_.erase(original);
        seen_.insert(edit->text());
        // Among the readings added after a pause and the corrections found while typing, the more common word comes
        // first (2026-11-16): the swapped-letter reading used to go behind the omission readings, which the omission
        // prism finds for nearly any input, so it was fifth (26-key words with two letters swapped, wanted word in
        // the first three after the pause 58%, 79% with the omission readings off). It now passes those of lower
        // word weight only: put third outright, it pushed 你说 for nihuo behind 你后 (uo read as ou).
        const double own = weight(edit);
        size_t at = 2;
        while (at < std::min<size_t>(5 + inserted, head_.size()) &&
               (head_[at]->type() == "cyime_omission" || corrected(head_[at])) && weight(head_[at]) >= own)
          ++at;
        head_.insert(head_.begin() + std::min(at, head_.size()), New<ShadowCandidate>(
            edit, "cyime_spelling_edit", edit->text(), numeric ? edit->comment() : "纠错", false));
        omission_inserted = true;
      }
    }
    // After a pause the most common corrected whole word comes third when only made-up sentences, initials and
    // shorter words are ahead of it (2026-11-16): while typing, the translator lists every sentence it can compose
    // from the letters as typed before any corrected word, so a doubled or extra letter left the wanted word fourth
    // to tenth (duuole: 都有了, 度U哦了, 度过了 before 多了). A word typed out in full, a learned word and a more
    // common pause reading keep their places before it, and so do the first two candidates. Not in double pinyin
    // (pause_kinds >= 0): its pause readings cover these slips already, and the corrected words listed for a correctly
    // typed clause there are longer phrases it could begin (是这样的 gained 一个运动员 third, 10 of 670 clauses).
    if (!numeric && pause_kinds < 0 && (omission || edits) && head_.size() > 3) {
      size_t best = 0;
      double best_weight = 0;
      for (size_t i = 2; i < head_.size(); ++i) {
        if (!corrected(head_[i]) || head_[i]->start() != 0) continue;
        const auto p = As<Phrase>(Candidate::GetGenuineCandidate(head_[i]));
        if (As<Sentence>(p) || p->type() == "user_phrase" || HanCharacters(p->text()).size() < 2) continue;
        if (!best || p->weight() > best_weight) { best = i; best_weight = p->weight(); }
      }
      auto stays = [&](const an<Candidate>& item) {
        if (item->type() == "cyime_omission" || item->type() == "cyime_spelling_edit")
          return weight(item) >= best_weight;
        const auto p = As<Phrase>(Candidate::GetGenuineCandidate(item));
        if (!p || HanCharacters(p->text()).empty()) return false;
        if (p->type() == "user_phrase") return true;
        return !As<Sentence>(p) && item->start() == 0 && p->end() == end_ && SpellingCoverage(p) >= 0.5 &&
               p->correction_marks().find('=') == std::string::npos;
      };
      size_t at = 2;
      while (best > 2 && at < std::min<size_t>(5, best) && stays(head_[at])) ++at;
      if (best > 2 && at < std::min<size_t>(5, best)) {
        std::rotate(head_.begin() + at, head_.begin() + best, head_.begin() + best + 1);
        omission_inserted = true;
      }
    }
    for (const auto& [index, cand] : kept) {
      const auto now = std::find(head_.begin(), head_.end(), cand);
      if (now != head_.end() && static_cast<size_t>(now - head_.begin()) > index)
        std::rotate(head_.begin() + index, now, now + 1);
    }
    // Merged keys: the corrected reading never leads a reading of all the keys as typed (corrected dictionary words
    // outweighed typed sentences: 很容易 before 很同意 for GEBTOBGTU, 不要脸 before 不太乱); it follows the first one.
    // When nothing reads all the keys as typed it keeps its place. A word that is not Chinese counts only right
    // behind it, where it stood first before the correction came in (CTUmE: CyIME, not 吃么).
    if (merged_corrections_ && head_.size() >= 2) {
      auto is_corrected = [](const an<Candidate>& item) {
        const auto p = As<Phrase>(Candidate::GetGenuineCandidate(item));
        return p && p->correction_marks().find('=') != std::string::npos;
      };
      // A corrected sentence first is the word model's choice (t9/slip_correction_margin, t9/slip_whole_words) and stays
      // before Chinese readings; the word model knows nothing of a word that is not Chinese, so that one still leads.
      const auto front = Candidate::GetGenuineCandidate(head_.front());
      if (is_corrected(head_.front())) {
        const bool chosen = front && front->type() == "sentence";
        const auto second = head_.begin() + 1;
        const auto typed = std::find_if(second, head_.end(), [&](const an<Candidate>& item) {
          if (is_corrected(item)) return false;
          return item == *second || (!chosen && !HanCharacters(item->text()).empty());
        });
        if (typed != head_.end() && (*typed)->start() == 0 && (*typed)->end() == end_ &&
            (!chosen || HanCharacters((*typed)->text()).empty()))
          std::rotate(head_.begin(), typed, typed + 1);
      }
    }
    if (particles) particles->Promote(input, particle_start, head_, hidden, seen_);
    Advance();
  }
  bool Next() override { current_.reset(); Advance(); return !exhausted(); }
  an<Candidate> Peek() override { return current_; }
 private:
  // A word in the user's own dictionary: picked before (user_phrase, or a table word with commits).
  static bool Learned(const an<Candidate>& c) {
    const auto p = As<Phrase>(Candidate::GetGenuineCandidate(c));
    return p && (p->type() == "user_phrase" || p->entry().commit_count > 0);
  }
  // Pairs the user ranks for himself, whatever was learned (2026-10-31): the plain 那个 comes
  // before the question 哪个 on the same span, even after picking 哪个 more often. Only the
  // first word moves, to just before the second; nothing else changes place.
  void KeepFixedOrder() {
    static const std::pair<const char*, const char*> kPairs[] = {{"那个", "哪个"}};
    for (const auto& pair : kPairs) {
      const auto find = [&](const char* text) {
        return std::find_if(head_.begin(), head_.end(), [&](const auto& c) { return c->text() == text; });
      };
      const auto first = find(pair.first), second = find(pair.second);
      if (first == head_.end() || second == head_.end() || first < second) continue;
      if ((*first)->start() != (*second)->start() || (*first)->end() != (*second)->end()) continue;
      std::rotate(second, first, first + 1);
    }
  }
  an<Candidate> Prepare(an<Candidate> cand) {
    if (!cand || hidden.count(cand->text())) return nullptr;
    // Two letters or fewer in full pinyin: only a one-key slip on a whole syllable is corrected (wp → 我, ni typed
    // nu). A correction that reads the letters as a longer syllable invents one (2026-11-11: ok gave 空 kong second,
    // and an emoji for it third, only with 邻键纠错 on). Longer input keeps every correction.
    if (drop_corrections_) {
      if (const auto p = As<Phrase>(Candidate::GetGenuineCandidate(cand))) {
        const auto marks = p->correction_marks();
        if (marks.find('=') != std::string::npos && !OneKeySubstitution(marks)) return nullptr;
      }
    }
    // Merged keys (14 keys, translator/cyime_merged_key_neighbors): a key covers two letters, so nearly every word is
    // one neighbouring key away from what was typed, and corrected words filled the menu (yijinghao: 于静瑶, 偷井盖
    // before 已经; taidake: 还打嗝, 要发歌). Only the first corrected reading of all the keys stays (太大了 for 太大可,
    // the word or the sentence the word model chose); corrected words for part of the keys are dropped.
    if (merged_corrections_) {
      if (const auto p = As<Phrase>(Candidate::GetGenuineCandidate(cand))) {
        const auto& marks = p->correction_marks();
        if (marks.find('=') != std::string::npos) {
          // One slip corrects one syllable. A reading through more corrected syllables (GABGZGUBQUE: 当众侮辱 for
          // 刚准确) is listed only where the word model put it first (t9/slip_correction_margin, paid per syllable).
          const bool one_slip = std::count(marks.begin(), marks.end(), '=') == 1;
          if (cand->end() != end_ || merged_corrected_whole_ || (!one_slip && !head_.empty())) return nullptr;
          merged_corrected_whole_ = true;
        }
      }
    }
    // Keep the engine's complete predictive word and identity. Trimming to the
    // matched prefix discarded precisely the completion the user could select.
    if (hidden.count(cand->text()) || !seen_.insert(cand->text()).second) return nullptr;
    return cand;
  }
  void Advance() {
    if (position_ < head_.size()) { current_ = head_[position_++]; return; }
    while (!source_->exhausted()) {
      current_ = Prepare(source_->Peek()); source_->Next();
      if (current_) return;
    }
    set_exhausted(true);
  }
  an<Translation> source_;
  size_t end_, position_ = 0;
  bool drop_corrections_ = false;
  bool merged_corrections_ = false;
  bool merged_corrected_whole_ = false;
  CandidateList head_;
  std::set<std::string> seen_;
  an<Candidate> current_;
};

class CandidatePolicy;
// Each opt-in schema owns its lookup until its filter is destroyed on a schema
// switch or session close. RefineOmission can probe it before rebuilding a menu.
std::map<Context*, CandidatePolicy*> spelling_policies;

class CandidatePolicy : public Filter {
 public:
  explicit CandidatePolicy(const Ticket& ticket) : Filter(ticket), particles_(ticket) {
    ticket.schema->config()->GetBool("cyime_particle_shortcuts/selected_tail", &selected_tail_);
    engine_->schema()->config()->GetString("cyime_omission_schema", &omission_id_);
    // Swapped-letter correction is on only in full pinyin (26 keys); double pinyin, 14 keys and nine keys
    // keep their short-input corrections (a two-key slip in double pinyin is a whole syllable).
    engine_->schema()->config()->GetBool("translator/cyime_transposition_correction", &full_pinyin_);
    // Merged keys (14 keys) correct neighbouring keys with a fixed key grid; see PolicyTranslation::Prepare.
    string merged_neighbors;
    merged_corrections_ = engine_->schema()->config()->GetString("translator/cyime_merged_key_neighbors", &merged_neighbors) &&
                          !merged_neighbors.empty();
    // t9/model_sentences_on_pause: the sentences the word model builds (t9/model_sentences) cost as much as a whole
    // key, so the translator makes them only in the refresh after a pause; see RefineOmission.
    {
      int count = 0;
      bool on_pause = false, rescore = false;
      pause_sentences_ = engine_->schema()->config()->GetInt("t9/model_sentences", &count) && count > 0 &&
                         engine_->schema()->config()->GetBool("t9/model_sentences_on_pause", &on_pause) && on_pause &&
                         engine_->schema()->config()->GetBool("t9/sentence_rescore", &rescore) && rescore;
    }
    // Registered for RefineOmission when there is anything to look up after a pause.
    if (engine_->schema()->config()->GetString("cyime_edit_schema", &edit_id_) || !omission_id_.empty() ||
        pause_sentences_)
      spelling_policies[engine_->context()] = this;
    engine_->schema()->config()->GetDouble("t9/correction_lead_bias", &correction_lead_bias_);
    // cyime_pause_correction (double pinyin, two keys a syllable): the pause prism (cyime_omission_schema) reads one
    // key left out, pressed twice or swapped with its neighbour. margin is the word-model gate (ln units): such a
    // reading is offered only when its text is that much more likely than the first candidate's. max_keys is the
    // longest input looked up (the lookup grows with length). The keyboard's 漏键, 多键, 顺序 switches reach it as
    // session options, see PauseKinds.
    pause_gate_ = engine_->schema()->config()->GetDouble("cyime_pause_correction/margin", &pause_margin_);
    engine_->schema()->config()->GetInt("cyime_pause_correction/max_keys", &pause_max_keys_);
    // letters (14 keys): one key a letter, so the slip kind is read from the letters of the reading's syllables.
    engine_->schema()->config()->GetBool("cyime_pause_correction/letters", &pause_letters_);
    // swap_margin (14 keys): the gate for a key pressed twice or two keys swapped; margin when not set.
    if (!engine_->schema()->config()->GetDouble("cyime_pause_correction/swap_margin", &pause_swap_margin_))
      pause_swap_margin_ = pause_margin_;
    // word_margin (14 keys): how much higher the dictionary weight (ln units) of a reading with a key left out must be
    // than that of a first candidate which is one word spelt as typed.
    if (!engine_->schema()->config()->GetDouble("cyime_pause_correction/word_margin", &pause_word_margin_))
      pause_word_margin_ = pause_margin_;
    if (pause_letters_) engine_->schema()->config()->GetBool("t9/ngram_sentence_start", &pause_sentence_start_);
    // t9/digit_edit_margin (nine keys): a reading with one digit edited is offered after a pause only when the
    // word model finds its text more likely than the first candidate's by this margin (ln units). Digits are
    // ambiguous enough that an edited reading exists for most correctly typed input (16% of everyday clauses got
    // one in third place, like 你老板我的小姨了吗 for 你看到我的消息了吗); a slip leaves a first candidate the model
    // finds unlikely. Without the option or the model file every edited reading is offered.
    string model;
    if ((engine_->schema()->config()->GetDouble("t9/digit_edit_margin", &edit_margin_) || pause_gate_) &&
        engine_->schema()->config()->GetString("t9/ngram_model", &model) && !model.empty()) {
      const ResourceType type{"t9_ngram", "", ".lm"};
      the<ResourceResolver> resolver(Service::instance().CreateResourceResolver(type));
      edit_model_ = T9NgramModel::Open(resolver->ResolvePath(model).string());
    }
  }
  ~CandidatePolicy() override {
    const auto it = spelling_policies.find(engine_->context());
    if (it != spelling_policies.end() && it->second == this) spelling_policies.erase(it);
  }
  EditLookup* Edits() {
    string dictionary;
    if (!edits_ && !edit_id_.empty() &&
        engine_->schema()->config()->GetString("translator/dictionary", &dictionary))
      edits_.reset(new EditLookup(edit_id_, dictionary));
    return edits_.get();
  }
  bool HasOmission() const { return !omission_id_.empty(); }
  // The one-key slips a double pinyin scheme's pause readings may assume (off when the option is set); -1 for other
  // schemes, where every reading of the pause prism is offered.
  int PauseKinds() const {
    if (!pause_gate_) return -1;
    const auto* context = engine_->context();
    const int kinds = (context->get_option("_cyime_pause_omission_off") ? 0 : kEditOmission) |
                      (context->get_option("_cyime_pause_extra_off") ? 0 : kEditExtra) |
                      (context->get_option("_cyime_pause_swap_off") ? 0 : kEditSwap);
    return kinds;
  }
  bool OmissionLookup(size_t keys) const {
    return HasOmission() && (!pause_gate_ || (PauseKinds() != 0 &&
        (pause_max_keys_ <= 0 || keys <= static_cast<size_t>(pause_max_keys_))));
  }
  // Letters: whether a pause has anything to look up for input of this many keys.
  bool PauseLookup(size_t keys) const { return !edit_id_.empty() || OmissionLookup(keys); }
  // Letters: the word model's sentences are made after a pause (t9/model_sentences_on_pause).
  bool PauseSentences() const { return pause_sentences_; }
  bool EditLikely(const string& edited, const string& first) const { return Likely(edited, first, edit_margin_); }
  // sentence_start: the first word is scored as the start of a sentence (nine keys, double pinyin). 14 keys scores
  // it without (t9/ngram_sentence_start: false, its input rarely starts a sentence): from the start of a sentence
  // 试过 was more likely than 时候 for AGUGUO, as a word on its own 时候 is 5 times as common.
  bool Likely(const string& edited, const string& first, double margin, bool sentence_start = true) const {
    if (!edit_model_) return true;
    auto score = [&](const string& text) {
      std::vector<int32_t> parts;
      edit_model_->Decompose(text, &parts);
      return edit_model_->Chain(T9NgramModel::kUnseen, T9NgramModel::kUnseen, true, sentence_start, parts, nullptr,
                                nullptr);
    };
    return score(edited) >= score(first) + margin;
  }
  an<Translation> Apply(an<Translation> source, CandidateList*) override {
    const auto& input = engine_->context()->input();
    const bool numeric = !input.empty() && input.find_first_not_of("23456789' ") == std::string::npos;
    const bool idle = omission_context == engine_->context();
    auto* edits = idle && !numeric && !engine_->context()->get_property("cyime_neighbors").empty() ?
        Edits() : nullptr;
    const auto& comp = engine_->context()->composition();
    const bool at_end = !comp.empty() && comp.back().HasTag("abc") && comp.back().end == input.size() &&
        engine_->context()->caret_pos() == input.size();
    const bool whole = at_end && comp.size() == 1 && comp.back().start == 0;
    const bool tail = selected_tail_ && at_end && comp.size() >= 2 && comp.back().start + 1 == input.size() &&
        comp[comp.size() - 2].status >= Segment::kSelected && comp[comp.size() - 2].end == comp.back().start;
    const bool short_letters = full_pinyin_ && !input.empty() && input.size() <= 2 &&
        input.find_first_not_of("abcdefghijklmnopqrstuvwxyz") == std::string::npos;
    const int pause_kinds = PauseKinds();
    std::function<bool(const an<Candidate>&, const an<Candidate>&, bool, int, bool)> pause_likely;
    // Without the model file nothing is offered: ungated, 22 of 670 correctly typed inputs gained a made-up sentence.
    if (pause_gate_)
      // 14 keys (letters): when the first candidate covers only part of the input there is no reading of all the keys
      // as typed (a merged key has two letters, so such input is nearly always a slip); the corrected reading then
      // only has to be at least as likely as that first word, not e^margin times.
      // A key pressed twice or two keys swapped (14 keys) against a first candidate spelt as typed: swap_margin
      // (scheme default 3.0: 时候 is only 1.6 above 试过 for AGUGUO; at 1.5, 18 more of 2000 common words typed
      // right gained a third candidate, 2026-11-20).
      // A key left out against a first candidate that is one dictionary word spelt as typed: besides the model, the
      // reading's dictionary weight must be word_margin above that word's. Two common words often differ by one key
      // (几天 and 今天, 下载 and 现在: 40 of 2000 common words typed right gained one on the model alone), and the
      // model does not know words added to the dictionary (语言键 5000 against 医院见 135, 倚天剑 67), while a slip
      // usually leaves a rare word first (新栽 8 against 现在 2821291 for xin zai). A word the user typed before
      // (user dictionary, its own weights) is kept.
      pause_likely = [this](const an<Candidate>& edited_candidate, const an<Candidate>& first_candidate,
                            bool whole_first, int kind, bool spelt_word) {
        if (!edit_model_) return false;
        const auto& edited = edited_candidate->text();
        const auto& first = first_candidate->text();
        if (!pause_letters_) return Likely(edited, first, pause_margin_);
        const bool start = pause_sentence_start_;
        if (!whole_first) return Likely(edited, first, 0.0, start);
        if (spelt_word) {
          const auto a = As<Phrase>(Candidate::GetGenuineCandidate(edited_candidate));
          const auto b = As<Phrase>(Candidate::GetGenuineCandidate(first_candidate));
          return a && b && b->type() != "user_phrase" && a->weight() >= b->weight() + pause_word_margin_ &&
                 Likely(edited, first, pause_margin_, start);
        }
        return Likely(edited, first, kind != kEditOmission ? pause_swap_margin_ : pause_margin_, start);
      };
    // The pause prism is opened only after a pause, never while typing.
    auto* omission = idle && OmissionLookup(input.size()) ? Omission() : nullptr;
    auto* letters = pause_letters_ ? dynamic_cast<Memory*>(omission) : nullptr;
    return New<PolicyTranslation>(source, input, numeric, omission,
                                  edits, idle && numeric ? pending_digit_edit : nullptr,
                                  whole || tail ? &particles_ : nullptr, tail ? comp.back().start : 0, short_letters,
                                  correction_lead_bias_, pause_kinds, pause_likely, letters ? letters->dict() : nullptr,
                                  merged_corrections_);
  }
 private:
  Translator* Omission() {
    if (!omission_ && HasOmission()) {
      Schema schema(omission_id_);
      std::string prism;
      auto component = Translator::Require("script_translator");
      if (component && schema.config()->GetString("translator/prism", &prism) && prism == omission_id_) {
        Ticket ticket(engine_, "translator");
        ticket.schema = &schema;
        omission_.reset(component->Create(ticket));
      }
    }
    return omission_.get();
  }
  the<Translator> omission_;
  the<EditLookup> edits_;
  string edit_id_, omission_id_;
  std::shared_ptr<const T9NgramModel> edit_model_;
  double edit_margin_ = 0.0;
  // cyime_pause_correction/margin is set: a double pinyin scheme, whose pause readings are gated and sorted by kind.
  bool pause_gate_ = false;
  double pause_margin_ = 0.0;
  double pause_swap_margin_ = 0.0;
  double pause_word_margin_ = 0.0;
  bool pause_sentence_start_ = true;
  int pause_max_keys_ = 0;
  bool pause_letters_ = false;
  bool pause_sentences_ = false;
  bool merged_corrections_ = false;
  // How much more common than 2000 times (ln units, either sign) a corrected word must be to lead the word as typed.
  double correction_lead_bias_ = 0.0;
  BasicParticleChoices particles_;
  bool selected_tail_ = false;
  bool full_pinyin_ = false;
};

bool StoreHidden(const std::set<std::string>& next) {
  if (hidden_path.empty()) return false;
  const auto pending = hidden_path + ".tmp";
  std::ofstream out(pending, std::ios::trunc | std::ios::binary);
  if (!out) return false;
  for (const auto& text : next) out << text << '\n';
  out.close();
  if (!out || std::rename(pending.c_str(), hidden_path.c_str()) != 0) return false;
  hidden = next;
  return true;
}
}

bool RefineOmission(rime::Context* context) {
  if (!context || context->input().size() < 4 || context->input().size() > 80 ||
      // Letters: a-z, and the 14-key merged keys (QETUOADGJZCB); a scheme without a pause prism for them finds nothing.
      (context->input().find_first_not_of("abcdefghijklmnopqrstuvwxyzQETUOADGJZCB") != std::string::npos &&
       (context->input().size() < 6 || context->input().size() > 32 ||
        context->input().find_first_not_of("23456789") != std::string::npos)) ||
      context->caret_pos() != context->input().size() || context->composition().size() != 1 ||
      context->composition().back().status >= rime::Segment::kSelected ||
      context->composition().back().selected_index != 0) return false;
  const auto original = context->composition().back();
  if (!original.menu || original.menu->Prepare(2) < 2) return false;
  an<Candidate> digit_edit;
  const bool numeric = context->input().front() >= '2' && context->input().front() <= '9';
  const auto registered = spelling_policies.find(context);
  // Letters: the sentences the word model builds are made now when the scheme leaves them to the pause.
  const bool sentences = !numeric && registered != spelling_policies.end() && registered->second->PauseSentences();
  // Letters: nothing to look up when the scheme has no pause prism, its switches are all off or the input is too long.
  if (!numeric && !sentences &&
      (registered == spelling_policies.end() || !registered->second->PauseLookup(context->input().size())))
    return false;
  const auto policy = numeric ? registered : spelling_policies.end();
  if (policy != spelling_policies.end()) {
    auto* edits = policy->second->Edits();
    auto first = original.menu->GetCandidateAt(0);
    // The keyboard's switches (邻键纠错, 顺序纠错, 漏键纠错) reach nine keys as session options, off when set.
    const int kinds = (context->get_option("_cyime_digit_omission_off") ? 0 : kEditOmission) |
                      (context->get_option("_cyime_digit_swap_off") ? 0 : kEditSwap) |
                      (context->get_option("_cyime_digit_tap_off") ? 0 : kEditTap);
    if (edits && first && kinds)
      digit_edit = edits->Best(context->input(), true, Candidate::GetGenuineCandidate(first)->text(), kinds);
    if (digit_edit && (hidden.count(digit_edit->text()) || HanCharacters(digit_edit->text()).empty() ||
                       !policy->second->EditLikely(digit_edit->text(), Candidate::GetGenuineCandidate(first)->text())))
      digit_edit.reset();
    for (size_t i = 0; digit_edit && i < 5; ++i) {
      auto present = original.menu->GetCandidateAt(i);
      if (!present) break;
      if (present->text() == digit_edit->text()) digit_edit.reset();
    }
    if (!digit_edit && !policy->second->HasOmission()) return false;
  }
  struct Scope {
    Scope(rime::Context* ctx, an<Candidate> digit_edit, bool sentences) : sentences(sentences) {
      omission_context = ctx; omission_inserted = false; pending_digit_edit = std::move(digit_edit);
      if (sentences) rime::T9SetRefiningSentences(true);
    }
    ~Scope() {
      omission_context = nullptr; pending_digit_edit.reset();
      if (sentences) rime::T9SetRefiningSentences(false);
    }
    const bool sentences;
  } scope(context, digit_edit, sentences);
  if (!context->RefreshNonConfirmedComposition() || context->composition().empty()) return false;
  auto menu = context->composition().back().menu;
  if (!menu) return false;
  menu->Prepare(5);
  // Late filters (e.g. simplified/traditional conversion) run after CandidatePolicy.
  // Keep the menu the user saw if the refresh failed to add anything or displaced a protected choice.
  bool keep = omission_inserted;
  for (size_t i = 0; keep && i < 2; ++i) {
    auto before = original.menu->GetCandidateAt(i);
    auto after = menu->GetCandidateAt(i);
    keep = before && after && before->text() == after->text() && before->end() == after->end();
  }
  if (keep) {
    // A downstream filter may hide or merge the extra candidate. Do not
    // invalidate taps on a menu that still shows the same first five choices.
    keep = false;
    for (size_t i = 2; i < 5; ++i) {
      auto before = original.menu->GetCandidateAt(i);
      auto after = menu->GetCandidateAt(i);
      if (after && (!before || before->text() != after->text() || before->end() != after->end())) {
        keep = true;
        break;
      }
    }
  }
  // The word model's sentence leads the refreshed menu: pause readings never take the first two places, so a new
  // first candidate is the sentence made in this refresh. The menu is the refreshed one.
  if (!keep && sentences) {
    auto before = original.menu->GetCandidateAt(0);
    auto after = menu->GetCandidateAt(0);
    keep = before && after && before->text() != after->text();
  }
  if (!keep) context->composition().back() = original;
  return keep;
}

void InitializeCandidatePolicy(const std::string& user_dir) {
  hidden.clear();
  std::string line;
  hidden_path = user_dir + "/cyime-hidden-candidates.txt";
  std::ifstream preferences(hidden_path);
  while (std::getline(preferences, line)) if (!line.empty()) hidden.insert(line);
  Registry::instance().Register("cyime_candidate_policy", new Component<CandidatePolicy>());
  pins::Initialize(user_dir);
}

bool HideCandidate(const std::string& text) {
  if (text.empty() || text.find_first_of("\r\n") != std::string::npos) return false;
  auto next = hidden; next.insert(text); return StoreHidden(next);
}
bool RestoreCandidate(const std::string& text) {
  auto next = hidden; next.erase(text); return StoreHidden(next);
}
std::vector<std::string> HiddenCandidates() {
  return std::vector<std::string>(hidden.begin(), hidden.end());
}
}
