#include "t9_ngram_model.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>
#include <limits>
#include <map>
#include <mutex>
#include <vector>

using cyime_t9_ngram::HashSlot;
using cyime_t9_ngram::Header;
using cyime_t9_ngram::Header3;
using cyime_t9_ngram::WordEntry;

namespace {
std::mutex& OpenMutex() {
  static std::mutex mutex;
  return mutex;
}
std::map<std::string, std::shared_ptr<const T9NgramModel>>& OpenModels() {
  static std::map<std::string, std::shared_ptr<const T9NgramModel>> models;
  return models;
}
}  // namespace

void T9NgramModel::ReleaseResidentPages() {
  std::lock_guard<std::mutex> lock(OpenMutex());
  for (const auto& entry : OpenModels()) {
    const auto& model = entry.second;
    // Read-only shared file mapping: DONTNEED only unmaps pages, it never loses data.
    if (model && model->data_) madvise(model->data_, model->size_, MADV_DONTNEED);
  }
}

std::shared_ptr<const T9NgramModel> T9NgramModel::Open(const std::string& path) {
  std::lock_guard<std::mutex> lock(OpenMutex());
  auto& models = OpenModels();
  auto found = models.find(path);
  if (found != models.end()) return found->second;
  std::shared_ptr<T9NgramModel> model(new T9NgramModel());
  if (path.empty() || !model->Map(path)) model.reset();
  // A missing file is also remembered: installing a model takes effect after restart,
  // like the grammar, and the decoder never stats the file on every key.
  models[path] = model;
  return model;
}

T9NgramModel::~T9NgramModel() {
  if (data_) munmap(data_, size_);
}

bool T9NgramModel::Map(const std::string& path) {
  const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) return false;
  struct stat st;
  if (fstat(fd, &st) != 0 || st.st_size < static_cast<off_t>(sizeof(Header))) { close(fd); return false; }
  void* data = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_SHARED, fd, 0);
  close(fd);
  if (data == MAP_FAILED) return false;
  data_ = data;
  size_ = static_cast<size_t>(st.st_size);
  const auto* bytes = static_cast<const char*>(data_);
  bool valid = false;
  if (std::memcmp(bytes, cyime_t9_ngram::kMagic, sizeof(cyime_t9_ngram::kMagic)) == 0) valid = MapV2(bytes);
  else if (std::memcmp(bytes, cyime_t9_ngram::kMagic3, sizeof(cyime_t9_ngram::kMagic3)) == 0 &&
           size_ >= sizeof(Header3)) valid = MapV3(bytes);
  if (!valid) { munmap(data_, size_); data_ = nullptr; return false; }
  return true;
}

bool T9NgramModel::MapV2(const char* bytes) {
  const Header& h = *reinterpret_cast<const Header*>(bytes);
  const uint64_t words = h.words, bigrams = h.bigrams, trigrams = h.trigrams;
  const bool valid = h.version == cyime_t9_ngram::kVersion && h.hash_capacity &&
      !(h.hash_capacity & (h.hash_capacity - 1)) && h.eos < words && h.bos < words &&
      h.hash_offset + h.hash_capacity * sizeof(HashSlot) <= size_ &&
      h.word_offset + words * sizeof(WordEntry) <= size_ &&
      h.begin_offset + (words + 1) * sizeof(uint32_t) <= size_ &&
      h.next_offset + bigrams * sizeof(uint32_t) <= size_ &&
      h.logp_offset + bigrams * sizeof(float) <= size_ &&
      h.bigram_backoff_offset + bigrams * sizeof(float) <= size_ &&
      h.tri_begin_offset + (bigrams + 1) * sizeof(uint32_t) <= size_ &&
      h.tri_next_offset + trigrams * sizeof(uint32_t) <= size_ &&
      h.tri_logp_offset + trigrams * sizeof(float) <= size_;
  if (!valid) return false;
  compact_ = false;
  version_ = h.version;
  words_count_ = h.words;
  eos_ = static_cast<int32_t>(h.eos);
  bos_ = static_cast<int32_t>(h.bos);
  bigrams_ = bigrams;
  trigrams_ = trigrams;
  hash_mask_ = h.hash_capacity - 1;
  unseen_lower_ = h.unseen_lower;
  unseen_ml_ = h.unseen_ml;
  slots_ = reinterpret_cast<const HashSlot*>(bytes + h.hash_offset);
  words_ = reinterpret_cast<const WordEntry*>(bytes + h.word_offset);
  begin_ = reinterpret_cast<const uint32_t*>(bytes + h.begin_offset);
  next_ = reinterpret_cast<const uint32_t*>(bytes + h.next_offset);
  logp_ = reinterpret_cast<const float*>(bytes + h.logp_offset);
  bigram_backoff_ = reinterpret_cast<const float*>(bytes + h.bigram_backoff_offset);
  tri_begin_ = reinterpret_cast<const uint32_t*>(bytes + h.tri_begin_offset);
  tri_next_ = reinterpret_cast<const uint32_t*>(bytes + h.tri_next_offset);
  tri_logp_ = reinterpret_cast<const float*>(bytes + h.tri_logp_offset);
  return begin_[words] == bigrams && tri_begin_[bigrams] == trigrams;
}

