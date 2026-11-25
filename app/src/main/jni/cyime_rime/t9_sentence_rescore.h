// 26-key sentence choice (t9/sentence_rescore): Rime makes translator/max_sentences sentences as usual
// and the word model only picks which one comes first (pinyin26-word-model-20261018.md).
#ifndef CYIME_T9_SENTENCE_RESCORE_H_
#define CYIME_T9_SENTENCE_RESCORE_H_

#include <rime/common.h>
#include <rime/gear/poet.h>

#include "t9_script_query.h"

class T9NgramModel;

namespace rime {

class Sentence;

// Leaves one sentence in `sentences`: the word model's choice among Rime's n-best made without
// corrected words, followed by Rime's best when that one is a correction. `sentence_graph` is the
// word graph the n-best came from; without it no second n-best is made. `keys_per_syllable`
// (t9/rescore_keys_per_syllable) counts syllables by keys for double pinyin, whose keys say nothing
// about vowels; 0 counts the vowel groups of pinyin letters. `rime_weight` (t9/rescore_rime_weight) adds that
// share of Rime's own sentence weight to the word model's score, so the model overrules Rime's order only by a
// clear margin (youfaxyigewenti: Rime ranks 又发现一个问题 first, the model alone took 有发现一个问题).
// `tail_correction_margin` (t9/tail_correction_margin): a sentence corrected only in its last word of three letters
// or more leads when the typed letters do not read in full and it beats the typed choice by this margin (0: off).
// `slip_margin` (t9/slip_correction_margin): a corrected sentence leads a typed one that reads in full when it
// beats it by this margin for every corrected syllable (0: off).
// `slip_only` (t9/slip_whole_words, 14 keys): a corrected sentence leads only by `slip_margin`; the stray-initials
// rule below, which lets the best corrected sentence lead without any margin, is off (a key covers two letters, so
// nearly every key sequence has stray initials: 刚准确 led by 当众侮辱).
// `initials_choices` (t9/initials_choices): input typed as initials only (wmjs) used to keep the dictionary's order and
// made no sentence when a dictionary word had those initials, so a common combination the dictionary lacks or counts
// low (我们就是, 需要多久) never led. With a count above 0 the n-best (whole words and sentences alike) is ordered by
// the same score as full sentences and that many lead the menu.
// `initials_margin` (t9/initials_lead_margin): the first choice of the old order (`initials_first`, the dictionary's
// first word with these initials; Rime's first sentence when empty) stays first unless another candidate beats it by
// this margin (0: the highest score always leads).
// `runners_up` (t9/menu_sentences): the typed sentences in the order of the computed score, best first (Rime's own
// order and weights without the word model), each with how far its score is below the best one (ln units), for a
// menu that lists more than one. The caller leaves out what it already lists.
// `built` (t9/model_sentences): whole sentences the caller built with the word model, typed out in full. The best
// of them leads when its score beats the typed choice's, and the typed choice follows it. `built_rime_weight`
// (t9/model_sentences_rime_weight) adds that share of Rime's own sentence weight to both sides of this comparison
// only.
// `stray_decided`: set when the typed letters hold stray initials and the corrected sentence was weighed against the
// typed choice by t9/stray_correction_margin (whichever leads), so the caller does not put Rime's best first again.
// `built_fuzzy` (beside `built`, t9/model_sentences_fuzzy_penalty): the syllables of each built sentence read only
// through a fuzzy spelling; each costs `fuzzy_penalty`. When such a sentence leads, the one that leads without them
// follows it, before the typed choice.
// T9InitialsOnly: every letter of `input` is a consonant (no key of it can be a whole syllable of pinyin letters).
bool T9InitialsOnly(const string& input);

void T9RescoreSentences(const T9ScriptQuery& q, const T9NgramModel* ngram, bool ngram_sentence_start,
                        size_t keys_per_syllable, double rime_weight, double tail_correction_margin,
                        double slip_margin, int initials_choices, double initials_margin,
                        const string& initials_first, double near_penalty, Poet* poet,
                        const WordGraph* sentence_graph, int max_sentences, double cutoff,
                        const an<Sentence>& exact_sentence, const an<Sentence>& rime_best,
                        deque<an<Sentence>>* sentences, bool slip_only = false,
                        vector<std::pair<double, an<Sentence>>>* runners_up = nullptr,
                        const deque<an<Sentence>>* built = nullptr, double built_rime_weight = 0.0,
                        bool* stray_decided = nullptr, const vector<int>* built_fuzzy = nullptr,
                        double fuzzy_penalty = 0.0);

// 14-key: a whole-input word read through the initial of the final merged key (都是 for DOUA, the A key read
// as s) normally follows the sentence of the typed letters (都啊). With the word model it goes first when its
// text is more likely than that sentence's by more than `margin` (ln units; t9/merged_tail_word_margin), and
// the sentence follows it. `word` is the best such whole-input word.
void T9PreferMergedTailWord(const T9ScriptQuery& q, const T9NgramModel* ngram, double margin,
                            const an<DictEntry>& word, deque<an<Sentence>>* sentences);

}  // namespace rime

#endif  // CYIME_T9_SENTENCE_RESCORE_H_
