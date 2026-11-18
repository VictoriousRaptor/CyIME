#include "full_pinyin_tail.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>
#include <vector>
#include <utf8.h>
#include <rime/dict/dictionary.h>
#include <rime/gear/translator_commons.h>
#include "t9_script_options.h"

namespace rime {
namespace {
// Little-endian CYTAIL1, followed by sorted (spelling, preceding character) rows.
struct TailRow {
  uint64_t key;
  std::array<uint16_t, 4> choices;
};
static_assert(sizeof(TailRow) == 16, "tail model format");

const vector<TailRow>& LoadTails(const string& path) {
  static std::mutex mutex;
  static std::map<string, vector<TailRow>> cache;
  std::lock_guard<std::mutex> lock(mutex);
  auto result = cache.try_emplace(path);
  auto& rows = result.first->second;
  if (!result.second) return rows;
  std::ifstream in(path, std::ios::binary | std::ios::ate);
  const auto size = static_cast<std::streamoff>(in.tellg());
  if (size < 8 || size > 8 * 1024 * 1024 || (size - 8) % sizeof(TailRow)) return rows;
  char magic[8];
  in.seekg(0);
  if (!in.read(magic, 8) || std::memcmp(magic, "CYTAIL1", 8)) return rows;
  rows.resize((size - 8) / sizeof(TailRow));
  if (!in.read(reinterpret_cast<char*>(rows.data()), size - 8)) rows.clear();
  return rows;
}

const TailRow* FindTails(const vector<TailRow>& rows, uint64_t key) {
  auto it = std::lower_bound(rows.begin(), rows.end(), key,
      [](const TailRow& row, uint64_t value) { return row.key < value; });
  return it != rows.end() && it->key == key ? &*it : nullptr;
}
}  // namespace

void OfferFullPinyinTails(Dictionary* dict, const string& input,
                         const an<PhraseSyllabifier>& syllabifier,
                         deque<an<Sentence>>* sentences) {
  if (sentences->empty() || sentences->size() >= 3) return;
  const auto lead = sentences->front();
  const auto& words = lead->components();
  if (words.size() < 2 || lead->code().size() < 3 || words.back().code.size() != 1 ||
      lead->end() - lead->start() != input.size() || words.back().text.size() != 3) return;
  const string& prefix = words[words.size() - 2].text;
  if (prefix.size() < 3 || !utf8::is_valid(prefix.end() - 3, prefix.end())) return;
  auto last = prefix.end() - 3;
  const uint32_t previous = utf8::next(last, prefix.end());
  if (previous < 0x4e00 || previous > 0x9fff) return;

  vector<string> readings;
  if (!dict->Decode(lead->code(), &readings) || readings.empty()) return;
  size_t pos = 0;
  for (const auto& spelling : readings) {
    if (pos && pos < input.size() && input[pos] == '\'') ++pos;
    if (input.compare(pos, spelling.size(), spelling) != 0) return;
    pos += spelling.size();
  }
  if (pos != input.size() || readings.back().size() > 6) return;
  uint64_t key = 0;
  for (char c : readings.back()) {
    if (c < 'a' || c > 'z') return;
    key = key * 27 + c - 'a' + 1;
  }
  key <<= 16;
  const auto& rows = LoadTails(T9ScriptOptions::T9ResolveOnce("cyime_tail", ".cyt", "cyime_tail"));
  const string stem = lead->text().substr(0, lead->text().size() - words.back().text.size());
  for (const uint64_t bucket : {key + previous, key}) {
    const TailRow* row = FindTails(rows, bucket);
    if (!row) continue;
    for (uint16_t ch : row->choices) {
      if (ch < 0x4e00 || ch > 0x9fff) continue;
      string text;
      utf8::append(ch, std::back_inserter(text));
      const string complete = stem + text;
      if (std::any_of(sentences->begin(), sentences->end(),
          [&](const auto& sentence) { return sentence->text() == complete; })) continue;
      auto alternate = New<Sentence>(lead->language());
      size_t stop = 0;
      for (size_t i = 0; i + 1 < words.size(); ++i) {
        stop += lead->word_lengths()[i];
        alternate->Extend(words[i], stop, lead->weight());
      }
      DictEntry tail;
      tail.code = words.back().code;
      tail.text = text;
      alternate->Extend(tail, input.size(), lead->weight());
      alternate->Offset(lead->start());
      alternate->set_syllabifier(syllabifier);
      sentences->push_back(alternate);
      if (sentences->size() == 3) return;
    }
  }
}
}  // namespace rime
