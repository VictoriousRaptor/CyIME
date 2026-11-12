#include "candidate_policy.h"
#include "candidate_pins.h"
#include "basic_particle_choices.h"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <map>
#include <set>
#include <sstream>
#include <utf8.h>
#include <rime/candidate.h>
#include <rime/config.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/filter.h>
#include <rime/menu.h>
#include <rime/schema.h>
#include <rime/translator.h>
#include <rime/gear/translator_commons.h>
#include <rime/language.h>
#include <rime/registry.h>
#include <rime/translation.h>
#include <t9_pinyin_map.h>

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

// The auxiliary prism can match an edit in several syllables. Only offer a
// whole reading that differs from the actual input by one edit in total.
bool IsSingleSpellingEdit(const string& input, const string& pinyin, bool digits, Config* tap_grid = nullptr) {
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
    return expected.compare(pos + 1, string::npos, input, pos, string::npos) == 0;
  return expected.size() == input.size() && pos + 1 < input.size() &&
         input[pos] == expected[pos + 1] && input[pos + 1] == expected[pos] &&
         input.compare(pos + 2, string::npos, expected, pos + 2, string::npos) == 0;
}

// ScriptTranslator consults its engine's schema and current input when querying.
// Use the small edit prism's own engine so it cannot start the active keyboard's
// joint decoder, sentence model, or learning callbacks a second time.
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

  an<Candidate> Best(const string& input, bool digits, const string& original_first) {
    auto hit = Lookup(input);
    if (hit && IsSingleSpellingEdit(input, hit->comment(), digits)) return hit;
    // Two independent decodings agree with the original reading. Looking for
    // a substituted key here mostly introduces alternatives to correctly typed text.
    if (!digits || tap_schema_.empty() || (hit && hit->text() == original_first)) return nullptr;
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
                    bool drop_corrections = false)
      : source_(source), end_(input.size()), drop_corrections_(drop_corrections) {
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
        const auto limit = head_.begin() + std::min<size_t>(5, head_.size());
        int count = 0;
        const auto corrected = std::find_if(head_.begin() + 1, limit, [&](const auto& c) {
          const auto p = As<Phrase>(Candidate::GetGenuineCandidate(c));
          return p && !As<Sentence>(p) && p->end() == end_ && !HanCharacters(p->text()).empty() &&
                 (count = corrections(p)) > 0;
        });
        static const double kPenalty = std::log(100.0), kMargin = std::log(2000.0);
        if (corrected != limit && corrections(top) == 0) {
          const auto p = As<Phrase>(Candidate::GetGenuineCandidate(*corrected));
          if (top->type() != "user_phrase" && p->type() != "user_phrase" &&
              p->weight() + count * kPenalty >= top->weight() + kMargin)
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
    size_t inserted = 0;
    if (omission && head_.size() >= 2) {
      Segment segment(0, input.size());
      segment.tags.insert("abc");
      auto alternatives = omission->Query(input, segment);
      if (alternatives && !alternatives->exhausted() && alternatives->Peek() &&
          alternatives->Peek()->text() != head_.front()->text()) {
        const auto original = head_;
        // Letter-input corrections the typing already found (neighbour key, swapped, doubled or extra letter)
        // are not pushed down by a rarer omission reading: an omission reading goes above such a correction in the
        // first five only when its word weight is higher (2026-11-03: a pause moved 今天 for jintiab from 3rd to
        // 6th; jntian still gets 今天 above 纪念日). The two translators' qualities are on different scales.
        // Only corrections of the whole input count: a shorter word's weight is not comparable with a sentence's.
        auto weight = [](const an<Candidate>& item) {
          const auto p = As<Phrase>(Candidate::GetGenuineCandidate(item));
          return p ? p->weight() : 0.0;
        };
        auto corrected = [&](const an<Candidate>& item) {
          if (numeric || item->type() == "cyime_omission" || item->end() != end_) return false;
          const auto p = As<Phrase>(Candidate::GetGenuineCandidate(item));
          return p && p->correction_marks().find('=') != std::string::npos;
        };
        for (size_t i = 0; i < 10 && inserted < 2 && !alternatives->exhausted(); ++i) {
          auto c = alternatives->Peek();
          alternatives->Next();
          if (!c || c->start() != 0 || c->end() != input.size() ||
              hidden.count(c->text()) || HanCharacters(c->text()).empty()) continue;
          auto same = [&](const auto& item) { return item->text() == c->text(); };
          auto first_five = original.begin() + std::min<size_t>(5, original.size());
          if (std::any_of(original.begin(), first_five, same) ||
              std::any_of(head_.begin() + 2, head_.begin() + std::min<size_t>(7, head_.size()), [&](const auto& item) {
                return item->type() == "cyime_omission" && same(item);
              })) continue;
          const double own = weight(c);
          auto old = std::find_if(head_.begin(), head_.end(), same);
          if (old != head_.end()) { c = *old; head_.erase(old); }
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
        head_.insert(head_.begin() + 2 + inserted, New<ShadowCandidate>(
            edit, "cyime_spelling_edit", edit->text(), numeric ? edit->comment() : "纠错", false));
        omission_inserted = true;
      }
    }
    for (const auto& [index, cand] : kept) {
      const auto now = std::find(head_.begin(), head_.end(), cand);
      if (now != head_.end() && static_cast<size_t>(now - head_.begin()) > index)
        std::rotate(head_.begin() + index, now, now + 1);
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
    if (engine_->schema()->config()->GetString("cyime_edit_schema", &edit_id_))
      spelling_policies[engine_->context()] = this;
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
    return New<PolicyTranslation>(source, input, numeric, idle ? Omission() : nullptr, edits,
                                  idle && numeric ? pending_digit_edit : nullptr, whole || tail ? &particles_ : nullptr,
                                  tail ? comp.back().start : 0, short_letters);
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
      (context->input().find_first_not_of("abcdefghijklmnopqrstuvwxyz") != std::string::npos &&
       (context->input().size() < 6 || context->input().size() > 32 ||
        context->input().find_first_not_of("23456789") != std::string::npos)) ||
      context->caret_pos() != context->input().size() || context->composition().size() != 1 ||
      context->composition().back().status >= rime::Segment::kSelected ||
      context->composition().back().selected_index != 0) return false;
  const auto original = context->composition().back();
  if (!original.menu || original.menu->Prepare(2) < 2) return false;
  an<Candidate> digit_edit;
  const bool numeric = context->input().front() >= '2' && context->input().front() <= '9';
  const auto policy = numeric ? spelling_policies.find(context) : spelling_policies.end();
  if (policy != spelling_policies.end()) {
    auto* edits = policy->second->Edits();
    auto first = original.menu->GetCandidateAt(0);
    if (edits && first)
      digit_edit = edits->Best(context->input(), true, Candidate::GetGenuineCandidate(first)->text());
    if (digit_edit && (hidden.count(digit_edit->text()) || HanCharacters(digit_edit->text()).empty()))
      digit_edit.reset();
    for (size_t i = 0; digit_edit && i < 5; ++i) {
      auto present = original.menu->GetCandidateAt(i);
      if (!present) break;
      if (present->text() == digit_edit->text()) digit_edit.reset();
    }
    if (!digit_edit && !policy->second->HasOmission()) return false;
  }
  struct Scope {
    Scope(rime::Context* ctx, an<Candidate> digit_edit) {
      omission_context = ctx; omission_inserted = false; pending_digit_edit = std::move(digit_edit);
    }
    ~Scope() { omission_context = nullptr; pending_digit_edit.reset(); }
  } scope(context, digit_edit);
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
