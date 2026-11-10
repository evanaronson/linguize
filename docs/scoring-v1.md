# Scoring and writing preferences V1

Every newly reported fix/rewording has independent integer confidence and impact
ratings 0..10. They are ordinal, not probabilities or a combined ranking. Confidence
is support for the exact adjustment; impact is benefit assuming it is justified.
Rewording impact excludes the benefit of fixes it already includes. The production
prompt defines anchors, meaning-preservation checks and register/variety guidance.
The current `confidence-impact-v3` rubric uses anchors 0 (none), 3 (weak support or
minor polish), 7 (moderate support or noticeable fluency/register improvement), and
10 (strong support or prevention of significant misunderstanding), allowing every
intermediate integer. Raw scores are retained for history, replay and analysis.
The database upgrade rescales existing ratings once: 0→0, 1→3, 2→7, 3→10.
All assessments then use the same scale and display mapping. The prompt hash
identifies the exact instructions and schema used for each attempt.

Ratings and temporary labels appear only when a suggestion explanation is opened.
No score filters, reordering, automatic acceptance or clipboard/write-back changes
are introduced. A validated report's assessment follows that linguistic decision
through mapping. A report split into independent repairs, a grouping of
unrelated reports or an inferred fallback repair remains Unassessed rather than
inventing a model rating. Records without an assessment remain unassessed. Model wire
responses require paired JSON integers; invalid, missing, quoted, boolean or
out-of-range fresh scores fail rather than becoming zero.

A complete WritingProfile has stable id/name, ISO language code, nullable variety,
register and punctuation. Profiles have no inheritance or overrides. V1 exposes
one per supported language, with Casual/Everyday/Formal register and the existing
punctuation choices. Migration copies the previous global punctuation once into
both profiles and retains existing regional defaults. Variety is stored but has
no editor yet; additional named profile selection is deferred. Each request
snapshots the active profiles in language order. Auto-detect selects the matching
detected-language profile; a pinned request selects its language's profile. The
prompt gets no competing hardcoded variety when profiles are supplied. Unknown
languages use the compatibility punctuation and everyday register with no assumed
regional variety.

History stores the exact request-time list per session/attempt, and exact lists
participate in cache matching. Later edits do not reinterpret past checks; old
unknown profiles stay empty. Scores preserve rubric version and generated versus
retrospective origin, with provider/model/prompt provenance on their attempt.
Recorded suggestion scores are authoritative on replay, including explicit nulls.
The History schema/migrations are documented in history.md. There is no automatic
historical scoring job: the separate offline experiment can assess old suggestions
without modifying their original evidence.

The card shows scores without words: under each change's reason, Impact then
Confidence as three bars each (screen readers hear the labels). Current raw scores
map to Low at 0–5, Medium at 6–8 and High at 9–10, using the same mapping for both
dimensions, including rescaled historical assessments. These display
bands do not filter, reorder or automatically accept suggestions. Under a hairline
the explanation names the reading the change rests on ("Assumed you meant: …"), which opens
the alternatives in place; Clarify settles one and checks again, and an answered
reading reads "You clarified: …". Meaning, the card's footer row, lists every assumption
the same way. Read-only History shows the readings without the clarify controls,
and supplied answers are not counted as unresolved. The
prompt surfaces plausible meaning alternatives affecting a suggestion rather than
creating intent questions for grammar, style or hypothetical dictionary senses.
Re-checks produce fresh ratings and retain the writer's settled facts; no automatic
confidence increase follows clarification.

Adjustments now declare optional meaning dependencies using `assumption_indices`,
zero-based references into the same response's existing assumptions. The schema
requires an explicit list (usually empty); legacy or missing lists read as empty.
The parser drops malformed/out-of-range references without discarding a usable
correction. Filtering malformed assumptions remaps links instead of retargeting
them. Split, grouped unrelated, and inferred repairs do not inherit dependencies.
No confidence cutoff creates a question or link.

Opening a suggestion shows its linked unresolved assumptions inline, using the
same ClarifyBlock and settling callback as Meaning. Clarify offers the current
reading plus distinct alternatives, so a writer can confirm or correct it. The
whole original text is checked again; answers belong to the check, not an inline
copy of the assumption. Duplicate question labels are not offered inline because
the existing settling action identifies a question by its label.

On Meaning, both a general clarification and an answered assumption use the same
Your clarification row, prefilled editor, and Remove clarification action. An
assumption keeps its topic, even when a later reply omits the resolved question.
History shows these answers without editing controls. Scope and authorship remain
distinct in the existing data model; no duplicate clarification state is needed.

History stores the exact response already, so no new link table or database
migration is needed. Replay reads dependencies only from that attempt and only
when interpreted edits exactly match the recorded rows. Otherwise links remain
unknown/empty. Historical cards render the same information without actions.

The existing offline evaluator supports explicit complete writing_profiles and
validates/prints both fresh scores on the 0–10 scale. The separate retrospective
experiment retains its original 0–3 rubric and artifacts; its importer resolves only
the profiles recorded on the suggestion's own attempt. No conversation import, memory,
free-form style instructions or profile manager is part of V1.
