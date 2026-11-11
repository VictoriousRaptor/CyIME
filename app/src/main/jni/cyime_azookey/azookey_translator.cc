// Japanese kana-kanji conversion by azooKey (libCyAzooKey.so, loaded at runtime).
// Rime keeps the romaji, segments, menu and selection; this translator only converts.
// Schema: translators: [..., cyime_azookey_translator]. Dictionary: <user_data_dir>/azookey/dictionary,
// learning: <user_data_dir>/azookey/memory. The library is the APK's own copy (editions with models),
// else the one the Japanese plugin downloaded (path in CYIME_AZOOKEY_LIBRARY, set by JapanesePack).
// Without it (not downloaded yet, or 32-bit x86, which the Swift SDK does not target) the translator
// gives nothing and the schema's echo shows the romaji.
#include <dlfcn.h>
#include <unistd.h>
#include <cstdlib>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>
#include <rime/candidate.h>
#include <rime/common.h>
#include <rime/component.h>
#include <rime/composition.h>
#include <rime/context.h>
#include <rime/deployer.h>
#include <rime/engine.h>
#include <rime/module.h>
#include <rime/registry.h>
#include <rime/segmentation.h>
#include <rime/service.h>
#include <rime/translation.h>
#include <rime/translator.h>
#include <rime_api.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace rime {
namespace {

// Also written to logcat (tag CyIME-azooKey): release builds cannot read the Rime log files.
void Report(const string& message) {
  LOG(WARNING) << message;
#ifdef __ANDROID__
  __android_log_print(ANDROID_LOG_WARN, "CyIME-azooKey", "%s", message.c_str());
#endif
}

struct AzooKeyApi {
  int32_t (*open)(const char*, const char*) = nullptr;
  char* (*query)(const char*, int32_t, int32_t, int32_t) = nullptr;
  void (*learn)(int32_t) = nullptr;
  void (*end)(int32_t) = nullptr;
  void (*release)(char*) = nullptr;
  bool loaded = false;
  bool broken = false;   // a library without the cyakkc_* functions; never retried
  bool reported = false; // "not available" is logged once, not every retry
  std::atomic<bool> ready{false};
  std::chrono::steady_clock::time_point next_open;
};

// The APK's copy first (found by name in the app's library directory), then the downloaded one.
void* OpenLibrary(string& error) {
  if (void* lib = dlopen("libCyAzooKey.so", RTLD_NOW | RTLD_LOCAL)) return lib;
  const char* reason = dlerror();
  error = reason ? reason : "?";
  const char* downloaded = getenv("CYIME_AZOOKEY_LIBRARY");
  if (!downloaded || !*downloaded || access(downloaded, R_OK) != 0) return nullptr;
  if (void* lib = dlopen(downloaded, RTLD_NOW | RTLD_LOCAL)) return lib;
  reason = dlerror();
  error = string(downloaded) + ": " + (reason ? reason : "?");
  return nullptr;
}

// Loading the library and opening the dictionary are both retried (at most every 5 s): the Japanese
// plugin may still be downloading them, or the app unpacking the dictionary, when the first key arrives.
AzooKeyApi& Api() {
  static AzooKeyApi api;
  static std::mutex open_mutex;
  if (api.ready) return api;
  std::lock_guard<std::mutex> lock(open_mutex);
  auto now = std::chrono::steady_clock::now();
  if (api.ready || api.broken || now < api.next_open) return api;
  api.next_open = now + std::chrono::seconds(5);
  if (!api.loaded) {
    string error;
    void* lib = OpenLibrary(error);
    if (!lib) {
      if (!api.reported) Report("azooKey library not available: " + error);
      api.reported = true;
      return api;
    }
    api.open = reinterpret_cast<int32_t (*)(const char*, const char*)>(dlsym(lib, "cyakkc_open"));
    api.query = reinterpret_cast<char* (*)(const char*, int32_t, int32_t, int32_t)>(dlsym(lib, "cyakkc_query"));
    api.learn = reinterpret_cast<void (*)(int32_t)>(dlsym(lib, "cyakkc_learn"));
    api.end = reinterpret_cast<void (*)(int32_t)>(dlsym(lib, "cyakkc_end"));
    api.release = reinterpret_cast<void (*)(char*)>(dlsym(lib, "cyakkc_free"));
    api.loaded = api.open && api.query && api.learn && api.end && api.release;
    if (!api.loaded) {
      api.broken = true;
      Report("azooKey library lacks the cyakkc_* functions");
      return api;
    }
  }
  path base = Service::instance().deployer().user_data_dir / "azookey";
  string dictionary = (base / "dictionary").string(), memory = (base / "memory").string();
  api.ready = api.open(dictionary.c_str(), memory.c_str()) == 0;
  if (api.ready) {
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "CyIME-azooKey", "azooKey ready: %s", dictionary.c_str());
#endif
  } else {
    Report("azooKey dictionary missing or unreadable: " + dictionary);
  }
  return api;
}

