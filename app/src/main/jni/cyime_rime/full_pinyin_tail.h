#pragma once

#include <rime/common.h>

namespace rime {
class Dictionary;
class Sentence;
class PhraseSyllabifier;

// Add at most two whole-input alternatives to an exact sentence ending in one character.
void OfferFullPinyinTails(Dictionary* dict, const string& input,
                         const an<PhraseSyllabifier>& syllabifier,
                         deque<an<Sentence>>* sentences);
}  // namespace rime
