// Build the T9 word bigram model: one consistent weight scale for every dictionary word.
//
// 1. Segment the corpus (Han runs, one per line) with the T9 dictionary vocabulary by
//    maximum unigram probability; re-estimate the unigram from the segmentation and
//    repeat, so every word weight comes from the same corpus counts.
// 2. Count bigrams on the final segmentation (with <s> and </s>), smooth them with
//    interpolated modified Kneser-Ney, prune by count and renormalize the backoff weights.
// 3. Write a memory-mappable model (format in t9_ngram_format.h) read by the joint decoder.
//
// Usage: ngram_build vocab.tsv corpus.txt out.bin [--rounds 3] [--min-count 2]
//                   [--tmp DIR] [--segment IN OUT]... [--format 3|2] [--max-words N]
// --max-words N (size experiments): keep only the N most frequent words in the model; the rest of the
// dictionary scores as unseen words. 0 (default) keeps every seen word, as before.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <memory>
#include <queue>
#include <string>
#include <utility>
#include <vector>

#include "ngram_write.h"
#include "t9_ngram_format.h"

namespace {

constexpr uint64_t kFnvOffset = 14695981039346656037ULL;
constexpr uint64_t kFnvPrime = 1099511628211ULL;
constexpr uint32_t kPrefixBit = 0x80000000u;
constexpr int kMaxWordChars = 12;
constexpr double kNegInf = -std::numeric_limits<double>::infinity();

inline uint64_t StepCodePoint(uint64_t h, uint32_t c) {
  for (int i = 0; i < 4; ++i) { h ^= (c >> (8 * i)) & 0xff; h *= kFnvPrime; }
  return h;
}

// Open addressing uint64 -> uint32 (key 0 is reserved for empty slots).
struct HashTable {
  std::vector<uint64_t> keys;
  std::vector<uint32_t> values;
  uint64_t mask = 0;
  void Init(size_t expected) {
    size_t capacity = 1;
    while (capacity < expected * 2) capacity <<= 1;
    keys.assign(capacity, 0);
    values.assign(capacity, 0);
    mask = capacity - 1;
  }
  static uint64_t Fix(uint64_t key) { return key ? key : 1; }
  uint32_t& Slot(uint64_t key) {
    key = Fix(key);
    for (uint64_t i = key & mask;; i = (i + 1) & mask) {
      if (keys[i] == key) return values[i];
      if (!keys[i]) { keys[i] = key; return values[i]; }
    }
  }
  const uint32_t* Find(uint64_t key) const {
    key = Fix(key);
    for (uint64_t i = key & mask;; i = (i + 1) & mask) {
      if (keys[i] == key) return &values[i];
      if (!keys[i]) return nullptr;
    }
  }
};

bool DecodeUtf8(const std::string& s, std::vector<uint32_t>* out) {
  out->clear();
  for (size_t i = 0; i < s.size();) {
    unsigned char c = s[i];
    uint32_t cp; int n;
    if (c < 0x80) { cp = c; n = 1; }
    else if ((c >> 5) == 6) { cp = c & 0x1f; n = 2; }
    else if ((c >> 4) == 14) { cp = c & 0x0f; n = 3; }
    else if ((c >> 3) == 30) { cp = c & 0x07; n = 4; }
    else return false;
    if (i + n > s.size()) return false;
    for (int k = 1; k < n; ++k) cp = (cp << 6) | (s[i + k] & 0x3f);
    out->push_back(cp);
    i += n;
  }
  return true;
}

struct Vocab {
  std::vector<std::string> words;     // UTF-8 text by id
  std::vector<double> prior;          // dictionary counts: first-round guess and unigram prior
  std::vector<uint8_t> chars;         // length in characters
  HashTable table;                    // code-point FNV of word or prefix -> id+1 | prefix bit
};

Vocab LoadVocab(const char* path) {
  Vocab v;
  std::ifstream in(path);
  std::string line;
  std::vector<std::vector<uint32_t>> cps;
  std::vector<uint32_t> buffer;
  while (std::getline(in, line)) {
    auto tab = line.find('\t');
    if (tab == std::string::npos) continue;
    std::string text = line.substr(0, tab);
    if (!DecodeUtf8(text, &buffer) || buffer.empty() || buffer.size() > kMaxWordChars) continue;
    v.words.push_back(text);
    v.prior.push_back(std::stod(line.substr(tab + 1)));
    v.chars.push_back(static_cast<uint8_t>(buffer.size()));
    cps.push_back(buffer);
  }
  size_t entries = 0;
  for (const auto& w : cps) entries += w.size();
  v.table.Init(entries);
  for (uint32_t id = 0; id < cps.size(); ++id) {
    uint64_t h = kFnvOffset;
    for (size_t j = 0; j < cps[id].size(); ++j) {
      h = StepCodePoint(h, cps[id][j]);
      uint32_t& slot = v.table.Slot(h);
      if (j + 1 < cps[id].size()) slot |= kPrefixBit;
      else if ((slot & ~kPrefixBit) == 0) slot |= (id + 1);
    }
  }
  std::cerr << "vocab words=" << v.words.size() << " table_slots=" << v.table.keys.size() << "\n";
  return v;
}

// Maximum-probability segmentation of one Han run. Characters outside the vocabulary
// become the OOV token (id = vocab size).
void Segment(const Vocab& v, const std::vector<double>& logp, double oov_logp,
             const std::vector<uint32_t>& cps, std::vector<uint32_t>* tokens,
             std::vector<double>& best, std::vector<int>& back, std::vector<uint32_t>& ids) {
  const size_t n = cps.size();
  const uint32_t oov = static_cast<uint32_t>(v.words.size());
  best.assign(n + 1, kNegInf);
  back.assign(n + 1, -1);
  ids.assign(n + 1, oov);
  best[0] = 0;
  for (size_t i = 0; i < n; ++i) {
    if (best[i] == kNegInf) continue;
    uint64_t h = kFnvOffset;
    bool single = false;
    for (size_t j = i; j < n && j < i + kMaxWordChars; ++j) {
      h = StepCodePoint(h, cps[j]);
      const uint32_t* slot = v.table.Find(h);
      if (!slot) break;
      const uint32_t id = *slot & ~kPrefixBit;
      if (id) {
        const double score = best[i] + logp[id - 1];
        if (score > best[j + 1]) { best[j + 1] = score; back[j + 1] = static_cast<int>(i); ids[j + 1] = id - 1; }
        if (j == i) single = true;
      }
      if (!(*slot & kPrefixBit)) break;
    }
    if (!single) {
      const double score = best[i] + oov_logp;
      if (score > best[i + 1]) { best[i + 1] = score; back[i + 1] = static_cast<int>(i); ids[i + 1] = oov; }
    }
  }
  tokens->clear();
  for (int k = static_cast<int>(n); k > 0; k = back[k]) tokens->push_back(ids[k]);
  std::reverse(tokens->begin(), tokens->end());
}

std::vector<double> LogProbs(const std::vector<double>& counts, double delta) {
  double total = 0;
  for (double c : counts) total += c;
  const double denominator = total + delta * counts.size();
  std::vector<double> logp(counts.size());
  for (size_t i = 0; i < counts.size(); ++i) logp[i] = std::log((counts[i] + delta) / denominator);
  return logp;
}

struct PairCount { uint64_t key; uint32_t count; };

// Counts of a chunk are multiplied by its corpus weight (integer source weighting).
void FlushChunk(std::vector<uint64_t>* pairs, const std::string& path, uint32_t weight = 1) {
  std::sort(pairs->begin(), pairs->end());
  std::ofstream out(path, std::ios::binary);
  for (size_t i = 0; i < pairs->size();) {
    size_t j = i;
    while (j < pairs->size() && (*pairs)[j] == (*pairs)[i]) ++j;
    PairCount pc{(*pairs)[i], static_cast<uint32_t>(j - i) * weight};
    out.write(reinterpret_cast<const char*>(&pc), sizeof(pc));
    i = j;
  }
  pairs->clear();
}

// k-way merge of sorted chunk files into one sorted (key, count) file.
void MergeChunks(const std::vector<std::string>& chunks, const std::string& output) {
  struct Reader { std::ifstream in; PairCount current; bool ok; };
  std::vector<std::unique_ptr<Reader>> readers;
  for (const auto& path : chunks) {
    auto r = std::make_unique<Reader>();
    r->in.open(path, std::ios::binary);
    r->ok = static_cast<bool>(r->in.read(reinterpret_cast<char*>(&r->current), sizeof(PairCount)));
    readers.push_back(std::move(r));
  }
  auto greater = [&](size_t a, size_t b) { return readers[a]->current.key > readers[b]->current.key; };
  std::priority_queue<size_t, std::vector<size_t>, decltype(greater)> heap(greater);
  for (size_t i = 0; i < readers.size(); ++i) if (readers[i]->ok) heap.push(i);
  std::ofstream out(output, std::ios::binary);
  bool has = false;
  PairCount acc{0, 0};
  while (!heap.empty()) {
    size_t i = heap.top(); heap.pop();
    const PairCount pc = readers[i]->current;
    if (has && pc.key == acc.key) acc.count += pc.count;
    else { if (has) out.write(reinterpret_cast<const char*>(&acc), sizeof(acc)); acc = pc; has = true; }
    if (readers[i]->in.read(reinterpret_cast<char*>(&readers[i]->current), sizeof(PairCount))) heap.push(i);
  }
  if (has) out.write(reinterpret_cast<const char*>(&acc), sizeof(acc));
}

uint64_t Fnv1a(const std::string& s) {
  uint64_t h = kFnvOffset;
  for (unsigned char c : s) { h ^= c; h *= kFnvPrime; }
  return h ? h : 1;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 4) { std::cerr << "usage: ngram_build vocab.tsv corpus.txt[,corpus.txt...] out.lm [options]\n"; return 2; }
  int rounds = 3;
  uint32_t min_count = 2, min_count3 = 2;
  bool trigrams = true;
  double delta = 0.5;
  double prior_weight = 0;  // share of the dictionary-count unigram in the lower orders
  // Relative-entropy pruning (Stolcke): keep an n-gram only if dropping it would move the
  // model by at least this much, i.e. P(n-gram) * ln(P / backed-off P) >= threshold.
  double prune2 = 0, prune3 = 0;
  size_t max_words = 0;
  uint32_t format = cyime_t9_ngram::kVersion3;  // --format 2 writes the original layout
  std::string tmp = "/tmp";
  std::vector<std::pair<std::string, std::string>> segment_jobs;
  for (int i = 4; i < argc; ++i) {
    std::string a = argv[i];
    if (a == "--rounds") rounds = std::stoi(argv[++i]);
    else if (a == "--min-count") min_count = static_cast<uint32_t>(std::stoul(argv[++i]));
    else if (a == "--min-count3") min_count3 = static_cast<uint32_t>(std::stoul(argv[++i]));
    else if (a == "--no-trigrams") trigrams = false;
    else if (a == "--delta") delta = std::stod(argv[++i]);
    else if (a == "--prior-weight") prior_weight = std::stod(argv[++i]);
    else if (a == "--prune2") prune2 = std::stod(argv[++i]);
    else if (a == "--prune3") prune3 = std::stod(argv[++i]);
    else if (a == "--max-words") max_words = static_cast<size_t>(std::stoull(argv[++i]));
    else if (a == "--format") format = static_cast<uint32_t>(std::stoul(argv[++i]));
    else if (a == "--tmp") tmp = argv[++i];
    else if (a == "--segment") { segment_jobs.emplace_back(argv[i + 1], argv[i + 2]); i += 2; }
  }
  // Corpora as path[:weight]; a weight multiplies that corpus's counts.
  std::vector<std::pair<std::string, uint32_t>> corpora;
  for (std::string list = argv[2]; !list.empty();) {
    const auto comma = list.find(',');
    std::string item = list.substr(0, comma);
    uint32_t weight = 1;
    const auto colon = item.rfind(':');
    if (colon != std::string::npos && colon + 1 < item.size() &&
        item.find_first_not_of("0123456789", colon + 1) == std::string::npos) {
      weight = static_cast<uint32_t>(std::stoul(item.substr(colon + 1)));
      item = item.substr(0, colon);
    }
    corpora.emplace_back(item, weight);
    list = comma == std::string::npos ? "" : list.substr(comma + 1);
  }
  Vocab vocab = LoadVocab(argv[1]);
  const size_t V = vocab.words.size();
  const uint32_t OOV = static_cast<uint32_t>(V), BOS = OOV + 1, EOS = OOV + 2;
  if (EOS >= (1u << 21)) { std::cerr << "vocabulary too large for 21-bit trigram keys\n"; return 2; }

