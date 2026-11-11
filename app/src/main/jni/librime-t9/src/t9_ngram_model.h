#ifndef CYIME_T9_NGRAM_MODEL_H_
#define CYIME_T9_NGRAM_MODEL_H_

// Memory-mapped word trigram model for the T9 joint decoder: every word weight and every
// collocation is ln P(word | previous words) from one set of corpus counts (t9_ngram_format.h).
// Reads the original (version 2) and the compact (version 3) file layout.
// Pure C++ with no Rime types, so it also links into the algorithm tests.
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "t9_ngram_format.h"

class T9NgramModel {
 public:
  // A dictionary word that never occurred in the corpus, or no history.
  static constexpr int32_t kUnseen = -1;

  // One mapping per path for the process lifetime; nullptr when the file is absent
  // or invalid (the decoder then keeps its previous scoring).
  static std::shared_ptr<const T9NgramModel> Open(const std::string& path);
  // Drops this process's page mappings of every opened model (madvise DONTNEED). The mapping and
  // the data stay valid: the next read faults the page back in from the page cache or storage.
  // For the keyboard to call when the current schema does not use a model (26 keys after 9 keys).
  static void ReleaseResidentPages();
  ~T9NgramModel();

  int32_t Id(const char* text, size_t size) const;
  int32_t Id(const std::string& text) const { return Id(text.data(), text.size()); }
  int32_t Bos() const { return bos_; }
  int32_t Eos() const { return eos_; }
  uint32_t Words() const { return words_count_; }
  uint32_t Version() const { return version_; }
  // First word of a composition: corpus frequency, or P(word | <s>) if sentence_start.
  double Start(int32_t word, bool sentence_start) const;
  // ln P(word | history). An unseen history backs off to the lower order.
  double LogProb(int32_t history, int32_t word) const;
  // ln P(word | history2, history); without a trigram it backs off to the bigram.
  double LogProb3(int32_t history2, int32_t history, int32_t word) const;
  // A text that is not one model word (an entry never seen in the corpus, or a learned
  // phrase) is estimated from its parts: split into model words by frequency (characters
  // outside the model stay kUnseen), then scored as a chain after the two histories.
  void Decompose(const std::string& text, std::vector<int32_t>* parts) const;
  // Word pairs that are mostly a corpus misspelling (觉 的 for 觉得); a short list, scanned linearly.
  using Pairs = std::vector<std::pair<int32_t, int32_t>>;
  static bool HasPair(const Pairs& pairs, int32_t first, int32_t second) {
    for (const auto& pair : pairs)
      if (pair.first == first && pair.second == second) return true;
    return false;
  }
  // A pair in typo_bigrams is scored from the second word's lower-order probability, as an unseen pair.
  double Chain(int32_t history2, int32_t history, bool first, bool sentence_start,
               const std::vector<int32_t>& parts, int32_t* last2, int32_t* last,
               const Pairs* typo_bigrams = nullptr) const;

 private:
  T9NgramModel() = default;
  bool Map(const std::string& path);
  bool MapV2(const char* bytes);
  bool MapV3(const char* bytes);

  double LnLower(int32_t word) const {
    return compact_ ? cyime_t9_ngram::DequantizeLog(words3_[word * 3]) : words_[word].ln_lower;
  }
  double LnMl(int32_t word) const {
    return compact_ ? cyime_t9_ngram::DequantizeLog(words3_[word * 3 + 1]) : words_[word].ln_ml;
  }
  double LnBackoff(int32_t word) const {
    return compact_ ? cyime_t9_ngram::DequantizeLog(words3_[word * 3 + 2]) : words_[word].ln_backoff;
  }
  double BigramLogp(uint64_t i) const {
    return compact_ ? cyime_t9_ngram::DequantizeLog(logp3_[i]) : logp_[i];
  }
  double BigramBackoff(uint64_t i) const {
    return compact_ ? cyime_t9_ngram::DequantizeLog(bigram_backoff3_[i]) : bigram_backoff_[i];
  }
  double TriLogp(uint64_t i) const {
    return compact_ ? cyime_t9_ngram::DequantizeLog(tri_logp3_[i]) : tri_logp_[i];
  }
  uint64_t TriBegin(uint64_t b) const {
    return compact_ ? static_cast<uint64_t>(tri_base_[b >> tri_block_shift_]) + tri_lo_[b] : tri_begin_[b];
  }
  // Index of `word` in the ascending id range [first, last) of a table, or -1.
  int64_t FindBigram(uint64_t first, uint64_t last, uint32_t word) const;
  int64_t FindTrigram(uint64_t first, uint64_t last, uint32_t word) const;

  bool compact_ = false;
  uint32_t version_ = 0;
  uint32_t words_count_ = 0;
  int32_t eos_ = kUnseen;
  int32_t bos_ = kUnseen;
  uint64_t bigrams_ = 0;
  uint64_t trigrams_ = 0;
  uint64_t hash_mask_ = 0;
  float unseen_lower_ = 0;
  float unseen_ml_ = 0;
  uint32_t tri_block_shift_ = 0;
  // Version 2 tables.
  const cyime_t9_ngram::HashSlot* slots_ = nullptr;
  const cyime_t9_ngram::WordEntry* words_ = nullptr;
  const uint32_t* next_ = nullptr;
  const float* logp_ = nullptr;
  const float* bigram_backoff_ = nullptr;
  const uint32_t* tri_begin_ = nullptr;
  const uint32_t* tri_next_ = nullptr;
  const float* tri_logp_ = nullptr;
  // Version 3 tables.
  const uint64_t* slots3_ = nullptr;
  const int16_t* words3_ = nullptr;
  const uint8_t* next3_ = nullptr;
  const int16_t* logp3_ = nullptr;
  const int16_t* bigram_backoff3_ = nullptr;
  const uint32_t* tri_base_ = nullptr;
  const uint16_t* tri_lo_ = nullptr;
  const uint8_t* tri_next3_ = nullptr;
  const int16_t* tri_logp3_ = nullptr;
  // Shared.
  const uint32_t* begin_ = nullptr;
  void* data_ = nullptr;
  size_t size_ = 0;
};

#endif  // CYIME_T9_NGRAM_MODEL_H_