bool T9NgramModel::MapV3(const char* bytes) {
  const Header3& h = *reinterpret_cast<const Header3*>(bytes);
  const uint64_t words = h.words, bigrams = h.bigrams, trigrams = h.trigrams;
  const uint64_t blocks = (bigrams >> h.tri_block_shift) + 1;
  const bool valid = h.version == cyime_t9_ngram::kVersion3 && h.hash_capacity &&
      !(h.hash_capacity & (h.hash_capacity - 1)) && h.eos < words && h.bos < words &&
      words <= cyime_t9_ngram::kMaxCompactWords && h.tri_block_shift <= 16 &&
      h.hash_offset + h.hash_capacity * sizeof(uint64_t) <= size_ &&
      h.word_offset + words * 3 * sizeof(int16_t) <= size_ &&
      h.begin_offset + (words + 1) * sizeof(uint32_t) <= size_ &&
      h.next_offset + bigrams * 3 <= size_ &&
      h.logp_offset + bigrams * sizeof(int16_t) <= size_ &&
      h.bigram_backoff_offset + bigrams * sizeof(int16_t) <= size_ &&
      h.tri_base_offset + blocks * sizeof(uint32_t) <= size_ &&
      h.tri_lo_offset + (bigrams + 1) * sizeof(uint16_t) <= size_ &&
      h.tri_next_offset + trigrams * 3 <= size_ &&
      h.tri_logp_offset + trigrams * sizeof(int16_t) <= size_;
  if (!valid) return false;
  compact_ = true;
  version_ = h.version;
  words_count_ = h.words;
  eos_ = static_cast<int32_t>(h.eos);
  bos_ = static_cast<int32_t>(h.bos);
  bigrams_ = bigrams;
  trigrams_ = trigrams;
  hash_mask_ = h.hash_capacity - 1;
  unseen_lower_ = h.unseen_lower;
  unseen_ml_ = h.unseen_ml;
  tri_block_shift_ = h.tri_block_shift;
  slots3_ = reinterpret_cast<const uint64_t*>(bytes + h.hash_offset);
  words3_ = reinterpret_cast<const int16_t*>(bytes + h.word_offset);
  begin_ = reinterpret_cast<const uint32_t*>(bytes + h.begin_offset);
  next3_ = reinterpret_cast<const uint8_t*>(bytes + h.next_offset);
  logp3_ = reinterpret_cast<const int16_t*>(bytes + h.logp_offset);
  bigram_backoff3_ = reinterpret_cast<const int16_t*>(bytes + h.bigram_backoff_offset);
  tri_base_ = reinterpret_cast<const uint32_t*>(bytes + h.tri_base_offset);
  tri_lo_ = reinterpret_cast<const uint16_t*>(bytes + h.tri_lo_offset);
  tri_next3_ = reinterpret_cast<const uint8_t*>(bytes + h.tri_next_offset);
  tri_logp3_ = reinterpret_cast<const int16_t*>(bytes + h.tri_logp_offset);
  return begin_[words] == bigrams && TriBegin(bigrams) == trigrams;
}

int32_t T9NgramModel::Id(const char* text, size_t size) const {
  const uint64_t key = cyime_t9_ngram::Hash(text, size);
  if (compact_) {
    const uint64_t high = key & cyime_t9_ngram::kKeyMask;
    for (uint64_t i = key & hash_mask_;; i = (i + 1) & hash_mask_) {
      const uint64_t slot = slots3_[i];
      if (!slot) return kUnseen;
      if ((slot & cyime_t9_ngram::kKeyMask) == high) return static_cast<int32_t>((slot & 0xFFFFFF) - 1);
    }
  }
  for (uint64_t i = key & hash_mask_;; i = (i + 1) & hash_mask_) {
    if (slots_[i].key == key) return static_cast<int32_t>(slots_[i].id);
    if (!slots_[i].key) return kUnseen;
  }
}

int64_t T9NgramModel::FindBigram(uint64_t first, uint64_t last, uint32_t word) const {
  if (!compact_) {
    const uint32_t* found = std::lower_bound(next_ + first, next_ + last, word);
    return found != next_ + last && *found == word ? found - next_ : -1;
  }
  const uint64_t end = last;
  while (first < last) {
    const uint64_t mid = first + (last - first) / 2;
    if (cyime_t9_ngram::ReadId24(next3_ + mid * 3) < word) first = mid + 1; else last = mid;
  }
  return first < end && cyime_t9_ngram::ReadId24(next3_ + first * 3) == word ? static_cast<int64_t>(first) : -1;
}