  // Round 0 guess: dictionary counts where they exist; presence-only words get a small count.
  std::vector<double> counts(V);
  for (size_t i = 0; i < V; ++i) counts[i] = vocab.prior[i] > 0 ? vocab.prior[i] : 1;
  std::vector<double> logp = LogProbs(counts, delta);
  double oov_logp = std::log(delta / 1e12);

  std::vector<uint32_t> cps, tokens, ids;
  std::vector<double> best;
  std::vector<int> back;
  std::vector<uint64_t> pairs, triples;
  std::vector<std::string> chunks, tchunks;
  const size_t kChunkPairs = 48u << 20;
  auto triple = [](uint64_t u, uint64_t v, uint64_t w) { return (u << 42) | (v << 21) | w; };
  uint64_t total_tokens = 0, total_runs = 0, oov_tokens = 0;
  for (int round = 0; round < rounds; ++round) {
    const bool last = round + 1 == rounds;
    std::vector<double> next(V, 0.0);
    total_tokens = total_runs = oov_tokens = 0;
    for (const auto& [corpus, weight] : corpora) {
      std::ifstream in(corpus);
      std::string line;
      while (std::getline(in, line)) {
        if (line.empty() || !DecodeUtf8(line, &cps)) continue;
        Segment(vocab, logp, oov_logp, cps, &tokens, best, back, ids);
        ++total_runs;
        uint32_t u = BOS, v = BOS;  // v: previous token, u: the one before it
        for (size_t k = 0; k < tokens.size(); ++k) {
          const uint32_t t = tokens[k];
          ++total_tokens;
          if (t == OOV) ++oov_tokens; else next[t] += weight;
          if (last) {
            pairs.push_back((static_cast<uint64_t>(v) << 32) | t);
            if (trigrams && k > 0) triples.push_back(triple(u, v, t));
          }
          u = v;
          v = t;
        }
        if (last) {
          pairs.push_back((static_cast<uint64_t>(v) << 32) | EOS);
          if (trigrams && !tokens.empty()) triples.push_back(triple(u, v, EOS));
          if (pairs.size() >= kChunkPairs) {
            chunks.push_back(tmp + "/chunk" + std::to_string(chunks.size()) + ".bin");
            FlushChunk(&pairs, chunks.back(), weight);
          }
          if (triples.size() >= kChunkPairs) {
            tchunks.push_back(tmp + "/tchunk" + std::to_string(tchunks.size()) + ".bin");
            FlushChunk(&triples, tchunks.back(), weight);
          }
        }
      }
      // A chunk never mixes corpora, so each keeps its own weight.
      if (last && !pairs.empty()) {
        chunks.push_back(tmp + "/chunk" + std::to_string(chunks.size()) + ".bin");
        FlushChunk(&pairs, chunks.back(), weight);
      }
      if (last && !triples.empty()) {
        tchunks.push_back(tmp + "/tchunk" + std::to_string(tchunks.size()) + ".bin");
        FlushChunk(&triples, tchunks.back(), weight);
      }
    }
    std::cerr << "round " << round << " runs=" << total_runs << " tokens=" << total_tokens
              << " oov_tokens=" << oov_tokens << "\n";
    logp = LogProbs(next, delta);
    counts.swap(next);
  }
  if (!pairs.empty()) {
    chunks.push_back(tmp + "/chunk" + std::to_string(chunks.size()) + ".bin");
    FlushChunk(&pairs, chunks.back());
  }
  if (!triples.empty()) {
    tchunks.push_back(tmp + "/tchunk" + std::to_string(tchunks.size()) + ".bin");
    FlushChunk(&triples, tchunks.back());
  }
  std::vector<uint64_t>().swap(pairs);
  std::vector<uint64_t>().swap(triples);
  const std::string merged = tmp + "/bigrams.bin", tmerged = tmp + "/trigrams.bin";
  MergeChunks(chunks, merged);
  for (const auto& c : chunks) std::remove(c.c_str());
  if (trigrams) {
    MergeChunks(tchunks, tmerged);
    for (const auto& c : tchunks) std::remove(c.c_str());
  }

