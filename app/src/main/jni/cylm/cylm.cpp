// CyLM batch decoder: the same algorithm as tools/cylm/decoder.py, fast enough to evaluate every board column
// in a minute and to serve as the reference for the IME's own implementation.
//
//   cylm.exe LEXICON.tsv MODEL.lm [--adapt COUNTS.tsv] [--set key=value ...] < items.tsv > out.tsv
//
// LEXICON.tsv: word \t syllables (space separated) \t weight   (exported by cylm/export.py, adjustments applied)
// COUNTS.tsv : a \t b \t w \t count                              (adaptive trigram counts, <s> and </s> included)
// items.tsv  : id \t mode (pinyin|t9|choice) \t keys \t context [\t candidate|candidate... for choice]
// out.tsv    : id \t ms \t cand1|cand2|...
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iostream>
#include <map>
#include <queue>
#include <memory>
#include <sstream>
#include <string>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#ifdef _WIN32
#define NOMINMAX
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

using u16 = std::u16string;
static const int kMaxWord = 8, kMaxAbbrev = 6;

// ------------------------------------------------------------------ utf-8
static std::vector<uint32_t> Utf8Decode(const std::string& s) {
  std::vector<uint32_t> out;
  for (size_t i = 0; i < s.size();) {
    unsigned char c = s[i];
    uint32_t cp; int n;
    if (c < 0x80) { cp = c; n = 1; }
    else if ((c >> 5) == 6) { cp = c & 0x1f; n = 2; }
    else if ((c >> 4) == 14) { cp = c & 0x0f; n = 3; }
    else { cp = c & 0x07; n = 4; }
    for (int k = 1; k < n && i + k < s.size(); ++k) cp = (cp << 6) | (s[i + k] & 0x3f);
    out.push_back(cp); i += n;
  }
  return out;
}
static void Utf8Append(std::string& s, uint32_t cp) {
  if (cp < 0x80) s += char(cp);
  else if (cp < 0x800) { s += char(0xc0 | (cp >> 6)); s += char(0x80 | (cp & 0x3f)); }
  else if (cp < 0x10000) { s += char(0xe0 | (cp >> 12)); s += char(0x80 | ((cp >> 6) & 0x3f)); s += char(0x80 | (cp & 0x3f)); }
  else { s += char(0xf0 | (cp >> 18)); s += char(0x80 | ((cp >> 12) & 0x3f)); s += char(0x80 | ((cp >> 6) & 0x3f)); s += char(0x80 | (cp & 0x3f)); }
}
static std::string Utf8Encode(const std::vector<uint32_t>& v, size_t a, size_t b) {
  std::string s; for (size_t i = a; i < b; ++i) Utf8Append(s, v[i]); return s;
}
static bool IsHan(uint32_t c) { return c >= 0x4e00 && c <= 0x9fff; }

static std::vector<std::string> Split(const std::string& s, char d) {
  std::vector<std::string> out; size_t a = 0;
  for (;;) { size_t b = s.find(d, a); out.push_back(s.substr(a, b - a)); if (b == std::string::npos) break; a = b + 1; }
  return out;
}

static const uint8_t* MapFile(const char* path) {
#ifdef _WIN32
  HANDLE f = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
  if (f == INVALID_HANDLE_VALUE) return nullptr;
  HANDLE map = CreateFileMappingA(f, nullptr, PAGE_READONLY, 0, 0, nullptr);
  return map ? static_cast<const uint8_t*>(MapViewOfFile(map, FILE_MAP_READ, 0, 0, 0)) : nullptr;
#else
  int fd = ::open(path, O_RDONLY);
  if (fd < 0) return nullptr;
  struct stat st; if (fstat(fd, &st) != 0) { ::close(fd); return nullptr; }
  void* addr = mmap(nullptr, size_t(st.st_size), PROT_READ, MAP_SHARED, fd, 0);
  ::close(fd);
  if (addr == MAP_FAILED) return nullptr;
  // Lookups jump around the file: no read-ahead, so only the pages actually read take memory (CyIME Android).
  madvise(addr, size_t(st.st_size), MADV_RANDOM);
  return static_cast<const uint8_t*>(addr);
#endif
}

// Cache caps. Inside an input method the caches live as long as the keyboard process, so the library keeps them to
// a few tens of MB (CyIME Android 2026-10-26: uncapped, the session hit cache grew to ~900 MB of native heap after
// a day of typing and the device swapped on every key).
// kWordCacheMax: the per-word prior and weight caches (Decoder::prior_cache, lnw_cache), which had no cap at all.
#ifdef CYLM_LIBRARY
constexpr size_t kIdCacheMax = 200000, kDecCacheMax = 100000, kHitCacheMax = 20000, kWordCacheMax = 200000;
#else
constexpr size_t kIdCacheMax = 4000000, kDecCacheMax = 2000000, kHitCacheMax = 3000000, kWordCacheMax = 4000000;
#endif

// ------------------------------------------------------------------ c3 model
#pragma pack(push, 1)
struct Header3 {
  char magic[8]; uint32_t version, words, eos, bos; uint64_t bigrams, trigrams, capacity;
  float unseen_lower, unseen_ml; uint32_t shift, reserved;
  uint64_t hash_offset, word_offset, begin_offset, next_offset, logp_offset, backoff_offset, tri_base_offset,
      tri_lo_offset, tri_next_offset, tri_logp_offset;
};
#pragma pack(pop)

struct C3 {
  const uint8_t* m = nullptr; Header3 h{};
  static constexpr double kScale = 1024.0;
  int32_t none = -1;
  size_t id_cap = kIdCacheMax, dec_cap = kDecCacheMax;  // cache caps (params id_cap, dec_cap in the library)
  std::unordered_map<std::string, int32_t> id_cache;
  std::unordered_map<std::string, std::vector<int32_t>> dec_cache;
  bool Open(const char* path) {
#ifdef _WIN32
    HANDLE f = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    HANDLE map = CreateFileMappingA(f, nullptr, PAGE_READONLY, 0, 0, nullptr);
    m = static_cast<const uint8_t*>(MapViewOfFile(map, FILE_MAP_READ, 0, 0, 0));
#else
    int fd = ::open(path, O_RDONLY);
    if (fd < 0) return false;
    struct stat st; if (fstat(fd, &st) != 0) return false;
    void* addr = mmap(nullptr, size_t(st.st_size), PROT_READ, MAP_SHARED, fd, 0);
    ::close(fd);
    if (addr == MAP_FAILED) return false;
    madvise(addr, size_t(st.st_size), MADV_RANDOM);  // random lookups: no read-ahead (see MapFile)
    m = static_cast<const uint8_t*>(addr);
#endif
    std::memcpy(&h, m, sizeof h);
    return std::memcmp(h.magic, "CYT9NGR3", 8) == 0 && h.version == 3;
  }
  template <class T> T At(uint64_t off) const { T v; std::memcpy(&v, m + off, sizeof v); return v; }
  uint32_t U24(uint64_t off) const { return m[off] | (m[off + 1] << 8) | (m[off + 2] << 16); }
  int32_t Id(const std::string& text) {
    auto it = id_cache.find(text);
    if (it != id_cache.end()) return it->second;
    uint64_t hh = 14695981039346656037ULL;
    for (unsigned char b : text) { hh ^= b; hh *= 1099511628211ULL; }
    if (!hh) hh = 1;
    uint64_t mask = h.capacity - 1, i = hh & mask; int32_t r = -1;
    for (;;) {
      uint64_t slot = At<uint64_t>(h.hash_offset + 8 * i);
      if (!slot) break;
      if ((slot >> 24) == (hh >> 24)) { r = int32_t(slot & 0xFFFFFF) - 1; break; }
      i = (i + 1) & mask;
    }
    if (id_cache.size() > id_cap) id_cache.clear();
    id_cache[text] = r;
    return r;
  }
  double Lower(int32_t i) const { return i < 0 ? h.unseen_lower : At<int16_t>(h.word_offset + 6 * i) / kScale; }
  double Ml(int32_t i) const { return i < 0 ? h.unseen_ml : At<int16_t>(h.word_offset + 6 * i + 2) / kScale; }
  double Backoff(int32_t i) const { return At<int16_t>(h.word_offset + 6 * i + 4) / kScale; }
  int64_t Find(uint64_t off, uint32_t b, uint32_t e, uint32_t v) const {
    uint32_t lo = b, hi = e;
    while (lo < hi) { uint32_t mid = (lo + hi) / 2; if (U24(off + 3ull * mid) < v) lo = mid + 1; else hi = mid; }
    return (lo < e && U24(off + 3ull * lo) == v) ? int64_t(lo) : -1;
  }
  int64_t Bigram(int32_t a, int32_t w) const {
    uint32_t b = At<uint32_t>(h.begin_offset + 4ull * a), e = At<uint32_t>(h.begin_offset + 4ull * a + 4);
    return Find(h.next_offset, b, e, uint32_t(w));
  }
  uint32_t TriBegin(int64_t b) const {
    return At<uint32_t>(h.tri_base_offset + 4ull * (uint64_t(b) >> h.shift)) + At<uint16_t>(h.tri_lo_offset + 2ull * b);
  }
  double Logp(int32_t a, int32_t w) const {
    if (a < 0) return Lower(w);
    if (w >= 0) { int64_t k = Bigram(a, w); if (k >= 0) return At<int16_t>(h.logp_offset + 2ull * k) / kScale; }
    return Backoff(a) + Lower(w);
  }
  double Logp3(int32_t a2, int32_t a, int32_t w) const {
    double bi = Logp(a, w);
    if (a2 < 0 || a < 0) return bi;
    int64_t b = Bigram(a2, a);
    if (b < 0) return bi;
    if (w >= 0) {
      int64_t k = Find(h.tri_next_offset, TriBegin(b), TriBegin(b + 1), uint32_t(w));
      if (k >= 0) return At<int16_t>(h.tri_logp_offset + 2ull * k) / kScale;
    }
    return At<int16_t>(h.backoff_offset + 2ull * b) / kScale + bi;
  }
  const std::vector<int32_t>& Decompose(const std::string& text) {
    auto it = dec_cache.find(text);
    if (it != dec_cache.end()) return it->second;
    std::vector<int32_t> parts;
    int32_t i = Id(text);
    if (i >= 0) parts.push_back(i);
    else {
      auto cps = Utf8Decode(text); size_t n = cps.size();
      std::vector<double> best(n + 1, -1e18); std::vector<size_t> from(n + 1, 0); std::vector<int32_t> wid(n + 1, -1);
      best[0] = 0;
      for (size_t a = 0; a < n; ++a) {
        if (best[a] <= -1e17) continue;
        for (size_t b = a + 1; b <= std::min(n, a + 8); ++b) {
          int32_t id = Id(Utf8Encode(cps, a, b));
          if (id < 0 && b > a + 1) continue;
          double s = best[a] + Ml(id);
          if (s > best[b]) { best[b] = s; from[b] = a; wid[b] = id; }
        }
      }
      for (size_t j = n; j > 0; j = from[j]) parts.push_back(wid[j]);
      std::reverse(parts.begin(), parts.end());
    }
    if (dec_cache.size() > dec_cap) dec_cache.clear();
    return dec_cache[text] = parts;
  }
};

// ------------------------------------------------------------------ adaptive model
// A count table kept small for a long-lived process: sorted (key, value) pairs plus a hash map of what was added
// since (learning); Freeze merges the additions into the sorted part. A hash map costs ~50 bytes an entry, the sorted
// pairs 8-12 (CyIME Android 2026-10-27: the user's 77k-trigram layer was 21 MB of heap).
template <class K, class V, class H = std::hash<K>> struct Packed {
  std::vector<std::pair<K, V>> base; std::unordered_map<K, V, H> add;
  V Get(K k) const {
    V v = 0;
    if (!add.empty()) { auto it = add.find(k); if (it != add.end()) v = it->second; }
    auto b = std::lower_bound(base.begin(), base.end(), k, [](const std::pair<K, V>& e, K x) { return e.first < x; });
    if (b != base.end() && b->first == k) v += b->second;
    return v;
  }
  V& Delta(K k) { return add[k]; }
  size_t Size() const { size_t n = base.size(); for (auto& kv : add) if (!InBase(kv.first)) ++n; return n; }
  bool InBase(K k) const {
    auto b = std::lower_bound(base.begin(), base.end(), k, [](const std::pair<K, V>& e, K x) { return e.first < x; });
    return b != base.end() && b->first == k;
  }
  void Freeze() {
    if (add.empty()) return;
    std::vector<std::pair<K, V>> extra(add.begin(), add.end());
    std::sort(extra.begin(), extra.end());
    std::vector<std::pair<K, V>> out; out.reserve(base.size() + extra.size());
    size_t i = 0, j = 0;
    while (i < base.size() || j < extra.size()) {
      if (j == extra.size() || (i < base.size() && base[i].first < extra[j].first)) out.push_back(base[i++]);
      else if (i == base.size() || extra[j].first < base[i].first) out.push_back(extra[j++]);
      else { out.push_back({base[i].first, V(base[i].second + extra[j].second)}); ++i; ++j; }
    }
    base.swap(out); base.shrink_to_fit(); add = std::unordered_map<K, V, H>();
  }
  template <class F> void Each(F f) const {  // every key with its total
    for (auto& [k, v] : base) { auto it = add.find(k); f(k, it == add.end() ? v : V(v + it->second)); }
    for (auto& [k, v] : add) if (!InBase(k)) f(k, v);
  }
  void Clear() { base.clear(); base.shrink_to_fit(); add = std::unordered_map<K, V, H>(); }
};