// Minimal reader for the library's JSON: [[text, consumed, reading, id], ...].
struct Row {
  string text;
  size_t consumed = 0;
  string reading;
  int32_t id = 0;
};

bool ReadString(const string& s, size_t& i, string& out) {
  if (i >= s.size() || s[i] != '"') return false;
  for (++i; i < s.size(); ++i) {
    char c = s[i];
    if (c == '"') { ++i; return true; }
    if (c != '\\') { out += c; continue; }
    if (++i >= s.size()) return false;
    switch (s[i]) {
      case 'n': out += '\n'; break;
      case 'r': out += '\r'; break;
      case 't': out += '\t'; break;
      case 'u': {
        if (i + 4 >= s.size()) return false;
        out += static_cast<char>(std::stoi(s.substr(i + 1, 4), nullptr, 16));
        i += 4;
        break;
      }
      default: out += s[i];
    }
  }
  return false;
}

bool ReadInt(const string& s, size_t& i, long& out) {
  size_t start = i;
  if (i < s.size() && s[i] == '-') ++i;
  while (i < s.size() && isdigit(static_cast<unsigned char>(s[i]))) ++i;
  if (i == start) return false;
  out = std::stol(s.substr(start, i - start));
  return true;
}

std::vector<Row> ParseRows(const string& s) {
  std::vector<Row> rows;
  size_t i = 0;
  auto skip = [&](char c) {
    while (i < s.size() && (s[i] == ' ' || s[i] == '\n')) ++i;
    if (i < s.size() && s[i] == c) { ++i; return true; }
    return false;
  };
  if (!skip('[')) return rows;
  while (skip('[')) {
    Row row;
    long consumed = 0, id = 0;
    if (!ReadString(s, i, row.text) || !skip(',') || !ReadInt(s, i, consumed) || !skip(',') ||
        !ReadString(s, i, row.reading) || !skip(',') || !ReadInt(s, i, id) || !skip(']'))
      break;
    row.consumed = static_cast<size_t>(consumed);
    row.id = static_cast<int32_t>(id);
    rows.push_back(std::move(row));
    if (!skip(',')) break;
  }
  return rows;
}

// Hiragana to katakana (U+3041..U+3096 -> U+30A1..U+30F6); other characters are kept.
string ToKatakana(const string& hiragana) {
  string out;
  for (size_t i = 0; i < hiragana.size();) {
    unsigned char c = hiragana[i];
    size_t len = c < 0x80 ? 1 : c < 0xE0 ? 2 : c < 0xF0 ? 3 : 4;
    if (len == 3 && i + 2 < hiragana.size()) {
      uint32_t cp = ((c & 0x0F) << 12) | ((hiragana[i + 1] & 0x3F) << 6) | (hiragana[i + 2] & 0x3F);
      if (cp >= 0x3041 && cp <= 0x3096) cp += 0x60;
      out += static_cast<char>(0xE0 | (cp >> 12));
      out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
      out += static_cast<char>(0x80 | (cp & 0x3F));
    } else {
      out.append(hiragana, i, len);
    }
    i += len;
  }
  return out;
}