  for (const auto& job : segment_jobs) {
    std::ifstream in(job.first);
    std::ofstream out(job.second);
    std::string line;
    while (std::getline(in, line)) {
      if (line.empty() || !DecodeUtf8(line, &cps)) continue;
      Segment(vocab, logp, oov_logp, cps, &tokens, best, back, ids);
      for (size_t k = 0; k < tokens.size(); ++k) {
        if (k) out << ' ';
        out << (tokens[k] == OOV ? std::string("<unk>") : vocab.words[tokens[k]]);
      }
      out << '\n';
    }
  }

  // Pass A: history totals, type counts per history and continuation counts.
  const size_t S = V + 3;  // words, OOV, BOS, EOS
  std::vector<double> history(S, 0), unigram(S, 0);
  std::vector<uint32_t> n1(S, 0), n2(S, 0), n3(S, 0), continuation(S, 0);
  uint64_t coc[5] = {0, 0, 0, 0, 0};
  uint64_t types = 0;
  {
    std::ifstream in(merged, std::ios::binary);
    PairCount pc;
    while (in.read(reinterpret_cast<char*>(&pc), sizeof(pc))) {
      const uint32_t v = static_cast<uint32_t>(pc.key >> 32), w = static_cast<uint32_t>(pc.key);
      history[v] += pc.count;
      unigram[w] += pc.count;
      if (pc.count == 1) ++n1[v]; else if (pc.count == 2) ++n2[v]; else ++n3[v];
      ++continuation[w];
      if (pc.count <= 4) ++coc[pc.count];
      ++types;
    }
  }
  const double Y = static_cast<double>(coc[1]) / (coc[1] + 2.0 * coc[2]);
  const double D1 = 1 - 2 * Y * coc[2] / coc[1];
  const double D2 = 2 - 3 * Y * coc[3] / coc[2];
  const double D3 = 3 - 4 * Y * coc[4] / coc[3];
  double pair_total = 0;  // all bigram tokens: n-gram frequency for entropy pruning
  for (double h : history) pair_total += h;
  // Lower order: continuation counts with add-delta over the whole dictionary (plus </s>),
  // so every dictionary word unseen in the corpus sits below every seen word.
  double continuation_total = 0, unigram_total = 0;
  for (size_t w = 0; w < S; ++w) if (w != BOS && w != OOV) { continuation_total += continuation[w]; unigram_total += unigram[w]; }
  const double lower_denominator = continuation_total + delta * (V + 1);
  const double ml_denominator = unigram_total + delta * (V + 1);
  // Second evidence source: the dictionary's own word counts (multi-character words; the
  // character table counts characters inside words, not tokens). Words the corpus lacks
  // keep the knowledge of the larger corpus behind the dictionary instead of a floor.
  std::vector<double> prior_p(S, 0.0);
  {
    double total = 0;
    for (uint32_t w = 0; w < V; ++w) if (vocab.chars[w] >= 2 && vocab.prior[w] > 0) total += vocab.prior[w];
    if (total > 0 && prior_weight > 0)
      for (uint32_t w = 0; w < V; ++w) if (vocab.chars[w] >= 2 && vocab.prior[w] > 0) prior_p[w] = vocab.prior[w] / total;
  }
  auto lower = [&](uint32_t w) {
    return (1 - prior_weight) * (continuation[w] + delta) / lower_denominator + prior_weight * prior_p[w];
  };

