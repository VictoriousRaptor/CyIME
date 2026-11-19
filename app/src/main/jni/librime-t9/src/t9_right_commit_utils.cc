#include "t9_right_commit_utils.h"

#include <algorithm>
#include <cctype>
#include <functional>

#include "t9_log.h"

namespace rime {

std::vector<std::string> ParseSyllables(const std::string& comment) {
    std::vector<std::string> syllables;
    size_t pos = 0;
    while (pos < comment.size()) {
        // 跳过空白
        while (pos < comment.size() &&
               (comment[pos] == ' ' || comment[pos] == '\t' ||
                comment[pos] == '\'' || comment[pos] == '\n' ||
                comment[pos] == '\r')) {
            ++pos;
        }
        if (pos >= comment.size()) break;

        // 读取一个音节
        size_t start = pos;
        while (pos < comment.size() &&
               comment[pos] != ' ' && comment[pos] != '\t' &&
               comment[pos] != '\'' && comment[pos] != '\n' &&
               comment[pos] != '\r') {
            ++pos;
        }
        std::string syl = comment.substr(start, pos - start);

        // 仅保留包含字母的音节
        bool has_letter = false;
        for (char c : syl) {
            if (std::isalpha(static_cast<unsigned char>(c))) {
                has_letter = true;
                break;
            }
        }
        if (has_letter) {
            syllables.push_back(syl);
        }
    }
    return syllables;
}

ConsumedResult ComputeConsumedDigitsFromPinyin(
    const std::string& segment,
    const std::optional<std::string>& candidate_pinyin) {
    const auto& pmap = T9PinyinMap::Instance();
    ConsumedResult result;

    if (candidate_pinyin.has_value() && !candidate_pinyin->empty()) {
        auto syllables = ParseSyllables(*candidate_pinyin);
        if (!syllables.empty()) {
            int consumed = 0;
            std::string remaining = segment;
            ConsumedResultStatus status = ConsumedResultStatus::kFullMatch;
            for (const auto& syl : syllables) {
                auto syl_code_opt = pmap.PinyinToDigitCode(syl);
                if (!syl_code_opt.has_value()) {
                    RCLOG(">> ComputeConsumed: syl='%s' → nullopt, break", syl.c_str());
                    break;
                }
                const std::string& syl_code = *syl_code_opt;

                if (remaining.size() >= syl_code.size() &&
                    remaining.compare(0, syl_code.size(), syl_code) == 0) {
                    // 完全匹配
                    consumed += static_cast<int>(syl_code.size());
                    remaining = remaining.substr(syl_code.size());
                    RCLOG(">> ComputeConsumed: syl='%s' code='%s' FULL_MATCH consumed=%d remaining='%s'",
                        syl.c_str(), syl_code.c_str(), consumed, remaining.c_str());
                } else {
                    // 前缀匹配：声母场景，用户只输入了音节首字母对应的数字
                    int match_len = 0;
                    int max_len = std::min(static_cast<int>(syl_code.size()),
                                           static_cast<int>(remaining.size()));
                    for (int len = 1; len <= max_len; ++len) {
                        if (remaining.compare(0, len, syl_code, 0, len) == 0) {
                            match_len = len;
                        }
                    }
                    if (match_len > 0) {
                        consumed += match_len;
                        remaining = remaining.substr(match_len);
                        status = ConsumedResultStatus::kPartialMatch;
                        RCLOG(">> ComputeConsumed: syl='%s' code='%s' PREFIX_MATCH matchLen=%d consumed=%d remaining='%s'",
                            syl.c_str(), syl_code.c_str(), match_len, consumed, remaining.c_str());
                    } else {
                        RCLOG(">> ComputeConsumed: syl='%s' code='%s' NO_MATCH remaining='%s', break",
                            syl.c_str(), syl_code.c_str(), remaining.c_str());
                        break;
                    }
                }
            }
            RCLOG(">> ComputeConsumed: total=%d, segment='%s'", consumed, segment.c_str());
            if (consumed > 0) {
                result.consumed_digits = consumed;
                result.status = status;
                return result;
            }
            // 多音节候选词首个音节都不匹配时返回 NoMatch（consumed_digits=0）。
            // 调用方（如 apostrophe fallback）通过 ConsumedResultStatus::kNoMatch
            // 显式判断是否需要提取额外音节重新匹配。
            if (syllables.size() > 1) {
                result.consumed_digits = 0;
                result.status = ConsumedResultStatus::kNoMatch;
                return result;
            }
        }

        // 回退①：字母数代理
        int letter_count = 0;
        for (char c : *candidate_pinyin) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                ++letter_count;
            }
        }
        if (letter_count > 0 && letter_count <= static_cast<int>(segment.size())) {
            result.consumed_digits = letter_count;
            result.status = ConsumedResultStatus::kPartialMatch;
            return result;
        }
    }

    // 回退②：贪婪最长匹配
    auto options = pmap.FirstSyllableOptions(segment, 1);
    if (!options.empty()) {
        result.consumed_digits = options[0].digit_length;
        result.status = ConsumedResultStatus::kPartialMatch;
        return result;
    }
    return result;
}

