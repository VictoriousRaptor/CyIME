// Nine-key joint decoder (t9/joint_decoder): dictionary and user words of every span in one beam,
// scored by the word n-gram model (or dictionary weight + grammar without it). Formerly
// cmake/T9JointDecoder.inc, compiled inside Rime's script translator.
#include "t9_joint_decoder.h"

#include <algorithm>
#include <cmath>
#include <deque>
#include <limits>
#include <tuple>
#include <unordered_map>

#include <boost/algorithm/string/join.hpp>
#include <gram_db.h>
#include <gram_encoding.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/prism.h>
#include <rime/dict/user_dictionary.h>
#include <rime/gear/grammar.h>
#include <rime/gear/poet.h>
#include <rime/gear/script_translator.h>
#include <rime/gear/translator_commons.h>
#include <t9_candidate_pool.h>
#include <t9_decode_cache.h>
#include <t9_lookup_trace.h>
#include <t9_ngram_model.h>
#include <t9_sentence_scorer.h>
#include <t9_suppression.h>
#include <utf8.h>

#include "t9_script_options.h"

namespace rime {

// System graphs and versioned, detached decoded results have separate caches.
namespace {
struct T9Line {
  an<T9Line> previous;
  an<DictEntry> entry;
  size_t end = 0;
  double score = 0;
  double prior = -1;
  double matched_length = 0;
  string text;
  Code code;
  int32_t lm_id = ::T9NgramModel::kUnseen;    // last model word, history of the next word
  int32_t lm_prev = ::T9NgramModel::kUnseen;  // the model word before it
};

static double T9WholeTextPrior(GramDb* db, const string& text) {
  if (!db || utf8::distance(text.begin(), text.end()) > grammar::kMaxEncodedUnicode) return -1;
  T9_WORK_COUNT(whole_text_priors); T9_WORK_COUNT(gram_lookups);
  const auto encoded = grammar::encode(text);
  GramDb::Match matches[GramDb::kMaxResults];
  const auto size = db->Lookup("", encoded, matches);
  for (int i = 0; i < size; ++i)
    if (matches[i].length == encoded.size()) return matches[i].value;
  return -1;
}


// wanted(end): false leaves that end's bucket unread (its entries are never built).
template <class QueryResult, class Wanted>
static void T9Collect(map<int, DictEntryList>& destination,
                      const an<QueryResult>& result, size_t budget, Wanted wanted) {
  if (!result) return;
  for (auto& end : *result) {
    if (!wanted(end.first)) continue;
    auto& entries = destination[end.first];
    // Bound each source independently, before the source-neutral merge.
    for (size_t n = 0; n < budget && !end.second.exhausted(); ++n) {
      entries.push_back(end.second.Peek());
      end.second.Next();
    }
  }
}
template <class QueryResult>
static void T9Collect(map<int, DictEntryList>& destination,
                      const an<QueryResult>& result, size_t budget) {
  T9Collect(destination, result, budget, [](int) { return true; });
}
}  // namespace

// displaced: the model's first sentence when a learned word took its place (t9/ngram_user_input_weight). Only one
// sentence is shown before the dictionary words, which list every learned word of the spelling first, so it is shown
// right behind (9464 after picking 鹰: 行 fell from first to ninth, behind 英 银行 信号 星 赢 影 音高).
struct T9Path { Sentence sentence; double prior; double coverage; bool displaced = false; };
struct T9JointFrame {
  EdgeMap edges;
  size_t total = 0, bytes = 0;
  WordGraph merged;
  map<int, size_t> system_reach, user_reach, omitted_end;
  map<size_t, vector<an<T9Line>>> states;
  // Learned user-dictionary words from the first key on, by end (t9/ngram_user_input_weight).
  map<int, DictEntryList> learned_whole;
};
struct T9JointCache {
  T9DecodeCache<vector<T9Path>> results;
  deque<T9JointFrame> frames;
  size_t frame_bytes = 0;
  uint32_t dict_checksum = 0, schema_checksum = 0;
  uint64_t user_revision = 0, user_tick = 0;
  const UserDictionary* user_dict = nullptr;
  string preceding;
  hash_set<string> blacklist;
  using Options = std::tuple<size_t, size_t, size_t, size_t, size_t,
                            double, double, double, string, bool, bool, bool,
                            const void*, double, bool, double, bool, double, double, double>;
  Options options;
};

namespace {
size_t T9EntryBytes(const DictEntry& entry) {
  return sizeof(DictEntry) + entry.text.capacity() + entry.comment.capacity() +
         entry.preedit.capacity() + entry.custom_code.capacity() +
         entry.code.capacity() * sizeof(SyllableId);
}
size_t T9SentenceBytes(const Sentence& sentence) {
  // Outer Sentence storage is counted by the vector. Count each component
  // object once; double-counting its slab evicts short rollback prefixes early.
  size_t bytes = T9EntryBytes(sentence.entry()) + 32 +
                 sentence.word_lengths().capacity() * sizeof(size_t) +
                 (sentence.components().capacity() - sentence.components().size()) * sizeof(DictEntry);
  for (const auto& entry : sentence.components()) bytes += T9EntryBytes(entry);
  return bytes;
}
}  // namespace

// Tail pass (t9/tail_completion_weight): words looked at for each start, and lines extended from each start.
constexpr size_t kTailWordsPerStart = 4;
constexpr size_t kTailLinesPerStart = 8;
// A completed word from the first key needs this many syllables: 一段时间 for yi duan shi jia. 2026-11-25 two (was
// three, so 时间 never came for 史嘉): 54684 jin ti showed 近体 and 546842 吉姆哈 while 今天 was being typed, 94432
// 直达 for 知道; chat replay: 27 typing states turn to the word being typed, no first choice after the last key
// changes, +0.004% lookups. 常用词被冷门词压住 (他：「这个必须进107修好」).
constexpr size_t kTailFirstWordSyllables = 2;

bool T9DecodeJoint(const T9ScriptQuery& q, const T9ScriptOptions& o, bool (*is_numeric_input)(const string&),
                   Dictionary* dict, UserDictionary* user_dict, deque<an<Sentence>>* sentences) {
  const auto& syllables = q.syllables;
  const size_t total = syllables.interpreted_length;
  if (!total) return false;
  T9_WORK_COUNT(joint_decodes);
  const auto& input = q.input;
  const bool single_t9_key = input.size() == 1 &&
                             input.front() >= '2' && input.front() <= '9';
  // A first key is still ambiguous between several initials and a complete
  // vowel. Soften only the extra sentence-level abbreviation cost; dictionary
  // credibility and the full-spelling candidate pool keep their normal role.
  const double spelling_penalty = o.t9_spelling_penalty_ * (single_t9_key ? 0.75 : 1.0);
  const string preceding = q.translator->GetPrecedingText(q.start);
  // Decoded results also depend on the committed text before the composition (n-gram history).
  // With neighbour correction the syllable graph also depends on the measured key geometry.
  const string cache_context = preceding + '\x1f' + o.t9_left_context_ +
      (q.enable_correction ? '\x1f' + o.t9_neighbors_ : string());
  T9JointCache* cache = nullptr;
  // Rebuild native objects with this query's syllabifier/spans. Neither Menu
  // indices nor mutable Phrase entries from a previous query are reused.
  auto restore = [&](const vector<T9Path>& paths) {
    vector<string> texts;
    for (const auto& path : paths) texts.push_back(preceding + path.sentence.text());
    vector<double> neural;
    const bool ready = T9RequestSentenceScores(o.t9_sentence_model_, texts, &neural);
    const bool apply = T9RefiningSentences() && ready && neural.size() == paths.size() &&
        std::all_of(neural.begin(), neural.end(), [](double n) { return std::isfinite(n); });
    vector<std::pair<size_t, double>> order;
    for (size_t i = 0; i < paths.size(); ++i)
      order.emplace_back(i, paths[i].sentence.entry().weight + (apply ? o.t9_sentence_weight_ * neural[i] : 0));
    std::sort(order.begin(), order.end(), [&](const auto& a, const auto& b) {
      if (a.second != b.second) return a.second > b.second;
      const auto& x = paths[a.first]; const auto& y = paths[b.first];
      if (x.prior != y.prior) return x.prior > y.prior;
      if (x.sentence.text() != y.sentence.text()) return x.sentence.text() < y.sentence.text();
      return x.sentence.code() < y.sentence.code();
    });
    // One key alone (t9/single_key_initial_margin): the word model ranks a lone 3 by how a message starts or
    // by the preceding word (额 at a sentence start, 饿 after 就), and the initial reading pays the
    // abbreviation penalty, so 的 came second. A character the key starts as an initial goes first when its
    // corpus frequency is that many ln units above the vowel's (only 的 for 3: 你 for 6 and 不 for 2 are
    // within 20 times of 哦 and 啊); the vowel sentence is shown right behind it.
    size_t demoted = order.size();
    if (single_t9_key && o.t9_ngram_ && o.t9_single_key_initial_margin_ > 0 && order.size() > 1) {
      auto lone = [&](const T9Path& path, bool* initial) {
        const auto& parts = path.sentence.components();
        if (parts.size() != 1) return -std::numeric_limits<double>::infinity();
        *initial = parts.front().quality_len < 1.0;
        const int32_t id = o.t9_ngram_->Id(parts.front().text);
        return id == ::T9NgramModel::kUnseen ? -std::numeric_limits<double>::infinity()
                                             : o.t9_ngram_->Start(id, false);
      };
      bool initial = true;
      const double vowel = lone(paths[order.front().first], &initial);
      if (!initial && std::isfinite(vowel)) {
        size_t pick = 0;
        double best = vowel + o.t9_single_key_initial_margin_;
        for (size_t i = 1; i < order.size(); ++i) {
          bool abbreviated = false;
          const double frequency = lone(paths[order[i].first], &abbreviated);
          if (abbreviated && frequency >= best) { best = frequency; pick = i; }
        }
        if (pick) {
          auto item = order[pick];
          item.second = std::max(item.second, order.front().second);
          order.erase(order.begin() + pick);
          order.insert(order.begin(), item);
          demoted = 1;
        }
      }
    }
    set<string> displayed;
#ifndef T9_ALGO_ONLY_BUILD
    // Sentences the user deleted (long press) are skipped here, so the next best one takes the slot.
    const auto suppressed = GetT9SuppressionStore();
#endif
    // The words before a sentence-final particle (礼貌 in 礼貌吗), or "" when the sentence does not end in one.
    auto particle_stem = [&](const Sentence& sentence) -> string {
      const auto& parts = sentence.components();
      if (!o.t9_final_particles_ || parts.size() < 2 || !o.t9_final_particles_->count(parts.back().text)) return {};
      return sentence.text().substr(0, sentence.text().size() - parts.back().text.size());
    };
    set<string> stems;
    bool particle_alternative = false;
    // Only where a particle is among the first three sentences (as for the particle end weight below).
    const bool particle_near = std::any_of(order.begin(), order.begin() + std::min<size_t>(order.size(), 3),
        [&](const auto& item) { return !particle_stem(paths[item.first].sentence).empty(); });
    for (size_t position = 0; position < order.size(); ++position) {
      const auto& item = order[position];
      const auto& path = paths[item.first];
      bool extra = false;
      const bool kept_vowel = position == demoted || path.displaced;
      // t9/display_sentences: whole-input sentences shown before the dictionary words
      // (1 = the best sentence, then shorter words, as stock Rime; 0 = all decoded).
      // When a sentence ending in a particle (吗, 呢, 吧…) is among the first three, one more sentence is
      // shown: the best one ending in a particle after other words than those shown (济南吗, then 礼貌吗;
      // 我们那, then 我们吗). Behind the particle those words fell below every shorter word, so such a
      // sentence could not be typed although its words alone come second.
      if (kept_vowel) {
        extra = true;
      } else if (o.t9_display_sentences_ && sentences->size() >= o.t9_display_sentences_) {
        if (particle_alternative || !particle_near || o.t9_particle_alternative_margin_ <= 0) break;
        // Not far behind the first sentence (t9/particle_alternative_margin, ln units): 2026-11-02 host sweep,
        // 541 of the 546 wanted sentences (礼貌吗) were within 4 of it, and most odd ones (我门吗) beyond.
        if (order.front().second - item.second > o.t9_particle_alternative_margin_) break;
        const string stem = particle_stem(path.sentence);
        if (stem.empty() || stems.count(stem)) continue;
        extra = true;
      }
      // Further whole-input sentences only from t9/more_sentences_min_keys keys on: on short input they
      // are weak alternatives that push dictionary words (的, 觉得) out of the first four places.
      if (!sentences->empty() && total < o.t9_more_sentences_min_keys_ && !extra) break;
#ifndef T9_ALGO_ONLY_BUILD
      if (suppressed && suppressed->Contains("t9_pinyin", path.sentence.text())) continue;
#endif
      if (!displayed.insert(path.sentence.text()).second) continue;
      {
        const string stem = particle_stem(path.sentence);
        if (!stem.empty()) stems.insert(stem);
      }
      auto sentence = New<Sentence>(path.sentence);
      sentence->Offset(q.start);
      sentence->set_syllabifier(q.syllabifier);
      sentence->set_weight(item.second);
      sentence->set_quality(path.coverage + 0.1 * (0.5 + std::atan(item.second / 10.0) / 3.141592653589793) + q.translator->initial_quality());
      sentences->push_back(std::move(sentence));
      if (extra && !kept_vowel) particle_alternative = true;
    }
  };
  const string cache_key = q.input + "#" + std::to_string(q.start) +
                           "#" + std::to_string(q.end_of_input);
  if (o.t9_decoded_cache_ && (!q.enable_correction || o.t9_cache_with_correction_) &&
      !q.translator->strict_spelling()) {
    auto& owned = q.translator->t9_joint_cache;
    if (!owned) owned = New<T9JointCache>();
    cache = owned.get();
    const auto revision = T9UserDictionaryRevision().load(std::memory_order_relaxed);
    const auto tick = user_dict ? user_dict->tick() : 0;
    auto* prism = dict->prism().get();
    T9JointCache::Options options{o.t9_source_budget_, o.t9_pool_budget_, o.t9_dictionary_beam_,
        o.t9_sentence_beam_, o.t9_result_budget_, o.t9_spelling_penalty_, o.t9_word_penalty_,
        o.t9_sentence_weight_, o.t9_sentence_model_, q.translator->enable_completion(),
        q.translator->enable_word_completion(), q.translator->complete_short_prefix(),
        o.t9_ngram_.get(), o.t9_ngram_user_weight_, o.t9_ngram_sentence_start_, o.t9_ngram_eos_weight_,
        o.t9_ngram_merge_segmentations_, o.t9_ngram_dictionary_prior_, o.t9_ngram_user_input_weight_,
        o.t9_whole_word_weight_margin_};
    if (cache->dict_checksum != prism->dict_file_checksum() ||
        cache->schema_checksum != prism->schema_file_checksum() ||
        cache->user_revision != revision || cache->user_tick != tick ||
        cache->user_dict != user_dict || cache->preceding != cache_context ||
        cache->blacklist != q.translator->blacklist() || cache->options != options) {
      cache->results.Clear();
      cache->frames.clear(); cache->frame_bytes = 0;
      cache->dict_checksum = prism->dict_file_checksum();
      cache->schema_checksum = prism->schema_file_checksum();
      cache->user_revision = revision;
      cache->user_tick = tick;
      cache->user_dict = user_dict;
      cache->preceding = cache_context;
      cache->blacklist = q.translator->blacklist();
      cache->options = std::move(options);
    }
    if (const auto* paths = cache->results.Find(cache_key)) {
      T9_WORK_COUNT(joint_cache_hits);
      restore(*paths);
      return !sentences->empty();
    }
  }
  // Compare actual post-pruning graph rows, not just an assumed unchanged
  // digit prefix. A new key can change abbreviation pruning much earlier.
  auto sameRow = [](const EndVertexMap& a, const EndVertexMap& b) {
    if (a.size() != b.size()) return false;
    auto x = a.begin(); auto y = b.begin();
    for (; x != a.end(); ++x, ++y) {
      if (x->first != y->first || x->second.size() != y->second.size()) return false;
      auto u = x->second.begin(); auto v = y->second.begin();
      for (; u != x->second.end(); ++u, ++v) {
        const auto& p = u->second; const auto& q = v->second;
        if (u->first != v->first || p.type != q.type || p.end_pos != q.end_pos ||
            p.credibility != q.credibility || p.is_correction != q.is_correction ||
            p.tips != q.tips || p.ambiguous_source_positions != q.ambiguous_source_positions) return false;
      }
    }
    return true;
  };
  // Pruning may alternate between two valid graph shapes as a syllable
  // completes. Keep bounded recent shapes instead of losing the stable prefix
  // every other key. Selection uses exact graph rows, never a digit heuristic.
  const T9JointFrame* previous = nullptr;
  size_t best_prefix = 0;
  if (cache) for (const auto& frame : cache->frames) {
    if (frame.total >= total) continue;
    size_t prefix = frame.total;
    for (const auto& row : syllables.edges) {
      auto old = frame.edges.find(row.first);
      if (old == frame.edges.end() || !sameRow(row.second, old->second)) { prefix = std::min(prefix, row.first); break; }
    }
    for (const auto& old : frame.edges) if (!syllables.edges.count(old.first)) { prefix = std::min(prefix, old.first); break; }
    if (!previous || prefix > best_prefix) { previous = &frame; best_prefix = prefix; }
  }
  set<size_t> dirty;
  if (previous) {
    T9_WORK_COUNT(joint_frame_reuse);
    for (const auto& row : syllables.edges) {
      auto old = previous->edges.find(row.first);
      if (old == previous->edges.end() || !sameRow(row.second, old->second)) dirty.insert(row.first);
    }
    for (const auto& old : previous->edges)
      if (!syllables.edges.count(old.first)) dirty.insert(old.first);
    if (previous->total != total) dirty.insert(std::min(previous->total, total));
  }
  auto reusable = [&](int origin, const map<int, size_t>& reach) {
    const auto old = reach.find(origin);
    const auto changed = dirty.lower_bound(origin);
    return old != reach.end() && (changed == dirty.end() || *changed > old->second);
  };
  const size_t stable = previous && previous->total > 0
      ? std::min(previous->total - 1, dirty.empty() ? previous->total - 1 : *dirty.begin()) : 0;
  WordGraph graph, system;
  map<int, size_t> system_reach, user_reach, omitted_end;
  map<int, DictEntryList> learned_whole;
  vector<size_t> starts;
  for (const auto& row : syllables.edges) {
    const bool needs_evicted_edges = previous && previous->omitted_end.count(row.first) &&
        previous->omitted_end.at(row.first) > stable;
    if (previous && !needs_evicted_edges && reusable(row.first, previous->system_reach) && reusable(row.first, previous->user_reach)) {
      if (previous->omitted_end.count(row.first)) omitted_end[row.first] = previous->omitted_end.at(row.first);
      const auto found = previous->merged.find(row.first);
      if (found != previous->merged.end()) graph[row.first] = found->second;
      system_reach[row.first] = previous->system_reach.at(row.first);
      user_reach[row.first] = previous->user_reach.at(row.first);
      if (row.first == 0) learned_whole = previous->learned_whole;
      T9_WORK_COUNT(joint_origins_reused);
    } else { starts.push_back(row.first); system_reach[row.first] = row.first; }
  }
  T9_WORK_ADD(joint_origins_queried, starts.size());
  // A start is looked up again when its words reached the changed tail, but its words that end at or before the
  // stable boundary cover only syllable rows the previous frame had (every changed row is at or after the
  // boundary), and this key extends no line into them. Building those entries again was most of a key's
  // dictionary work on a long input (2026-11-26: 55-60 buckets merged a key, about 20 of them used). They are
  // taken from the previous frame instead; one that frame had already dropped (omitted_end) stays dropped.
  // 0 = build it, 1 = the previous frame holds it, 2 = dropped there.
  auto carried = [&](int start, int end) {
    if (!previous || static_cast<size_t>(end) > stable) return 0;
    const auto row = previous->merged.find(start);
    if (row != previous->merged.end() && row->second.count(end)) return 1;
    const auto omitted = previous->omitted_end.find(start);
    return omitted != previous->omitted_end.end() && static_cast<size_t>(end) <= omitted->second ? 2 : 0;
  };
  {
    T9LookupScope scope(&system_reach);
    // The dictionary does not build the chunks of those words either (skip_end): with a previous frame every
    // end at or before the stable boundary is carried or was dropped, never built here.
    auto buckets = dict->LookupAll(syllables, starts, &q.translator->blacklist(), false, 0, o.t9_dictionary_beam_,
                                   is_numeric_input(input), previous ? stable : 0);
    for (auto& start : buckets)
      T9Collect(system[start.first], start.second, o.t9_source_budget_,
                [&](int end) { return carried(static_cast<int>(start.first), end) == 0; });
  }
  for (const auto start : starts) {
    map<int, DictEntryList> user;
    user_reach[start] = start;
    if (user_dict) {
      T9LookupScope scope(&user_reach); T9OriginScope origin(start);
      // The first start keeps every end: learned_whole below reads the user's words of all of them.
      T9Collect(user, user_dict->Lookup(syllables, start, 0), o.t9_source_budget_,
                [&](int end) { return start == 0 || carried(static_cast<int>(start), end) == 0; });
    }
    // Keep what the user committed from the first key on: the merge below may keep the system entry of a
    // learned word, and the ordering of whole-input words reads the user's own evidence from here.
    if (start == 0 && o.t9_ngram_user_input_weight_ > 0)
      for (const auto& end : user)
        for (const auto& entry : end.second)
          if (entry && entry->commit_count > 0) learned_whole[end.first].push_back(entry);
    auto merged = std::move(system[start]);
    for (const auto& end : merged) T9_WORK_ADD(system_entries, end.second.size());
    for (const auto& end : user) {
      if (carried(static_cast<int>(start), end.first) != 0) continue;
      T9_WORK_ADD(user_entries_collected, end.second.size());
      auto& entries = merged[end.first];
      entries.insert(entries.end(), end.second.begin(), end.second.end());
    }
    if (o.t9_prefer_user_entries_) {
      // A learned word and the same system word share text and code; the merge keeps the higher
      // dictionary weight and would drop the user entry, so the user layer never applied.
      for (auto& end : merged) {
        auto& list = end.second;
        // Copy the learned identities first: remove_if moves elements while it runs, so the
        // predicate must not read the list itself (it saw moved-from null entries and crashed).
        vector<std::pair<string, Code>> learned;
        for (const auto& entry : list)
          if (entry && entry->commit_count > 0) learned.emplace_back(entry->text, entry->code);
        if (learned.empty()) continue;
        list.erase(std::remove_if(list.begin(), list.end(), [&](const an<DictEntry>& entry) {
          return entry && entry->commit_count <= 0 &&
                 std::any_of(learned.begin(), learned.end(), [&](const auto& other) {
                   return other.first == entry->text && other.second == entry->code;
                 });
        }), list.end());
      }
    }
    for (auto& end : merged) {
      T9_WORK_COUNT(merge_calls); T9_WORK_ADD(merge_entries_in, end.second.size());
      DictEntryList learned;
      if (o.t9_prefer_user_entries_)
        for (const auto& entry : end.second) if (entry->commit_count > 0) learned.push_back(entry);
      T9MergeEntries(end.second, o.t9_pool_budget_);
      // Learned words survive the pool budget: their user-dictionary weight is on another scale.
      for (const auto& entry : learned)
        if (std::find(end.second.begin(), end.second.end(), entry) == end.second.end()) end.second.push_back(entry);
      T9_WORK_ADD(merge_entries_out, end.second.size());
      graph[start][end.first] = std::move(end.second);
    }
    if (previous) {
      const auto row = previous->merged.find(static_cast<int>(start));
      if (row != previous->merged.end())
        for (const auto& end : row->second)
          if (static_cast<size_t>(end.first) <= stable) graph[start].emplace(end.first, end.second);
      const auto omitted = previous->omitted_end.find(static_cast<int>(start));
      if (omitted != previous->omitted_end.end())
        omitted_end[static_cast<int>(start)] =
            std::max(omitted_end[static_cast<int>(start)], std::min(omitted->second, stable));
    }
  }
  // Whole-input dictionary phrases bypass the prefix beam (26-key initials: the beam ranks prefixes by
  // single-character weight and can drop 化妆品 for hzp before the word model sees it).
  if (o.t9_whole_input_phrases_ > 0) {
    auto whole = dict->Lookup(syllables, 0, &q.translator->blacklist(), false, 0.0, 0, false, false);
    if (whole) {
      auto bucket = whole->find(static_cast<int>(total));
      if (bucket != whole->end()) {
        auto& list = graph[0][static_cast<int>(total)];
        for (size_t n = 0; n < o.t9_whole_input_phrases_ && !bucket->second.exhausted(); ++n) {
          auto entry = bucket->second.Peek();
          if (!entry->IsPredictiveMatch() &&
              std::none_of(list.begin(), list.end(), [&](const an<DictEntry>& x) { return x->text == entry->text; }))
            list.push_back(entry);
          bucket->second.Next();
        }
      }
    }
  }
  map<size_t, vector<an<T9Line>>> states;
  if (previous) for (const auto& state : previous->states) if (state.first <= stable) states.insert(state);
  if (states.empty()) {
    auto root = New<T9Line>();
    // Committed text before the composition ("\x1e" + text from the app; absent = unknown):
    // its last two model words are the first word's history. Nothing or a non-Han character
    // right before the composition is a sentence start, as for the training Han runs.
    if (o.t9_ngram_ && !o.t9_left_context_.empty() && o.t9_left_context_[0] == '\x1e') {
      const string text = o.t9_left_context_.substr(1);
      auto run = text.end();
      size_t chars = 0;
      bool bounded = false;
      while (chars < 16) {
        if (run == text.begin()) break;
        auto at = run;
        const uint32_t c = utf8::unchecked::prior(at);
        if (!((c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF) || c == 0x3007)) { bounded = true; break; }
        run = at; ++chars;
      }
      if (!chars) {
        root->lm_id = o.t9_ngram_->Bos();
      } else {
        vector<int32_t> parts;
        o.t9_ngram_->Decompose(string(run, text.end()), &parts);
        root->lm_id = parts.back();
        root->lm_prev = parts.size() >= 2 ? parts[parts.size() - 2]
                                          : bounded ? o.t9_ngram_->Bos() : ::T9NgramModel::kUnseen;
        if (root->lm_id == ::T9NgramModel::kUnseen) root->lm_prev = ::T9NgramModel::kUnseen;
      }
    }
    states[0].push_back(root);
  }
  // Model word of each entry; an entry the corpus never contained is kept as its parts,
  // so a long unseen entry never wins by paying a single unseen-word cost.
  // typo: the entry's own words contain a t9/ngram_typo_bigrams pair (a learned 觉的); typo_follow: its first word
  // is the second word of such a pair (的), so only those entries look at the pair list while scoring.
  struct T9LmEntry {
    int32_t id; vector<int32_t> parts; double reading = 0; bool particle = false;
    bool typo = false, typo_follow = false;
  };
  std::unordered_map<const DictEntry*, T9LmEntry> lm_entries;
  auto lm_entry_of = [&](const an<DictEntry>& entry) -> const T9LmEntry& {
    const auto found = lm_entries.find(entry.get());
    if (found != lm_entries.end()) return found->second;
    T9LmEntry lm{o.t9_ngram_->Id(entry->text), {}};
    if (lm.id == ::T9NgramModel::kUnseen) o.t9_ngram_->Decompose(entry->text, &lm.parts);
    if (const auto* typos = o.t9_ngram_typo_bigrams_.get()) {
      const int32_t head = lm.parts.empty() ? lm.id : lm.parts.front();
      for (const auto& pair : *typos) if (pair.second == head) lm.typo_follow = true;
      for (size_t k = 1; k < lm.parts.size(); ++k)
        if (::T9NgramModel::HasPair(*typos, lm.parts[k - 1], lm.parts[k])) lm.typo = true;
    }
    lm.particle = o.t9_final_particles_ && o.t9_final_particles_->count(entry->text);
    if (o.t9_readings_) {
      const auto readings = o.t9_readings_->find(entry->text);
      vector<string> spelling;
      if (readings != o.t9_readings_->end() && dict->Decode(entry->code, &spelling)) {
        const string code = boost::algorithm::join(spelling, " ");
        for (const auto& reading : readings->second)
          if (reading.first == code) lm.reading = reading.second;
      }
    }
    return lm_entries.emplace(entry.get(), std::move(lm)).first->second;
  };
  auto better = [](const an<T9Line>& a, const an<T9Line>& b) {
    if (a->score != b->score) return a->score > b->score;
    if (a->prior != b->prior) return a->prior > b->prior;
    if (a->text != b->text) return a->text < b->text;
    return a->code < b->code;
  };
  map<SyllableId, std::pair<size_t, size_t>> tail_syllables;  // completion syllable → digits typed, digits still to type
  // Extends the lines of states by the words of a graph into out (the main pass: out is states itself).
  // tail: words ending in a syllable the last digits only begin (see the tail pass below), from at most
  // tail_lines lines of each start.
  auto extend = [&](const WordGraph& words, map<size_t, vector<an<T9Line>>>& out, bool tail, size_t tail_lines) {
  for (const auto& start : words) {
    const auto found = states.find(start.first);
    if (found == states.end()) continue;
    for (const auto& end : start.second) {
      if (end.first <= start.first || static_cast<size_t>(end.first) > total || static_cast<size_t>(end.first) <= stable) continue;
      auto& target = out[end.first];
      size_t used = 0;
      for (const auto& previous : found->second) {
        if (tail && used++ >= tail_lines) break;
        for (const auto& entry : end.second) {
          if (entry->IsPredictiveMatch()) continue;
          T9_WORK_COUNT(beam_evaluations);
          double score = previous->score;
          int32_t lm_id = ::T9NgramModel::kUnseen, lm_prev = ::T9NgramModel::kUnseen;
          if (o.t9_ngram_) {
            // One weight scale: ln P(word | previous words) from one set of corpus counts
            // replaces the dictionary weight, the grammar and the per-word penalty.
            T9_WORK_COUNT(ngram_lookups);
            // The root carries a history only when the committed text before it is known.
            const bool first = !previous->entry && previous->lm_id == ::T9NgramModel::kUnseen;
            const auto& lm = lm_entry_of(entry);
            // A phrase spelling a corpus misspelling (觉的; t9/ngram_typo_bigrams), from the dictionary or learned
            // fewer than three times (most likely picked once because it came first), gets neither the dictionary
            // prior nor the user layer, which lifted a once-picked 觉的 over 觉得 by its user-dictionary weight.
            constexpr int kTypoLearnedMinCommits = 3;
            const bool typo_unconfirmed = lm.typo && entry->commit_count < kTypoLearnedMinCommits;
            double lp;
            if (!lm.parts.empty()) {
              lp = o.t9_ngram_->Chain(previous->lm_prev, previous->lm_id, first, o.t9_ngram_sentence_start_,
                                    lm.parts, &lm_prev, &lm_id,
                                    lm.typo || lm.typo_follow ? o.t9_ngram_typo_bigrams_.get() : nullptr);
              if (o.t9_ngram_dictionary_prior_ > 0 && std::isfinite(entry->weight) && !typo_unconfirmed) {
                // A dictionary (or cell-pack) word the corpus never contained: its dictionary
                // frequency is the only evidence for the whole word, so mix it in as a unigram
                // probability of the entry, like the user layer; the chain of parts keeps 1-μ.
                lp = std::log((1 - o.t9_ngram_dictionary_prior_) * std::exp(lp) +
                              o.t9_ngram_dictionary_prior_ * std::exp(entry->weight));
              }
            } else if (first) {
              lm_id = lm.id;
              lm_prev = o.t9_ngram_sentence_start_ ? o.t9_ngram_->Bos() : ::T9NgramModel::kUnseen;
              lp = o.t9_ngram_->Start(lm.id, o.t9_ngram_sentence_start_);
            } else if (lm.typo_follow && ::T9NgramModel::HasPair(*o.t9_ngram_typo_bigrams_, previous->lm_id, lm.id)) {
              // A corpus misspelling (觉的 for 觉得): ln P(的 | 就 觉) was -0.66, so 就觉的 beat 就觉得.
              // Score the word as after an unknown history (its lower-order probability).
              lm_id = lm.id;
              lm_prev = previous->lm_id;
              lp = o.t9_ngram_->LogProb(::T9NgramModel::kUnseen, lm.id);
            } else {
              lm_id = lm.id;
              lm_prev = previous->lm_id;
              // A sentence-final particle right after the first word: which particle follows depends on that
              // word, while the sentence-start trigram (<s> 礼貌 嘛) rests on a few one-word messages and
              // put 礼貌嘛 over 礼貌吗; such a particle takes the bigram (t9/particle_bigram_after_start).
              lp = lm.particle && o.t9_particle_bigram_after_start_ && previous->lm_prev == o.t9_ngram_->Bos()
                  ? o.t9_ngram_->LogProb(previous->lm_id, lm.id)
                  : o.t9_ngram_->LogProb3(previous->lm_prev, previous->lm_id, lm.id);
            }
            if (entry->commit_count > 0 && o.t9_ngram_user_weight_ > 0 && !typo_unconfirmed) {
              // User layer: P = (1-λ)·P(base) + λ·P(user), where P(user) is the user dictionary's own
              // estimate (commit share plus a recency term that fades over a few hundred commits).
              // The base model is read-only and keeps weight 1-λ, so learning never forgets grammar.
              const double user = std::min(1.0, std::exp(entry->weight));
              lp = std::log((1 - o.t9_ngram_user_weight_) * std::exp(lp) + o.t9_ngram_user_weight_ * user);
            }
            // P(reading | word): the user's own entries were learned with this reading.
            if (entry->commit_count <= 0) score += lm.reading;
            score += lp;
          } else {
            score += Grammar::Evaluate(
                previous->text.empty() ? preceding : previous->text,
                entry->text, entry->weight, static_cast<size_t>(end.first) == total, o.t9_grammar_.get());
            // Discourage over-segmentation.
            score -= o.t9_word_penalty_;
          }
          // Apply the same score before allocating a path. Strictly worse
          // paths cannot enter a full beam; equal scores still reach tie-breaks.
          // Keyboard-neighbour corrections (26-key touch typing): the word model scores a corrected
          // word as if it had been typed, so charge it t9/correction_penalty (nine-key has no corrector).
          if (q.enable_correction && q.IsCorrection(entry->code, end.first, start.first))
            score += o.t9_correction_penalty_;
          // Penalize abbreviated/fuzzy spans. A completed last syllable is not spelled out: it pays
          // t9/tail_completion_weight for each key still to type instead.
          double unmatched = static_cast<double>(end.first - start.first) - entry->quality_len;
          if (tail) {
            const auto& completed = tail_syllables.at(entry->code.back());
            unmatched -= static_cast<double>(completed.first);
            score += o.t9_tail_completion_weight_ * static_cast<double>(completed.second);
          }
          score -= spelling_penalty * std::max(0.0, unmatched);
          if (target.size() >= o.t9_sentence_beam_ && score < target.back()->score) { T9_WORK_COUNT(beam_rejected); continue; }
          auto line = New<T9Line>();
          line->previous = previous;
          line->entry = entry;
          line->matched_length = previous->matched_length + entry->quality_len;
          line->end = end.first;
          line->lm_id = lm_id;
          line->lm_prev = lm_prev;
          line->text = previous->text + entry->text;
          if (line->end == total) line->prior = T9WholeTextPrior(o.t9_gram_db_, line->text);
          line->code = previous->code;
          line->code.insert(line->code.end(), entry->code.begin(), entry->code.end());
          line->score = score;
          auto duplicate = std::find_if(target.begin(), target.end(), [&](const auto& other) {
            return line->text == other->text && line->code == other->code;
          });
          if (duplicate != target.end()) {
            if (o.t9_ngram_ && o.t9_ngram_merge_segmentations_) {
              // The same text and spelling through other words: P(text) sums its segmentations.
              // The better segmentation keeps its words as the history of what follows; lines
              // ending here have not been extended yet.
              const double a = line->score, b = (*duplicate)->score;
              if (!better(line, *duplicate)) line = *duplicate;
              target.erase(duplicate);
              line->score = std::max(a, b) + std::log1p(std::exp(-std::fabs(a - b)));
            } else {
              if (!better(line, *duplicate)) continue;
              target.erase(duplicate);
            }
          }
          const auto pos = std::lower_bound(target.begin(), target.end(), line, better);
          target.insert(pos, line);
          T9_WORK_COUNT(beam_insertions);
          if (target.size() > o.t9_sentence_beam_) target.pop_back();
        }
      }
    }
  }
  };
  extend(graph, states, false, 0);
  if (cache) {
    // Keep the search frontier, not every dictionary bucket behind it. The
    // lattice already owns the winning prefixes. If future pruning moves the
    // stable boundary back past any evicted bucket, that origin is re-queried.
    WordGraph frontier = graph;
    const size_t cutoff = total > 12 ? total - 12 : 0;
    for (auto& start : frontier) for (auto it = start.second.begin(); it != start.second.end();) {
      if (static_cast<size_t>(it->first) <= cutoff) {
        omitted_end[start.first] = std::max(omitted_end[start.first], static_cast<size_t>(it->first));
        it = start.second.erase(it);
      } else ++it;
    }
    // Conservative accounting includes containers and copied line text/codes.
    size_t bytes = 0;
    for (const auto& start : frontier) {
      bytes += 128;
      for (const auto& end : start.second) {
        bytes += 128 + end.second.size() * 32;
        for (const auto& entry : end.second) bytes += T9EntryBytes(*entry);
      }
    }
    for (const auto& state : states) for (const auto& line : state.second)
      bytes += sizeof(T9Line) + 128 + line->text.capacity() + line->code.capacity() * sizeof(SyllableId);
    for (const auto& row : syllables.edges) for (const auto& end : row.second) bytes += 128 + end.second.size() * 256;
    // Frames are kept for any input length; the byte limits below bound the memory. A 128-key cap made
    // every key past it (about 45 syllables) decode the whole input again: 150 syllables took 90 ms a key.
    if (bytes <= 16 * 1024 * 1024) {
      // Retain distinct prefix shapes, rather than filling the budget with
      // near-identical consecutive suffixes and evicting a recurring shape.
      // Discarding a frame only affects reuse: every surviving frame still
      // goes through the exact dependency checks above.
      for (auto it = cache->frames.begin(); it != cache->frames.end();) {
        const size_t prefix = it->total > 12 ? it->total - 12 : 0;
        bool same_prefix = prefix > 0;
        for (const auto& row : it->edges) {
          if (row.first >= prefix) break;
          const auto current = syllables.edges.find(row.first);
          if (current == syllables.edges.end() || !sameRow(row.second, current->second)) { same_prefix = false; break; }
        }
        if (same_prefix) for (const auto& row : syllables.edges) {
          if (row.first >= prefix) break;
          if (!it->edges.count(row.first)) { same_prefix = false; break; }
        }
        if (same_prefix) { cache->frame_bytes -= it->bytes; it = cache->frames.erase(it); }
        else ++it;
      }
      while (!cache->frames.empty() && (cache->frames.size() >= 16 || cache->frame_bytes + bytes > 32 * 1024 * 1024)) {
        cache->frame_bytes -= cache->frames.front().bytes; cache->frames.pop_front();
      }
      cache->frames.push_back({syllables.edges, total, bytes, std::move(frontier),
          std::move(system_reach), std::move(user_reach), std::move(omitted_end), states, learned_whole});
      cache->frame_bytes += bytes;
    }
  }
  // Last syllable still being typed (t9/tail_completion_weight, ln units for each key still to type; 0 = off): 542 is
  // jia, and also the start of jian, jiang, jiao. Nine keys spell every prefix of jian as a syllable of its own, so
  // the sentence never saw 时间 until the last key: 搁置了一段时 + 542 came out as 何止可以断食加. A word after other
  // words, or from the first key with three syllables or more (two would turn 史嘉 typed out into 时间), that ends in a
  // syllable the last digits begin also finishes a sentence, scored by the word model as typed out. One lookup in the
  // last few syllables on a small copy of their rows, after the frame for later keys is kept: the main pass, its graph
  // and the frames stay as they are (completions in the main graph made every next key look its words up again).
  map<size_t, vector<an<T9Line>>> tail_states;
  if (o.t9_tail_completion_weight_ < 0 && o.t9_ngram_ && !q.translator->strict_spelling() && total >= 4) {
    auto* prism = dict->prism().get();
    SyllableGraph tail_graph;
    set<SyllableId> typed;
    for (const auto& row : syllables.edges) {
      const auto last = row.second.find(total);
      if (last != row.second.end()) for (const auto& spelling : last->second) typed.insert(spelling.first);
    }
    for (const auto& row : syllables.edges) {
      const auto last = row.second.find(total);
      const size_t length = total - row.first;
      if (row.first == 0 || length < 2 || length > 5) continue;
      // Digits that spell no syllable yet (842: tia, only the start of tian/tiao) are completed too: 546842 showed
      // 吉姆哈 while 今天 was being typed (2026-11-25).
      if (last != row.second.end() && std::none_of(last->second.begin(), last->second.end(),
                       [](const auto& spelling) { return spelling.second.type == kNormalSpelling; })) continue;
      vector<Prism::Match> keys;
      prism->ExpandSearch(input.substr(row.first, length), &keys, 64);
      for (const auto& key : keys) {
        if (key.length <= length) continue;
        SpellingAccessor accessor(prism->QuerySpelling(key.value));
        for (; !accessor.exhausted(); accessor.Next()) {
          const SyllableId id = accessor.syllable_id();
          if (accessor.properties().type != kNormalSpelling || typed.count(id)) continue;
          EdgeProperties props(accessor.properties());
          props.type = kCompletion;
          props.end_pos = total;
          const size_t missing = key.length - length;
          tail_graph.edges[row.first][total].emplace(id, props);
          auto known = tail_syllables.emplace(id, std::make_pair(length, missing)).first;
          if (missing < known->second.second) known->second = {length, missing};
        }
      }
    }
    if (!tail_graph.edges.empty()) {
      // Words of up to four syllables end in the completion: their starts are the completed rows and up to three
      // syllables before them. Only these rows are copied, the completion edges replace their edges to the end.
      const size_t first_completed = tail_graph.edges.begin()->first;
      set<size_t> origins;
      for (const auto& row : tail_graph.edges) origins.insert(row.first);
      for (int step = 0; step < 3; ++step) {
        const set<size_t> reached = origins;
        for (const auto& row : syllables.edges)
          for (const auto& end : row.second)
            if (reached.count(end.first)) { origins.insert(row.first); break; }
      }
      for (const auto origin : origins) {
        const auto row = syllables.edges.find(origin);
        if (row == syllables.edges.end()) continue;
        for (const auto& end : row->second)
          if (static_cast<size_t>(end.first) < total) tail_graph.edges[origin][end.first] = end.second;
      }
      tail_graph.input_length = syllables.input_length;
      tail_graph.interpreted_length = total;
      for (const auto& start : tail_graph.edges) {
        auto& index = tail_graph.indices[start.first];
        for (auto end = start.second.rbegin(); end != start.second.rend(); ++end)
          for (const auto& spelling : end->second) index[spelling.first].push_back(&spelling.second);
      }
      vector<size_t> starts;
      for (const auto origin : origins) if (states.count(origin)) starts.push_back(origin);
      // Only entries from the first completed row on: words of four syllables are collected where their fourth
      // syllable starts, which is that row.
      auto buckets = dict->LookupAll(tail_graph, starts, &q.translator->blacklist(), false, first_completed,
                                     o.t9_dictionary_beam_,
                                     is_numeric_input(input));
      WordGraph words;
      for (auto& start : buckets) {
        map<int, DictEntryList> found;
        T9Collect(found, start.second, kTailWordsPerStart);
        const auto at_end = found.find(static_cast<int>(total));
        if (at_end == found.end()) continue;
        auto& list = words[start.first][static_cast<int>(total)];
        for (const auto& entry : at_end->second)
          if (entry && !entry->IsPredictiveMatch() && !entry->code.empty() && tail_syllables.count(entry->code.back()) &&
              (start.first > 0 || entry->code.size() >= kTailFirstWordSyllables))
            list.push_back(entry);
      }
      extend(words, tail_states, true, kTailLinesPerStart);
    }
  }
  // Explicit final beam: all survivors receive the same bounded second pass.
  // Reorder the actual native Sentence objects before filters and Menu indexing.
  // Sentence end (n-gram </s>, weighted by t9/ngram_eos_weight): how naturally a finished
  // path could end here. It only orders the finished paths; states kept for later keys
  // are unchanged, since the composition may continue.
  vector<std::pair<double, an<T9Line>>> final;
  if (tail_states.count(total)) {
    auto& finished = states[total];
    for (const auto& line : tail_states[total])
      if (std::none_of(finished.begin(), finished.end(), [&](const auto& other) { return other->text == line->text; }))
        finished.push_back(line);
  }
  for (const auto& line : states[total]) {
    double rank = line->score;
    if (o.t9_ngram_ && o.t9_ngram_eos_weight_ > 0)
      rank += o.t9_ngram_eos_weight_ * o.t9_ngram_->LogProb3(line->lm_prev, line->lm_id, o.t9_ngram_->Eos());
    final.emplace_back(rank, line);
  }
  std::stable_sort(final.begin(), final.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
  // One word typed as the whole input (t9/whole_word_weight_margin): the word model scores it as a sentence start,
  // where 事后 (often as 事 + 后) is likelier than 时候 and 王子 than 样子, though the dictionary has 时候 ten times
  // more often. When the first sentence reads as a dictionary word of the whole input and another dictionary word of
  // the same input has a weight higher by more than the margin, that word comes first. Sentences that are no
  // dictionary word (several words) keep the model's order.
  if (o.t9_whole_word_weight_margin_ > 0 && !final.empty() && states.count(0) && !states.at(0).empty()) {
    const auto& top = final.front().second;
    const auto row = graph.find(0);
    const DictEntryList* whole = nullptr;
    if (row != graph.end()) {
      const auto at_end = row->second.find(static_cast<int>(total));
      if (at_end != row->second.end()) whole = &at_end->second;
    }
    auto system_word = [&](const an<DictEntry>& entry) {
      return entry && !entry->IsPredictiveMatch() && entry->commit_count <= 0 && std::isfinite(entry->weight) &&
             entry->quality_len + 1e-9 >= static_cast<double>(total) &&
             utf8::distance(entry->text.begin(), entry->text.end()) >= 2;
    };
    an<DictEntry> shown, best;
    if (whole && utf8::distance(top->text.begin(), top->text.end()) >= 2)
      for (const auto& entry : *whole) {
        if (!system_word(entry)) continue;
        if (entry->text == top->text) { if (!shown || entry->weight > shown->weight) shown = entry; }
        else if (!best || entry->weight > best->weight) best = entry;
      }
    // The beam seldom keeps 时候 itself (its places at the end go to likelier pairs such as 是 后): the line is made here.
    if (shown && best && best->weight - shown->weight > o.t9_whole_word_weight_margin_) {
      auto line = New<T9Line>();
      line->previous = states.at(0).front();
      line->entry = best;
      line->matched_length = best->quality_len;
      line->end = total;
      line->text = best->text;
      line->code = best->code;
      line->prior = T9WholeTextPrior(o.t9_gram_db_, line->text);
      line->lm_prev = o.t9_ngram_ && o.t9_ngram_sentence_start_ ? o.t9_ngram_->Bos() : ::T9NgramModel::kUnseen;
      if (o.t9_ngram_) line->lm_id = lm_entry_of(best).id;
      // Just above the first sentence: equal weights are ordered by the gram prior, which kept 事后 first.
      line->score = final.front().first + 1e-3;
      final.erase(std::remove_if(final.begin(), final.end(),
                                 [&](const auto& ranked) { return ranked.second->text == line->text; }), final.end());
      final.insert(final.begin(), {line->score, line});
    }
  }
  // Words typed as the whole input (指导 for 944326, 是要) that the user has committed lately: 26 keys order such words by
  // the user dictionary, nine keys only through the small user layer (λ), so 指导 needed four picks to pass 知道
  // and fell back after a few hundred other words. Among the whole-input words of two or more characters, spelled
  // out (no initial-only syllable), P(word) = (1-κe)·P(model) + κe·share·M, where M is these words' summed
  // probability (unchanged, so sentences, single characters and abbreviations keep their place), share is 1 for
  // the word picked most (recency-weighted) and 0 for the others, as 26 keys order learned words by their user
  // weight (picked 是要 once after 试验: 是要 comes back first; split between them, the model kept 试验 first),
  // and κe = κ·E/(E0+E) with E the summed recent picks here and E0 one pick 300 commits ago. Recent picks are the user dictionary's recency term (each pick fades by e
  // every 200 commits), without its long-run commit rate: a word picked 13 times 10,000 commits ago (食补 over
  // 是不) is not what the user is choosing now. A pick just made gets nearly κ, an old one next to nothing; with
  // nothing picked lately nothing changes. It reorders the finished lines only: no lookup, the beam is untouched.
  const T9Line* displaced_line = nullptr;
  if (o.t9_ngram_user_input_weight_ > 0 && o.t9_ngram_user_input_weight_ < 1 && learned_whole.count(total)) {
    // librime's user dictionary: p = m + (0.5 - m)·dee/kM for dee < 20, m = commits/T·(1 - e^(-T/10000))^10 at
    // tick T, 1/kM = 1 - e^(-0.005). The recent part 0.5·dee/kM; one pick 300 commits ago has dee = e^(-1.5).
    const double kOldPick = 0.5 * std::exp(-300.0 / 200) * (1 - std::exp(-0.005));
    const double present = user_dict ? static_cast<double>(user_dict->tick()) + 1 : 0;
    auto recent = [&](const an<DictEntry>& entry) {
      const double p = std::min(1.0, std::exp(entry->weight));
      const double m = present > 0 ? entry->commit_count / present * std::pow(1 - std::exp(-present / 10000), 10) : 0;
      return m < 0.5 ? 0.5 * std::max(0.0, p - m) / (0.5 - m) : 0.0;
    };
    const auto& learned = learned_whole.at(static_cast<int>(total));
    auto log_add = [](double a, double b) {
      if (!std::isfinite(a)) return b;
      if (!std::isfinite(b)) return a;
      return std::max(a, b) + std::log1p(std::exp(-std::fabs(a - b)));
    };
    // Single characters count too (2026-11-24, his「选多少次都调不了」: a picked character, 粤语字 and 生僻字 among them,
    // never moved on nine keys; the user layer λ alone gives a pick 5%). One syllable typed in full is a whole input
    // like a word is.
    auto spelled_word = [&](const an<DictEntry>& entry) {
      return utf8::distance(entry->text.begin(), entry->text.end()) >= 1 &&
             entry->quality_len + 1e-9 >= static_cast<double>(total);
    };
    // A learned word may be shown through words of the model (是要 = 是 + 要, the better segmentation of the same
    // text and spelling), so learned words are matched by the line's text and spelling.
    auto evidence = [&](const an<T9Line>& line) {
      double best = 0;
      for (const auto& entry : learned)
        if (entry->text == line->text && entry->code == line->code && spelled_word(entry))
          best = std::max(best, recent(entry));
      return best;
    };
    auto eligible = [&](const an<T9Line>& line) {
      return line->entry && ((!(line->previous && line->previous->entry) && spelled_word(line->entry)) ||
                             evidence(line) > 0);
    };
    double mass = -std::numeric_limits<double>::infinity(), sum = 0;
    vector<double> user(final.size(), 0);
    for (size_t i = 0; i < final.size(); ++i) {
      if (!eligible(final[i].second)) continue;
      mass = log_add(mass, final[i].first);
      user[i] = evidence(final[i].second);
      sum += user[i];
    }
    if (sum > 0 && std::isfinite(mass)) {
      const T9Line* model_top = final.front().second.get();
      const double kappa = o.t9_ngram_user_input_weight_ * sum / (kOldPick + sum);
      const double most = *std::max_element(user.begin(), user.end());
      const double winners = static_cast<double>(std::count(user.begin(), user.end(), most));
      for (size_t i = 0; i < final.size(); ++i) {
        if (!eligible(final[i].second)) continue;
        // A single character not picked keeps its score (a whole syllable has dozens of them), so the model's best
        // character stays next in line behind the picked one and is shown second (T9Path::displaced).
        const auto& text = final[i].second->text;
        const bool single = utf8::distance(text.begin(), text.end()) == 1;
        final[i].first = log_add((single ? 0.0 : std::log(1 - kappa)) + final[i].first,
            user[i] == most ? std::log(kappa / winners) + mass : -std::numeric_limits<double>::infinity());
      }
      std::stable_sort(final.begin(), final.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
      if (final.front().second.get() != model_top) displaced_line = model_top;
    }
  }
  // Input that can end in a sentence-final particle (吗, 呢, 吧…: one of the first sentences ends with one) is
  // most likely typed to its end, so the end of the sentence counts there: 我们吗 over 我们那, 礼貌吗 over
  // 礼貌嘛. Other input keeps t9/ngram_eos_weight; it may still be typed on.
  if (o.t9_ngram_ && o.t9_final_particles_ && o.t9_particle_eos_weight_ > 0) {
    const size_t window = std::min<size_t>(final.size(), 3);
    const bool particle_end = std::any_of(final.begin(), final.begin() + window, [&](const auto& ranked) {
      return ranked.second->entry && o.t9_final_particles_->count(ranked.second->entry->text);
    });
    if (particle_end) {
      for (auto& ranked : final)
        ranked.first += o.t9_particle_eos_weight_ *
            o.t9_ngram_->LogProb3(ranked.second->lm_prev, ranked.second->lm_id, o.t9_ngram_->Eos());
      std::stable_sort(final.begin(), final.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
    }
  }
  if (final.size() > o.t9_result_budget_) final.resize(o.t9_result_budget_);
  vector<T9Path> paths;
  double coverage = 0;
  for (const auto& ranked : final)
    coverage = std::max(coverage, std::min(1.0, ranked.second->matched_length / total));
  for (const auto& [rank, line] : final) {
    auto sentence = New<Sentence>(q.translator->language());
    vector<an<T9Line>> components;
    for (auto cursor = line; cursor && cursor->entry; cursor = cursor->previous)
      components.push_back(cursor);
    for (auto it = components.rbegin(); it != components.rend(); ++it)
      sentence->Extend(*(*it)->entry, (*it)->end, (*it)->score);
    sentence->set_weight(rank);
    // All hypotheses in this translation share a match-coverage baseline,
    // preserving monotonic scores. Exact English can outrank an all-abbreviated
    // Chinese stream (e.g. two keys), without a forced language/source slot.
    sentence->set_quality(coverage + 0.1 *
                           (0.5 + std::atan(rank / 10.0) / 3.141592653589793));
    paths.push_back({*sentence, line->prior, coverage, line.get() == displaced_line});
    if (paths.size() >= o.t9_result_budget_) break;
  }
  restore(paths);
  if (cache) {
    size_t bytes = paths.capacity() * sizeof(T9Path);
    for (const auto& path : paths) bytes += T9SentenceBytes(path.sentence);
    cache->results.Put(cache_key, std::move(paths), bytes);
  }
  return !sentences->empty();
}

}  // namespace rime