  // Model ids: seen dictionary words (by vocab id), then </s>, then <s> (history only).
  std::vector<int64_t> model_id(S, -1);
  std::vector<uint32_t> model_vocab;
  std::vector<char> keep(V, 0);
  {
    std::vector<uint32_t> seen;
    for (uint32_t w = 0; w < V; ++w) if (unigram[w] > 0 || history[w] > 0 || prior_p[w] > 0) seen.push_back(w);
    // Most frequent first: corpus count, then the dictionary's own count; ties by id, so builds repeat.
    if (max_words > 0 && seen.size() > max_words) {
      std::nth_element(seen.begin(), seen.begin() + max_words, seen.end(), [&](uint32_t a, uint32_t b) {
        if (unigram[a] != unigram[b]) return unigram[a] > unigram[b];
        if (prior_p[a] != prior_p[b]) return prior_p[a] > prior_p[b];
        return a < b;
      });
      seen.resize(max_words);
      std::cerr << "max_words=" << max_words << " kept the most frequent words\n";
    }
    for (uint32_t w : seen) keep[w] = 1;
  }
  for (uint32_t w = 0; w < V; ++w)
    if (keep[w]) { model_id[w] = model_vocab.size(); model_vocab.push_back(w); }
  const uint32_t model_eos = model_vocab.size(); model_id[EOS] = model_eos; model_vocab.push_back(EOS);
  const uint32_t model_bos = model_vocab.size(); model_id[BOS] = model_bos; model_vocab.push_back(BOS);
  const uint32_t M = model_vocab.size();