int64_t T9NgramModel::FindTrigram(uint64_t first, uint64_t last, uint32_t word) const {
  if (!compact_) {
    const uint32_t* found = std::lower_bound(tri_next_ + first, tri_next_ + last, word);
    return found != tri_next_ + last && *found == word ? found - tri_next_ : -1;
  }
  const uint64_t end = last;
  while (first < last) {
    const uint64_t mid = first + (last - first) / 2;
    if (cyime_t9_ngram::ReadId24(tri_next3_ + mid * 3) < word) first = mid + 1; else last = mid;
  }
  return first < end && cyime_t9_ngram::ReadId24(tri_next3_ + first * 3) == word ? static_cast<int64_t>(first) : -1;
}

double T9NgramModel::Start(int32_t word, bool sentence_start) const {
  if (sentence_start) return LogProb(Bos(), word);
  return word == kUnseen ? unseen_ml_ : LnMl(word);
}

double T9NgramModel::LogProb(int32_t history, int32_t word) const {
  const double lower = word == kUnseen ? unseen_lower_ : LnLower(word);
  if (history == kUnseen) return lower;
  if (word != kUnseen) {
    const int64_t found = FindBigram(begin_[history], begin_[history + 1], static_cast<uint32_t>(word));
    if (found >= 0) return BigramLogp(static_cast<uint64_t>(found));
  }
  return LnBackoff(history) + lower;
}

double T9NgramModel::LogProb3(int32_t history2, int32_t history, int32_t word) const {
  const double bigram = LogProb(history, word);
  if (history2 == kUnseen || history == kUnseen) return bigram;
  // The decoder scores every candidate word after one path before moving to the next path, so the
  // same (history2, history) pair comes many times in a row: remember its index per thread.
  struct PairMemo {
    const T9NgramModel* model = nullptr;
    int32_t history2 = 0, history = 0;
    int64_t pair = -1;
  };
  static thread_local PairMemo memo;
  if (memo.model != this || memo.history2 != history2 || memo.history != history) {
    memo.pair = FindBigram(begin_[history2], begin_[history2 + 1], static_cast<uint32_t>(history));
    memo.model = this;
    memo.history2 = history2;
    memo.history = history;
  }
  const int64_t pair = memo.pair;
  if (pair < 0) return bigram;
  const uint64_t b = static_cast<uint64_t>(pair);
  if (word != kUnseen) {
    const int64_t found = FindTrigram(TriBegin(b), TriBegin(b + 1), static_cast<uint32_t>(word));
    if (found >= 0) return TriLogp(static_cast<uint64_t>(found));
  }
  return BigramBackoff(b) + bigram;
}

void T9NgramModel::Decompose(const std::string& text, std::vector<int32_t>* parts) const {
  constexpr size_t kMaxChars = 8;
  std::vector<size_t> starts;  // byte offset of each UTF-8 character, plus the end
  for (size_t i = 0; i < text.size(); ++i)
    if ((static_cast<unsigned char>(text[i]) & 0xC0) != 0x80) starts.push_back(i);
  starts.push_back(text.size());
  const size_t n = starts.size() - 1;
  // Frequency-only split; the chain then applies the n-grams.
  std::vector<double> best(n + 1, -std::numeric_limits<double>::infinity());
  std::vector<size_t> back(n + 1, 0);
  std::vector<int32_t> ids(n + 1, kUnseen);
  best[0] = 0;
  for (size_t i = 0; i < n; ++i) {
    for (size_t j = i + 1; j <= n && j - i <= kMaxChars; ++j) {
      const int32_t id = Id(text.data() + starts[i], starts[j] - starts[i]);
      if (id == kUnseen && j > i + 1) continue;
      const double score = best[i] + (id == kUnseen ? unseen_ml_ : LnMl(id));
      if (score > best[j]) { best[j] = score; back[j] = i; ids[j] = id; }
    }
  }
  parts->clear();
  for (size_t k = n; k > 0; k = back[k]) parts->push_back(ids[k]);
  std::reverse(parts->begin(), parts->end());
}

double T9NgramModel::Chain(int32_t history2, int32_t history, bool first, bool sentence_start,
                           const std::vector<int32_t>& parts, int32_t* last2, int32_t* last,
                           const Pairs* typo_bigrams) const {
  double total = 0;
  int32_t a = history2, b = history;
  for (size_t k = 0; k < parts.size(); ++k) {
    if (k == 0 && first) {
      total += Start(parts[k], sentence_start);
      a = sentence_start ? Bos() : kUnseen;
    } else {
      total += typo_bigrams && HasPair(*typo_bigrams, b, parts[k]) ? LogProb(kUnseen, parts[k])
                                                                    : LogProb3(a, b, parts[k]);
      a = b;
    }
    b = parts[k];
  }
  if (last2) *last2 = a;
  if (last) *last = b;
  return total;
}
