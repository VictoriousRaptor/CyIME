#pragma once
// 固定候选 (pinned candidates): words the user pinned for a code. A pin either goes to the front (first pinned
// first) or stays at a fixed place in the list (固定在第 N 位); words that are not pinned keep their learned
// order in the places left.
// Shared by CyIME Android (app/src/main/jni/librime_jni) and CyIME Windows (third_party/cyime-engine/policy);
// keep both copies identical. Storage: <Rime user dir>/cyime_pins.txt, one pin per line,
// "code<TAB>text<TAB>pinned at (Unix seconds)[<TAB>place]"; no place (or 0) = front. Lines starting with # are
// comments. A reader without places reads "time<TAB>place" as the time and treats the pin as a front pin.
#include <string>
#include <vector>

namespace rime { class Context; }

namespace cyime::pins {
// Highest place a pin can be fixed at: three pages of nine.
constexpr int kMaxPlace = 27;

struct Entry { std::string code; std::string text; int place = 0; };  // place 0 = front

// Loads the pins and registers the "cyime_pins" filter. librime's engine appends that filter after every
// schema's own filters (engine.cc, InitializeComponents), so no schema lists it. Call once after Rime is
// initialized, before sessions are created.
void Initialize(const std::string& user_dir);
// The code a pin belongs to: a segment's input without the ' and space delimiters.
std::string NormalizeCode(const std::string& input);
// The code of the segment whose candidates are shown; empty when nothing is being composed.
std::string ActiveCode(rime::Context* context);
// Texts pinned for a code (front and fixed places), first pinned first.
std::vector<std::string> Pinned(const std::string& code);
bool IsPinned(const std::string& code, const std::string& text);
// -1 not pinned, 0 pinned to the front, N fixed at place N.
int PinnedPlace(const std::string& code, const std::string& text);
// 1-based place of `text` in the menu of the active code as it is shown now; 0 when not among the first 200.
int MenuPlace(rime::Context* context, const std::string& text);
// Pins (only a text the menu of the active code offers) to the front or unpins `text` for the active code,
// saves the file and refreshes the menu. True when the pin state now is the requested one.
bool SetPinned(rime::Context* context, const std::string& text, bool pinned);
// Same, fixing `text` at `place` (1..kMaxPlace) of the active code's menu. Another pin of the code at that place is
// removed: one place holds one word, so the place shown for a pin is where it is.
bool SetPinnedAt(rime::Context* context, const std::string& text, int place);
// For the settings page (no composition): pin `text` for `code` at `place` (0 = front, else 1..kMaxPlace),
// or unpin it (a place taken by another pin is replaced, as SetPinnedAt). The menu is not checked; a pin whose
// text the code never offers does nothing.
bool PinCode(const std::string& code, const std::string& text, int place);
bool UnpinCode(const std::string& code, const std::string& text);
// Every pin, by code, first pinned first.
std::vector<Entry> All();

namespace detail {
struct Placed { std::string text; int place = 0; };
// The new order of the first texts.size() candidates: indexes into `texts`. Pins (in pin order) found in
// `texts` take their place: front pins first, then everything else in its own order, then each fixed pin is
// put at its place (lowest place first; a place past the end goes last; of two pins at one place the later pinned
// keeps it and the other goes right after). Later copies of a pinned text are dropped.
std::vector<size_t> Arrange(const std::vector<std::string>& texts, const std::vector<Placed>& pins);
}  // namespace detail
}  // namespace cyime::pins