  // Pass B: interpolated probabilities for kept bigrams; renormalized backoff per history.
  std::vector<cyime_t9_ngram::WordEntry> entries(M);
  for (uint32_t m = 0; m < M; ++m) {
    const uint32_t w = model_vocab[m];
    entries[m].ln_lower = static_cast<float>(std::log(lower(w)));
    entries[m].ln_ml = static_cast<float>(std::log((1 - prior_weight) * (unigram[w] + delta) / ml_denominator +
                                                   prior_weight * prior_p[w]));
    entries[m].ln_backoff = 0;
    entries[m].reserved = 0;
  }
  std::vector<std::vector<std::pair<uint32_t, float>>> blocks(M);
  uint64_t kept = 0;
  {
    std::ifstream in(merged, std::ios::binary);
    PairCount pc;
    int64_t current = -1;
    double kept_p = 0, kept_lower = 0, lambda = 0;
    auto close = [&]() {
      if (current < 0 || model_id[current] < 0) return;
      const int64_t mv = model_id[current];
      std::sort(blocks[mv].begin(), blocks[mv].end());
      const double rest = 1 - kept_p, rest_lower = 1 - kept_lower;
      const double backoff = rest > 0 && rest_lower > 0 ? rest / rest_lower : lambda;
      entries[mv].ln_backoff = static_cast<float>(std::log(std::max(backoff, 1e-12)));
    };
    while (in.read(reinterpret_cast<char*>(&pc), sizeof(pc))) {
      const uint32_t v = static_cast<uint32_t>(pc.key >> 32), w = static_cast<uint32_t>(pc.key);
      if (static_cast<int64_t>(v) != current) {
        close();
        current = v;
        kept_p = kept_lower = 0;
        lambda = (D1 * n1[v] + D2 * n2[v] + D3 * n3[v]) / history[v];
      }
      if (pc.count < min_count || model_id[w] < 0 || model_id[v] < 0) continue;
      const double d = pc.count == 1 ? D1 : pc.count == 2 ? D2 : D3;
      const double p = (pc.count - d) / history[v] + lambda * lower(w);
      if (prune2 > 0 && pc.count / pair_total * (std::log(p) - std::log(lambda * lower(w))) < prune2) continue;
      blocks[model_id[v]].emplace_back(static_cast<uint32_t>(model_id[w]), static_cast<float>(std::log(p)));
      kept_p += p;
      kept_lower += lower(w);
      ++kept;
    }
    close();
  }
  std::vector<uint64_t> bigram_base(M + 1, 0);
  for (uint32_t m = 0; m < M; ++m) bigram_base[m + 1] = bigram_base[m] + blocks[m].size();
  const float kLowest = -std::numeric_limits<float>::infinity();
  // ln P(w | v) of the bigram model, both as model ids.
  auto bigram_ln = [&](uint32_t mv, uint32_t mw) -> double {
    const auto& block = blocks[mv];
    const auto found = std::lower_bound(block.begin(), block.end(), std::make_pair(mw, kLowest));
    if (found != block.end() && found->first == mw) return found->second;
    return entries[mv].ln_backoff + entries[mw].ln_lower;
  };
  // Index of the kept bigram (mu, mv), or -1.
  auto bigram_index = [&](uint32_t mu, uint32_t mv) -> int64_t {
    const auto& block = blocks[mu];
    const auto found = std::lower_bound(block.begin(), block.end(), std::make_pair(mv, kLowest));
    if (found == block.end() || found->first != mv) return -1;
    return static_cast<int64_t>(bigram_base[mu] + (found - block.begin()));
  };