bool IsFullCommitByJianpinAlignment(const std::string& selected_pinyin,
                                     const std::vector<std::string>& comment_syllables) {
    if (comment_syllables.empty()) return false;

    const auto& pmap = T9PinyinMap::Instance();
    auto buffer_digits_opt = pmap.PinyinToDigitCode(selected_pinyin);
    if (!buffer_digits_opt.has_value()) return false;
    const std::string& buffer_digits = *buffer_digits_opt;

    // 条件1：候选音节数 == buffer 数字码位数
    if (static_cast<int>(comment_syllables.size()) !=
        static_cast<int>(buffer_digits.size())) {
        return false;
    }

    // 条件2：每个音节首字母数字码 == buffer 对应位数字
    for (size_t i = 0; i < comment_syllables.size(); ++i) {
        auto syl_code_opt = pmap.PinyinToDigitCode(comment_syllables[i]);
        if (!syl_code_opt.has_value()) return false;
        if (syl_code_opt->empty()) return false;
        if ((*syl_code_opt)[0] != buffer_digits[i]) return false;
    }
    return true;
}

bool IsAllSelectedConsumed(const std::string& input_buffer,
                            const std::vector<std::string>& comment_syllables,
                            const std::vector<SyllableOption>& selection_history) {
    if (selection_history.empty()) return false;

    // 检查 selectionHistory 拼接 == inputBuffer
    std::string joined;
    for (const auto& sel : selection_history) {
        joined += sel.pinyin;
    }
    if (joined != input_buffer) return false;

    // 检查音节数 == selectionHistory 数
    if (comment_syllables.size() != selection_history.size()) return false;

    // 逐音节数字码对齐
    const auto& pmap = T9PinyinMap::Instance();
    for (size_t i = 0; i < comment_syllables.size(); ++i) {
        auto syl_code = pmap.PinyinToDigitCode(comment_syllables[i]);
        auto sel_code = pmap.PinyinToDigitCode(selection_history[i].pinyin);
        if (!syl_code.has_value() || !sel_code.has_value()) return false;
        if (*syl_code != *sel_code) return false;
    }
    return true;
}

bool ShouldFullCommitInSelection(int consumed_from_non_selected,
                                  int non_selected_pinyin_length,
                                  const std::string& remaining_after_commit,
                                  const std::optional<std::string>& candidate_pinyin,
                                  const std::string& selected_pinyin) {
    if (consumed_from_non_selected < non_selected_pinyin_length) return false;
    if (!remaining_after_commit.empty()) return false;

    auto comment_syllables = candidate_pinyin.has_value()
        ? ParseSyllables(*candidate_pinyin)
        : std::vector<std::string>{};

    const auto& pmap = T9PinyinMap::Instance();
    for (const auto& syl : comment_syllables) {
        if (pmap.AreDigitCodesMatching(syl, selected_pinyin)) {
            return true;
        }
    }
    return false;
}

namespace {

void SplitInitial(const std::string& syl, std::string* initial, std::string* fin) {
    static const char* kTwo[] = {"zh", "ch", "sh"};
    for (const char* two : kTwo) {
        if (syl.compare(0, 2, two) == 0) {
            *initial = two;
            *fin = syl.substr(2);
            return;
        }
    }
    static const std::string kOne = "bpmfdtnlgkhjqxrzcsyw";
    if (!syl.empty() && kOne.find(syl[0]) != std::string::npos) {
        *initial = syl.substr(0, 1);
        *fin = syl.substr(1);
        return;
    }
    initial->clear();
    *fin = syl;
}

std::vector<std::string> InitialSwaps(const std::string& initial) {
    if (initial == "z") return {"zh"};
    if (initial == "zh") return {"z"};
    if (initial == "c") return {"ch"};
    if (initial == "ch") return {"c"};
    if (initial == "s") return {"sh"};
    if (initial == "sh") return {"s"};
    if (initial == "n") return {"l"};
    if (initial == "l") return {"n", "r"};
    if (initial == "r") return {"l"};
    if (initial == "f") return {"h"};
    if (initial == "h") return {"f"};
    return {};
}

bool EndsWith(const std::string& s, const std::string& tail) {
    return s.size() >= tail.size() &&
           s.compare(s.size() - tail.size(), tail.size(), tail) == 0;
}

std::vector<std::string> FinalSwaps(const std::string& fin) {
    // ang/eng/ing（含 iang/uang）去 g；an/en/in（含 ian/uan）加 g。
    if (EndsWith(fin, "ang") || EndsWith(fin, "eng") || EndsWith(fin, "ing")) {
        return {fin.substr(0, fin.size() - 1)};
    }
    if (EndsWith(fin, "an") || EndsWith(fin, "en") || EndsWith(fin, "in")) {
        return {fin + "g"};
    }
    return {};
}

bool StartsWith(const std::string& s, const std::string& head) {
    return s.size() >= head.size() && s.compare(0, head.size(), head) == 0;
}

std::string JoinSyllables(const std::vector<std::string>& syllables) {
    std::string out;
    for (const auto& syl : syllables) {
        if (!out.empty()) out += ' ';
        out += syl;
    }
    return out;
}

}  // namespace