struct Adapt {
  static constexpr double D = 0.75;
  std::unordered_map<std::string, int32_t> ids; std::vector<std::string> names;
  struct H2 { size_t operator()(uint64_t x) const { return std::hash<uint64_t>()(x * 0x9E3779B97F4A7C15ULL); } };
  Packed<uint64_t, float, H2> c3, h3; Packed<uint64_t, int32_t, H2> t3, cont2;
  Packed<int32_t, float> h2; Packed<int32_t, int32_t> t2, cont1;
  double n1 = 1, vocab = 1; bool loaded = false;
  uint64_t version = 0;  // bumped by Add: sessions then drop their reused scores (Decoder::Sync)
  int32_t Intern(const std::string& s) {
    auto it = ids.find(s); if (it != ids.end()) return it->second;
    int32_t id = int32_t(names.size()); names.push_back(s); ids[s] = id; return id;
  }
  int32_t Lookup(const std::string& s) const { auto it = ids.find(s); return it == ids.end() ? -1 : it->second; }
  static uint64_t K2(int32_t a, int32_t b) { return (uint64_t(uint32_t(a)) << 32) | uint32_t(b); }
  static uint64_t K3(int32_t a, int32_t b, int32_t w) { return (uint64_t(uint32_t(a)) << 42) ^ (uint64_t(uint32_t(b)) << 21) ^ uint32_t(w); }
  void Freeze() { c3.Freeze(); h3.Freeze(); t3.Freeze(); cont2.Freeze(); h2.Freeze(); t2.Freeze(); cont1.Freeze(); }
  bool Load(const char* path) {
    std::ifstream in(path); if (!in) return false;
    std::string line;
    while (std::getline(in, line)) {
      auto f = Split(line, '\t'); if (f.size() < 4) continue;
      if (!loaded) { n1 = 0; vocab = 1; loaded = true; }
      AddTrigram(Intern(f[0]), Intern(f[1]), Intern(f[2]), std::stod(f[3]));
      if (c3.add.size() >= 65536) Freeze();  // keep the hash part small while reading too
    }
    Freeze();
    if (n1 == 0) n1 = 1;
    return loaded;
  }
  // One more occurrence of a trigram, keeping the lower orders as the trigram types imply (Kneser-Ney counts).
  void AddTrigram(int32_t a, int32_t b, int32_t w, double c) {
    const uint64_t k3 = K3(a, b, w), k2 = K2(a, b), kb = K2(b, w);
    const bool fresh = c3.Get(k3) == 0;
    c3.Delta(k3) += float(c); h3.Delta(k2) += float(c);
    if (!fresh) return;
    t3.Delta(k2) += 1;
    if (cont2.Get(kb) == 0) { t2.Delta(b) += 1; if (cont1.Get(w) == 0) vocab += 1; cont1.Delta(w) += 1; n1 += 1; }
    cont2.Delta(kb) += 1; h2.Delta(b) += 1;
  }
  // Learning (cylm_learn): a committed sentence's words, with <s> <s> before and </s> after, count c each. Past
  // `cap` trigram types every count is halved and those under 0.5 dropped (old, rare entries go first).
  void Add(const std::vector<std::string>& words, double c, size_t cap) {
    if (!loaded) { n1 = 0; vocab = 1; loaded = true; }
    int32_t a = Intern("<s>"), b = a;
    for (auto& w : words) { const int32_t x = Intern(w); AddTrigram(a, b, x, c); a = b; b = x; }
    AddTrigram(a, b, Intern("</s>"), c);
    if (c3.add.size() > 4096 + c3.base.size() / 8) Freeze();
    if (cap && c3.Size() > cap) Decay();
    ++version;
  }
  void Decay() {
    std::vector<std::pair<uint64_t, float>> old;
    c3.Each([&](uint64_t k, float c) { if (c / 2 >= 0.5f) old.push_back({k, c / 2}); });
    c3.Clear(); h3.Clear(); t3.Clear(); cont2.Clear(); h2.Clear(); t2.Clear(); cont1.Clear(); n1 = 0; vocab = 1;
    for (auto& [k, c] : old) AddTrigram(int32_t(k >> 42), int32_t((k >> 21) & 0x1FFFFF), int32_t(k & 0x1FFFFF), c);
    Freeze();
    if (n1 == 0) n1 = 1;
  }
  // cylm_forget: every trigram with one of `ids` or among `keys` is taken out; the lower orders are rebuilt from the
  // rest as Decay does. Returns the number taken out.
  size_t Forget(const std::unordered_set<int32_t>& ids, const std::unordered_set<uint64_t>& keys) {
    std::vector<std::pair<uint64_t, float>> kept;
    size_t removed = 0;
    c3.Each([&](uint64_t k, float c) {
      const int32_t a = int32_t(k >> 42), b = int32_t((k >> 21) & 0x1FFFFF), w = int32_t(k & 0x1FFFFF);
      if (keys.count(k) || ids.count(a) || ids.count(b) || ids.count(w)) { ++removed; return; }
      kept.push_back({k, c});
    });
    if (!removed) return 0;
    c3.Clear(); h3.Clear(); t3.Clear(); cont2.Clear(); h2.Clear(); t2.Clear(); cont1.Clear(); n1 = 0; vocab = 1;
    for (auto& [k, c] : kept) AddTrigram(int32_t(k >> 42), int32_t((k >> 21) & 0x1FFFFF), int32_t(k & 0x1FFFFF), c);
    Freeze();
    if (n1 == 0) n1 = 1;
    ++version;
    return removed;
  }
  size_t Trigrams() const { return c3.Size(); }
  bool Save(const char* path) const {  // the counts file Load reads (cylm_adapt)
    std::ofstream out(path, std::ios::binary); if (!out) return false;
    c3.Each([&](uint64_t k, float c) {
      const int32_t a = int32_t(k >> 42), b = int32_t((k >> 21) & 0x1FFFFF), w = int32_t(k & 0x1FFFFF);
      out << names[a] << '\t' << names[b] << '\t' << names[w] << '\t' << c << '\n';
    });
    return bool(out);
  }
  double P(int32_t a, int32_t b, int32_t w) const {
    double p1 = ((w >= 0 ? double(cont1.Get(w)) : 0.0) + 0.5) / (n1 + 0.5 * vocab);
    double p2 = p1;
    if (b >= 0) { double hh = h2.Get(b); if (hh > 0) p2 = (std::max(double(cont2.Get(K2(b, w))) - D, 0.0) + D * t2.Get(b) * p1) / hh; }
    if (a < 0 || b < 0) return p2;
    double hh3 = h3.Get(K2(a, b));
    if (hh3 <= 0) return p2;
    return (std::max(double(c3.Get(K3(a, b, w))) - D, 0.0) + D * t3.Get(K2(a, b)) * p2) / hh3;
  }
};

// ------------------------------------------------------------------ lexicon and keys
struct Lexicon {
  std::vector<std::string> syls; std::unordered_map<std::string, char16_t> code; std::vector<char> first;
  std::unordered_map<std::string, int64_t> weight;
  std::unordered_map<u16, std::vector<std::pair<int64_t, std::string>>> by_key;
  std::unordered_map<std::string, std::vector<u16>> by_init;
  std::unordered_set<std::string> init_prefix;
  double total = 1;
  // With a compiled lexicon the maps above hold only the user's own words (cylm_add_word), read on top of it.
  size_t overlay = 0; uint64_t version = 0;
  bool AddWord(const std::string& w, const std::vector<std::string>& syl, int64_t wt) {
    u16 key;
    for (auto& x : syl) { auto it = code.find(x); if (it == code.end()) return false; key += it->second; }
    if (key.empty() || key.size() > size_t(kMaxWord)) return false;
    auto& v = by_key[key];
    bool seen = false;
    for (auto& e : v) if (e.second == w) { e.first = std::max(e.first, wt); seen = true; }
    if (!seen) v.emplace_back(wt, w);
    std::sort(v.begin(), v.end(), [](auto& x, auto& y) { return x.first > y.first; });
    auto& cur = weight[w]; cur = std::max(cur, wt);
    std::string ini; for (char16_t c : key) ini += first[c - 1];
    if (key.size() <= size_t(kMaxAbbrev)) { auto& ks = by_init[ini]; if (std::find(ks.begin(), ks.end(), key) == ks.end()) ks.push_back(key); }
    for (size_t i = 1; i <= ini.size(); ++i) init_prefix.insert(ini.substr(0, i));
    ++overlay; ++version;
    return true;
  }
  bool Load(const char* path) {
    std::ifstream in(path); if (!in) return false;
    std::string line; std::vector<std::tuple<std::string, std::vector<std::string>, int64_t>> rows;
    std::map<std::string, int> sylset;
    while (std::getline(in, line)) {
      auto f = Split(line, '\t'); if (f.size() < 3) continue;
      auto s = Split(f[1], ' ');
      for (auto& x : s) sylset[x] = 0;
      rows.emplace_back(f[0], s, std::stoll(f[2]));
    }
    for (auto& [s, i] : sylset) { i = int(syls.size()); syls.push_back(s); }
    first.resize(syls.size());
    for (size_t i = 0; i < syls.size(); ++i) { code[syls[i]] = char16_t(i + 1); first[i] = syls[i][0]; }
    for (auto& [w, s, wt] : rows) {
      u16 key; for (auto& x : s) key += code[x];
      by_key[key].emplace_back(wt, w);
      auto& cur = weight[w]; if (wt > cur || cur == 0) cur = std::max(cur, wt);
    }
    std::unordered_map<u16, int64_t> best;
    for (auto& [k, v] : by_key) {
      std::sort(v.begin(), v.end(), [](auto& x, auto& y) { return x.first != y.first ? x.first > y.first : x.second > y.second; });
      best[k] = v[0].first;
      if (k.size() > size_t(kMaxWord)) continue;
      std::string ini; for (char16_t c : k) ini += first[c - 1];
      if (k.size() <= size_t(kMaxAbbrev)) by_init[ini].push_back(k);
      for (size_t i = 1; i <= ini.size(); ++i) init_prefix.insert(ini.substr(0, i));
    }
    for (auto& [i, v] : by_init) std::stable_sort(v.begin(), v.end(), [&](const u16& a, const u16& b) { return best[a] > best[b]; });
    total = 0; for (auto& [w, wt] : weight) total += double(wt); if (total <= 0) total = 1;
    return true;
  }
  const std::string& Syl(char16_t c) const { return syls[c - 1]; }