  // Pass C: trigrams (modified Kneser-Ney over the bigram model), stored under kept bigrams.
  std::vector<float> bigram_backoff(kept, 0.0f);
  std::vector<uint32_t> tri_begin(kept + 1, 0), tri_next;
  std::vector<float> tri_logp;
  uint64_t trigram_types = 0, tcoc[5] = {0, 0, 0, 0, 0};
  double T1 = 0, T2 = 0, T3 = 0;
  if (trigrams) {
    {
      std::ifstream in(tmerged, std::ios::binary);
      PairCount pc;
      while (in.read(reinterpret_cast<char*>(&pc), sizeof(pc))) {
        ++trigram_types;
        if (pc.count <= 4) ++tcoc[pc.count];
      }
    }
    const double TY = static_cast<double>(tcoc[1]) / (tcoc[1] + 2.0 * tcoc[2]);
    T1 = 1 - 2 * TY * tcoc[2] / tcoc[1];
    T2 = 2 - 3 * TY * tcoc[3] / tcoc[2];
    T3 = 3 - 4 * TY * tcoc[4] / tcoc[3];
    std::ifstream in(tmerged, std::ios::binary);
    PairCount pc;
    std::vector<std::pair<uint32_t, uint32_t>> group;  // (w original id, count)
    uint64_t group_key = ~uint64_t{0}, next_b = 0;
    int64_t last_b = -1;
    auto flush = [&]() {
      if (group.empty()) return;
      const uint32_t u = static_cast<uint32_t>(group_key >> 21), v = static_cast<uint32_t>(group_key & ((1u << 21) - 1));
      const int64_t mu = model_id[u], mv = model_id[v];
      const int64_t b = mu >= 0 && mv >= 0 ? bigram_index(static_cast<uint32_t>(mu), static_cast<uint32_t>(mv)) : -1;
      if (b < 0) { group.clear(); return; }
      if (b <= last_b) { std::cerr << "trigram histories out of order\n"; std::exit(3); }
      last_b = b;
      double total = 0;
      uint32_t g1 = 0, g2 = 0, g3 = 0;
      for (const auto& e : group) { total += e.second; if (e.second == 1) ++g1; else if (e.second == 2) ++g2; else ++g3; }
      const double gamma = (T1 * g1 + T2 * g2 + T3 * g3) / total;
      for (; next_b <= static_cast<uint64_t>(b); ++next_b) tri_begin[next_b] = tri_next.size();
      double kept_p = 0, kept_lower = 0;
      std::vector<std::pair<uint32_t, float>> chosen;
      for (const auto& e : group) {
        if (e.second < min_count3 || model_id[e.first] < 0) continue;
        const uint32_t mw = static_cast<uint32_t>(model_id[e.first]);
        const double d = e.second == 1 ? T1 : e.second == 2 ? T2 : T3;
        const double lower2 = std::exp(bigram_ln(static_cast<uint32_t>(mv), mw));
        const double p = (e.second - d) / total + gamma * lower2;
        if (prune3 > 0 && e.second / pair_total * (std::log(p) - std::log(gamma * lower2)) < prune3) continue;
        chosen.emplace_back(mw, static_cast<float>(std::log(p)));
        kept_p += p;
        kept_lower += lower2;
      }
      std::sort(chosen.begin(), chosen.end());
      for (const auto& c : chosen) { tri_next.push_back(c.first); tri_logp.push_back(c.second); }
      const double rest = 1 - kept_p, rest_lower = 1 - kept_lower;
      const double backoff = chosen.empty() ? 1.0 : rest > 0 && rest_lower > 0 ? rest / rest_lower : gamma;
      bigram_backoff[b] = static_cast<float>(std::log(std::max(backoff, 1e-12)));
      group.clear();
    };
    while (in.read(reinterpret_cast<char*>(&pc), sizeof(pc))) {
      const uint64_t key = pc.key >> 21;
      if (key != group_key) { flush(); group_key = key; }
      group.emplace_back(static_cast<uint32_t>(pc.key & ((1u << 21) - 1)), pc.count);
    }
    flush();
    for (; next_b <= kept; ++next_b) tri_begin[next_b] = tri_next.size();
  }
  const uint64_t kept3 = tri_next.size();

