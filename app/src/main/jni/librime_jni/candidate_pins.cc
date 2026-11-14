#include "candidate_pins.h"
#include <algorithm>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <map>
#include <mutex>
#include <set>
#include <rime/candidate.h>
#include <rime/component.h>
#include <rime/composition.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/filter.h>
#include <rime/menu.h>
#include <rime/registry.h>
#include <rime/segmentation.h>
#include <rime/translation.h>

namespace cyime::pins {
namespace {
using namespace rime;

constexpr char kHeader[] = "# CyIME pinned candidates 1: code<TAB>text<TAB>pinned at (Unix seconds)[<TAB>place]";
// How far down the list a pinned word is looked for. The menu of a code rarely goes this deep.
constexpr size_t kScan = 200;
constexpr size_t kMaxPins = 5000, kMaxCode = 64, kMaxText = 256;

struct Pin { std::string text; long long time = 0; int place = 0; };

std::mutex mutex;
std::map<std::string, std::vector<Pin>> pins;  // code -> pins, first pinned first
std::vector<std::string> unreadable;           // lines kept as they are, never dropped by a save
std::filesystem::path file;
bool writable = false;
size_t count = 0;

bool Valid(const std::string& code, const std::string& text) {
  return !code.empty() && code.size() <= kMaxCode && !text.empty() && text.size() <= kMaxText &&
         code.find_first_of("\t\r\n") == std::string::npos && text.find_first_of("\t\r\n") == std::string::npos;
}

void Load() {
  pins.clear();
  unreadable.clear();
  count = 0;
  std::error_code error;
  const bool exists = std::filesystem::exists(file, error);
  std::ifstream in(file, std::ios::binary);
  // A file that exists but cannot be read is never overwritten.
  writable = !exists || static_cast<bool>(in);
  if (!in) return;
  std::string line;
  while (std::getline(in, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    if (line.empty() || line == kHeader) continue;
    if (line.front() == '#') { unreadable.push_back(line); continue; }
    const auto first = line.find('\t');
    const auto second = first == std::string::npos ? first : line.find('\t', first + 1);
    const auto code = line.substr(0, first);
    const auto text = first == std::string::npos ? std::string() :
        line.substr(first + 1, second == std::string::npos ? std::string::npos : second - first - 1);
    long long time = 0;
    int place = 0;
    if (second != std::string::npos) {
      const auto third = line.find('\t', second + 1);
      try {
        time = std::stoll(line.substr(second + 1, third == std::string::npos ? std::string::npos : third - second - 1));
        if (third != std::string::npos) place = std::stoi(line.substr(third + 1));
      } catch (...) { time = -1; }
    }
    auto& list = pins[NormalizeCode(code)];
    if (!Valid(code, text) || time < 0 || place < 0 || place > kMaxPlace || count >= kMaxPins ||
        std::any_of(list.begin(), list.end(), [&](const Pin& pin) { return pin.text == text; })) {
      if (list.empty()) pins.erase(NormalizeCode(code));
      unreadable.push_back(line);
      continue;
    }
    list.push_back({text, time, place});
    ++count;
  }
  for (auto& [code, list] : pins)
    std::stable_sort(list.begin(), list.end(), [](const Pin& a, const Pin& b) { return a.time < b.time; });
}

bool Save() {
  if (!writable || file.empty()) return false;
  auto pending = file;
  pending += ".tmp";
  {
    std::ofstream out(pending, std::ios::binary | std::ios::trunc);
    if (!out) return false;
    out << kHeader << '\n';
    for (const auto& line : unreadable) out << line << '\n';
    for (const auto& [code, list] : pins)
      for (const auto& pin : list) {
        out << code << '\t' << pin.text << '\t' << pin.time;
        if (pin.place > 0) out << '\t' << pin.place;  // front pins keep the old three columns
        out << '\n';
      }
    out.flush();
    if (!out) return false;
  }
  std::error_code error;
  std::filesystem::rename(pending, file, error);  // replaces the old file on Windows too
  return !error;
}

// The menu of a code with pins: the first candidates are read (until every pin is found and every fixed
// place is filled, at most kScan) and put in order by detail::Arrange; the rest follow as they come, without
// another copy of a pinned word. Words not offered for this input are not added.
class PinTranslation : public Translation {
 public:
  PinTranslation(an<Translation> source, const std::vector<detail::Placed>& pins) : source_(source) {
    size_t needed = 0;  // candidates to read so that the highest fixed place exists
    for (const auto& pin : pins) needed = std::max(needed, static_cast<size_t>(pin.place));
    std::set<std::string> wanted;
    for (const auto& pin : pins) wanted.insert(pin.text);
    CandidateList scanned;
    std::vector<std::string> texts;
    std::set<std::string> found;
    // Non-pinned candidates read, so that `needed` counts places the pins do not take.
    size_t others = 0;
    while (scanned.size() < kScan && !source_->exhausted() &&
           (found.size() < wanted.size() || others + found.size() < needed)) {
      auto candidate = source_->Peek();
      source_->Next();
      if (!candidate) continue;
      if (wanted.count(candidate->text())) found.insert(candidate->text()); else ++others;
      scanned.push_back(candidate);
      texts.push_back(candidate->text());
    }
    for (const auto index : detail::Arrange(texts, pins)) buffered_.push_back(scanned[index]);
    moved_ = found;
    Advance();
  }
  bool Next() override {
    if (exhausted()) return false;
    Advance();
    return !exhausted();
  }
  an<Candidate> Peek() override { return current_; }

 private:
  void Advance() {
    current_.reset();
    if (position_ < buffered_.size()) {
      current_ = buffered_[position_++];
      return;
    }
    while (!source_->exhausted()) {
      auto candidate = source_->Peek();
      source_->Next();
      // A pinned word already placed is not offered a second time further down.
      if (!candidate || moved_.count(candidate->text())) continue;
      current_ = candidate;
      return;
    }
    set_exhausted(true);
  }
  an<Translation> source_;
  CandidateList buffered_;
  size_t position_ = 0;
  std::set<std::string> moved_;
  an<Candidate> current_;
};

std::vector<detail::Placed> PinsOf(const std::string& code) {
  std::vector<detail::Placed> placed;
  std::lock_guard<std::mutex> lock(mutex);
  const auto found = pins.find(code);
  if (found == pins.end()) return placed;
  for (const auto& pin : found->second) placed.push_back({pin.text, pin.place});
  return placed;
}

class PinFilter : public Filter {
 public:
  explicit PinFilter(const Ticket& ticket) : Filter(ticket) {}
  // The engine applies a filter right after asking whether it applies to the segment.
  bool AppliesToSegment(Segment* segment) override {
    pinned_.clear();
    if (!engine_ || !segment || !engine_->context()) return false;
    const auto& input = engine_->context()->composition().input();
    if (segment->end <= segment->start || segment->end > input.size()) return false;
    pinned_ = PinsOf(NormalizeCode(input.substr(segment->start, segment->end - segment->start)));
    return !pinned_.empty();
  }
  an<Translation> Apply(an<Translation> translation, CandidateList*) override {
    if (pinned_.empty() || !translation) return translation;
    return New<PinTranslation>(translation, pinned_);
  }

 private:
  std::vector<detail::Placed> pinned_;
};

// Adds, moves (place changed) or removes the pin of `text` for `code` and saves. `place` -1 removes.
// True when the pins now are as requested.
bool Change(const std::string& code, const std::string& text, int place) {
  if (!Valid(code, text) || place < -1 || place > kMaxPlace) return false;
  std::lock_guard<std::mutex> lock(mutex);
  auto& list = pins[code];
  const auto existing = std::find_if(list.begin(), list.end(), [&](const Pin& pin) { return pin.text == text; });
  const bool unchanged = place < 0 ? existing == list.end() : existing != list.end() && existing->place == place;
  if (unchanged) {
    if (list.empty()) pins.erase(code);
    return true;
  }
  const auto before = list;
  const auto countBefore = count;
  if (existing != list.end()) { list.erase(existing); --count; }
  // One place holds one word: a word fixed at a place another pin of this code holds replaces that pin, which
  // goes back to its learned order. Pushing it one place down instead (as before) made both cards wrong: the new
  // word showed 「第 2 位」 at 3, and the words fixed below it moved down one each.
  if (place > 0) {
    const auto size = list.size();
    list.erase(std::remove_if(list.begin(), list.end(), [&](const Pin& pin) { return pin.place == place; }), list.end());
    count -= size - list.size();
  }
  if (place >= 0) {
    if (count >= kMaxPins) { list = before; count = countBefore; if (list.empty()) pins.erase(code); return false; }
    // Later than every pin of this code, so a clock set back still appends.
    long long time = static_cast<long long>(std::time(nullptr));
    for (const auto& pin : list) time = std::max(time, pin.time);
    list.push_back({text, time, place});
    ++count;
  }
  if (list.empty()) pins.erase(code);
  if (!Save()) {
    if (before.empty()) pins.erase(code); else pins[code] = before;
    count = countBefore;
    return false;
  }
  return true;
}
}  // namespace

void Initialize(const std::string& user_dir) {
  {
    std::lock_guard<std::mutex> lock(mutex);
    file = std::filesystem::u8path(user_dir) / "cyime_pins.txt";
    Load();
  }
  Registry::instance().Register("cyime_pins", new Component<PinFilter>());
}

std::string NormalizeCode(const std::string& input) {
  std::string code;
  for (const char c : input)
    if (c != '\'' && c != ' ') code.push_back(c);
  return code;
}

std::string ActiveCode(Context* context) {
  if (!context || context->composition().empty()) return {};
  const auto& composition = context->composition();
  const auto& segment = composition.back();
  const auto& input = composition.input();
  if (segment.end <= segment.start || segment.end > input.size()) return {};
  return NormalizeCode(input.substr(segment.start, segment.end - segment.start));
}

std::vector<std::string> Pinned(const std::string& code) {
  std::vector<std::string> texts;
  std::lock_guard<std::mutex> lock(mutex);
  const auto found = pins.find(code);
  if (found == pins.end()) return texts;
  for (const auto& pin : found->second) texts.push_back(pin.text);
  return texts;
}

bool IsPinned(const std::string& code, const std::string& text) {
  std::lock_guard<std::mutex> lock(mutex);
  const auto found = pins.find(code);
  return found != pins.end() && std::any_of(found->second.begin(), found->second.end(),
      [&](const Pin& pin) { return pin.text == text; });
}

int PinnedPlace(const std::string& code, const std::string& text) {
  std::lock_guard<std::mutex> lock(mutex);
  const auto found = pins.find(code);
  if (found == pins.end()) return -1;
  for (const auto& pin : found->second)
    if (pin.text == text) return pin.place;
  return -1;
}

int MenuPlace(Context* context, const std::string& text) {
  if (!context || context->composition().empty() || text.empty()) return 0;
  const auto& menu = context->composition().back().menu;
  for (size_t i = 0; menu && i < kScan; ++i) {
    const auto candidate = menu->GetCandidateAt(i);
    if (!candidate) break;
    if (candidate->text() == text) return static_cast<int>(i) + 1;
  }
  return 0;
}

namespace {
bool SetActive(Context* context, const std::string& text, int place) {
  const auto code = ActiveCode(context);
  if (!Valid(code, text)) return false;
  if (place >= 0 && MenuPlace(context, text) == 0) return false;
  if (!Change(code, text, place)) return false;
  context->RefreshNonConfirmedComposition();
  return true;
}
}  // namespace

bool SetPinned(Context* context, const std::string& text, bool pinned) {
  return SetActive(context, text, pinned ? 0 : -1);
}

bool SetPinnedAt(Context* context, const std::string& text, int place) {
  return place >= 1 && SetActive(context, text, place);
}

bool PinCode(const std::string& code, const std::string& text, int place) {
  return place >= 0 && Change(NormalizeCode(code), text, place);
}

bool UnpinCode(const std::string& code, const std::string& text) {
  return Change(NormalizeCode(code), text, -1);
}

std::vector<Entry> All() {
  std::vector<Entry> entries;
  std::lock_guard<std::mutex> lock(mutex);
  for (const auto& [code, list] : pins)
    for (const auto& pin : list) entries.push_back({code, pin.text, pin.place});
  return entries;
}

namespace detail {
std::vector<size_t> Arrange(const std::vector<std::string>& texts, const std::vector<Placed>& pins) {
  std::vector<size_t> at(pins.size(), texts.size());  // where each pin's text first appears
  std::set<std::string> pinned;
  for (size_t p = 0; p < pins.size(); ++p) {
    if (!pinned.insert(pins[p].text).second) continue;  // a text pinned twice: the first pin counts
    const auto found = std::find(texts.begin(), texts.end(), pins[p].text);
    if (found != texts.end()) at[p] = found - texts.begin();
  }
  std::vector<size_t> order;
  for (size_t p = 0; p < pins.size(); ++p)
    if (at[p] < texts.size() && pins[p].place == 0) order.push_back(at[p]);
  for (size_t i = 0; i < texts.size(); ++i)
    if (!pinned.count(texts[i])) order.push_back(i);
  std::vector<size_t> fixed;
  for (size_t p = 0; p < pins.size(); ++p)
    if (at[p] < texts.size() && pins[p].place > 0) fixed.push_back(p);
  // Lowest place first; two pins at one place (a file written before Change replaced such a pin): the later pin
  // keeps it, as it would replace the earlier one now, and the earlier one goes right after.
  std::stable_sort(fixed.begin(), fixed.end(), [&](size_t a, size_t b) {
    return pins[a].place != pins[b].place ? pins[a].place < pins[b].place : a > b;
  });
  size_t next = 0;
  for (const auto p : fixed) {
    const auto slot = std::min(std::max(static_cast<size_t>(pins[p].place - 1), next), order.size());
    order.insert(order.begin() + slot, at[p]);
    next = slot + 1;
  }
  return order;
}
}  // namespace detail
}  // namespace cyime::pins
