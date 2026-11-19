/* CyLM C API: shared by the Windows Core and the Android JNI bridge (docs/CYLM_PHONE_DESIGN.md).
 * Build the library by compiling cylm.cpp with -DCYLM_LIBRARY. Models are memory-mapped; one CylmModel can serve
 * many sessions (one per text field). A session keeps the incremental decoding state of its last input: keep one
 * session per text field for its whole life and pass the whole key string on every keystroke with the same context;
 * a new session, a changed context or a changed mode makes the next call decode from scratch (several times slower). */
#ifndef CYLM_H
#define CYLM_H
#ifdef __cplusplus
extern "C" {
#endif

typedef struct CylmModel CylmModel;
typedef struct CylmSession CylmSession;

/* lexicon: compiled lexicon (cylm LEXICON.tsv --compile OUT.bin); model2 may be NULL;
 * params: "mix=0.8;mu=0.04;eos=0.6;nbest=16;abbrev_full=-4". Returns NULL on failure. */
CylmModel* cylm_open(const char* lexicon, const char* model, const char* model2, const char* params);
/* As cylm_open, plus a reranker: reranker = ONNX file from tools/cylm/nn/export_onnx.py (int8 recommended),
 * vocab = its character table (vocab.txt: one token per line, ids in line order). params may add
 * "rr_gamma=0.8;rr_short=0.1;rr_threads=4". The reranker runs inside cylm_decode (same keystroke). Needs a build
 * with -DCYLM_ORT linked against onnxruntime; without it cylm_open2 with a reranker returns NULL. */
CylmModel* cylm_open2(const char* lexicon, const char* model, const char* model2, const char* reranker, const char* vocab,
                      const char* params);
/* The user's own preferences, applied after ranking: a TSV of "P<TAB>D" rows (the user types P rather than its
 * homophone D, or reported it); a P ranked below its D moves to just above D. Call before cylm_session. Returns the
 * number of D entries, or -1. */
int cylm_prefer(CylmModel* model, const char* path);
/* Characters missing from the second (chat) model's corpus, one per line: words containing them are scored by the
 * main model alone. Call before cylm_session. Returns the number of characters, or -1. */
int cylm_holes(CylmModel* model, const char* path);
/* The user's own word layer: trigram counts "a\tb\tw\tcount" (tools/cylm, the private adapt.counts.tsv), mixed in
 * by the adapt_add or adapt_lambda parameter. Private data: only for the user's own devices. Call before
 * cylm_session. Returns the number of words, or -1. */
int cylm_adapt(CylmModel* model, const char* path);
/* Learning, on the device. cylm_learn: add a committed text to the user's layer (count per occurrence; 0 = 1), in
 * effect from the next decode; the layer keeps at most adapt_cap trigram types (params, default 100000), older counts
 * decay past that. Returns the number of words learned. cylm_add_word: a word the user chose ("我的词"), typed by
 * its pinyin ("zi mu"), on top of the compiled lexicon; weight 0 = as common as a frequent word. Returns 0 or -1.
 * cylm_adapt_save: write the layer back (the file cylm_adapt reads); returns the number of trigrams or -1.
 * None of these may overlap a decode on any session of the model: call them from the decoding thread. */
int cylm_learn(CylmModel* model, const char* text, double count);
int cylm_add_word(CylmModel* model, const char* word, const char* pinyin, long long weight);
int cylm_adapt_save(CylmModel* model, const char* path);
/* Long-press 忘记: takes what cylm_learn learned from `text` out of the user's layer, so the word or sentence goes
 * back to the model's own order. Text of one word: every trigram with that word goes. Longer text: every trigram
 * with one of its words of 2+ characters, and the text's own trigrams (as cylm_learn adds them). Returns the number
 * of trigram types taken out, or -1. Not during a decode, as cylm_learn. */
int cylm_forget(CylmModel* model, const char* text);
/* 1 when the word is in CyLM's lexicon (the user's words included), else 0. The hook keeps Rime's sentence when one
 * of its words (2+ characters) is not: CyLM would only spell that word out of single characters. */
int cylm_has_word(CylmModel* model, const char* word);
/* The replace gate's character model (a small reranker, e.g. rs int8; needs a -DCYLM_ORT build): load it once, then
 * cylm_gate_score gives ln P(text </s>) for one sentence (Rime's and CyLM's best, before deciding to replace). It is not
 * used by the decode itself. Returns -1 / 0.0 without it. Call from the decoding thread. */
int cylm_gate_load(CylmModel* model, const char* model_path, const char* vocab);
double cylm_gate_score(CylmModel* model, const char* text);
/* A separate reranker for nine-key input (the full tier uses a larger one there). Call before cylm_session. */
int cylm_reranker_t9(CylmModel* model, const char* reranker, const char* vocab);
CylmSession* cylm_session(CylmModel* model);
/* keys: pinyin letters (full, initials or mixed, "'" separates) or nine-key digits; mode 0 = pinyin, 1 = nine-key;
 * context: text before the caret (UTF-8, may be NULL). Candidates are written to out as UTF-8 strings each ended
 * by '\0', best first. Returns the number of candidates, or -1 on bad arguments. */
int cylm_decode(CylmSession* session, const char* keys, int mode, const char* context, char* out, int cap);
/* As cylm_decode, but each record is "text\x1fpinyin\x1fspans\x1fscore\0" (score: the ranking score, higher is
 * better; the differences between candidates are log-probability ratios): pinyin = the candidate's syllables separated by
 * spaces ("bei xian qi le"; for initials typed as abbreviations, the full syllable of the chosen reading), spans = the
 * key range [start-end) of each word in the input, comma separated ("0-3,3-7,7-9,9-11"), which maps nine-key digits
 * and pinyin letters alike back to the keys the user pressed. Use the pinyin as the Rime code when committing. */
int cylm_decode2(CylmSession* session, const char* keys, int mode, const char* context, char* out, int cap);
/* Stops a cylm_decode / cylm_decode2 running on this session in another thread (the only call that may overlap one):
 * it returns -2 with no candidates at its next check, and the session keeps its state of the input's common prefix
 * with the previous one, so the next decode is still incremental. Decodes started after the call are not affected. */
void cylm_cancel(CylmSession* session);
void cylm_close_session(CylmSession* session);
void cylm_close(CylmModel* model);

#ifdef __cplusplus
}
#endif
#endif