  // Write the file through the shared writer (compact version 3 unless --format 2).
  cyime_t9_ngram::ModelTables tables;
  tables.eos = model_eos;
  tables.bos = model_bos;
  tables.unseen_lower = static_cast<float>(std::log((1 - prior_weight) * delta / lower_denominator));
  tables.unseen_ml = static_cast<float>(std::log((1 - prior_weight) * delta / ml_denominator));
  tables.keys.assign(M, 0);
  for (uint32_t m = 0; m < M; ++m) {
    const uint32_t w = model_vocab[m];
    if (w < V) tables.keys[m] = cyime_t9_ngram::Hash(vocab.words[w].data(), vocab.words[w].size());
  }
  tables.entries = std::move(entries);
  tables.begin.resize(M + 1);
  for (uint32_t m = 0; m <= M; ++m) tables.begin[m] = static_cast<uint32_t>(bigram_base[m]);
  tables.next.reserve(kept); tables.logp.reserve(kept);
  for (auto& b : blocks) {
    for (const auto& e : b) { tables.next.push_back(e.first); tables.logp.push_back(e.second); }
    b.clear(); b.shrink_to_fit();
  }
  tables.bigram_backoff = std::move(bigram_backoff);
  tables.tri_begin = std::move(tri_begin);
  tables.tri_next = std::move(tri_next);
  tables.tri_logp = std::move(tri_logp);
  cyime_t9_ngram::WriteStats stats;
  if (!cyime_t9_ngram::WriteModel(argv[3], tables, format, &stats)) {
    std::cerr << "cannot write " << argv[3] << " (format " << format << ")\n";
    return 2;
  }
  const uint64_t collisions = stats.hash_collisions, file_size = stats.bytes;