class AzooKeyCandidate : public SimpleCandidate {
 public:
  AzooKeyCandidate(size_t start, size_t end, const string& text, const string& preedit, int32_t id)
      : SimpleCandidate("azookey", start, end, text, "", preedit), id_(id) {}
  int32_t id() const { return id_; }

 private:
  int32_t id_;
};

class AzooKeyTranslator : public Translator {
 public:
  explicit AzooKeyTranslator(const Ticket& ticket) : Translator(ticket) {
    if (!engine_) return;
    Context* context = engine_->context();
    commit_connection_ = context->commit_notifier().connect([this](Context* ctx) { OnCommit(ctx); });
    update_connection_ = context->update_notifier().connect([this](Context* ctx) {
      if (!ctx->IsComposing() && composing_) { composing_ = false; EndComposition(false); }
    });
  }
  ~AzooKeyTranslator() override {
    commit_connection_.disconnect();
    update_connection_.disconnect();
  }

  an<Translation> Query(const string& input, const Segment& segment) override {
    if (!segment.HasTag("abc")) return nullptr;
    // Rime passes this segment's own input (not the whole composition).
    const string& code = input;
    if (code.empty()) return nullptr;
    // Uppercase romaji asks for katakana (jaroomaji convention); "l" is the long-vowel key like "-",
    // unless the keyboard has its own long-vowel key (option _cyime_l_letter): then l is a letter (la -> ぁ).
    bool katakana = false;
    const bool l_long_vowel = !engine_->context()->get_option("_cyime_l_letter");
    string romaji;
    for (char c : code) {
      if (isupper(static_cast<unsigned char>(c))) katakana = true;
      char lower = static_cast<char>(tolower(static_cast<unsigned char>(c)));
      // azooKey's romaji table has no hyphen: send the long-vowel mark itself (one character either way).
      if ((lower == 'l' && l_long_vowel) || lower == '-') romaji += "\u30fc"; else romaji += lower;
    }
    AzooKeyApi& api = Api();
    if (!api.ready) return nullptr;
    composing_ = true;
    bool learning = !engine_->context()->get_option("_cyime_incognito");
    char* raw = api.query(romaji.c_str(), 60, 1, learning ? 1 : 0);
    std::vector<Row> rows = ParseRows(raw ? raw : "[]");
    if (raw) api.release(raw);
    if (rows.empty()) return nullptr;
    auto translation = New<FifoTranslation>();
    if (katakana) {
      const string kana = ToKatakana(rows.front().reading);
      translation->Append(New<SimpleCandidate>("azookey", segment.start, segment.end, kana, "", kana));
    }
    for (const Row& row : rows) {
      size_t consumed = std::min(row.consumed, code.size());
      if (consumed == 0) continue;
      translation->Append(New<AzooKeyCandidate>(segment.start, segment.start + consumed, row.text, row.reading, row.id));
    }
    return translation;
  }

 private:
  void OnCommit(Context* ctx) {
    if (!composing_) return;
    composing_ = false;
    bool learning = !ctx->get_option("_cyime_incognito");
    AzooKeyApi& api = Api();
    if (learning && api.ready) {
      for (const Segment& seg : ctx->composition()) {
        if (auto cand = As<AzooKeyCandidate>(Candidate::GetGenuineCandidate(seg.GetSelectedCandidate())))
          api.learn(cand->id());
      }
    }
    EndComposition(learning);
  }
  void EndComposition(bool save) {
    AzooKeyApi& api = Api();
    if (api.ready) api.end(save ? 1 : 0);
  }

  bool composing_ = false;
  connection commit_connection_;
  connection update_connection_;
};

}  // namespace
}  // namespace rime

static void rime_cyime_azookey_initialize() {
  rime::Registry::instance().Register("cyime_azookey_translator",
                                      new rime::Component<rime::AzooKeyTranslator>);
}
static void rime_cyime_azookey_finalize() {}

RIME_REGISTER_MODULE(cyime_azookey)
