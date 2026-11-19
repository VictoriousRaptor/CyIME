#include "t9_filter.h"

#include <algorithm>
#include <cctype>
#include <sstream>
#include <vector>

#include "t9_digit_userdict.h"
#include "t9_log.h"
#include "t9_pinyin_map.h"  // NormalizePinyinComment（声调归一化，统一入口）

#ifndef T9_ALGO_ONLY_BUILD
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/schema.h>
#include <rime/config.h>
#include <rime/common.h>
#include <rime/gear/translator_commons.h>  // Phrase（Phrase 码缓存）
#include "t9_digit_userdict.h"  // 去重集合（T9 用户词文本）
#include "t9_processor.h"  // T9ProcessorRequire / CachePhraseCode（Phrase 码缓存）
#endif

namespace rime {

// ════════════════════════════════════════════════════════════════
// T9 Preedit Converter（无 RIME 依赖，纯字符串算法）
// ════════════════════════════════════════════════════════════════
// 原 t9_preedit_converter.cc 合并至此。Converter 逻辑无任何 RIME 依赖，
// 可在纯算法测试（t9-algo-objs + T9_ALGO_ONLY_BUILD）中独立编译。

// ── UTF-8 辅助 ──

static uint32_t DecodeUtf8(const char*& p, const char* end) {
    if (p >= end) return 0;
    unsigned char c = static_cast<unsigned char>(*p);
    if (c < 0x80) {
        uint32_t cp = c;
        ++p;
        return cp;
    }
    if ((c & 0xE0) == 0xC0) {
        if (p + 1 >= end) { ++p; return 0; }
        uint32_t cp = ((c & 0x1F) << 6) | (static_cast<unsigned char>(p[1]) & 0x3F);
        p += 2;
        return cp;
    }
    if ((c & 0xF0) == 0xE0) {
        if (p + 2 >= end) { ++p; return 0; }
        uint32_t cp = ((c & 0x0F) << 12)
                    | ((static_cast<unsigned char>(p[1]) & 0x3F) << 6)
                    | (static_cast<unsigned char>(p[2]) & 0x3F);
        p += 3;
        return cp;
    }
    if ((c & 0xF8) == 0xF0) {
        if (p + 3 >= end) { ++p; return 0; }
        uint32_t cp = ((c & 0x07) << 18)
                    | ((static_cast<unsigned char>(p[1]) & 0x3F) << 12)
                    | ((static_cast<unsigned char>(p[2]) & 0x3F) << 6)
                    | (static_cast<unsigned char>(p[3]) & 0x3F);
        p += 4;
        return cp;
    }
    ++p;
    return 0;
}

static bool IsChinese(uint32_t cp) {
    return cp >= 0x4E00 && cp <= 0x9FFF;
}

static bool IsDigit(char c) {
    return c >= '0' && c <= '9';
}

// ── 输入分段 ──

struct InputPart {
    std::string text;
    bool is_separator;
    bool is_all_digits;
    bool is_chinese;
};

static std::vector<InputPart> SplitPreedit(const std::string& preedit) {
    std::vector<InputPart> parts;
    const char* p = preedit.c_str();
    const char* end = p + preedit.size();

    std::string buf;
    bool buf_is_chinese = false;
    bool buf_has_digit = false;

    auto flush = [&]() {
        if (!buf.empty()) {
            InputPart part;
            part.text = buf;
            part.is_separator = false;
            part.is_all_digits = buf_has_digit && !buf_is_chinese;
            if (part.is_all_digits) {
                for (char c : buf) {
                    if (!IsDigit(c)) {
                        part.is_all_digits = false;
                        break;
                    }
                }
            }
            part.is_chinese = buf_is_chinese;
            parts.push_back(std::move(part));
            buf.clear();
            buf_is_chinese = false;
            buf_has_digit = false;
        }
    };

    while (p < end) {
        if (*p == ' ' || *p == '\'') {
            flush();
            InputPart sep;
            sep.text = std::string(1, *p);
            sep.is_separator = true;
            sep.is_all_digits = false;
            sep.is_chinese = false;
            parts.push_back(std::move(sep));
            ++p;
            continue;
        }

        const char* prev = p;
        uint32_t cp = DecodeUtf8(p, end);
        if (cp == 0) continue;

        bool is_chi = IsChinese(cp);
        std::string ch = std::string(prev, p);

        if (!buf.empty() && buf_is_chinese != is_chi) {
            flush();
        }

        if (is_chi) buf_is_chinese = true;
        if (cp >= '0' && cp <= '9') buf_has_digit = true;
        buf += ch;
    }
    flush();
    return parts;
}

// ── ConvertPreedit 算法 ──
// 移植自 T9PreeditConverter.kt 的 convertT9PreeditToPinyin()

// A run of two or more keys may span several syllables of the candidate: its preedit keeps the raw keys when
// it comes from a phrase looked up by keys (e.g. 你好吗 after picking ni: "ni'42662"). Each syllable takes
// its full keys, or a prefix of them when typed short (963 = w m d); the syllables are joined by a space like
// Rime's own preedit. Before 2026-11-08 the run took only one syllable: "nihaoma" without separators, and
// "ni hao" for ni'42662 with the last syllable gone. [reserve] syllables stay for the parts after the run; the
// last run must use up the reading. False when the keys and the reading don't line up (the old rule applies).
static bool SpellDigitRun(const std::string& digits, const std::vector<std::string>& pinyin, size_t pi,
                          size_t reserve, std::vector<std::string>* pieces, size_t* next, int* budget) {
    if (pieces->size() > 64 || --*budget < 0) return false;
    if (digits.empty()) {
        const size_t left = pinyin.size() - pi;
        if (reserve == 0 ? left != 0 : left < reserve) return false;
        *next = pi;
        return true;
    }
    if (pi >= pinyin.size() || pinyin.size() - pi <= reserve) return false;
    std::string py = pinyin[pi];
    for (auto& c : py) c = static_cast<char>(tolower(c));
    const std::string code = T9PinyinMap::Instance().PinyinToDigitCode(py).value_or("");
    if (code.empty() || code.size() != py.size()) return false;
    for (size_t take = std::min(code.size(), digits.size()); take > 0; --take) {
        if (digits.compare(0, take, code, 0, take) != 0) continue;
        // One physical key represents one letter, never the digraph zh/ch/sh.
        pieces->push_back(take == code.size() ? py : py.substr(0, take));
        if (SpellDigitRun(digits.substr(take), pinyin, pi + 1, reserve, pieces, next, budget)) return true;
        pieces->pop_back();
    }
    return false;
}

std::string T9ConvertPreedit(const std::string& preedit,
                               const std::string& comment) {
    if (preedit.empty() || comment.empty()) return preedit;

    std::vector<std::string> pinyin_parts;
    {
        std::istringstream iss(comment);
        std::string word;
        while (iss >> word) {
            if (!word.empty()) {
                // 过滤 comment_format 引入的非字母字符（如「」括号），
                // 同时归一化带声调的预组合字符（如带声调方案的 jī huà），
                // 确保 pinyin_parts 只包含纯 ASCII 拼音。
                std::string filtered = NormalizePinyinComment(word);
                if (!filtered.empty()) {
                    pinyin_parts.push_back(filtered);
                }
            }
        }
    }
    if (pinyin_parts.empty()) return preedit;

    bool has_digit_or_separator = false;
    for (char c : preedit) {
        if (IsDigit(c) || c == '\'' || c == ' ') {
            has_digit_or_separator = true;
            break;
        }
    }
    if (!has_digit_or_separator) return preedit;

    auto input_parts = SplitPreedit(preedit);

    size_t pi = 0;
    for (size_t i = 0; i < input_parts.size(); ++i) {
        InputPart& part = input_parts[i];
        if (part.is_separator) {
            part.text = " ";
        } else if (part.is_all_digits) {
            size_t reserve = 0;
            for (size_t j = i + 1; j < input_parts.size(); ++j)
                if (!input_parts[j].is_separator && !input_parts[j].is_chinese) ++reserve;
            std::vector<std::string> pieces;
            size_t next = pi;
            int budget = 4096;
            if (part.text.size() >= 2 &&
                SpellDigitRun(part.text, pinyin_parts, pi, reserve, &pieces, &next, &budget) && pieces.size() >= 2) {
                std::string spelled;
                for (const auto& piece : pieces) spelled += (spelled.empty() ? "" : " ") + piece;
                part.text = spelled;
                pi = next;
            } else if (pi < pinyin_parts.size()) {
                const std::string& py = pinyin_parts[pi];
                bool single_segment_multiple_pinyins =
                    (input_parts.size() == 1 && pinyin_parts.size() > 1);

                if (part.text.size() == 1) {
                    // 单数字段 → 判断是否要触发简拼
                    // 触发简拼条件：是末尾段（最后非分隔符段），或后面紧跟分隔符
                    bool is_last_non_separator = true;
                    bool next_is_separator = false;
                    for (size_t j = i + 1; j < input_parts.size(); ++j) {
                        if (input_parts[j].is_separator) {
                            next_is_separator = true;
                        } else {
                            is_last_non_separator = false;
                            break;
                        }
                    }
                    if (is_last_non_separator || next_is_separator) {
                        // 末尾单数字段或分隔符前的单数字段 → 使用首字母作为简拼
                        // 如 "5" → "j" (从 "jia" 取首字母)
                        // One physical key represents one letter, never the digraph zh/ch/sh.
                        part.text = std::string(1, static_cast<char>(tolower(py[0])));
                    } else {
                        // 中间段单数字 → 使用完整拼音（如 "7公民" 中的 "7"→"shen"）
                        std::string lower = py;
                        for (auto& c : lower) c = static_cast<char>(tolower(c));
                        part.text = lower;
                    }
                } else if (single_segment_multiple_pinyins) {
                    std::string joined;
                    for (const auto& p : pinyin_parts) {
                        std::string lower = p;
                        for (auto& c : lower) c = static_cast<char>(tolower(c));
                        joined += lower;
                    }
                    part.text = joined;
                } else {
                    std::string lower = py;
                    for (auto& c : lower) c = static_cast<char>(tolower(c));
                    if (lower.size() > part.text.size() &&
                        T9PinyinMap::Instance().PinyinToDigitCode(lower).value_or("").compare(0, part.text.size(), part.text) == 0) {
                        lower.resize(part.text.size());
                    }
                    part.text = lower;
                }
                ++pi;
            }
        } else if (part.is_chinese) {
            // 中文 = 已提交文本，原样保留，不消耗拼音索引
        } else {
            ++pi;
        }
    }

    std::string result;
    for (const auto& part : input_parts) {
        result += part.text;
    }
    return result;
}

// ── 候选级 preedit 转换 ──
// 英文九键方案（如 melt_eng_t9，table_translator）候选无拼音注释：
//   - comment 为空 → 直接显示候选词文本（"8378" → "test"）
//   - comment 以 '~' 开头（librime 统一编码后缀标记 '~s'/'~ed'）→ 同上
// 中文九键方案（t9_pinyin，script_translator）候选带拼音注释 → 沿用数字→拼音转换。

std::string T9ConvertCandidatePreedit(const std::string& preedit,
                                      const std::string& comment,
                                      const std::string& candidate_text) {
    // A comment without letters is no reading either: a word from the English list carries its keys
    // (fuck: 3825). Shown as typed keys, the code bar kept the previous key's reading once such a word came
    // first (2026-11-08, learned B站 / fuck).
    if (comment.empty() || comment[0] == '~' ||
        std::none_of(comment.begin(), comment.end(), [](unsigned char c) { return std::isalpha(c); })) {
        return candidate_text;
    }
    return T9ConvertPreedit(preedit, comment);
}

// ── 双拼九键 preedit ──
// 双拼每个字固定两个键：编码段（数字或字母）按两键一字换成注释里的拼音，
// 只按了一个键的字显示它的声母（zh/ch/sh 整体）。中文（已选部分）原样保留。

std::string T9ConvertDoublePinyinPreedit(const std::string& preedit,
                                         const std::string& comment) {
    if (preedit.empty() || comment.empty() || comment[0] == '~') return preedit;
    std::vector<std::string> syllables;
    {
        std::istringstream iss(comment);
        std::string word;
        while (iss >> word) {
            std::string filtered = NormalizePinyinComment(word);
            if (!filtered.empty()) syllables.push_back(filtered);
        }
    }
    if (syllables.empty()) return preedit;

    size_t si = 0;
    std::string result;
    for (const auto& part : SplitPreedit(preedit)) {
        if (part.is_separator) {
            result += ' ';
            continue;
        }
        if (part.is_chinese) {
            result += part.text;
            continue;
        }
        for (size_t i = 0; i < part.text.size(); i += 2) {
            if (si >= syllables.size()) {
                result += part.text.substr(i);
                break;
            }
            std::string py = syllables[si++];
            for (auto& c : py) c = static_cast<char>(tolower(c));
            if (i + 1 < part.text.size()) {
                result += py;
            } else {
                const bool retroflex = py.size() > 2 && py[1] == 'h' &&
                    (py[0] == 'z' || py[0] == 'c' || py[0] == 's');
                result += py.substr(0, retroflex ? 2 : 1);
            }
        }
    }
    return result;
}

// ════════════════════════════════════════════════════════════════
// T9Filter / T9Translation（RIME 依赖）
// ════════════════════════════════════════════════════════════════
// 编译守卫：纯算法测试（T9_ALGO_ONLY_BUILD）仅编译上方 converter，
// 不编译 RIME 依赖的 Filter/Translation 代码。

#ifndef T9_ALGO_ONLY_BUILD

// ── T9Translation ──

void T9Translation::ConvertCurrent() {
    T9_PERF_SCOPED_TIMER("[T9Filter] ConvertCurrent");
    if (!cand_) return;
    auto genuine = Candidate::GetGenuineCandidate(cand_);
    // 缓存 Phrase 真实码（含声调真相）：t9_filter 位于 filters 最前，候选尚为
    // 带调 Phrase；后续 lua filter 链可能重建为非 Phrase，导致右选调频无法从
    // 候选取码。缓存由 T9Processor 在每次 flush 重建、右选时按 (text, 归一化
    // comment) 匹配兜底。
    if (auto phrase = As<Phrase>(genuine)) {
        if (auto* proc = T9ProcessorRequire()) {
            proc->CachePhraseCode(genuine->text(), genuine->comment(), phrase->code());
        }
    }
    if (!convert_preedit_) return;  // 透传：仅缓存，不改 preedit
    // 双拼九键的英文词（无拼音注释）与中文九键一样显示单词本身。
    const std::string& comment = genuine->comment();
    std::string converted = double_pinyin_ && !comment.empty() && comment[0] != '~'
        ? T9ConvertDoublePinyinPreedit(genuine->preedit(), comment)
        : T9ConvertCandidatePreedit(genuine->preedit(), genuine->comment(),
                                    genuine->text());
    T9FLOG("ConvertCurrent: \"%s\" -> \"%s\"",
          genuine->preedit().c_str(), converted.c_str());
    if (converted != genuine->preedit()) {
        cand_ = New<T9PreeditCandidate>(cand_, converted);
    }
}

T9Translation::T9Translation(an<Translation> translation,
                               char auto_delim,
                               char manual_delim,
                               bool convert_preedit,
                               std::shared_ptr<T9SuppressionStore> suppression,
                               std::string schema,
                               bool double_pinyin)
    : translation_(translation),
      auto_delim_(auto_delim),
      manual_delim_(manual_delim),
      convert_preedit_(convert_preedit),
      double_pinyin_(double_pinyin),
      suppression_(std::move(suppression)),
      schema_(std::move(schema)) {
    // 定位到第一个候选（构造时 translation 已定位在第一个候选）。
    Advance();
}

bool T9Translation::Next() {
    if (exhausted()) return false;
    if (!pending_.empty()) {
        pending_.pop_front();
        if (!pending_.empty()) {
            cand_ = pending_.front();
            ConvertCurrent();
            return true;
        }
        // 补出的候选给完了，接着给原来停在那里的候选（还没从 translation_ 取走）。
        Advance();
        return !exhausted();
    }
    translation_->Next();
    Advance();
    return !exhausted();
}

void T9Translation::Advance() {
    while (!translation_->exhausted()) {
        cand_ = translation_->Peek();
        if (cand_ && suppression_ && suppression_->Contains(schema_, cand_->text())) {
            translation_->Next();
            continue;
        }
        // 拂动固定的字母：只保留该位置拼音字母一致的候选。
        if (cand_) {
            const size_t end = cand_->end();
            if (longest_end_ == 0) longest_end_ = end;
            if (auto* proc = T9ProcessorRequire()) {
                if (proc->HasLetterConstraints()) {
                    auto genuine = Candidate::GetGenuineCandidate(cand_);
                    if (genuine && !proc->MatchesLetterConstraints(genuine->start(), genuine->end(), genuine->comment())) {
                        if (end >= longest_end_ && !longest_shown_ && fallback_.size() < 3) fallback_.push_back(cand_);
                        translation_->Next();
                        continue;
                    }
                }
            }
            if (end < longest_end_ && !longest_shown_ && !fallback_.empty()) {
                // 最长一档一个都没对上：先给出它被拦下的前几个，当前这个短候选留在 translation_ 里稍后再给。
                longest_shown_ = true;
                pending_.assign(fallback_.begin(), fallback_.end());
                fallback_.clear();
                cand_ = pending_.front();
                ConvertCurrent();
                return;
            }
            if (end >= longest_end_) longest_shown_ = true;
        }
        ConvertCurrent();
        return;
    }
    if (!longest_shown_ && !fallback_.empty()) {
        longest_shown_ = true;
        pending_.assign(fallback_.begin(), fallback_.end());
        fallback_.clear();
        cand_ = pending_.front();
        ConvertCurrent();
        return;
    }
    cand_ = nullptr;
    set_exhausted(true);
}

// ── T9Filter ──

T9Filter::T9Filter(const Ticket& ticket) : Filter(ticket) {
    if (auto* schema = ticket.schema) {
        schema_ = schema->schema_id();
        suppression_ = GetT9SuppressionStore();
        if (auto* config = schema->config()) {
            bool display_original = false;
            config->GetBool("t9/isDisplayOriginalPreedit", &display_original);
            convert_preedit_ = !display_original;
            config->GetBool("t9/double_pinyin", &double_pinyin_);

            std::string delimiter;
            if (config->GetString("speller/delimiter", &delimiter)
                && delimiter.size() >= 2) {
                auto_delimiter_ = delimiter[0];
                manual_delimiter_ = delimiter[1];
            }
        }
    }
}

an<Translation> T9Filter::Apply(an<Translation> translation,
                                 CandidateList* candidates) {
    T9_PERF_SCOPED_TIMER("[T9Filter] Apply");
    if (!translation) return translation;
    // 去重由 filter 链末尾的 uniquifier 兜底，t9_filter 只做 preedit 转换。
    return New<T9Translation>(translation, auto_delimiter_, manual_delimiter_,
                              convert_preedit_, suppression_, schema_,
                              double_pinyin_);
}

#endif  // T9_ALGO_ONLY_BUILD

}  // namespace rime