  // ---------------------------------------------------------------- compiled form (mmap, no parsing)
  // Sorted arrays searched by binary search, so loading is a file mapping. Layout (little endian):
  //   header | syllables (8 bytes each) | words: offsets u32[nw+1], pool, weights u32[nw] (sorted by bytes)
  //   | keys: offsets u32[nk+1], pool u16 (sorted), entry offsets u32[nk+1], entries {u32 weight, u32 word}
  //   | initials: offsets u32[ni+1], pool (sorted), key-list offsets u32[ni+1], key indices u32
  //   | prefixes: offsets u32[np+1], pool (sorted)
  struct BinHeader { char magic[8]; uint32_t nsyl, nw, nk, ni, np, pad; double total;
                     uint64_t syl, woff, wpool, wwt, koff, kpool, eoff, ent, ioff, ipool, loff, list, poff, ppool; };
  struct Ent { uint32_t wt, word; };
  bool bin = false; const uint8_t* B = nullptr; BinHeader H{};
  template <class T> const T* At(uint64_t off) const { return reinterpret_cast<const T*>(B + off); }
  std::string_view Word(uint32_t i) const { auto o = At<uint32_t>(H.woff); return {reinterpret_cast<const char*>(B + H.wpool + o[i]), o[i + 1] - o[i]}; }
  std::u16string_view Key(uint32_t i) const { auto o = At<uint32_t>(H.koff); return {At<char16_t>(H.kpool) + o[i], o[i + 1] - o[i]}; }
  std::string_view Ini(uint32_t i) const { auto o = At<uint32_t>(H.ioff); return {reinterpret_cast<const char*>(B + H.ipool + o[i]), o[i + 1] - o[i]}; }
  std::string_view Pre(uint32_t i) const { auto o = At<uint32_t>(H.poff); return {reinterpret_cast<const char*>(B + H.ppool + o[i]), o[i + 1] - o[i]}; }
  template <class F, class V> static int64_t Find(uint32_t n, F get, const V& v) {
    uint32_t lo = 0, hi = n;
    while (lo < hi) { uint32_t mid = (lo + hi) / 2; if (get(mid) < v) lo = mid + 1; else hi = mid; }
    return lo < n && get(lo) == v ? int64_t(lo) : -1;
  }
  size_t WordCount() const { return bin ? H.nw : weight.size(); }
  int64_t WeightOf(const std::string& w) const {
    if (!bin) { auto it = weight.find(w); return it == weight.end() ? -1 : it->second; }
    auto k = Find(H.nw, [&](uint32_t i) { return Word(i); }, std::string_view(w));
    int64_t r = k < 0 ? -1 : int64_t(At<uint32_t>(H.wwt)[k]);
    if (overlay) { auto it = weight.find(w); if (it != weight.end()) r = std::max(r, it->second); }
    return r;
  }
  std::vector<std::pair<int64_t, std::string>> KeyWords(const u16& key) const {
    if (!bin) { auto it = by_key.find(key); return it == by_key.end() ? std::vector<std::pair<int64_t, std::string>>{} : it->second; }
    std::vector<std::pair<int64_t, std::string>> out;
    auto k = Find(H.nk, [&](uint32_t i) { return Key(i); }, std::u16string_view(key));
    if (k < 0) return out;
    auto eo = At<uint32_t>(H.eoff); auto e = At<Ent>(H.ent);
    for (uint32_t j = eo[k]; j < eo[k + 1]; ++j) out.emplace_back(int64_t(e[j].wt), std::string(Word(e[j].word)));
    return out;
  }
  std::vector<std::pair<int64_t, std::string>> KeyWordsAll(const u16& key) const {  // compiled lexicon + the user's words
    auto out = KeyWords(key);
    if (bin && overlay) { auto it = by_key.find(key); if (it != by_key.end()) out.insert(out.end(), it->second.begin(), it->second.end()); }
    return out;
  }
  std::vector<u16> InitKeys(const std::string& ini) const {
    if (!bin) { auto it = by_init.find(ini); return it == by_init.end() ? std::vector<u16>{} : it->second; }
    std::vector<u16> out;
    auto k = Find(H.ni, [&](uint32_t i) { return Ini(i); }, std::string_view(ini));
    if (k < 0) return out;
    auto lo = At<uint32_t>(H.loff); auto l = At<uint32_t>(H.list);
    for (uint32_t j = lo[k]; j < lo[k + 1]; ++j) out.emplace_back(Key(l[j]));
    return out;
  }
  std::vector<u16> InitKeysAll(const std::string& ini) const {
    auto out = InitKeys(ini);
    if (bin && overlay) {
      auto it = by_init.find(ini);
      if (it != by_init.end()) for (auto& k : it->second) if (std::find(out.begin(), out.end(), k) == out.end()) out.push_back(k);
    }
    return out;
  }
  bool HasPrefix(const std::string& p) const {
    if (!bin) return init_prefix.count(p) > 0;
    return Find(H.np, [&](uint32_t i) { return Pre(i); }, std::string_view(p)) >= 0 || (overlay && init_prefix.count(p) > 0);
  }
  bool Compile(const char* out_path) const {
    std::vector<std::string> words; for (auto& [w, wt] : weight) words.push_back(w);
    std::sort(words.begin(), words.end());
    std::unordered_map<std::string, uint32_t> wid; for (uint32_t i = 0; i < words.size(); ++i) wid[words[i]] = i;
    std::vector<u16> keys; for (auto& [k, v] : by_key) keys.push_back(k);
    std::sort(keys.begin(), keys.end());
    std::unordered_map<u16, uint32_t> kid; for (uint32_t i = 0; i < keys.size(); ++i) kid[keys[i]] = i;
    std::vector<std::string> inis; for (auto& [i, v] : by_init) inis.push_back(i);
    std::sort(inis.begin(), inis.end());
    std::vector<std::string> pres(init_prefix.begin(), init_prefix.end()); std::sort(pres.begin(), pres.end());
    std::string data; BinHeader h{}; std::memcpy(h.magic, "CYLX0002", 8);
    h.nsyl = uint32_t(syls.size()); h.nw = uint32_t(words.size()); h.nk = uint32_t(keys.size()); h.ni = uint32_t(inis.size()); h.np = uint32_t(pres.size()); h.total = total;
    data.resize(sizeof h);
    auto align = [&]() { while (data.size() % 8) data.push_back('\0'); };
    auto put = [&](const void* p, size_t n) { data.append(static_cast<const char*>(p), n); };
    auto offsets = [&](auto& items, auto size_of) { std::vector<uint32_t> o{0}; for (auto& x : items) o.push_back(o.back() + uint32_t(size_of(x))); return o; };
    align(); h.syl = data.size(); for (auto& s : syls) { char b[8] = {0}; std::memcpy(b, s.data(), std::min<size_t>(7, s.size())); put(b, 8); }
    align(); h.woff = data.size(); { auto o = offsets(words, [](auto& w) { return w.size(); }); put(o.data(), o.size() * 4); }
    h.wpool = data.size(); for (auto& w : words) put(w.data(), w.size());
    align(); h.wwt = data.size(); for (auto& w : words) { uint32_t x = uint32_t(std::min<int64_t>(weight.at(w), 0xFFFFFFFF)); put(&x, 4); }
    align(); h.koff = data.size(); { auto o = offsets(keys, [](auto& k) { return k.size(); }); put(o.data(), o.size() * 4); }
    align(); h.kpool = data.size(); for (auto& k : keys) put(k.data(), k.size() * 2);
    align(); h.eoff = data.size(); { std::vector<uint32_t> o{0}; for (auto& k : keys) o.push_back(o.back() + uint32_t(by_key.at(k).size())); put(o.data(), o.size() * 4); }
    align(); h.ent = data.size(); for (auto& k : keys) for (auto& [wt, w] : by_key.at(k)) { Ent e{uint32_t(std::min<int64_t>(std::max<int64_t>(wt, 0), 0xFFFFFFFF)), wid.at(w)}; put(&e, sizeof e); }
    align(); h.ioff = data.size(); { auto o = offsets(inis, [](auto& s) { return s.size(); }); put(o.data(), o.size() * 4); }
    h.ipool = data.size(); for (auto& s : inis) put(s.data(), s.size());
    align(); h.loff = data.size(); { std::vector<uint32_t> o{0}; for (auto& s : inis) o.push_back(o.back() + uint32_t(by_init.at(s).size())); put(o.data(), o.size() * 4); }
    align(); h.list = data.size(); for (auto& s : inis) for (auto& k : by_init.at(s)) { uint32_t x = kid.at(k); put(&x, 4); }
    align(); h.poff = data.size(); { auto o = offsets(pres, [](auto& s) { return s.size(); }); put(o.data(), o.size() * 4); }
    h.ppool = data.size(); for (auto& s : pres) put(s.data(), s.size());
    std::memcpy(data.data(), &h, sizeof h);
    std::ofstream f(out_path, std::ios::binary); f.write(data.data(), std::streamsize(data.size()));
    return bool(f);
  }
  bool LoadBin(const uint8_t* mapped) {
    std::memcpy(&H, mapped, sizeof H);
    if (std::memcmp(H.magic, "CYLX0002", 8) != 0) return false;
    B = mapped; bin = true; total = H.total;
    for (uint32_t i = 0; i < H.nsyl; ++i) syls.emplace_back(reinterpret_cast<const char*>(B + H.syl + 8 * i));
    first.resize(syls.size());
    for (size_t i = 0; i < syls.size(); ++i) { code[syls[i]] = char16_t(i + 1); first[i] = syls[i][0]; }
    return true;
  }
};

// kind f / a / s(eparator); typo: a full syllable whose first letter is the previous key, typed once for two
// ("chengong" for cheng'gong: an n or ng+g syllable boundary with the shared letter typed once)
// vowel (parameter vowel, off by default): 1 a vowel letter typed as the initial of a vowel syllable (a for ai / an /
// ang / ao, e for ei / en / eng / er, o for ou), 2 e typed for 嗯 (syllable n or ng)
// keys: keys an initial-only token covers (2 for a pinyin zh / ch / sh initial; counted by abbrev_keys)
struct Token { char kind; char16_t full; char letter; std::string prefix; int typo = 0; int vowel = 0; int keys = 1; };  // typo: 1 doubled letter, 2 neighbour key

struct Params {
  double vowel_pen = -1.0, typo_first = 0.0, neighbor_pen = -2.0, adapt_add = 0.0, typo = 0.0, mu_word = 0.0, eos_word = -1.0, adapt_word = -1.0, rr_gamma = 0.8, rr_short = 0.1, abbrev_full = 0.0, abbrev_syl = -8.0, eos2 = -1.0, mix = 0.0, mu = 0.02, abbrev = -0.7, word_pen = 0.0, eos = 1.0, adapt_lambda = 0.0, select_lm = 1.0;
  int skip_inner = 0, abbrev_keys = 0, vowel = 0, neighbor = 0, prune = 1, rr_cache = 2048, t9_beam = 0, t9_per_span = 0, rr_threads = 4, incremental = 0, interleave = 1, per_span = 24, per_span_abbrev = 48, per_span1 = 80, scan = 4000, beam = 32, nbest = 10;
  // cache caps: hit_cap (span word lists), word_cap (word priors and weights), id_cap / dec_cap (model word ids and
  // splits, per model); the library defaults keep a session to a few tens of MB
  size_t hit_cap = kHitCacheMax, word_cap = kWordCacheMax, id_cap = kIdCacheMax, dec_cap = kDecCacheMax;
  size_t adapt_cap = 100000;  // trigram types the user's layer keeps while learning (cylm_learn); then old counts decay
  bool Set(const std::string& k, const std::string& v) {
    if (k == "hit_cap") { hit_cap = std::stoull(v); return true; }
    if (k == "adapt_cap") { adapt_cap = std::stoull(v); return true; }
    if (k == "word_cap") { word_cap = std::stoull(v); return true; }
    if (k == "id_cap") { id_cap = std::stoull(v); return true; }
    if (k == "dec_cap") { dec_cap = std::stoull(v); return true; }
    if (k == "mu_word") mu_word = std::stod(v); else if (k == "eos_word") eos_word = std::stod(v); else if (k == "adapt_word") adapt_word = std::stod(v);
    else if (k == "t9_beam") t9_beam = std::stoi(v); else if (k == "t9_per_span") t9_per_span = std::stoi(v);
    else if (k == "rr_gamma") rr_gamma = std::stod(v); else if (k == "rr_short") rr_short = std::stod(v);
    else if (k == "rr_threads") rr_threads = std::stoi(v);
    else if (k == "rr_cache") rr_cache = std::stoi(v);
    else if (k == "prune") prune = std::stoi(v);
    else if (k == "typo") typo = std::stod(v);
    else if (k == "neighbor") neighbor = std::stoi(v);
    else if (k == "neighbor_pen") neighbor_pen = std::stod(v);  // kept in a neighbour-key reading's own score
    else if (k == "vowel") vowel = std::stoi(v);  // a / e / o as initials of vowel syllables (and e for 嗯); 2: not in full pinyin of any length
    else if (k == "vowel_pen") vowel_pen = std::stod(v);  // per such initial, on top of abbrev
    else if (k == "abbrev_keys") abbrev_keys = std::stoi(v);  // 1: abbrev per key (zh / ch / sh initials count 2)
    else if (k == "typo_first") typo_first = std::stod(v);  // margin (nats) for a typo reading to go first; 0 = never
    else if (k == "skip_inner") skip_inner = std::stoi(v);  // 1: no initial-only readings before the syllable being typed in spelled input
    else if (k == "adapt_add") adapt_add = std::stod(v);
    else if (k == "incremental") incremental = std::stoi(v); else if (k == "abbrev_full") abbrev_full = std::stod(v); else if (k == "abbrev_syl") abbrev_syl = std::stod(v); else if (k == "eos2") eos2 = std::stod(v); else if (k == "interleave") interleave = std::stoi(v);
    else if (k == "mix") mix = std::stod(v); else if (k == "mu") mu = std::stod(v); else if (k == "abbrev") abbrev = std::stod(v);
    else if (k == "word_pen") word_pen = std::stod(v); else if (k == "eos") eos = std::stod(v);
    else if (k == "adapt_lambda") adapt_lambda = std::stod(v); else if (k == "select_lm") select_lm = std::stod(v);
    else if (k == "per_span") per_span = std::stoi(v); else if (k == "per_span_abbrev") per_span_abbrev = std::stoi(v);
    else if (k == "per_span1") per_span1 = std::stoi(v); else if (k == "scan") scan = std::stoi(v);
    else if (k == "beam") beam = std::stoi(v); else if (k == "nbest") nbest = std::stoi(v);
    else return false;
    return true;
  }
};


// ------------------------------------------------------------------ reranker (character model, onnxruntime)
// Built with -DCYLM_ORT. The ONNX graph (tools/cylm/nn/export_onnx.py) maps ids [batch, T] to the log-probability of
// each next token [batch, T-1]; a candidate's score is the sum over its characters (and </s>), given <s> + context.
#ifdef CYLM_ORT
#include "onnxruntime_c_api.h"
// The reranker's per-session cache (incremental graphs, nn/export_kv.py): a trie of the token prefixes scored so far.
// Node 0 is <s>; each node keeps log P(its token | its path) and, once it has been a run input, its keys and values.
struct KvCache {
  struct Node { int32_t parent; int64_t tok; int32_t depth; float logp; std::vector<float> k, v;
                std::unordered_map<int64_t, int32_t> kids; };
  std::vector<Node> nodes;
  size_t limit = 2048;  // nodes; beyond it only the current candidates' paths are kept
  int32_t Child(int32_t at, int64_t tok, bool& made) {
    auto it = nodes[at].kids.find(tok);
    made = it == nodes[at].kids.end();
    if (!made) return it->second;
    int32_t id = int32_t(nodes.size());
    nodes.push_back(Node{at, tok, nodes[at].depth + 1, 0.f, {}, {}, {}});
    nodes[at].kids.emplace(tok, id);
    return id;
  }
  // Keep only the nodes on `paths` (renumbered) once the trie has grown past the limit.
  void Compact(std::vector<std::vector<int32_t>>& paths) {
    if (nodes.size() <= limit) return;
    std::vector<int32_t> map(nodes.size(), -1); std::vector<Node> kept;
    for (auto& path : paths) for (int32_t n : path) if (map[n] < 0) { map[n] = int32_t(kept.size()); kept.push_back(std::move(nodes[n])); }
    for (auto& n : kept) {
      n.parent = n.parent >= 0 ? map[n.parent] : -1;
      std::unordered_map<int64_t, int32_t> kids;
      for (auto& [t, c] : n.kids) if (map[c] >= 0) kids.emplace(t, map[c]);
      n.kids = std::move(kids);
    }
    nodes = std::move(kept);
    for (auto& path : paths) for (auto& n : path) n = map[n];
  }
};

