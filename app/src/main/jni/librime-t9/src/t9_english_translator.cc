#include "t9_english_translator.h"
#ifndef T9_ALGO_ONLY_BUILD
#include <algorithm>
#include <cmath>
#include <rime/segmentation.h>

namespace rime {
namespace {
// Four keys or more, or a mixed word (B站, T恤): shorter English words (go, the, she) stay after the Chinese
// candidates, where every common 2–3-key Chinese prefix would otherwise show one.
bool T9EnglishSlotted(const Phrase& phrase) {
  const string& text = phrase.text();
  return phrase.end() - phrase.start() >= 4 ||
         std::any_of(text.begin(), text.end(), [](char c) { return static_cast<unsigned char>(c) >= 0x80; });
}
bool HasHan(const string& text) {
  // A UTF-8 lead byte of U+4E00..U+9FFF (CJK unified ideographs).
  return std::any_of(text.begin(), text.end(), [](char c) {
    const auto b = static_cast<unsigned char>(c);
    return b >= 0xE4 && b <= 0xE9;
  });
}
class EnglishLearningTranslation : public CacheTranslation {
 public:
  EnglishLearningTranslation(an<Translation> source, double exact_quality)
      : CacheTranslation(source), exact_quality_(exact_quality) {}
  an<Candidate> Peek() override {
    auto candidate = CacheTranslation::Peek();
    auto phrase = As<Phrase>(candidate);
    // 2026-11-11「都按权重算」: a mixed word (B站, 哆啦A梦; Han characters with letters) typed in full is a whole word
    // like an exact Chinese phrase, 1 + its weight, which comes from public-corpus counts on the Chinese scale
    // (data/cyime_mixed_word_counts.tsv). Before, every one got exact_quality and was placed 6th, off the bar.
    if (phrase && phrase->entry().commit_count <= 0 && phrase->type() == "table" && HasHan(phrase->text())) {
      phrase->set_quality(1.0 + std::exp(phrase->weight()));
      return candidate;
    }
    if (phrase && exact_quality_ > -1e9 && phrase->entry().commit_count <= 0 &&
        (phrase->type() == "table" || phrase->type() == "user_table") && T9EnglishSlotted(*phrase)) {
      // exact_quality: a whole word not learned yet comes right after the fully spelled Chinese words, among
      // the candidates cyime_candidate_policy places (candidate_policy.cc); completions keep theirs.
      phrase->set_quality(exact_quality_);
    }
    if (phrase && phrase->type() == "user_table" && phrase->entry().commit_count > 0) {
      // Learned whole words have full coverage, just like exact Chinese phrases.
      // Keep Rime's frequency/recency probability: repeated choices progressively
      // overtake joint sentences, with no word-specific rule or reserved rank.
      phrase->set_quality(std::max(phrase->quality(), 1.0 + std::exp(phrase->weight())));
    }
    return candidate;
  }

 private:
  double exact_quality_;
};
}  // namespace
an<Translation> T9EnglishTranslator::Query(const string& input, const Segment& segment) {
  an<Translation> exact = New<EnglishLearningTranslation>(TableTranslator::Query(input, segment), exact_quality_);
  if (exact_quality_ > -1e9 && exact && !exact->exhausted()) {
    // Rime merges translations by the quality of each one's next candidate, so a lower-quality word ahead of a
    // slotted one (bit before A股 for 248) kept that one after every Chinese candidate: order the whole words by
    // quality first (only exact matches of the typed keys, a handful).
    vector<an<Candidate>> words;
    for (; words.size() < 64 && !exact->exhausted(); exact->Next()) words.push_back(exact->Peek());
    std::stable_sort(words.begin(), words.end(), [](const an<Candidate>& a, const an<Candidate>& b) {
      return a->quality() > b->quality();
    });
    auto sorted = New<FifoTranslation>();
    for (const auto& word : words) sorted->Append(word);
    exact = exact->exhausted() ? an<Translation>(sorted) : an<Translation>(sorted + exact);
  }
  // CyIME: a few common words starting with the typed keys, after the whole words: English typed
  // half way shows up as on 26 keys. From 4 keys on, so short inputs stay Chinese.
  if (completion_limit_ > 0 && input.size() >= 4 && input.size() <= 32 &&
      input.find_first_not_of(completion_keys_) == string::npos && segment.HasAnyTagIn(tags_) &&
      dict_ && dict_->loaded()) {
    DictEntryIterator iter;
    dict_->LookupWords(&iter, input, true, 64, &blacklist());
    auto completions = New<FifoTranslation>();
    for (int taken = 0; taken < completion_limit_ && !iter.exhausted(); iter.Next()) {
      auto entry = iter.Peek();
      if (!entry || entry->remaining_code_length <= 0) continue;
      auto word = New<DictEntry>(*entry);
      word->comment.clear();
      auto phrase = New<Phrase>(language(), "completion", segment.start,
                                segment.start + input.size(), word);
      phrase->set_quality(initial_quality_ - 0.5);
      completions->Append(phrase);
      ++taken;
    }
    if (!completions->exhausted()) exact = New<DistinctTranslation>(exact + completions);
  }
  // Bounded exact prefix lookups, never scan the word list or enumerate letter
  // combinations. Learned full codes naturally come back through the base query.
  if (!digit_suffixes_ || input.size() < 4 || input.size() > 64 ||
      input.find_first_not_of("0123456789") != string::npos ||
      !segment.HasAnyTagIn(tags_)) return exact;
  auto suffixes = New<FifoTranslation>();
  for (size_t count = 1; count <= 4 && count + 3 <= input.size(); ++count) {
    const size_t length = input.size() - count;
    Segment prefix(segment.start, segment.start + length);
    prefix.tags = segment.tags;
    auto bases = TableTranslator::Query(input.substr(0, length), prefix);
    for (size_t n = 0; bases && !bases->exhausted() && n < 16; ++n, bases->Next()) {
      auto base = As<Phrase>(Candidate::GetGenuineCandidate(bases->Peek()));
      if (!base || base->text().size() < 3 ||
          !std::all_of(base->text().begin(), base->text().end(), [](unsigned char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
          })) continue;
      auto entry = New<DictEntry>();
      entry->text = base->text() + input.substr(length);
      entry->custom_code = input + " ";
      entry->preedit = input;
      auto phrase = New<Phrase>(language(), "table", segment.start,
                                segment.start + input.size(), entry);
      // Inferred forms follow exact English entries. Shorter digit suffixes win
      // ties; a selected form subsequently gets normal native user-word ranking.
      phrase->set_quality(initial_quality_ - 0.25 - count * 0.01);
      suffixes->Append(phrase);
    }
  }
  if (suffixes->exhausted()) return exact;
  return New<DistinctTranslation>(exact + suffixes);
}
}  // namespace rime
#endif
