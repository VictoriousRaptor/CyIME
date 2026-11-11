#ifndef CYIME_PINYIN_SYLLABLE_SELECTION_H_
#define CYIME_PINYIN_SYLLABLE_SELECTION_H_

#include <algorithm>
#include <cstdint>
#include <iomanip>
#include <map>
#include <memory>
#include <sstream>
#include <set>
#include <string>
#include <string_view>
#include <vector>
#include <rime/algo/syllabifier.h>
#include <rime/candidate.h>
#include <rime/context.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/corrector.h>
#include <rime/engine.h>
#include <rime/gear/translator_commons.h>
#include <rime/menu.h>
#include <rime/schema.h>
#include <rime/ticket.h>

namespace cyime {
namespace pinyin_selection {

// A session-local constraint, never a spelling rewrite. The underscore makes
// Rime discard it at the schema boundary. The engine update hook preserves
// an unchanged selected prefix across suffix edits, while dropping damaged
// readings immediately so that clearing/retyping cannot revive an old selection.
inline constexpr const char* kProperty = "_cyime_pinyin_syllable_lock_v1";
inline constexpr const char* kAppliedProperty = "_cyime_pinyin_syllable_applied_v1";

// One chosen reading. Like nine-key choices, readings are chosen one after
// another: each span starts where the previous one ended (after delimiters).
struct Span {
  size_t start = 0;
  size_t end = 0;
  rime::SyllableId syllable = -1;
};

struct Lock {
  std::string schema;
  std::string input;
  std::string confirmed_text;
  uint64_t epoch = 0;
  uint32_t dictionary_checksum = 0;
  uint32_t schema_checksum = 0;
  size_t confirmed = 0;
  std::vector<Span> spans;
};

struct Option {
  rime::SyllableId syllable = -1;
  std::string pinyin;
  size_t start = 0;
  size_t end = 0;
  bool selected = false;
};

inline std::string ConfirmedText(const rime::Context& context) {
  const auto confirmed = context.composition().GetConfirmedPosition();
  std::string text;
  for (const auto& segment : context.composition()) {
    if (segment.end > confirmed || segment.status < rime::Segment::kSelected)
      break;
    if (auto candidate = segment.GetSelectedCandidate()) text += candidate->text();
  }
  return text;
}

inline std::string Encode(const Lock& lock) {
  std::ostringstream out;
  out << 2 << ' ' << std::quoted(lock.schema) << ' ' << std::quoted(lock.input)
      << ' ' << std::quoted(lock.confirmed_text) << ' ' << lock.epoch << ' '
      << lock.dictionary_checksum << ' ' << lock.schema_checksum << ' '
      << lock.confirmed << ' ' << lock.spans.size();
  for (const auto& span : lock.spans)
    out << ' ' << span.start << ' ' << span.end << ' ' << span.syllable;
  return out.str();
}

inline bool Decode(const std::string& value, Lock* lock) {
  if (value.empty() || !lock) return false;
  std::istringstream in(value);
  unsigned version = 0;
  size_t count = 0;
  Lock parsed;
  if (!(in >> version >> std::quoted(parsed.schema) >> std::quoted(parsed.input)
        >> std::quoted(parsed.confirmed_text) >> parsed.epoch
        >> parsed.dictionary_checksum >> parsed.schema_checksum
        >> parsed.confirmed >> count) ||
      version != 2 || count == 0 || count > parsed.input.size()) {
    return false;
  }
  size_t previous_end = parsed.confirmed;
  for (size_t i = 0; i < count; ++i) {
    Span span;
    if (!(in >> span.start >> span.end >> span.syllable) || span.syllable < 0 ||
        span.start >= span.end || span.end > parsed.input.size() ||
        (i == 0 ? span.start != parsed.confirmed : span.start < previous_end)) return false;
    previous_end = span.end;
    parsed.spans.push_back(span);
  }
  in >> std::ws;
  if (!in.eof()) return false;
  *lock = std::move(parsed);
  return true;
}

inline std::string Delimiters(const rime::Schema* schema) {
  std::string delimiters = " '";
  if (schema && schema->config()) schema->config()->GetString("speller/delimiter", &delimiters);
  return delimiters;
}

// Delimiters typed after a reading belong to it, as in Rime's own graph edges.
inline size_t SkipDelimiters(const std::string& input, size_t position,
                             const std::string& delimiters, size_t limit) {
  limit = std::min(limit, input.size());
  while (position < limit && delimiters.find(input[position]) != std::string::npos) ++position;
  return position;
}

// Readings that lie before the composition caret. Moving the caret into a
// chosen reading pauses it and the ones after it; moving out restores them.
inline size_t ActiveSpans(const Lock& lock, size_t caret) {
  size_t active = 0;
  while (active < lock.spans.size() && lock.spans[active].end <= caret) ++active;
  return active;
}

inline void ClearLock(rime::Context* context) {
  if (!context) return;
  if (!context->get_property(kProperty).empty()) context->set_property(kProperty, "");
  if (!context->get_property(kAppliedProperty).empty())
    context->set_property(kAppliedProperty, "");
}

// Runs before the engine composes an updated Context. Comparing the protected
// raw prefix at every edit (including Clear) prevents delete/retype ABA, while
// appending or removing only the unselected suffix keeps the chosen reading.
inline void BeforeContextUpdate(rime::Context* context, const rime::Schema* schema) {
  if (!context) return;
  const auto value = context->get_property(kProperty);
  if (value.empty()) return;
  Lock lock;
  if (!schema || !Decode(value, &lock) || context->get_option("ascii_mode") ||
      schema->schema_id() != lock.schema || context->input().empty() ||
      context->composition().GetConfirmedPosition() != lock.confirmed ||
      ConfirmedText(*context) != lock.confirmed_text) {
    ClearLock(context);
    return;
  }
  // Keep the readings whose code is untouched; an edit inside one drops it and
  // every reading after it, never the readings before it.
  const auto& input = context->input();
  size_t same = 0;
  while (same < input.size() && same < lock.input.size() && input[same] == lock.input[same]) ++same;
  size_t kept = 0;
  while (kept < lock.spans.size() && lock.spans[kept].end <= same) ++kept;
  if (kept == 0) {
    ClearLock(context);
    return;
  }
  if (kept != lock.spans.size() || lock.input != input ||
      lock.epoch != context->composition_epoch()) {
    lock.spans.resize(kept);
    lock.input = context->input();
    lock.epoch = context->composition_epoch();
    context->set_property(kProperty, Encode(lock));
    if (!context->get_property(kAppliedProperty).empty())
      context->set_property(kAppliedProperty, "");
  }
}

// OnSelect has paths that call Compose directly, or only Forward when the
// entire input was selected. Do not wait for a subsequent update/translation.
// A word that covers exactly the first chosen readings keeps the readings
// chosen after it, as the rest of the code is still waiting for its word.
inline void BeforeCandidateSelection(rime::Context* context) {
  if (!context) return;
  Lock lock;
  const auto& composition = context->composition();
  if (composition.empty() || !Decode(context->get_property(kProperty), &lock) ||
      lock.input != context->input() || lock.epoch != context->composition_epoch()) {
    ClearLock(context);
    return;
  }
  const auto& segment = composition.back();
  const auto candidate = segment.GetSelectedCandidate();
  std::string confirmed_text;
  for (size_t i = 0; i + 1 < composition.size(); ++i) {
    if (composition[i].status < rime::Segment::kSelected) break;
    if (auto previous = composition[i].GetSelectedCandidate()) confirmed_text += previous->text();
  }
  size_t first = 1;
  while (candidate && first < lock.spans.size() && lock.spans[first].start != candidate->end()) ++first;
  if (!candidate || segment.start != lock.confirmed || confirmed_text != lock.confirmed_text ||
      first >= lock.spans.size()) {
    ClearLock(context);
    return;
  }
  lock.spans.erase(lock.spans.begin(), lock.spans.begin() + first);
  lock.confirmed = candidate->end();
  lock.confirmed_text = confirmed_text + candidate->text();
  context->set_property(kProperty, Encode(lock));
  if (!context->get_property(kAppliedProperty).empty())
    context->set_property(kAppliedProperty, "");
}

inline bool Matches(const Lock& lock, const rime::Context& context,
                    const rime::Schema& schema, const rime::Prism& prism) {
  return !context.get_option("ascii_mode") && lock.schema == schema.schema_id() &&
         lock.input == context.input() && lock.epoch == context.composition_epoch() &&
         lock.dictionary_checksum == prism.dict_file_checksum() &&
         lock.schema_checksum == prism.schema_file_checksum() &&
         lock.confirmed == context.composition().GetConfirmedPosition() &&
         lock.confirmed_text == ConfirmedText(context) &&
         !lock.spans.empty() && lock.spans.front().start == lock.confirmed;
}

inline bool ReadLock(rime::Context* context, const rime::Schema* schema,
                     const rime::Prism& prism, Lock* lock) {
  if (!context || !schema || !lock) return false;
  const auto value = context->get_property(kProperty);
  if (value.empty()) return false;
  if (Decode(value, lock) && Matches(*lock, *context, *schema, prism)) return true;
  ClearLock(context);
  return false;
}

// Rebuild pointer-bearing indices after pruning edges. Copying the old index
// would retain dangling EdgeProperties pointers into erased syllable paths.
inline void RebuildIndices(rime::SyllableGraph* graph) {
  graph->indices.clear();
  for (const auto& start : graph->edges) {
    for (auto end = start.second.rbegin(); end != start.second.rend(); ++end) {
      for (const auto& spelling : end->second) {
        graph->indices[start.first][spelling.first].push_back(&spelling.second);
      }
    }
  }
}

// Pure graph operation, also used by the native regression probe. Span ends
// include any trailing speller delimiters, exactly as Rime's graph does.
inline bool ConstrainGraph(rime::SyllableGraph* graph, size_t start, size_t end,
                           rime::SyllableId syllable) {
  if (!graph || start >= end || end > graph->input_length) return false;
  auto from = graph->edges.find(start);
  if (from == graph->edges.end()) return false;
  auto to = from->second.find(end);
  if (to == from->second.end()) return false;
  auto target = to->second.find(syllable);
  if (target == to->second.end() || target->second.type >= rime::kAbbreviation) return false;
  auto chosen = target->second;
  chosen.ambiguous_source_positions.clear();

  graph->indices.clear();
  for (auto source = graph->edges.begin(); source != graph->edges.end();) {
    for (auto edge = source->second.begin(); edge != source->second.end();) {
      if (edge->first <= start || source->first >= end) {
        ++edge;
      } else if (source->first == start && edge->first == end) {
        edge->second.clear();
        edge->second.emplace(syllable, chosen);
        ++edge;
      } else {
        edge = source->second.erase(edge);
      }
    }
    if (source->second.empty()) source = graph->edges.erase(source);
    else ++source;
  }
  for (auto vertex = graph->vertices.upper_bound(start);
       vertex != graph->vertices.end() && vertex->first < end;) {
    vertex = graph->vertices.erase(vertex);
  }
  graph->vertices[start] = rime::kNormalSpelling;
  graph->vertices[end] = chosen.type;
  RebuildIndices(graph);
  return true;
}

// Called only in the script translator, after its actual graph has been built.
// A valid lock that cannot be represented must yield no translation; silently
// ignoring it would show candidates for a different pronunciation.
inline bool ApplyGraphLock(rime::Context* context, rime::Schema* schema,
                           rime::Prism& prism, size_t segment_start,
                           rime::SyllableGraph* graph, bool* constrained = nullptr) {
  if (constrained) *constrained = false;
  Lock lock;
  if (!ReadLock(context, schema, prism, &lock)) return true;
  // Moving the composition caret into a chosen syllable pauses it and the
  // ones after it. Moving it out again restores them without rewriting code.
  const size_t active = ActiveSpans(lock, context->caret_pos());
  if (active == 0) return true;
  const size_t lock_start = lock.spans.front().start;
  const size_t lock_end = lock.spans[active - 1].end;
  if (segment_start >= lock_end || segment_start + graph->input_length <= lock_start)
    return true;
  bool applied = segment_start <= lock_start &&
      lock_end <= segment_start + graph->input_length;
  for (size_t i = 0; applied && i < active; ++i) {
    applied = ConstrainGraph(graph, lock.spans[i].start - segment_start,
                             lock.spans[i].end - segment_start, lock.spans[i].syllable);
  }
  if (applied) {
    if (constrained) *constrained = true;
    context->set_property(kAppliedProperty, Encode(lock));
    return true;
  }
  graph->indices.clear();
  graph->edges.clear();
  graph->vertices.clear();
  graph->interpreted_length = 0;
  return false;
}

// Append a separately syllabified part using offsets into the unchanged raw
// input. Edge indices point inside maps, so only rebuild them after all parts
// have been copied into their final owner.
inline void AppendGraph(const rime::SyllableGraph& part, size_t offset,
                        rime::SyllableGraph* graph) {
  for (const auto& vertex : part.vertices)
    graph->vertices[vertex.first + offset] = vertex.second;
  for (const auto& source : part.edges) {
    for (const auto& edge : source.second) {
      auto& destination = graph->edges[source.first + offset][edge.first + offset];
      for (const auto& spelling : edge.second) {
        auto properties = spelling.second;
        properties.end_pos += offset;
        std::set<size_t> ambiguous;
        for (const auto position : properties.ambiguous_source_positions)
          ambiguous.insert(position + offset);
        properties.ambiguous_source_positions = std::move(ambiguous);
        destination.emplace(spelling.first, std::move(properties));
      }
    }
  }
}

// An explicit syllable boundary must participate BEFORE global pruning. For
// example ni+n must remain available after choosing ni, even though the normal
// unselected graph prefers the complete syllable nin and removes that path.
// Reuse the translator's own builder so completion/correction/strict options
// are identical; no synthetic delimiter or full-pinyin replacement is written.
inline size_t BuildGraph(rime::Syllabifier& builder, const std::string& input,
                         rime::Prism& prism, rime::Context* context,
                         rime::Schema* schema, size_t segment_start,
                         rime::SyllableGraph* graph,
                         rime::SyllableGraphCache* cache, bool* constrained) {
  if (constrained) *constrained = false;
  Lock lock;
  const size_t active = ReadLock(context, schema, prism, &lock)
      ? ActiveSpans(lock, context->caret_pos()) : 0;
  if (active == 0 || segment_start >= lock.spans[active - 1].end ||
      segment_start + input.size() <= lock.spans.front().start) {
    return static_cast<size_t>(builder.BuildSyllableGraph(input, prism, graph, cache));
  }
  if (constrained) *constrained = true;
  const auto fail = [&]() -> size_t {
    graph->indices.clear();
    graph->edges.clear();
    graph->vertices.clear();
    graph->input_length = input.size();
    graph->interpreted_length = 0;
    return 0;
  };
  if (segment_start > lock.spans.front().start ||
      lock.spans[active - 1].end > segment_start + input.size() ||
      context->input().compare(segment_start, input.size(), input) != 0) return fail();
  const size_t start = lock.spans.front().start - segment_start;
  // A newly typed delimiter belongs to the preceding graph edge, just as in
  // ordinary Rime syllabification. It need not enlarge the immutable lock span.
  const auto delimiters = Delimiters(schema);
  rime::SyllableGraph before;
  if (start != 0 && static_cast<size_t>(builder.BuildSyllableGraph(
          input.substr(0, start), prism, &before)) != start) return fail();
  std::vector<std::pair<size_t, rime::SyllableGraph>> chosen(active);
  size_t end = start;
  for (size_t i = 0; i < active; ++i) {
    // Each later reading was chosen right where the previous one ended.
    if (lock.spans[i].start - segment_start != end) return fail();
    const size_t chosen_end = SkipDelimiters(
        input, lock.spans[i].end - segment_start, delimiters, input.size());
    const size_t chosen_length = chosen_end - end;
    chosen[i].first = end;
    if (static_cast<size_t>(builder.BuildSyllableGraph(
            input.substr(end, chosen_length), prism, &chosen[i].second)) != chosen_length ||
        !ConstrainGraph(&chosen[i].second, 0, chosen_length, lock.spans[i].syllable)) return fail();
    end = chosen_end;
  }
  rime::SyllableGraph after;
  const size_t suffix_length = end < input.size()
      ? static_cast<size_t>(builder.BuildSyllableGraph(input.substr(end), prism, &after))
      : 0;
  graph->indices.clear();
  graph->edges.clear();
  graph->vertices.clear();
  graph->input_length = input.size();
  graph->interpreted_length = end + suffix_length;
  AppendGraph(before, 0, graph);
  for (const auto& part : chosen) AppendGraph(part.second, part.first, graph);
  AppendGraph(after, end, graph);
  RebuildIndices(graph);
  context->set_property(kAppliedProperty, Encode(lock));
  return graph->interpreted_length;
}

inline std::unique_ptr<rime::Dictionary> OpenDictionary(rime::Schema* schema) {
  if (!schema || !schema->config()) return nullptr;
  auto* config = schema->config();
  auto has_component = [&](const char* path, const std::string& component,
                           bool primary_namespace) {
    const auto list = config->GetList(path);
    if (!list) return false;
    for (const auto& item : *list) {
      const auto value = rime::As<rime::ConfigValue>(item);
      if (!value) continue;
      const auto& name = value->str();
      if (name == component ||
          (primary_namespace && name == component + "@translator") ||
          (!primary_namespace && name.compare(0, component.size() + 1,
                                              component + "@") == 0)) return true;
    }
    return false;
  };
  // Query the primary phonetic translator, not the physical key count. T9 has
  // its own stateful syllable-selection contract and must never enter this API.
  if (!has_component("engine/translators", "script_translator", true) ||
      has_component("engine/processors", "t9_processor", false)) return nullptr;
  std::string alphabet;
  if (!config->GetString("speller/alphabet", &alphabet) ||
      alphabet.find_first_of("abcdefghijklmnopqrstuvwxyz") == std::string::npos ||
      alphabet.find_first_of("0123456789") != std::string::npos) return nullptr;
  auto component = rime::Dictionary::Require("dictionary");
  if (!component) return nullptr;
  std::unique_ptr<rime::Dictionary> dictionary(
      component->Create(rime::Ticket(schema, "translator")));
  if (!dictionary || (!dictionary->loaded() && !dictionary->Load()) ||
      !dictionary->loaded() ||
      !dictionary->prism() || !dictionary->primary_table()) return nullptr;
  // Legacy deployed schemas lack language/scheme metadata. Their dictionary's
  // actual syllabary is the authority, independent of full/double/merged codes.
  // These Mandarin readings distinguish the shared pinyin table from kana and
  // alphabetic word tables without guessing schema or dictionary filenames.
  rime::Syllabary syllabary;
  if (!dictionary->primary_table()->GetSyllabary(&syllabary) ||
      syllabary.count("ni") == 0 || syllabary.count("hao") == 0 ||
      syllabary.count("zhong") == 0) return nullptr;
  return dictionary;
}

// Readings that start at [start] and end by the caret, from the prism itself.
inline std::vector<Option> OptionsAt(rime::Context* context, rime::Schema* schema, rime::Engine* engine,
                                     rime::Dictionary* dictionary, size_t start,
                                     const Lock* selected) {
  std::vector<Option> result;
  const size_t caret = context->caret_pos();
  const auto delimiters = Delimiters(schema);
  bool strict = false;
  schema->config()->GetBool("translator/strict_spelling", &strict);
  const size_t code_start = SkipDelimiters(context->input(), start, delimiters, caret);
  if (code_start >= caret) return result;
  std::vector<rime::Prism::Match> matches;
  dictionary->prism()->CommonPrefixSearch(
      std::string_view(context->input().data() + code_start, caret - code_start), &matches);
  std::set<std::pair<size_t, rime::SyllableId>> seen;
  // Query the authoritative prism before whole-input pruning: choosing ni from
  // nin is legitimate and locks ni+n rather than making ni disappear from UI.
  for (const auto& match : matches) {
    if (match.length == 0 || match.length > caret - code_start) continue;
    size_t end = code_start + match.length;
    while (end < caret && delimiters.find(context->input()[end]) != std::string::npos) ++end;
    for (auto spelling = dictionary->prism()->QuerySpelling(match.value);
         !spelling.exhausted(); spelling.Next()) {
      const auto properties = spelling.properties();
      if (properties.type >= rime::kAbbreviation || properties.is_correction ||
          (strict && end == caret && properties.type != rime::kNormalSpelling)) continue;
      const auto syllable = spelling.syllable_id();
      if (!seen.emplace(end, syllable).second) continue;
      const auto pinyin = dictionary->primary_table()->GetSyllableById(syllable);
      if (pinyin.empty()) continue;
      result.push_back({syllable, pinyin, start, end,
          selected && std::any_of(selected->spans.begin(), selected->spans.end(),
              [&](const Span& span) {
                return span.start == start && span.end >= code_start + match.length &&
                       span.end <= end && span.syllable == syllable;
              })});
    }
  }
  // Use the same schema and live keyboard geometry as the script translator.
  // Corrections are graph edges, not necessarily entries in the prism itself.
  // The schemas always enable correction; the keyboard switch only decides whether
  // a neighbor map is sent (cyime_neighbors), so skip the extra graph when it is empty.
  bool correction = false;
  if (engine && !context->get_property("cyime_neighbors").empty() &&
      schema->config()->GetBool("translator/enable_correction", &correction) && correction) {
    auto component = rime::Corrector::Require("corrector");
    std::unique_ptr<rime::Corrector> corrector(component
        ? component->Create(rime::Ticket(engine, "translator", "script_translator")) : nullptr);
    if (corrector) {
      bool completion = false;
      schema->config()->GetBool("translator/enable_completion", &completion);
      rime::Syllabifier builder(delimiters, completion, strict);
      bool preserve_final_ne = false;
      if (!strict && schema->config()->GetBool("translator/preserve_final_ne", &preserve_final_ne) && preserve_final_ne)
        builder.PreserveFinalNe();
      builder.EnableCorrection(corrector.get());
      rime::SyllableGraph graph;
      builder.BuildSyllableGraph(context->input().substr(code_start, caret - code_start),
                                *dictionary->prism(), &graph);
      auto from = graph.edges.find(0);
      if (from != graph.edges.end()) {
        for (const auto& edge : from->second) {
          const size_t end = code_start + edge.first;
          if (end > caret) continue;
          for (const auto& spelling : edge.second) {
            if (!spelling.second.is_correction || spelling.second.type >= rime::kAbbreviation ||
                !seen.emplace(end, spelling.first).second) continue;
            const auto pinyin = dictionary->primary_table()->GetSyllableById(spelling.first);
            if (pinyin.empty()) continue;
            result.push_back({spelling.first, pinyin, start, end,
                selected && std::any_of(selected->spans.begin(), selected->spans.end(),
                    [&](const Span& span) { return span.start == start && span.end == end && span.syllable == spelling.first; })});
          }
        }
      }
    }
  }
  // Prefer readings used by already-visible candidates, in their existing
  // order. Use the actual syllable IDs and spans rather than formatted comments.
  std::map<std::pair<size_t, rime::SyllableId>, size_t> candidate_rank;
  if (!context->composition().empty()) {
    const auto menu = context->composition().back().menu;
    if (menu) for (size_t i = 0; i < std::min<size_t>(menu->candidate_count(), 20); ++i) {
      const auto displayed = menu->GetCandidateAt(i);
      if (!displayed) continue;
      const auto phrase = rime::As<rime::Phrase>(rime::Candidate::GetGenuineCandidate(displayed));
      if (!phrase || phrase->start() > start || phrase->end() <= start) continue;
      const auto spans = phrase->spans();
      if (!spans.HasVertex(start)) continue;
      const auto index = spans.Count(phrase->start(), start);
      if (index >= phrase->code().size()) continue;
      for (const auto& option : result) {
        if (option.syllable == phrase->code()[index] && spans.HasVertex(option.end) &&
            spans.Count(start, option.end) == 1)
          candidate_rank.emplace(std::make_pair(option.end, option.syllable), i);
      }
    }
  }
  const auto rank = [&](const Option& option) {
    const auto found = candidate_rank.find(std::make_pair(option.end, option.syllable));
    return found == candidate_rank.end() ? size_t{20} : found->second;
  };
  std::stable_sort(result.begin(), result.end(), [&](const Option& a, const Option& b) {
    const auto a_rank = rank(a), b_rank = rank(b);
    if (a_rank != b_rank) return a_rank < b_rank;
    return a.end != b.end ? a.end > b.end : a.pinyin < b.pinyin;
  });
  return result;
}

inline std::vector<Option> Options(rime::Context* context, rime::Schema* schema, rime::Engine* engine,
                                   rime::Dictionary* dictionary) {
  if (!context || !schema || !dictionary || context->get_option("ascii_mode")) return {};
  const size_t confirmed = context->composition().GetConfirmedPosition();
  const size_t caret = context->caret_pos();
  if (confirmed >= caret || caret > context->input().size()) return {};
  Lock selected;
  const bool has_selected = ReadLock(context, schema, *dictionary->prism(), &selected);
  const Lock* lock = has_selected ? &selected : nullptr;
  const size_t active = has_selected ? ActiveSpans(selected, caret) : 0;
  if (active == 0) return OptionsAt(context, schema, engine, dictionary, confirmed, lock);
  // Like the nine-key rail, the next syllable is offered once one is chosen.
  // When nothing can follow before the caret, the last chosen one can be changed.
  auto next = OptionsAt(context, schema, engine, dictionary,
      SkipDelimiters(context->input(), selected.spans[active - 1].end, Delimiters(schema),
                     context->input().size()), lock);
  return next.empty()
      ? OptionsAt(context, schema, engine, dictionary, selected.spans[active - 1].start, lock) : next;
}

// Applies [lock] (empty spans: none) and keeps it only if Rime still has a menu.
inline bool Apply(rime::Context* context, rime::Schema* schema, const rime::Prism& prism,
                  const Lock& lock) {
  const auto previous_property = context->get_property(kProperty);
  const auto previous_applied = context->get_property(kAppliedProperty);
  const auto previous_composition = context->composition();
  const auto caret = context->caret_pos();
  const auto encoded = lock.spans.empty() ? std::string() : Encode(lock);
  context->set_property(kAppliedProperty, "");
  context->set_property(kProperty, encoded);
  const bool refreshed = context->RefreshNonConfirmedComposition();
  if (refreshed && context->caret_pos() == caret && context->HasMenu() &&
      (lock.spans.empty() || (context->get_property(kAppliedProperty) == encoded &&
                              Matches(lock, *context, *schema, prism)))) return true;

  // No input/caret/commit API participates in a successful selection. Restore
  // the old constraint and composition if the selected graph has no result.
  context->set_property(kProperty, previous_property);
  context->set_property(kAppliedProperty, previous_applied);
  context->set_composition(rime::Composition(previous_composition));
  return false;
}

inline bool Select(rime::Context* context, rime::Schema* schema, rime::Engine* engine,
                   const std::string& expected_input, const std::string& expected_schema,
                   size_t start, size_t end, rime::SyllableId syllable) {
  if (!context || !schema || context->get_option("ascii_mode") ||
      context->input() != expected_input || schema->schema_id() != expected_schema ||
      start >= end || end > context->caret_pos() ||
      start < context->composition().GetConfirmedPosition()) return false;
  auto dictionary = OpenDictionary(schema);
  if (!dictionary) return false;
  const auto options = Options(context, schema, engine, dictionary.get());
  if (std::none_of(options.begin(), options.end(), [&](const Option& option) {
        return option.start == start && option.end == end && option.syllable == syllable;
      })) return false;

  // The readings chosen before this syllable stay; this one replaces any
  // reading chosen at the same place and the readings after it.
  Lock previous;
  const bool has_previous = ReadLock(context, schema, *dictionary->prism(), &previous);
  const auto confirmed = context->composition().GetConfirmedPosition();
  Lock lock{expected_schema, expected_input, ConfirmedText(*context),
            context->composition_epoch(), dictionary->prism()->dict_file_checksum(),
            dictionary->prism()->schema_file_checksum(), confirmed, {}};
  if (has_previous) {
    for (const auto& span : previous.spans) {
      if (span.end > start) break;
      lock.spans.push_back(span);
    }
  }
  if (lock.spans.empty() && start != confirmed) return false;
  lock.spans.push_back({start, end, syllable});
  return Apply(context, schema, *dictionary->prism(), lock);
}

// Number of chosen syllables, for the rail and its delete key.
inline size_t ChosenCount(rime::Context* context, rime::Schema* schema) {
  Lock lock;
  if (!context || !schema || context->get_option("ascii_mode") ||
      !Decode(context->get_property(kProperty), &lock) ||
      lock.schema != schema->schema_id() || lock.input != context->input() ||
      lock.epoch != context->composition_epoch() ||
      lock.confirmed != context->composition().GetConfirmedPosition()) return 0;
  return lock.spans.size();
}

// The rail's delete key takes back the last chosen syllable, like nine-key.
inline bool Undo(rime::Context* context, rime::Schema* schema,
                 const std::string& expected_input, const std::string& expected_schema) {
  if (!context || !schema || context->input() != expected_input ||
      schema->schema_id() != expected_schema || ChosenCount(context, schema) == 0) return false;
  auto dictionary = OpenDictionary(schema);
  if (!dictionary) return false;
  Lock lock;
  if (!ReadLock(context, schema, *dictionary->prism(), &lock)) return false;
  lock.spans.pop_back();
  return Apply(context, schema, *dictionary->prism(), lock);
}

}  // namespace pinyin_selection
}  // namespace cyime
#endif  // CYIME_PINYIN_SYLLABLE_SELECTION_H_