struct Reranker {
  const OrtApi* api = nullptr; OrtEnv* env = nullptr; OrtSession* sess = nullptr; OrtMemoryInfo* mem = nullptr;
  std::unordered_map<uint32_t, int64_t> vocab;
  bool Load(const char* model, const char* vocab_path, int threads, bool lean = false) {
    api = OrtGetApiBase()->GetApi(17);
    if (!api) return false;
    std::ifstream in(vocab_path, std::ios::binary); std::string line; int64_t id = 0;
    while (std::getline(in, line)) { auto cps = Utf8Decode(line); if (cps.size() == 1) vocab[cps[0]] = id; ++id; }
    if (vocab.empty()) return false;
    OrtSessionOptions* so = nullptr;
    if (api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "cylm", &env) || api->CreateSessionOptions(&so)) return false;
    api->SetIntraOpNumThreads(so, threads); api->SetSessionGraphOptimizationLevel(so, ORT_ENABLE_ALL);
    if (lean) { api->DisableCpuMemArena(so); api->DisableMemPattern(so); api->SetInterOpNumThreads(so, 1); }  // the gate: small, rare calls
#ifdef _WIN32
    std::wstring w(model, model + std::strlen(model));
    OrtStatus* st = api->CreateSession(env, w.c_str(), so, &sess);
#else
    OrtStatus* st = api->CreateSession(env, model, so, &sess);
#endif
    api->ReleaseSessionOptions(so);
    if (st) { std::fprintf(stderr, "reranker: %s\n", api->GetErrorMessage(st)); api->ReleaseStatus(st); return false; }
    size_t inputs = 0; api->SessionGetInputCount(sess, &inputs);
    if (inputs == 6) {  // incremental graph: past_k is [layers, heads, P, d/heads]
      OrtTypeInfo* ti = nullptr; const OrtTensorTypeAndShapeInfo* tsi = nullptr; int64_t dims[4] = {0, 0, 0, 0};
      if (api->SessionGetInputTypeInfo(sess, 3, &ti) || api->CastTypeInfoToTensorInfo(ti, &tsi) || api->GetDimensions(tsi, dims, 4)) return false;
      api->ReleaseTypeInfo(ti);
      kv = true; layers = dims[0]; heads = dims[1]; dh = dims[3];
    }
    return !api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &mem);
  }
  bool kv = false; int64_t layers = 0, heads = 0, dh = 0;
  OrtValue* Tensor(void* data, size_t bytes, const int64_t* shape, size_t rank, ONNXTensorElementDataType type) const {
    OrtValue* v = nullptr;
    if (api->CreateTensorWithDataAsOrtValue(mem, data, bytes, shape, rank, type, &v)) return nullptr;
    return v;
  }
  // Incremental scoring: only tokens not yet in the cache run, all candidates in one call; the sums equal Score's.
  std::vector<double> ScoreKV(KvCache& cache, const std::string& context, const std::vector<std::string>& cands, bool eos) const {
    std::vector<double> out(cands.size(), 0.0);
    if (!sess || cands.empty()) return out;
    auto ctx = Utf8Decode(context); size_t e = ctx.size(), b = e;
    while (b > 0 && IsHan(ctx[b - 1])) --b;
    if (e - b > 24) b = e - 24;
    std::vector<int64_t> head{1}; for (size_t i = b; i < e; ++i) head.push_back(Id(ctx[i]));
    if (cache.nodes.empty()) cache.nodes.push_back(KvCache::Node{-1, 1, 0, 0.f, {}, {}, {}});
    std::vector<std::vector<int32_t>> paths;
    std::vector<int32_t> pending;  // nodes whose log-probability is unknown, parents before children
    for (auto& c : cands) {
      auto s = head; for (uint32_t cp : Utf8Decode(c)) s.push_back(Id(cp));
      if (eos) s.push_back(2);
      if (s.size() > 129) s.resize(129);
      std::vector<int32_t> path{0};
      for (size_t t = 1; t < s.size(); ++t) {
        bool made = false;
        int32_t n = cache.Child(path.back(), s[t], made);
        if (made) pending.push_back(n);
        path.push_back(n);
      }
      paths.push_back(std::move(path));
    }
    if (!pending.empty()) {
      const size_t T = pending.size(), slot = size_t(layers * heads * dh);
      // Run token r: input = the parent of pending[r], target = pending[r]. A node without keys and values takes
      // them from its first run token (`rep`); nodes that have them are past slots (slot 0 is a masked dummy).
      std::unordered_map<int32_t, int32_t> rep, past_of;
      std::vector<int32_t> past{-1};
      for (size_t r = 0; r < T; ++r) {
        int32_t q = cache.nodes[pending[r]].parent;
        if (cache.nodes[q].k.empty() && !rep.count(q)) rep[q] = int32_t(r);
      }
      std::vector<int64_t> ids(T), pos(T), tgt(T);
      for (size_t r = 0; r < T; ++r) {
        const auto& n = cache.nodes[pending[r]]; const auto& q = cache.nodes[n.parent];
        ids[r] = q.tok; pos[r] = q.depth; tgt[r] = n.tok;
        for (int32_t a = q.parent; a >= 0; a = cache.nodes[a].parent)
          if (!cache.nodes[a].k.empty() && !past_of.count(a)) { past_of[a] = int32_t(past.size()); past.push_back(a); }
      }
      const size_t P = past.size(), W = P + T;
      std::vector<float> mask(T * W, -INFINITY);
      for (size_t r = 0; r < T; ++r) {
        float* row = &mask[r * W];
        int32_t q = cache.nodes[pending[r]].parent;
        row[P + r] = 0.f;  // itself (its input is q)
        for (int32_t a = cache.nodes[q].parent; a >= 0; a = cache.nodes[a].parent)
          row[!cache.nodes[a].k.empty() ? size_t(past_of[a]) : P + size_t(rep[a])] = 0.f;
      }
      std::vector<float> pk(slot * P, 0.f), pv(slot * P, 0.f);
      for (size_t si = 1; si < P; ++si) {
        const auto& n = cache.nodes[past[si]];
        for (int64_t lh = 0; lh < layers * heads; ++lh) {
          std::memcpy(&pk[(lh * P + si) * dh], &n.k[lh * dh], dh * sizeof(float));
          std::memcpy(&pv[(lh * P + si) * dh], &n.v[lh * dh], dh * sizeof(float));
        }
      }
      int64_t s1[1] = {int64_t(T)}, sp[4] = {layers, heads, int64_t(P), dh}, sm[2] = {int64_t(T), int64_t(W)};
      OrtValue* in[6] = {Tensor(ids.data(), T * 8, s1, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64),
                         Tensor(pos.data(), T * 8, s1, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64),
                         Tensor(tgt.data(), T * 8, s1, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64),
                         Tensor(pk.data(), pk.size() * 4, sp, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT),
                         Tensor(pv.data(), pv.size() * 4, sp, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT),
                         Tensor(mask.data(), mask.size() * 4, sm, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)};
      OrtValue* res[3] = {nullptr, nullptr, nullptr};
      const char* inames[] = {"ids", "pos", "tgt", "past_k", "past_v", "mask"}; const char* onames[] = {"logp", "new_k", "new_v"};
      bool ok = true; for (auto* v : in) ok = ok && v;
      OrtStatus* st = ok ? api->Run(sess, nullptr, inames, in, 6, onames, 3, res) : nullptr;
      if (ok && !st) {
        float *lp = nullptr, *nk = nullptr, *nv = nullptr;
        api->GetTensorMutableData(res[0], reinterpret_cast<void**>(&lp));
        api->GetTensorMutableData(res[1], reinterpret_cast<void**>(&nk));
        api->GetTensorMutableData(res[2], reinterpret_cast<void**>(&nv));
        for (size_t r = 0; r < T; ++r) cache.nodes[pending[r]].logp = lp[r];
        for (auto& [q, r] : rep) {
          auto& n = cache.nodes[q]; n.k.resize(slot); n.v.resize(slot);
          for (int64_t lh = 0; lh < layers * heads; ++lh) {
            std::memcpy(&n.k[lh * dh], &nk[(lh * int64_t(T) + r) * dh], dh * sizeof(float));
            std::memcpy(&n.v[lh * dh], &nv[(lh * int64_t(T) + r) * dh], dh * sizeof(float));
          }
        }
      } else if (st) { std::fprintf(stderr, "reranker: %s\n", api->GetErrorMessage(st)); api->ReleaseStatus(st); }
      for (auto* v : res) if (v) api->ReleaseValue(v);
      for (auto* v : in) if (v) api->ReleaseValue(v);
      if (!ok || st) { cache.nodes.clear(); return out; }  // failed: forget this call's nodes, score nothing
    }
    for (size_t r = 0; r < cands.size(); ++r) {
      double sum = 0;
      for (int32_t n : paths[r]) if (cache.nodes[n].depth >= int32_t(head.size())) sum += cache.nodes[n].logp;
      out[r] = sum;
    }
    cache.Compact(paths);
    return out;
  }
  int64_t Id(uint32_t c) const { auto it = vocab.find(c); return it == vocab.end() ? 3 : it->second; }  // 3 = <unk>
  std::vector<double> Score(const std::string& context, const std::vector<std::string>& cands, bool eos) const {
    std::vector<double> out(cands.size(), 0.0);
    if (!sess || cands.empty()) return out;
    auto ctx = Utf8Decode(context); size_t e = ctx.size(), b = e;
    while (b > 0 && IsHan(ctx[b - 1])) --b;
    if (e - b > 24) b = e - 24;
    std::vector<int64_t> head{1}; for (size_t i = b; i < e; ++i) head.push_back(Id(ctx[i]));
    std::vector<std::vector<int64_t>> seqs; size_t L = 0;
    for (auto& c : cands) {
      auto s = head; for (uint32_t cp : Utf8Decode(c)) s.push_back(Id(cp));
      if (eos) s.push_back(2);
      if (s.size() > 129) s.resize(129);
      L = std::max(L, s.size()); seqs.push_back(std::move(s));
    }
    std::vector<int64_t> ids(seqs.size() * L, 0);
    for (size_t r = 0; r < seqs.size(); ++r) std::copy(seqs[r].begin(), seqs[r].end(), ids.begin() + r * L);
    int64_t shape[2] = {int64_t(seqs.size()), int64_t(L)};
    OrtValue* in = nullptr; OrtValue* res = nullptr;
    if (api->CreateTensorWithDataAsOrtValue(mem, ids.data(), ids.size() * sizeof(int64_t), shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &in)) return out;
    const char* inames[] = {"ids"}; const char* onames[] = {"logp"};
    OrtStatus* st = api->Run(sess, nullptr, inames, &in, 1, onames, 1, &res);
    if (!st) {
      float* lp = nullptr; api->GetTensorMutableData(res, reinterpret_cast<void**>(&lp));
      for (size_t r = 0; r < seqs.size(); ++r) {
        double sum = 0; size_t a = head.size();
        for (size_t t = a - 1; t + 1 < seqs[r].size(); ++t) sum += lp[r * (L - 1) + t];
        out[r] = sum;
      }
      api->ReleaseValue(res);
    } else api->ReleaseStatus(st);
    api->ReleaseValue(in);
    return out;
  }
};
#else
struct KvCache { size_t limit = 2048; };
struct Reranker {
  bool kv = false;
  std::vector<double> ScoreKV(KvCache&, const std::string&, const std::vector<std::string>& c, bool) const { return std::vector<double>(c.size(), 0.0); }
  bool Load(const char*, const char*, int, bool = false) { return false; }
  std::vector<double> Score(const std::string&, const std::vector<std::string>& c, bool) const { return std::vector<double>(c.size(), 0.0); }
};
#endif

