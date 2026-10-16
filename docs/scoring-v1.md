# Scoring and writing preferences V1

Every newly reported fix/rewording has independent integer confidence and impact
ratings 0..3. They are ordinal, not probabilities or a combined ranking. Confidence
is support for the exact adjustment; impact is benefit assuming it is justified.
Rewording impact excludes the benefit of fixes it already includes. The production
prompt defines anchors, meaning-preservation checks and register/variety guidance.

Ratings and temporary labels appear only when a suggestion explanation is opened.
No score filters, reordering, automatic acceptance or clipboard/write-back changes
are introduced. A validated report's assessment follows that linguistic decision
through mapping. A report split into independent repairs, a grouping of
unrelated reports or an inferred fallback repair remains Unassessed rather than
inventing a model rating. Legacy records similarly remain unassessed. Model wire
responses require paired JSON integers; invalid, missing, quoted, boolean or
out-of-range fresh scores fail rather than becoming zero.

A complete WritingProfile has stable id/name, ISO language code, nullable variety,
register and punctuation. Profiles have no inheritance or overrides. V1 exposes
one per supported language, with Casual/Everyday/Formal register and the existing
punctuation choices. Migration copies the previous global punctuation once into
both profiles and retains existing regional defaults. Variety is stored but has
no editor yet; additional named profile selection is deferred. Each request
snapshots the active profiles in language order. Auto Detect selects the matching
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

Live unresolved assumptions use Review assumptions with a pending-only count.
Clarify opens the existing alternatives and settling one checks again. Read-only
History uses Meaning, and supplied answers are not counted as unresolved. The
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
same AssumptionItem and settling callback as Meaning. Clarify offers the current
reading plus distinct alternatives, so a writer can confirm or correct it. The
whole original text is checked again; answers belong to the check, not an inline
copy of the assumption. Duplicate question labels are not offered inline because
the existing settling action identifies a question by its label.

History stores the exact response already, so no new link table or database
migration is needed. Replay reads dependencies only from that attempt and only
when interpreted edits exactly match the recorded rows. Otherwise links remain
unknown/empty. Historical cards render the same information without actions.

The existing offline evaluator supports explicit complete writing_profiles and
validates/prints both fresh scores. The retrospective importer resolves only the
profiles recorded on the suggestion's own attempt. No conversation import, memory,
free-form style instructions or profile manager is part of V1.