std::vector<std::string> FuzzyPinyinVariants(const std::string& syllable) {
    std::vector<std::string> out;
    std::string initial, fin;
    SplitInitial(syllable, &initial, &fin);
    std::vector<std::string> initials = {initial};
    for (auto& i : InitialSwaps(initial)) initials.push_back(i);
    std::vector<std::string> finals = {fin};
    for (auto& f : FinalSwaps(fin)) finals.push_back(f);
    const auto& pmap = T9PinyinMap::Instance();
    for (const auto& i : initials) {
        for (const auto& f : finals) {
            std::string v = i + f;
            if (v == syllable) continue;
            if (!pmap.PinyinToDigitCode(v).has_value()) continue;
            if (std::find(out.begin(), out.end(), v) == out.end()) out.push_back(v);
        }
    }
    return out;
}

std::optional<std::string> AlignFuzzyReadingToTyped(
    const std::optional<std::string>& candidate_pinyin,
    const std::vector<SyllableOption>& selections,
    const std::string& unassigned) {
    if (!candidate_pinyin.has_value() || candidate_pinyin->empty()) {
        return candidate_pinyin;
    }
    auto syllables = ParseSyllables(*candidate_pinyin);
    if (syllables.empty()) return candidate_pinyin;
    for (const auto& syl : syllables) {
        for (char ch : syl) {
            if (!std::islower(static_cast<unsigned char>(ch))) return candidate_pinyin;
        }
    }

    const auto& pmap = T9PinyinMap::Instance();
    bool changed = false;
    size_t i = 0;
    for (; i < syllables.size() && i < selections.size(); ++i) {
        const std::string& typed = selections[i].pinyin;
        if (typed.empty() || syllables[i] == typed) continue;
        const auto variants = FuzzyPinyinVariants(syllables[i]);
        if (std::find(variants.begin(), variants.end(), typed) != variants.end()) {
            syllables[i] = typed;  // 左栏选的完整音节（ying）和读音（yin）互为模糊音
            changed = true;
            continue;
        }
        if (StartsWith(syllables[i], typed)) continue;  // 只选了声母等前半截
        std::string best;
        for (const auto& v : variants) {
            if (StartsWith(v, typed)) { best = v; break; }
        }
        if (best.empty()) return candidate_pinyin;  // 对不上不是模糊音造成的，不动
        syllables[i] = best;
        changed = true;
    }

    // 其余音节对 unassigned：逐音节找能接上的拼法（原读音优先），
    // 一条路走不通时回头换前面音节的变体（yin 本身也能接上 946，但接完后 xiao 对不上）。
    // 取对上音节最多、替换最少的一种。
    if (i < syllables.size() && !unassigned.empty()) {
        struct Best { size_t matched = 0; int swaps = 0; std::vector<std::string> picks; };
        Best best;
        std::vector<std::string> picks;
        std::function<void(size_t, size_t, int)> search =
            [&](size_t k, size_t pos, int swaps) {
                size_t matched = k - i;
                if (matched > best.matched || (matched == best.matched && swaps < best.swaps)) {
                    best = {matched, swaps, picks};
                }
                if (k >= syllables.size() || pos >= unassigned.size()) return;
                const std::string rest = unassigned.substr(pos);
                std::vector<std::string> options{syllables[k]};
                for (auto& v : FuzzyPinyinVariants(syllables[k])) options.push_back(v);
                for (size_t o = 0; o < options.size(); ++o) {
                    auto code = pmap.PinyinToDigitCode(options[o]);
                    if (!code.has_value() || code->empty()) continue;
                    size_t used = 0;
                    if (StartsWith(rest, *code)) used = code->size();
                    else if (StartsWith(*code, rest)) used = rest.size();  // 只打了前半截
                    if (used == 0) continue;
                    picks.push_back(options[o]);
                    search(k + 1, pos + used, swaps + (o == 0 ? 0 : 1));
                    picks.pop_back();
                }
            };
        search(i, 0, 0);
        for (size_t n = 0; n < best.picks.size(); ++n) {
            if (syllables[i + n] != best.picks[n]) {
                syllables[i + n] = best.picks[n];
                changed = true;
            }
        }
    }

    if (!changed) return candidate_pinyin;
    return JoinSyllables(syllables);
}

}  // namespace rime