static const char* kT9[] = {"", "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
static char T9Of(char c) { for (int d = 2; d <= 9; ++d) if (std::strchr(kT9[d], c)) return char('0' + d); return '?'; }
static const std::string kInitials = "bpmfdtnlgkhjqxrzcsyw";
static const char kQwerty[] = "qwertyuiop asdfghjkl zxcvbnm";  // rows separated by a space: no neighbour across rows

struct State {
  int32_t a, b, wa, wb, a2 = -1, b2 = -1, ty = 0;  // ty: typo syllables on the path
  bool operator==(const State& o) const { return a == o.a && b == o.b && wa == o.wa && wb == o.wb && a2 == o.a2 && b2 == o.b2 && ty == o.ty; }
};
struct StateHash { size_t operator()(const State& s) const {
  uint64_t x = uint64_t(uint32_t(s.a)) * 0x9E3779B97F4A7C15ULL ^ uint64_t(uint32_t(s.b)) * 0xC2B2AE3D27D4EB4FULL ^
               uint64_t(uint32_t(s.wa)) * 0x165667B19E3779F9ULL ^ uint64_t(uint32_t(s.wb)) ^
               uint64_t(uint32_t(s.a2)) * 0x27D4EB2F165667C5ULL ^ uint64_t(uint32_t(s.b2)) * 0x94D049BB133111EBULL ^ uint64_t(uint32_t(s.ty)) * 0xD6E8FEB86659FD93ULL; return size_t(x ^ (x >> 29)); } };

struct Edge { int j; std::string word; int abbrevs; u16 key; int typos = 0; int nears = 0; int vowels = 0; };

struct Decoder {
  Lexicon& lex; C3& base; Adapt& adapt; Params p; C3* base2 = nullptr; const Reranker* rr = nullptr; const Reranker* rr_t9 = nullptr;  // optional nine-key reranker
  // The user's own preferences: "P above D" pairs (their typing uses P and not its homophone D, or they reported it).
  // After ranking, a preferred P listed below its D moves to just above D; nothing else moves.
  const std::unordered_map<std::string, std::vector<std::string>>* prefer = nullptr;  // D -> the P that beat it
  // Characters the chat corpus never contains (LCCC was filtered by a word list when published): for words with
  // them the chat model's estimate is an artefact, so the mixture uses the main model alone for those words.
  const std::unordered_set<uint32_t>* holes = nullptr;
  bool HasHole(const std::string& w) const {
    if (!holes) return false;
    for (uint32_t c : Utf8Decode(w)) if (holes->count(c)) return true;
    return false;
  }
  std::unordered_map<std::string, char16_t> spell; size_t max_syl = 0;
  std::unordered_map<std::string, std::vector<char16_t>> by_t9; std::unordered_map<std::string, std::vector<std::string>> t9_prefix;
  std::unordered_map<std::string, double> prior_cache, lnw_cache;
  std::unordered_map<std::string, std::vector<std::tuple<double, std::string, u16>>> hit_cache;
  // Called between decodes: every cache a long-lived session fills is dropped once past its cap.
  void TrimCaches() {
    if (hit_cache.size() > p.hit_cap) hit_cache.clear();
    if (prior_cache.size() > p.word_cap) prior_cache.clear();
    if (lnw_cache.size() > p.word_cap) lnw_cache.clear();
  }
  int32_t BOSW, EOSW;
  // Learning changed the user's layer or words since the last decode: reused scores and word lists are stale.
  uint64_t seen_lex_ = 0, seen_adapt_ = 0;
  void Sync() {
    if (lex.version != seen_lex_) { seen_lex_ = lex.version; hit_cache.clear(); prior_cache.clear(); lnw_cache.clear(); inc_beams_.clear(); }
    if (adapt.version != seen_adapt_) { seen_adapt_ = adapt.version; inc_beams_.clear(); BOSW = adapt.Lookup("<s>"); EOSW = adapt.Lookup("</s>"); }
  }
  // cylm_cancel (another thread) bumps cancel_gen_; a decode that started before stops at its next check, returns
  // nothing and keeps the session's incremental state of the common prefix (cylm.h).
  std::atomic<uint32_t> cancel_gen_{0}; uint32_t run_gen_ = 0; bool last_cancelled_ = false;
  bool Cancelled() const { return cancel_gen_.load(std::memory_order_relaxed) != run_gen_; }
  // skip_inner: initial-only tokens are not generated before skip_before_ (see SpelledFrom); 0 = everywhere as before
  std::unordered_set<std::string> spell_prefix; int skip_before_ = 0, inc_skip_before_ = 0;
  Decoder(Lexicon& l, C3& b, Adapt& a, Params pp) : lex(l), base(b), adapt(a), p(pp) {
    for (auto& s : lex.syls) {
      if (s == "m" || s == "n" || s == "ng" || s == "hm" || s == "hng") continue;
      spell[s] = lex.code[s]; max_syl = std::max(max_syl, s.size());
      for (size_t i = 1; i <= s.size(); ++i) spell_prefix.insert(s.substr(0, i));
      std::string d; for (char c : s) d += T9Of(c);
      by_t9[d].push_back(lex.code[s]);
      for (size_t i = 1; i <= d.size(); ++i) {
        auto& v = t9_prefix[d.substr(0, i)]; std::string pre = s.substr(0, i);
        if (std::find(v.begin(), v.end(), pre) == v.end()) v.push_back(pre);
      }
    }
    BOSW = adapt.Lookup("<s>"); EOSW = adapt.Lookup("</s>");
    seen_lex_ = lex.version; seen_adapt_ = adapt.version;
  }
  // The start of the syllable being typed when the keys before it spell complete syllables (separators allowed) and
  // the rest is the beginning of a syllable, possibly empty ("nihaozho" -> 5); 0 when not (an initial-only key in
  // between, "nihaozg"). With skip_inner, initial-only tokens before it are left out: in spelled input they are
  // readings the user did not type (abbrev_full already marks them down), and each one opens abbreviation spans.
  int SpelledFrom(const std::string& text) const {
    const size_t n = text.size(); std::vector<char> ok(n + 1, 0); ok[0] = 1;
    for (size_t i = 0; i < n; ++i) {
      if (!ok[i]) continue;
      if (text[i] == '\'') { ok[i + 1] = 1; continue; }
      for (size_t j = i + 1; j <= std::min(n, i + max_syl); ++j) if (spell.count(text.substr(i, j - i))) ok[j] = 1;
    }
    for (size_t b = n + 1; b-- > 0;) if (ok[b] && (b == n || spell_prefix.count(text.substr(b)))) return int(b);
    return 0;
  }
  std::vector<std::vector<std::pair<int, Token>>> Tokens(const std::string& text, bool t9) {
    size_t n = text.size(); std::vector<std::vector<std::pair<int, Token>>> out(n);
    for (size_t i = 0; i < n; ++i) {
      if (text[i] == '\'') { out[i].push_back({int(i + 1), Token{'s', 0, 0, ""}}); continue; }
      for (size_t j = i + 1; j <= std::min(n, i + max_syl); ++j) {
        std::string sub = text.substr(i, j - i);
        if (!t9) { auto it = spell.find(sub); if (it != spell.end()) out[i].push_back({int(j), Token{'f', it->second, 0, ""}}); }
        else {
          auto it = by_t9.find(sub);
          if (it != by_t9.end()) for (char16_t c : it->second) out[i].push_back({int(j), Token{'f', c, 0, ""}});
          if (j == n) {
            auto pt = t9_prefix.find(sub);
            if (pt != t9_prefix.end()) for (auto& pre : pt->second) {
              auto sp = spell.find(pre);
              bool dup = false;
              if (sp != spell.end()) for (auto& e : out[i]) if (e.second.kind == 'f' && e.second.full == sp->second && e.first == int(j)) dup = true;
              if (!dup) out[i].push_back({int(j), Token{'a', 0, pre[0], pre}});
            }
          }
        }
      }
      if (!t9 && p.typo != 0 && i > 0 && (text[i - 1] == 'n' || (text[i - 1] == 'g' && i > 1 && text[i - 2] == 'n'))) {
        for (size_t j = i + 1; j <= std::min(n, i + max_syl - 1); ++j) {
          auto it = spell.find(text[i - 1] + text.substr(i, j - i));
          if (it != spell.end()) out[i].push_back({int(j), Token{'f', it->second, 0, "", 1}});
        }
      }
      if (!t9 && p.typo != 0 && p.neighbor) {
        // One key of a syllable (not its first) hit a QWERTY neighbour: "hai" typed for "hao". Typo readings, so
        // the same rules hold: places 2-5 only when far more common than what was typed, first only past typo_first.
        for (size_t j = i + 2; j <= std::min(n, i + max_syl); ++j) {
          std::string sub = text.substr(i, j - i);
          if (sub.find('\'') != std::string::npos) break;
          if (sub.find_first_of("aeiouv") == std::string::npos) continue;  // initials typed as abbreviations: no typo
          // neighbor=2: only where the keys spell no syllable (zuu, xon): far fewer typo paths (speed), and a typed
          // valid syllable is taken as meant
          if (p.neighbor == 2 && spell.count(sub)) continue;
          for (size_t k = 1; k < sub.size(); ++k) {
            const char* row = std::strchr(kQwerty, sub[k]);
            if (!row) continue;
            for (int d : {-1, 1}) {
              const char c = row[d];
              if (d < 0 && row == kQwerty) continue;
              if (c == ' ' || c == '\0') continue;
              std::string alt = sub; alt[k] = c;
              auto it = spell.find(alt);
              if (it != spell.end()) out[i].push_back({int(j), Token{'f', it->second, 0, "", 2}});
            }
          }
        }
      }
      if (!t9 && int(i) >= skip_before_) {
        char ch = text[i];
        if (kInitials.find(ch) != std::string::npos) {
          std::string two = text.substr(i, 2);
          if (two == "zh" || two == "ch" || two == "sh") out[i].push_back({int(i + 2), Token{'a', 0, ch, two, 0, 0, 2}});
          out[i].push_back({int(i + 1), Token{'a', 0, ch, ""}});
        }
        if (vowel_on_ && (ch == 'a' || ch == 'e' || ch == 'o')) {
          // A vowel letter as the initial of a vowel syllable (wa for 晚安, qad for 亲爱的), and e for 嗯 (n / ng).
          // These words are their own class (vowels in the span key: own budget, vowel_pen each); the bare
          // syllables a / e / o keep their full-syllable tokens, so nothing that was readable before changes.
          out[i].push_back({int(i + 1), Token{'a', 0, ch, "", 0, 1}});
          if (ch == 'e') out[i].push_back({int(i + 1), Token{'a', 0, 'n', "", 0, 2}});
        }
      }
    }
    return out;
  }
  double Lnw(const std::string& w) {
    auto it = lnw_cache.find(w); if (it != lnw_cache.end()) return it->second;
    double wt = double(std::max<int64_t>(0, lex.WeightOf(w)));
    return lnw_cache[w] = std::log((wt + 1) / lex.total);
  }
  double Prior(const std::string& w) {
    auto it = prior_cache.find(w); if (it != prior_cache.end()) return it->second;
    double lm = 0;
    if (p.select_lm != 0) for (int32_t x : base.Decompose(w)) lm += base.Ml(x);
    double r = p.select_lm * lm + std::max(p.mu, 0.02) * Lnw(w) * (p.select_lm != 0 ? 1 : 50);
    return prior_cache[w] = r;
  }
  bool Matches(const u16& key, const std::vector<Token>& path) {
    for (size_t i = 0; i < path.size(); ++i) {
      const Token& t = path[i];
      if (t.kind == 'f') { if (key[i] != t.full) return false; }
      else {
        const std::string& s = lex.Syl(key[i]);
        if (t.vowel == 2) { if (s != "n" && s != "ng") return false; continue; }  // e typed for 嗯
        if (s[0] != t.letter) return false;
        if (t.vowel == 1 && s.size() == 1) return false;  // the bare a / e / o: already its full-syllable token
        if (!t.prefix.empty() && s.compare(0, t.prefix.size(), t.prefix) != 0) return false;
      }
    }
    return true;
  }
  using Bucket = std::unordered_map<std::string, std::tuple<int, double, u16>>;  // word -> (initial-only syllables, prior, reading)
  void Collect(Bucket& bucket, const std::vector<Token>& path, const std::string& ini) {
    // initial-only syllables; abbrev_keys 1 counts keys instead, so z + sh (这是) costs as much as z + s + h (早上好):
    // every reading of an all-initials input then pays the same abbreviation total, and in a span's word list the
    // two compete by prior (with 0, both the cost and the list order favour the zh / ch / sh reading every time)
    int ab = 0; for (auto& t : path) ab += t.kind == 'a' ? (p.abbrev_keys ? t.keys : 1) : 0;
    std::string ck;
    if (!ab) { ck = "F"; for (auto& t : path) { ck += char(t.full >> 8); ck += char(t.full & 0xff); } }
    else {
      if (path.size() > size_t(kMaxAbbrev)) return;
      ck = "A";
      for (auto& t : path) { if (t.kind == 'f') { ck += 'f'; ck += char(t.full >> 8); ck += char(t.full & 0xff); } else { ck += 'a'; ck += t.letter; ck += t.prefix; if (t.vowel) ck += char('0' + t.vowel); ck += '/'; } }
    }
    auto it = hit_cache.find(ck);
    if (it == hit_cache.end()) {
      std::vector<std::tuple<double, std::string, u16>> hits;
      auto order = [](auto& x, auto& y) { return std::get<0>(x) != std::get<0>(y) ? std::get<0>(x) > std::get<0>(y) : std::get<1>(x) > std::get<1>(y); };
      if (!ab) {
        u16 key; for (auto& t : path) key += t.full;
        for (auto& [wt, w] : lex.KeyWordsAll(key)) hits.emplace_back(Prior(w), w, key);
        std::sort(hits.begin(), hits.end(), order);
        if (hits.size() > size_t(p.per_span)) hits.resize(p.per_span);
      } else {
        size_t limit = path.size() == 1 ? p.per_span1 : p.per_span_abbrev;
        std::unordered_map<std::string, u16> words;
        auto init_keys = lex.InitKeysAll(ini);
        {
          size_t k = 0;
          for (auto& key : init_keys) {
            if (k++ >= size_t(p.scan)) break;
            if (Matches(key, path)) for (auto& [wt, w] : lex.KeyWordsAll(key)) words.emplace(w, key);
          }
        }
        for (auto& [w, key] : words) hits.emplace_back(Prior(w), w, key);
        std::sort(hits.begin(), hits.end(), order);
        if (hits.size() > limit) hits.resize(limit);
      }
      it = hit_cache.emplace(ck, std::move(hits)).first;
    }
    for (auto& [pr, w, key] : it->second) {
      auto old = bucket.find(w);
      if (old == bucket.end() || std::make_pair(std::get<0>(old->second), -std::get<1>(old->second)) > std::make_pair(ab, -pr)) bucket[w] = {ab, pr, key};
    }
  }
  std::vector<std::vector<Edge>> Lattice(const std::string& text, bool t9, int min_end = -1) {
    auto toks = Tokens(text, t9);
    size_t n = text.size();
    std::map<std::tuple<int, int, int, int>, Bucket> spans;  // (start, end, typo syllables, vowel initials)
    // A word covers at most kMaxWord syllables of at most 6 keys (plus separators), so spans that end after
    // min_end start no earlier than this.
    const size_t first = min_end < 0 ? 0 : size_t(std::max(0, min_end + 1 - int(kMaxWord * max_syl)));
    for (size_t i = first; i < n; ++i) {
      if (Cancelled()) break;  // Decode returns nothing; the partial spans are dropped
      for (auto& [j, t] : toks[i]) if (t.kind == 's' && j > min_end) spans[{int(i), j, 0, 0}][""] = {0, 0.0, u16()};
      struct Item { int pos; std::vector<Token> path; std::string ini; int typos; int vowels; };
      std::vector<Item> stack{{int(i), {}, "", 0, 0}};
      while (!stack.empty()) {
        Item cur = std::move(stack.back()); stack.pop_back();
        if (cur.pos >= int(n)) continue;
        for (auto& [j, t] : toks[cur.pos]) {
          if (t.kind == 's') continue;
          char f = t.kind == 'f' ? lex.first[t.full - 1] : t.letter;
          std::string ini2 = cur.ini + f;
          if (!lex.HasPrefix(ini2)) continue;
          if (cur.typos && t.typo) continue;  // at most one typo syllable per word
          const int typos = cur.typos ? cur.typos : t.typo;  // its kind (1 doubled letter, 2 neighbour key)
          const int vowels = cur.vowels + (t.vowel ? 1 : 0);
          std::vector<Token> path2 = cur.path; path2.push_back(t);
          if (j > min_end) Collect(spans[{int(i), j, typos, vowels}], path2, ini2);
          // extend only paths that can still end after min_end (kMaxWord syllables of at most max_syl keys)
          if (path2.size() < size_t(kMaxWord) && j + int((kMaxWord - path2.size()) * max_syl) > min_end)
            stack.push_back({j, std::move(path2), ini2, typos, vowels});
        }
      }
    }
    std::vector<std::vector<Edge>> out(n);
    for (auto& [ij, words] : spans) {
      std::vector<std::pair<std::string, std::tuple<int, double, u16>>> items(words.begin(), words.end());
      // Fewest initial-only syllables first, so with abbrev_keys 0 every two-syllable reading of "zsh" (z + sh: 这是)
      // comes before every three-syllable one (z + s + h: 早上好); abbrev_keys 1 makes them equal (prior decides).
      std::sort(items.begin(), items.end(), [](auto& x, auto& y) {
        if (std::get<0>(x.second) != std::get<0>(y.second)) return std::get<0>(x.second) < std::get<0>(y.second);
        if (std::get<1>(x.second) != std::get<1>(y.second)) return std::get<1>(x.second) > std::get<1>(y.second);
        return x.first < y.first; });
      const auto [si, sj, typos, vowels] = ij;
      int kf = 0, ka = 0; bool single = sj - si <= 2;
      int la = single ? p.per_span1 : p.per_span_abbrev;
      int lf = t9 && p.t9_per_span > 0 ? p.t9_per_span : p.per_span;
      if (typos) { lf = std::max(1, lf / 4); la = std::max(1, la / 8); }  // a typo reading: only its likeliest words
      if (vowels) la = std::max(1, la / 4);  // vowel initials: their own, smaller budget (never crowd the others out)
      for (auto& [w, v] : items) {
        if (std::get<0>(v) == 0) { if (kf++ >= lf) continue; }
        else { if (ka++ >= la) continue; }
        out[si].push_back({sj, w, std::get<0>(v), std::get<2>(v), typos ? 1 : 0, typos == 2 ? 1 : 0, vowels});
      }
    }
    return out;
  }
  static double Chain(C3& m, int32_t& a, int32_t& b, const std::string& w) {
    double lp = 0;
    for (int32_t x : m.Decompose(w)) { lp += b >= 0 ? m.Logp3(a, b, x) : m.Lower(x); a = b; b = x; }
    return lp;
  }
  static double Mix(double lp1, double lp2, double w2) { return std::log((1 - w2) * std::exp(lp1) + w2 * std::exp(lp2)); }
  std::pair<double, State> EdgeScore(const State& st, const std::string& w) {
    int32_t a = st.a, b = st.b, a2 = st.a2, b2 = st.b2;
    double lp = Chain(base, a, b, w);
    if (base2 && p.mix > 0) { double lp2 = Chain(*base2, a2, b2, w); if (!HasHole(w)) lp = Mix(lp, lp2, p.mix); }
    int32_t wid = adapt.loaded ? adapt.Lookup(w) : -1;
    // The user's own text: a mixture (adapt_lambda) scales every other word down by 1 - lambda per word, which also
    // favours fewer, longer words; adapt_add only adds the user's probability, so it can raise a word and never
    // lowers the others or changes the per-word cost.
    if (p.adapt_add > 0 && adapt.loaded) lp = std::log(std::exp(lp) + p.adapt_add * adapt.P(st.wa, st.wb, wid));
    else if (p.adapt_lambda > 0 && adapt.loaded) lp = std::log((1 - p.adapt_lambda) * std::exp(lp) + p.adapt_lambda * adapt.P(st.wa, st.wb, wid));
    State out{a, b, st.wb, wid, a2, b2, st.ty};
    return {lp, out};
  }
  double Eos(const State& st) {
    double lp = base.Logp3(st.a, st.b, int32_t(base.h.eos));
    if (base2 && p.mix > 0) lp = Mix(lp, base2->Logp3(st.a2, st.b2, int32_t(base2->h.eos)), p.mix);
    if (p.adapt_add > 0 && adapt.loaded) lp = std::log(std::exp(lp) + p.adapt_add * adapt.P(st.wa, st.wb, EOSW));
    else if (p.adapt_lambda > 0 && adapt.loaded) lp = std::log((1 - p.adapt_lambda) * std::exp(lp) + p.adapt_lambda * adapt.P(st.wa, st.wb, EOSW));
    return lp;
  }
  State Start(const std::string& context) {
    auto cps = Utf8Decode(context);
    size_t e = cps.size(), s = e;
    while (s > 0 && IsHan(cps[s - 1])) --s;
    State st{-1, int32_t(base.h.bos), BOSW, BOSW, -1, base2 ? int32_t(base2->h.bos) : -1};
    if (s == e) return st;
    if (e - s > 16) s = e - 16;
    std::string run = Utf8Encode(cps, s, e);
    auto parts = base.Decompose(run);
    st.b = parts.back(); st.a = parts.size() >= 2 ? parts[parts.size() - 2] : int32_t(base.h.bos);
    if (base2) {
      auto p2 = base2->Decompose(run);
      st.b2 = p2.back(); st.a2 = p2.size() >= 2 ? p2[p2.size() - 2] : int32_t(base2->h.bos);
    }
    return st;
  }
  // The words of a path, kept as a shared back-pointer chain so extending a hypothesis does not copy its reading.
  // The words of a path, kept as back-pointers into an arena (index, -1 = none) so extending a hypothesis does not
  // copy its reading. The arena lives as long as the reused beams (reset when a decode starts from scratch).
  struct Trace { int32_t prev; int16_t i, j; uint8_t len; char16_t key[kMaxWord]; };
  struct Hyp { double score; State st; std::string out; int ab = 0; int32_t tr = -1; };
  std::vector<Trace> arena_;
  KvCache kv_cache_, kv_cache_t9_;
  std::pair<std::string, std::string> Reading(int32_t tr) const {
    std::vector<const Trace*> words; for (int32_t t = tr; t >= 0; t = arena_[t].prev) words.push_back(&arena_[t]);
    std::string py, seg;
    for (auto it = words.rbegin(); it != words.rend(); ++it) {
      if (!py.empty()) { py += ' '; seg += ','; }
      py += Spell(u16((*it)->key, (*it)->len)); seg += std::to_string((*it)->i) + "-" + std::to_string((*it)->j);
    }
    return {py, seg};
  }
  // Reading of each returned candidate: syllables ("bei xian qi le") and the key span of each word ("0-3,3-7,..").
  std::unordered_map<std::string, std::pair<std::string, std::string>> last_meta_;
  std::string Spell(const u16& key) const { std::string s; for (char16_t c : key) { if (!s.empty()) s += ' '; s += lex.Syl(c); } return s; }
  struct KeyHash { size_t operator()(const std::pair<State, std::string>& k) const { return StateHash()(k.first) ^ std::hash<std::string>()(k.second); } };
  using Beam = std::unordered_map<std::pair<State, std::string>, Hyp, KeyHash>;
  // Incremental state: the previous input's edges and beams. A new key changes only the spans that end after
  // the common prefix, so beams up to it are reused as they are (the IME decodes once per keystroke).
  std::string inc_text_, inc_context_; bool inc_t9_ = false; std::vector<std::vector<Edge>> inc_edges_; std::vector<Beam> inc_beams_;
  bool vowel_on_ = false, inc_vowel_on_ = false;  // vowel initials for this input (set by Decode, read by Tokens)
  // Each position's beam, sorted and cut to the beam width: unchanged positions are not sorted again.
  std::vector<std::vector<Hyp>> inc_sorted_; std::vector<char> inc_sorted_ok_;
  std::vector<std::pair<std::string, double>> Decode(const std::string& text, bool t9, const std::string& context) {
    size_t n = text.size();
    Sync();
    run_gen_ = cancel_gen_.load(std::memory_order_relaxed); last_cancelled_ = false;
    skip_before_ = 0;  // the abbrev_full check below reads every token
    // When the whole input spells complete syllables (all but possibly the last), initial-only readings inside it
    // are what the user did not type: they cost abbrev_full instead of abbrev (applied to finished paths below).
    double abbrev = p.abbrev;
    // Vowel initials (vowel): off for an input of 6+ keys that spells complete syllables, where they would be
    // initials the user did not type and would only add paths at every vowel of the sentence. vowel=2 turns them off
    // for such an input of any length, so a full-pinyin prefix (xia, typing 下) never reads as xi + a (西安).
    vowel_on_ = p.vowel != 0 && !t9;
    if ((p.abbrev_full != 0 || vowel_on_) && (n >= 6 || (vowel_on_ && p.vowel == 2))) {
      const bool vowel_wanted = vowel_on_;
      vowel_on_ = false;  // this check reads the plain tokens
      auto toks = Tokens(text, t9);
      std::vector<char> ok(n + 1, 0); ok[0] = 1;
      for (size_t i = 0; i < n; ++i) {
        if (!ok[i]) continue;
        for (auto& [j, t] : toks[i]) if ((t.kind == 'f' && !t.typo) || t.kind == 's' || (t.kind == 'a' && size_t(j) == n && i > 0)) ok[j] = 1;
      }
      if (ok[n] && p.abbrev_full != 0 && n >= 6) abbrev = p.abbrev_full;
      vowel_on_ = vowel_wanted && !ok[n];
    }
    // One complete syllable (da, dan, sa) is a syllable the user spelled, however short: its initial-only readings
    // (d + a 对啊, d + an 答案, da + n 打你) cost abbrev_syl each, so they never come before the syllable's own words.
    if (p.abbrev_syl != 0 && !t9 && n < 6 && abbrev == p.abbrev) {
      const bool vowel_saved = vowel_on_;
      vowel_on_ = false;
      const auto toks = Tokens(text, t9);
      for (auto& [j, t] : toks[0]) if (t.kind == 'f' && !t.typo && size_t(j) == n) abbrev = p.abbrev_syl;
      vowel_on_ = vowel_saved;
    }
    // (6+ keys, as abbrev_full: in a short input an initial-only reading is as likely as the spelled one, sa = 是啊)
    if (p.skip_inner && !t9 && n >= 6) skip_before_ = SpelledFrom(text);
    // Reuse boundary: edges and beams up to `keep` are unchanged. Nine-key inputs allow a partial syllable only at
    // the end, so the old last position is recomputed for them.
    size_t keep = 0;
    if (p.incremental && t9 == inc_t9_ && context == inc_context_ && vowel_on_ == inc_vowel_on_ && !inc_beams_.empty()) {
      while (keep < n && keep < inc_text_.size() && text[keep] == inc_text_[keep]) ++keep;
      if (t9 && keep > 0 && (keep == inc_text_.size() || keep == n)) --keep;
      if (!t9 && keep == inc_text_.size() && keep > 0 && keep < n) {}  // pinyin: abbreviations are valid anywhere
      // skip_inner: tokens differ only between the old and the new skip_before_, so edges ending at or before the
      // smaller one are the same either way
      if (skip_before_ != inc_skip_before_) keep = std::min(keep, size_t(std::min(skip_before_, inc_skip_before_)));
    }
    std::vector<std::vector<Edge>> edges(n), fresh;
    std::vector<Beam> beams(n + 1);
    std::vector<std::vector<Hyp>> sorted(n + 1); std::vector<char> sorted_ok(n + 1, 0);
    if (keep == 0) arena_.clear();
    if (keep > 0) {
      for (size_t i = 0; i < std::min(n, inc_edges_.size()); ++i)
        for (auto& e : inc_edges_[i]) if (size_t(e.j) <= keep) edges[i].push_back(std::move(e));
      for (size_t i = 0; i <= keep; ++i) beams[i] = std::move(inc_beams_[i]);
      for (size_t i = 0; i <= keep && i < inc_sorted_.size(); ++i)
        if (inc_sorted_ok_[i]) { sorted[i] = std::move(inc_sorted_[i]); sorted_ok[i] = 1; }
      fresh = Lattice(text, t9, int(keep));
      for (size_t i = 0; i < n; ++i) for (auto& e : fresh[i]) edges[i].push_back(e);
    } else {
      fresh = Lattice(text, t9);
      edges = fresh;
      State init = Start(context);
      beams[0][{init, ""}] = Hyp{0.0, init, "", 0, -1};
    }
    // Exact pruning: a word's log-probability is at most 0, so hy.score + mu*ln(weight) + abbrev penalties bounds
    // what an edge can reach. Each new position keeps a lower bound of its cut-off: the beam-th best score among
    // its distinct entries (at the end, the nbest-th best final score among distinct outputs); an edge whose bound
    // is below it could not have survived the beam (or the returned list), so it is not scored. Results are the same.
    const size_t beam_w = t9 && p.t9_beam > 0 ? size_t(p.t9_beam) : size_t(p.beam);
    const bool prune = p.prune && !adapt.loaded, prune_n = prune && p.eos2 < 0 && p.mu_word == 0 && abbrev <= p.abbrev && p.eos >= 0 && p.eos_word < 0;
    // Typo readings (p.typo != 0) are a separate class with their own, smaller budget, so they never change what
    // the strict readings keep.
    const size_t typo_w = std::max<size_t>(1, beam_w / 4), typo_n = std::max<size_t>(4, size_t(p.nbest) / 4);
    std::vector<std::priority_queue<double, std::vector<double>, std::greater<double>>> cut(n + 1), cut_t(n + 1);
    std::unordered_set<std::string> seen_n, seen_t;
    auto cap_of = [&](size_t j, bool ty) { return j == n ? (ty ? typo_n : size_t(p.nbest)) : (ty ? typo_w : beam_w); };
    auto push_cut = [&](size_t j, bool ty, double v) {
      auto& h = ty ? cut_t[j] : cut[j]; const size_t cap = cap_of(j, ty);
      if (h.size() < cap) h.push(v); else if (v > h.top()) { h.pop(); h.push(v); }
    };
    auto bound = [&](size_t j, bool ty) { auto& h = ty ? cut_t[j] : cut[j]; return h.size() >= cap_of(j, ty) ? h.top() : -INFINITY; };
    // Only the fresh edges (ending after `keep`) are new; the reused ones are already in the reused beams.
    for (size_t i = 0; i < n; ++i) {
      if (Cancelled()) break;
      if (beams[i].empty() || fresh[i].empty()) continue;
      if (!sorted_ok[i]) {
        auto& items = sorted[i]; items.clear(); items.reserve(beams[i].size());
        for (auto& kv : beams[i]) items.push_back(kv.second);
        std::sort(items.begin(), items.end(), [](const Hyp& x, const Hyp& y) { return x.score != y.score ? x.score > y.score : x.out < y.out; });
        if (p.typo == 0) { if (items.size() > beam_w) items.resize(beam_w); }
        else {
          std::vector<Hyp> kept; size_t ns = 0, nt = 0;
          for (auto& h : items) {
            if (h.st.ty == 0 ? ns++ < beam_w : nt++ < typo_w) kept.push_back(std::move(h));
          }
          items = std::move(kept);
        }
        sorted_ok[i] = 1;
      }
      for (auto& hy : sorted[i]) {
        for (auto& e : fresh[i]) {
          double s2; State st2;
          const size_t ej = size_t(e.j);
          if (e.word.empty()) { s2 = hy.score; st2 = hy.st; }
          else {
            if (prune && (ej < n || prune_n) && hy.score + p.mu * Lnw(e.word) + p.abbrev * e.abbrevs + p.typo * e.typos + p.vowel_pen * e.vowels + p.word_pen < bound(ej, hy.st.ty + e.typos > 0)) continue;
            auto sc = EdgeScore(hy.st, e.word);
            if (i == 0 && size_t(e.j) == n && p.adapt_word >= 0 && adapt.loaded) {
              // the whole input as one word: add the user's own frequency of it, weighted by adapt_word
              double lam = p.adapt_word, lp_base = sc.first;
              if (p.adapt_lambda > 0) {  // undo the general mixture to get the base log-probability back
                double pa = adapt.P(hy.st.wa, hy.st.wb, adapt.Lookup(e.word));
                lp_base = std::log(std::max(1e-300, (std::exp(sc.first) - p.adapt_lambda * pa) / (1 - p.adapt_lambda)));
              }
              // additive: the user's own use can only raise a word, never push down words they have not typed
              int32_t wid = adapt.Lookup(e.word);
              double pa = wid >= 0 ? adapt.P(hy.st.wa, hy.st.wb, wid) : 0.0;
              sc.first = std::log(std::exp(lp_base) + lam * pa);
            }
            s2 = hy.score + sc.first + p.mu * Lnw(e.word) + p.abbrev * e.abbrevs + p.typo * e.typos + p.neighbor_pen * e.nears + p.vowel_pen * e.vowels + p.word_pen; st2 = sc.second;
            st2.ty += e.typos;
          }
          std::string out2 = hy.out + e.word;
          auto key = std::make_pair(st2, out2);
          auto old = beams[e.j].find(key);
          if (prune && old == beams[e.j].end()) {
            const bool ty = st2.ty > 0;
            if (ej < n) push_cut(ej, ty, s2);
            else if (prune_n && (ty ? seen_t : seen_n).insert(out2).second) {
              // eos_word < 0 here, so every output takes p.eos (as in ranked below)
              push_cut(n, ty, s2 + p.eos * Eos(st2) + (abbrev - p.abbrev) * (hy.ab + e.abbrevs));
            }
          }
          if (old == beams[e.j].end() || s2 > old->second.score) {
            Hyp h{s2, st2, out2, hy.ab + e.abbrevs, hy.tr};
            if (!e.word.empty()) {
              Trace t{hy.tr, int16_t(i), int16_t(e.j), uint8_t(std::min<size_t>(e.key.size(), kMaxWord)), {}};
              std::copy(e.key.begin(), e.key.begin() + t.len, t.key);
              arena_.push_back(t); h.tr = int32_t(arena_.size() - 1);
            }
            beams[e.j][key] = std::move(h);
          }
        }
      }
    }
    if (Cancelled()) {
      // A newer request is waiting (cylm_cancel): return nothing, and keep as the state of the common prefix what this
      // call reused unchanged (edges ending at or before `keep`, beams up to `keep`; fresh edges end after it and only
      // beams after it were touched), so the next decode still continues from there instead of from scratch.
      last_cancelled_ = true;
      if (p.incremental) {
        edges.resize(keep);
        for (auto& v : edges) v.erase(std::remove_if(v.begin(), v.end(), [&](const Edge& e) { return size_t(e.j) > keep; }), v.end());
        beams.resize(keep + 1); sorted.resize(keep + 1); sorted_ok.resize(keep + 1);
        inc_text_ = text.substr(0, keep); inc_context_ = context; inc_t9_ = t9; inc_vowel_on_ = vowel_on_; inc_skip_before_ = skip_before_;
        inc_edges_ = std::move(edges); inc_beams_ = std::move(beams); inc_sorted_ = std::move(sorted); inc_sorted_ok_ = std::move(sorted_ok);
      }
      return {};
    }
    std::unordered_map<std::string, int32_t> best_tr, best_tr_t;
    std::unordered_map<std::string, double> typo_raw;  // typo-only outputs: score without the typo penalty
    auto ranked = [&](double eos_w, int cls) {  // cls 0: strict paths, 1: typo paths
      std::unordered_map<std::string, double> best;
      for (auto& kv : beams[n]) {
        if ((kv.second.st.ty > 0) != (cls == 1)) continue;
        const bool one_word = kv.second.tr >= 0 && arena_[kv.second.tr].prev < 0;
        const double ew = one_word && p.eos_word >= 0 && eos_w == p.eos ? p.eos_word : eos_w;
        double s = kv.second.score + ew * Eos(kv.second.st) + (abbrev - p.abbrev) * kv.second.ab;
        if (one_word && p.mu_word != 0) s += p.mu_word * Lnw(kv.second.out);  // a typed word: how common the word is
        if (cls == 1) s -= p.typo * kv.second.st.ty;  // ranked by the reading's own score; listed later only if far more common
        auto it = best.find(kv.second.out);
        if (it == best.end() || s > it->second) { best[kv.second.out] = s; (cls == 0 ? best_tr : best_tr_t)[kv.second.out] = kv.second.tr; }
      }
      std::vector<std::pair<std::string, double>> out(best.begin(), best.end());
      std::sort(out.begin(), out.end(), [](auto& x, auto& y) { return x.second != y.second ? x.second > y.second : x.first < y.first; });
      return out;
    };
    last_meta_.clear();
    auto out = ranked(p.eos, 0);
    if (p.eos2 >= 0) {
      // Two readings of a short input: a whole message (with the end-of-message probability) and a word that the
      // message continues after (eos2). The list alternates them: `interleave` whole-message candidates, then one word.
      auto alt = ranked(p.eos2, 0);
      std::vector<std::pair<std::string, double>> merged; std::unordered_set<std::string> seen;
      size_t ia = 0, ib = 0;
      while (merged.size() < size_t(p.nbest) && (ia < out.size() || ib < alt.size())) {
        for (int k = 0; k < p.interleave && ia < out.size(); ++k, ++ia)
          if (seen.insert(out[ia].first).second) merged.push_back(out[ia]);
        while (ib < alt.size() && !seen.insert(alt[ib].first).second) ++ib;
        if (ib < alt.size()) merged.push_back(alt[ib++]);
      }
      out = merged;
    }
    if (out.size() > size_t(p.nbest)) out.resize(p.nbest);
    const size_t n_strict = out.size();
    if (p.typo != 0 && p.eos2 < 0) {
      // Typo readings (a doubled letter typed once): kept apart; after reranking they may only take places 2-5, and
      // only when far more common than the strict reading; first place only past typo_first (docs/CYLM_TYPO.md).
      std::unordered_set<std::string> strict; for (auto& [w, sc] : out) strict.insert(w);
      for (auto& kv : beams[n]) if (kv.second.st.ty == 0) strict.insert(kv.second.out);
      auto ty = ranked(p.eos, 1); size_t added = 0;
      for (auto& [w, sc] : ty) {
        if (added >= typo_n) break;
        if (strict.count(w)) continue;
        typo_raw[w] = sc; best_tr[w] = best_tr_t[w]; out.push_back({w, sc}); ++added;
      }
    }
    std::unordered_map<std::string, double> decoder_score; for (auto& [w, sc] : out) decoder_score[w] = sc;
    for (auto& [w, sc] : out) last_meta_[w] = Reading(best_tr[w]);  // readings only for the returned candidates
    const Reranker* use_rr = t9 && rr_t9 ? rr_t9 : rr;
    if (use_rr && !out.empty()) {  // rerank: decoder score + gamma * character-model log-probability (same step, no pause)
      std::vector<std::string> cands; for (auto& [w, sc] : out) cands.push_back(w);
      auto& cache = use_rr == rr ? kv_cache_ : kv_cache_t9_;
      cache.limit = size_t(p.rr_cache);
      auto nn = use_rr->kv ? use_rr->ScoreKV(cache, context, cands, true) : use_rr->Score(context, cands, true);
      double g = Utf8Decode(out[0].first).size() <= 3 ? p.rr_short : p.rr_gamma;
      for (size_t k = 0; k < out.size(); ++k) out[k].second += g * nn[k];
      std::stable_sort(out.begin(), out.end(), [](auto& x, auto& y) { return x.second > y.second; });
    }
    if (prefer && out.size() > 1) {
      for (size_t k = 0; k < out.size(); ++k) {
        auto it = prefer->find(out[k].first);
        if (it == prefer->end()) continue;
        for (auto& pw : it->second)
          for (size_t m = k + 1; m < out.size(); ++m)
            if (out[m].first == pw) { auto moved = out[m]; out.erase(out.begin() + m); out.insert(out.begin() + k, moved); ++k; break; }
      }
    }
    if (!typo_raw.empty()) {
      // First place: the best strict reading of what was typed, whatever the reranker or the preferences said. A typo
      // reading is listed only when its decoder score beats the best strict reading's by ln 20 (20 times as common),
      // and only within places 2-5. With no strict reading at all, the typo readings stand alone.
      // typo_first > 0 (user, 2026-10-25): the typo reading with the best decoder score goes first instead when it
      // beats the best strict reading's by more than typo_first nats; the strict candidate that was first is then
      // second, and the rest follow the rule above.
      double top_strict = -INFINITY;
      for (auto& [w, sc] : out) if (!typo_raw.count(w)) top_strict = std::max(top_strict, decoder_score[w]);
      if (n_strict > 0) {
        std::string lead; double lead_sc = -INFINITY;  // the best typo reading by its own decoder score
        if (p.typo_first > 0)
          for (auto& [w, sc] : out) if (typo_raw.count(w) && decoder_score[w] > lead_sc) { lead = w; lead_sc = decoder_score[w]; }
        const bool lead_first = p.typo_first > 0 && lead_sc > top_strict + p.typo_first;
        std::vector<std::pair<std::string, double>> placed; bool first = false;
        if (lead_first) for (auto& c : out) if (c.first == lead) { placed.push_back(c); break; }
        for (auto& c : out) if (!typo_raw.count(c.first)) { placed.push_back(c); first = true; break; }
        const std::string head = first ? placed.back().first : std::string();  // the first strict candidate (second after a lead)
        for (auto& c : out) {
          if (first && c.first == head) continue;
          if (lead_first && c.first == lead) continue;
          if (typo_raw.count(c.first)) {
            if (placed.size() >= 5 || decoder_score[c.first] < top_strict + std::log(20.0)) continue;
          }
          placed.push_back(c);
        }
        out = std::move(placed);
      }
    }
    if (p.incremental) { inc_text_ = text; inc_context_ = context; inc_t9_ = t9; inc_vowel_on_ = vowel_on_; inc_skip_before_ = skip_before_; inc_edges_ = std::move(edges); inc_beams_ = std::move(beams);
                         inc_sorted_ = std::move(sorted); inc_sorted_ok_ = std::move(sorted_ok); }
    return out;
  }
  std::vector<std::pair<std::string, double>> Choice(const std::string& context, const std::vector<std::string>& cands) {
    State st = Start(context);
    std::vector<std::pair<std::string, double>> sc;
    for (auto& c : cands) sc.emplace_back(c, EdgeScore(st, c).first + p.mu * Lnw(c));
    std::stable_sort(sc.begin(), sc.end(), [](auto& x, auto& y) { return x.second > y.second; });
    return sc;
  }
};

static bool LoadPrefer(const char* path, std::unordered_map<std::string, std::vector<std::string>>& out) {
  std::ifstream in(path, std::ios::binary); if (!in) return false;
  std::string line;
  while (std::getline(in, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    if (line.empty() || line[0] == '#') continue;
    auto f = Split(line, '\t'); if (f.size() >= 2 && !f[0].empty() && !f[1].empty()) out[f[1]].push_back(f[0]);
  }
  return true;
}

// ------------------------------------------------------------------ C API (cylm.h): library for Windows Core / Android JNI
#include "cylm.h"
struct CylmModel { Lexicon lex; C3 base; C3 base2; bool has2 = false; Adapt adapt; Params p; Reranker rr; bool has_rr = false;
                   Reranker gate; bool has_gate = false;  // a small character model the hook asks about two sentences (cylm_gate_score)
                   Reranker rr_t9; bool has_rr_t9 = false;
                   std::unordered_map<std::string, std::vector<std::string>> prefer; std::unordered_set<uint32_t> holes; };
struct CylmSession { std::unique_ptr<Decoder> d; };
// Words of a committed text for learning: Han runs split into lexicon words (the user's words included) by the
// model's unigram, -2 per word as in the Python decoder's segment(); other characters end a run.
static std::vector<std::vector<std::string>> LearnRuns(CylmModel* m, const std::string& text) {
  std::vector<std::vector<std::string>> runs; std::vector<uint32_t> run;
  auto flush = [&]() {
    const size_t n = run.size();
    if (n) {
      std::vector<double> best(n + 1, -1e18); std::vector<size_t> from(n + 1, 0); best[0] = 0;
      for (size_t i = 0; i < n; ++i) {
        if (best[i] <= -1e17) continue;
        for (size_t j = i + 1; j <= std::min(n, i + size_t(kMaxWord)); ++j) {
          const std::string w = Utf8Encode(run, i, j);
          const int64_t wt = m->lex.WeightOf(w);
          if (j > i + 1 && wt < 0) continue;
          const int32_t id = m->base.Id(w);
          const double sc = best[i] + (id >= 0 ? m->base.Ml(id) : std::log((std::max<int64_t>(wt, 0) + 1) / m->lex.total)) - 2.0;
          if (sc > best[j]) { best[j] = sc; from[j] = i; }
        }
      }
      std::vector<std::string> words;
      for (size_t j = n; j > 0; j = from[j]) words.push_back(Utf8Encode(run, from[j], j));
      std::reverse(words.begin(), words.end());
      runs.push_back(std::move(words));
    }
    run.clear();
  };
  for (uint32_t c : Utf8Decode(text)) { if (IsHan(c)) run.push_back(c); else flush(); }
  flush();
  return runs;
}
extern "C" {
CylmModel* cylm_open(const char* lexicon, const char* model, const char* model2, const char* params) {
  return cylm_open2(lexicon, model, model2, nullptr, nullptr, params);
}
CylmModel* cylm_open2(const char* lexicon, const char* model, const char* model2, const char* reranker, const char* vocab,
                      const char* params) {
  auto m = std::make_unique<CylmModel>();
  const uint8_t* mapped = MapFile(lexicon);
  if (!mapped || !m->lex.LoadBin(mapped)) return nullptr;  // the library takes compiled lexicons only
  if (!m->base.Open(model)) return nullptr;
  if (model2 && *model2) { if (!m->base2.Open(model2)) return nullptr; m->has2 = true; }
  for (auto& kv : Split(params ? params : "", ';')) {
    auto eq = kv.find('=');
    if (eq != std::string::npos && !m->p.Set(kv.substr(0, eq), kv.substr(eq + 1))) return nullptr;
  }
  m->p.incremental = 1;
  m->base.id_cap = m->base2.id_cap = m->p.id_cap; m->base.dec_cap = m->base2.dec_cap = m->p.dec_cap;
  if (reranker && *reranker) {
    if (!vocab || !m->rr.Load(reranker, vocab, m->p.rr_threads)) return nullptr;
    m->has_rr = true;
  }
  return m.release();
}
CylmSession* cylm_session(CylmModel* m) {
  if (!m) return nullptr;
  auto s = new CylmSession();
  s->d = std::make_unique<Decoder>(m->lex, m->base, m->adapt, m->p);
  if (m->has2) s->d->base2 = &m->base2;
  if (m->has_rr) s->d->rr = &m->rr;
  if (m->has_rr_t9) s->d->rr_t9 = &m->rr_t9;
  if (!m->prefer.empty()) s->d->prefer = &m->prefer;
  if (!m->holes.empty()) s->d->holes = &m->holes;
  return s;
}
int cylm_decode(CylmSession* s, const char* keys, int mode, const char* context, char* out, int cap) {
  if (!s || !keys || !out || cap <= 0) return -1;
  s->d->TrimCaches();
  auto ranked = s->d->Decode(keys, mode == 1, context ? context : "");
  if (s->d->last_cancelled_) return -2;
  int n = 0, used = 0;
  for (auto& [w, sc] : ranked) {
    if (used + int(w.size()) + 1 > cap) break;
    std::memcpy(out + used, w.data(), w.size()); used += int(w.size()); out[used++] = '\0'; ++n;
  }
  return n;
}
int cylm_reranker_t9(CylmModel* m, const char* reranker, const char* vocab) {
  if (!m || !reranker || !vocab || !m->rr_t9.Load(reranker, vocab, m->p.rr_threads)) return -1;
  m->has_rr_t9 = true;
  return 0;
}
int cylm_prefer(CylmModel* m, const char* path) {
  if (!m || !path) return -1;
  m->prefer.clear();
  return LoadPrefer(path, m->prefer) ? int(m->prefer.size()) : -1;
}
int cylm_learn(CylmModel* m, const char* text, double count) {
  if (!m || !text) return -1;
  int words = 0;
  for (auto& run : LearnRuns(m, text)) { m->adapt.Add(run, count > 0 ? count : 1.0, m->p.adapt_cap); words += int(run.size()); }
  return words;
}
// cylm_forget on the words of a text (LearnRuns): the trigrams to take out, see cylm.h.
static size_t ForgetRuns(Adapt& adapt, const std::vector<std::vector<std::string>>& runs) {
  if (!adapt.loaded) return 0;
  size_t words = 0;
  for (const auto& run : runs) words += run.size();
  std::unordered_set<int32_t> ids;
  std::unordered_set<uint64_t> keys;
  const int32_t start = adapt.Lookup("<s>"), end = adapt.Lookup("</s>");
  for (const auto& run : runs) {
    int32_t a = start, b = start;
    bool known = start >= 0;
    for (const auto& w : run) {
      const int32_t x = adapt.Lookup(w);
      if (x >= 0 && (words == 1 || Utf8Decode(w).size() >= 2)) ids.insert(x);
      known = known && x >= 0;
      if (known) keys.insert(Adapt::K3(a, b, x));
      a = b; b = x;
    }
    if (known && end >= 0) keys.insert(Adapt::K3(a, b, end));
  }
  return adapt.Forget(ids, keys);
}
int cylm_forget(CylmModel* m, const char* text) {
  if (!m || !text) return -1;
  return m->adapt.loaded ? int(ForgetRuns(m->adapt, LearnRuns(m, text))) : 0;
}
int cylm_add_word(CylmModel* m, const char* word, const char* pinyin, long long weight) {
  if (!m || !word || !pinyin) return -1;
  std::vector<std::string> syl; for (auto& x : Split(pinyin, ' ')) if (!x.empty()) syl.push_back(x);
  if (Utf8Decode(word).size() != syl.size()) return -1;
  // default weight: about as common as a frequent word of the lexicon (1/20000 of its total)
  const int64_t wt = weight > 0 ? int64_t(weight) : std::max<int64_t>(1, int64_t(m->lex.total / 20000));
  return m->lex.AddWord(word, syl, wt) ? 0 : -1;
}
int cylm_gate_load(CylmModel* m, const char* model, const char* vocab) {
  if (!m || !model || !vocab || !m->gate.Load(model, vocab, 1, true)) return -1;
  m->has_gate = true;
  return 0;
}
double cylm_gate_score(CylmModel* m, const char* text) {
  if (!m || !text || !m->has_gate) return 0.0;
  auto v = m->gate.Score("", {std::string(text)}, true);
  return v.empty() ? 0.0 : v[0];
}
int cylm_has_word(CylmModel* m, const char* word) { return m && word && m->lex.WeightOf(word) >= 0 ? 1 : 0; }
int cylm_adapt_save(CylmModel* m, const char* path) {
  if (!m || !path) return -1;
  return m->adapt.Save(path) ? int(m->adapt.Trigrams()) : -1;
}
int cylm_adapt(CylmModel* m, const char* path) {
  if (!m || !path) return -1;
  m->adapt = Adapt();
  return m->adapt.Load(path) ? int(m->adapt.names.size()) : -1;
}
int cylm_holes(CylmModel* m, const char* path) {
  if (!m || !path) return -1;
  std::ifstream in(path, std::ios::binary); if (!in) return -1;
  std::string line; m->holes.clear();
  while (std::getline(in, line)) { auto cps = Utf8Decode(line); if (!cps.empty() && cps[0] > 0x7f) m->holes.insert(cps[0]); }
  return int(m->holes.size());
}
int cylm_decode2(CylmSession* s, const char* keys, int mode, const char* context, char* out, int cap) {
  if (!s || !keys || !out || cap <= 0) return -1;
  s->d->TrimCaches();
  auto ranked = s->d->Decode(keys, mode == 1, context ? context : "");
  if (s->d->last_cancelled_) return -2;
  int n = 0, used = 0;
  for (auto& [w, sc] : ranked) {
    auto it = s->d->last_meta_.find(w);
    char score[32]; std::snprintf(score, sizeof score, "%.3f", sc);  // the ranking score (higher is better)
    std::string rec = w + '\x1f' + (it == s->d->last_meta_.end() ? "" : it->second.first) + '\x1f' +
                      (it == s->d->last_meta_.end() ? "" : it->second.second) + '\x1f' + score;
    if (used + int(rec.size()) + 1 > cap) break;
    std::memcpy(out + used, rec.data(), rec.size()); used += int(rec.size()); out[used++] = '\0'; ++n;
  }
  return n;
}
void cylm_cancel(CylmSession* s) { if (s) s->d->cancel_gen_.fetch_add(1, std::memory_order_relaxed); }
void cylm_close_session(CylmSession* s) { delete s; }
void cylm_close(CylmModel* m) { delete m; }
}

#ifndef CYLM_LIBRARY
int main(int argc, char** argv) {
  if (argc < 3) { std::fprintf(stderr, "usage: cylm LEXICON.tsv MODEL.lm [--adapt COUNTS.tsv] [--set k=v]... < items > out\n"); return 2; }
  Params p; std::string adapt_path, model2, reranker, vocab, prefer_path, holes_path, reranker_t9;
  for (int i = 3; i < argc; ++i) {
    std::string a = argv[i];
    if (a == "--adapt" && i + 1 < argc) adapt_path = argv[++i];
    else if (a == "--model2" && i + 1 < argc) model2 = argv[++i];
    else if (a == "--reranker" && i + 1 < argc) reranker = argv[++i];
    else if (a == "--vocab" && i + 1 < argc) vocab = argv[++i];
    else if (a == "--prefer" && i + 1 < argc) prefer_path = argv[++i];
    else if (a == "--holes" && i + 1 < argc) holes_path = argv[++i];
    else if (a == "--reranker_t9" && i + 1 < argc) reranker_t9 = argv[++i];
    else if (a == "--set" && i + 1 < argc) {
      std::string kv = argv[++i]; auto eq = kv.find('=');
      if (eq == std::string::npos || !p.Set(kv.substr(0, eq), kv.substr(eq + 1))) { std::fprintf(stderr, "bad --set %s\n", kv.c_str()); return 2; }
    }
  }
  auto t0 = std::chrono::steady_clock::now();
  Lexicon lex;
  {
    const uint8_t* mapped = nullptr;
    { std::ifstream probe(argv[1], std::ios::binary); char magic[8] = {0}; probe.read(magic, 8); if (std::memcmp(magic, "CYLX0002", 8) == 0) mapped = MapFile(argv[1]); }
    if (mapped ? !lex.LoadBin(mapped) : !lex.Load(argv[1])) { std::fprintf(stderr, "cannot read lexicon\n"); return 1; }
  }
  if (std::string(argv[2]) == "--compile") {  // cylm LEXICON.tsv --compile OUT.bin
    if (argc < 4 || !lex.Compile(argv[3])) { std::fprintf(stderr, "compile failed\n"); return 1; }
    std::fprintf(stderr, "compiled %zu words\n", lex.WordCount()); return 0;
  }
  C3 base; if (!base.Open(argv[2])) { std::fprintf(stderr, "cannot open model\n"); return 1; }
  Adapt adapt; if (!adapt_path.empty() && !adapt.Load(adapt_path.c_str())) { std::fprintf(stderr, "cannot read adapt\n"); return 1; }
  C3 base2; if (!model2.empty() && !base2.Open(model2.c_str())) { std::fprintf(stderr, "cannot open model2\n"); return 1; }
  Decoder d(lex, base, adapt, p);
  if (!model2.empty()) d.base2 = &base2;
  std::unordered_map<std::string, std::vector<std::string>> prefer;
  if (!prefer_path.empty()) {
    for (auto& one : Split(prefer_path, ',')) if (!LoadPrefer(one.c_str(), prefer)) { std::fprintf(stderr, "cannot read %s\n", one.c_str()); return 1; }
    d.prefer = &prefer;
  }
  std::unordered_set<uint32_t> holes;
  if (!holes_path.empty()) {
    std::ifstream in(holes_path, std::ios::binary); std::string line;
    while (std::getline(in, line)) { auto cps = Utf8Decode(line); if (!cps.empty() && cps[0] > 0x7f) holes.insert(cps[0]); }
    d.holes = &holes;
  }
  Reranker rr;
  if (!reranker.empty()) { if (!rr.Load(reranker.c_str(), vocab.c_str(), p.rr_threads)) { std::fprintf(stderr, "cannot load reranker\n"); return 1; } d.rr = &rr; }
  Reranker rr9;
  if (!reranker_t9.empty()) { if (!rr9.Load(reranker_t9.c_str(), vocab.c_str(), p.rr_threads)) { std::fprintf(stderr, "cannot load nine-key reranker\n"); return 1; } d.rr_t9 = &rr9; }
  std::fprintf(stderr, "loaded in %.1fs: %zu words\n", std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count(), lex.WordCount());
  std::ios::sync_with_stdio(false);
  std::string line;
  while (std::getline(std::cin, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    auto f = Split(line, '\t'); if (f.size() < 4) continue;
    auto t = std::chrono::steady_clock::now();
    std::vector<std::string> cands; std::vector<double> scores;
    auto ranked = f[1] == "choice" ? d.Choice(f[3], f.size() > 4 ? Split(f[4], '|') : std::vector<std::string>{})
                                   : d.Decode(f[2], f[1] == "t9", f[3]);
    for (auto& [w, s] : ranked) { cands.push_back(w); scores.push_back(s); }
    double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
    std::string out = f[0] + "\t" + std::to_string(ms) + "\t";
    for (size_t k = 0; k < cands.size(); ++k) { if (k) out += '|'; out += cands[k]; }
    out += "\t";
    for (size_t k = 0; k < scores.size(); ++k) { if (k) out += '|'; char b[32]; std::snprintf(b, sizeof b, "%.3f", scores[k]); out += b; }
    out += "\t";
    for (size_t k = 0; k < cands.size(); ++k) {  // reading of each candidate: syllables / key spans of its words
      if (k) out += '|';
      auto it = d.last_meta_.find(cands[k]);
      if (it != d.last_meta_.end() && f[1] != "choice") out += it->second.first + "/" + it->second.second;
    }
    std::cout << out << '\n';
    std::cout.flush();
    d.TrimCaches();
  }
  return 0;
}
#endif  // CYLM_LIBRARY
