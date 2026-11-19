// CyLM's best sentence in place of Rime's first sentence (cylm/* in the schema; off by default).
#ifndef CYIME_T9_CYLM_SENTENCE_H_
#define CYIME_T9_CYLM_SENTENCE_H_

#include <cstddef>

#include <cylm.h>
#include <rime/common.h>

namespace rime {

class Dictionary;
class Language;
class PhraseSyllabifier;
class Sentence;

// Words keep their places. With its pinyin (cylm_decode2) the sentence is one dictionary entry of
// `language`, learned like a typed phrase; a syllable the dictionary does not know leaves it as plain
// text that is not learned. An optional extra long sentence requires an exact, learnable full spelling.
void T9ApplyCylmSentence(CylmModel* model, bool offer_alternative, const string& input, const string& left_context,
                         Dictionary* dict, const Language* language, size_t start,
                         const an<PhraseSyllabifier>& syllabifier, deque<an<Sentence>>* sentences);

// How long a key waits for CyLM (schema cylm/budget_ms); a slower result is not shown for that key.
void T9CylmSetBudget(int ms);
// Keep Rime's sentence when one of its words is unknown to CyLM (schema cylm/guard_unknown_words).
void T9CylmSetGuardUnknownWords(bool on);
// Loads the replace gate's character model (schema cylm/gate + cylm/gate_vocab); the hook then uses its rule.
bool T9CylmGate(CylmModel* model, const string& gate, const string& vocab);
// False after `adb shell setprop debug.cyime.cylm 0` (developer switch); CyLM is then not even opened.
bool T9CylmEnabled();
// Loads a newly opened model's pages on the CyLM thread, off the key path.
void T9CylmWarm(CylmModel* model);
// What the user commits teaches CyLM (cylm_learn on the CyLM thread); the layer is saved to the LearnInto file
// every 20 texts and read back from it when the model opens.
void T9CylmLearn(CylmModel* model, const string& text);
void T9CylmLearnInto(CylmModel* model, const string& file);
// Long-press 忘记: takes `text` out of every model's learned layer (cylm_forget), queued commits of it included, and
// saves the layer. Waits for a running decode at most `wait_ms`. Returns the trigram types taken out, -1 when CyLM
// was busy past the wait (nothing taken out).
int T9CylmForget(const string& text, int wait_ms);
// The user's own words in the Rime user directory `dir` go to CyLM (cylm_add_word); rechecked when files change.
void T9CylmSyncWords(CylmModel* model, const string& dir);
// Starts CyLM on this key's input while Rime builds its own sentence (called when the translation is created).
void T9CylmPrefetch(CylmModel* model, const string& input, const string& left_context);

}  // namespace rime

#endif  // CYIME_T9_CYLM_SENTENCE_H_