  std::printf("{\"corpora\":%zu,\"runs\":%llu,\"tokens\":%llu,\"oov_tokens\":%llu,\"bigram_types\":%llu,"
              "\"coc\":[%llu,%llu,%llu,%llu],\"D\":[%.4f,%.4f,%.4f],\"trigram_types\":%llu,\"D3\":[%.4f,%.4f,%.4f],"
              "\"model_words\":%u,\"kept_bigrams\":%llu,\"kept_trigrams\":%llu,\"min_count\":%u,\"min_count3\":%u,"
              "\"prior_weight\":%.3f,\"hash_collisions\":%llu,\"format\":%u,\"tri_block_shift\":%u,\"clamped\":%llu,\"bytes\":%llu}\n",
              corpora.size(), (unsigned long long)total_runs, (unsigned long long)total_tokens,
              (unsigned long long)oov_tokens, (unsigned long long)types, (unsigned long long)coc[1],
              (unsigned long long)coc[2], (unsigned long long)coc[3], (unsigned long long)coc[4], D1, D2, D3,
              (unsigned long long)trigram_types, T1, T2, T3, M, (unsigned long long)kept,
              (unsigned long long)kept3, min_count, min_count3, prior_weight, (unsigned long long)collisions,
              format, stats.tri_block_shift, (unsigned long long)stats.clamped, (unsigned long long)file_size);
  return 0;
}
