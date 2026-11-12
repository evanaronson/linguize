# Meaning

How the card answers "does it say what I mean, and how will it come across?" in one place.
Slug `meaning-lands`. Code: `ui/card/MeaningLands.kt`. Tests: `ui/card/MeaningLandsTest.kt`.

## The problem

The card had two ways to look at the same text. **Meaning** was a drill-down page (the
translation, "Not what you mean" (now "Clarify what you mean"), the assumptions). **How it lands** was a footer row that
opened in place (the reading, scales, driving words, notes). They feed each other: how a
message lands depends on what it means, and a clarified meaning changes how it lands. The
owner asked for one section. [The expand pattern](expand-pattern.md) had already decided that
nothing inside the card drills down, so the section is a `Disclosure`.

## Inventory

| Content | Before | Now |
|---|---|---|
| Translation (`meaning`, in the writer's language; empty when the text already is) | Meaning page, on a grey inset under "How your message was read" | First line(s) of the header; full and selectable when open |
| "Clarify what you mean" / the writer's clarification, edit and Remove | Meaning page | Open section, right under the meaning it corrects |
| Assumptions ("The suggestions rely on this reading…"), each with its alternatives, Clarify, answered ones with edit and Remove | Meaning page | Open section, after the clarification. Open ones are counted on the header |
| Assumptions under a change's explanation ("Assumes…", "You said…") | Explanation panel | Unchanged: they belong to that change |
| How it lands: overall + summary | Its own footer row | Header line, and the first line of the How it lands part |
| Driving words, warmth/directness/formality scales, notes, caveat | Under the How it lands row | Last part of the open section |
| Footer row subtitle "How your message was read" | Always, as the row's subtitle | Only when nothing else can be summarised (text already in your language, no reading, no assumptions) |
| Back arrow, page title, `NavigationBackHandler` level, page slide, scroll-to-top reveal | Meaning page | Gone |
| Unclear result: "Clarify what you mean" editor | Card body | Unchanged: it is the card's main task there, not secondary detail |
| History: Meaning block (inset, "Assumptions, 2 ›" link to a page, clarification row) and a separate How it lands block | Two blocks after Suggestions | One read-only Meaning section in the same place, readings inline |

## The hierarchy

One section, **Meaning**, at the card's foot. The name stays: it is what people already tap,
and "what it means" covers both the exact sense and its connotation.

**Collapsed** (the header; one tap target):

```
Meaning                                       ⌄
I want you to send me the contract.            (up to 2 lines)
Could land differently · polite but cold
2 assumptions                                  (only while some are unanswered)
```

**Expanded**, top to bottom, which is also the screen-reader order:

```
Meaning                                       ⌃
[ full translation, selectable ]
Clarify what you mean ›        or  Your clarification ›  Remove
Assumptions
  The suggestions rely on this reading. If it isn't what you meant…
  [answered readings]  [open readings ›]
How it lands
  Could land differently · polite but cold
  [text with driving words]  Highlighted words shape it most.
  Warmth / Directness / Formality scales
  Good to know: notes
  A likely reading. People and places vary.
```

Why this order: meaning first, because how it lands is judged on what it says. The writer's
own correction sits directly under the translation it corrects. The readings come next because
they are what the meaning rests on. How it lands comes last: it is the consequence, and it is
read-only. When open, the header drops its summary rather than repeating it above the full
version. The title stays put.

**Merged:** the translation, the clarification, the assumptions and the landing are one
section with one state.

**Separate:** only what belongs to one change (a change's "Assumes…" and "You said…" lines
and their inline clarify), and the unclear card's "Clarify what you mean", which is the card's
main content there.

**Dropped:** the Meaning page with its back arrow and Back level, the separate How it lands row
with its own chevron and state strings, the "How your message was read" caption above the
translation, and History's link to a separate assumptions page.

## Clarifying

Clarify is still a re-check. Sending a clarification from inside the section turns the card
to Checking. The new answer arrives with a new meaning, new assumptions and a new landing,
and the section is still open on them. The open state is kept by `ResultCard`
(`rememberSaveable`) and not inside the section, because the section leaves the composition
while the card checks. A different text starts closed. Nothing else changed: the editor,
choices, Something else, Cancel, edit and Remove are the same composables as before.

## Placement and the final action

The review flow ends on "2 changes accepted, Undo, Replace" (Send in the chat), and scrolls
that row into view when every change is decided. Meaning sits **below** it, edge to edge under
a hairline, like the old footer rows. So:

- Collapsed, it adds three short lines under Replace and never hides it.
- Opening it grows downward, so Replace never moves down. The card scrolls just enough to show
  the section, as every Disclosure does. Replace stays one short scroll up.
- Tone help is not lost down there. Changes that would help it land better are ordinary
  rewordings with their reason, above, in the walk.

Placing it between the suggestions and Replace would have put up to a screen of detail
between the last change and the button the walk leads to.

## Accessibility

- One header target with "Show how your message was read" / "Hide how your message was read",
  the state "Collapsed" / "Expanded", and the expand and collapse actions (`disclosureToggle`).
- The header is a heading. So are "Assumptions" and "How it lands" inside.
- Collapsed, the header reads as one phrase: "Meaning, I want you to send me the contract,
  Could land differently · polite but cold, 2 assumptions". Focus stays on it when it opens,
  and the content follows in reading order.
- No Back level: system Back closes the popup, as it does everywhere else on the card.

## History

The saved check shows the same section, read-only, collapsed, after Before, After and
Suggestions. Nothing can be clarified or removed. The assumptions use the past-tense caption.
`SessionAssumptionsScreen` is no longer linked from it. It stays routed so a back stack
restored from before this change still opens.

## Teach me

Same change, from the expand-pattern follow-ups: "Teach me" under a change's explanation is a
`Disclosure` now (down chevron, "Open a short lesson on this change" / "Hide lesson", state
read out). Opening it asks for the lesson. Its content is the loading line, the failure with
Retry, or the lesson. Collapsing it is Hide, so the failure has no Hide button of its own. The
lesson keeps one at its end, since a long lesson can scroll the header away.

## Limits

- The collapsed meaning is cut at two lines. The full text is one tap away.
- The middle dot in "Lands well · friendly" comes from How it lands' existing string.
- `SessionScreen`'s `onOpenAssumptions` and the `DetailAssumptions` screen are unused by the
  session page now, and can be removed in a cleanup together with `Screen.DetailAssumptions`.
