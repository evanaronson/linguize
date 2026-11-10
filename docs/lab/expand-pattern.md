# Expand pattern

How Linguize shows "more about this": when content opens in place, when it becomes a page, and the one component that does the opening. Code: `ui/components/Disclosure.kt`. Tests: `ui/components/DisclosureTest.kt`.

## The question

On the check card, **Meaning** is a drill-down: tapping it replaces the card's content with a Meaning page that has its own Back. **How it lands** is an accordion: tapping it opens the details under the row. The owner liked the accordion but asked whether it is out of place, too risky, or something the app should use more widely.

Short answer: the accordion isn't out of place. The app already opens things in place more often than it drills down, but it does so in five different ways. The fix is one shared component, plus a clear rule for when a page is the right choice.

## Inventory

| Where | What opens | Component today | Interaction | Back | Accessibility today |
|---|---|---|---|---|---|
| Card: Meaning | Meaning, clarification, assumptions | `UnderstandingEntry` footer row → `UnderstandingPage` in `AnimatedContent` (`ResultCard.kt`) | Drill-down. The page replaces the suggestions, sliding in from the end | System Back and the page's back arrow return to the suggestions. A second Back closes the popup | Row with a click label. The page has a heading. Focus lands at the top of the new page, and the suggestions' scroll position is lost |
| Card: How it lands | Scales, drivers, notes | `LandingFooter` (`tonecheck/`): its own row, rotated `Chevron`, `AnimatedVisibility` | Accordion in the footer | Back closes the popup | Click label, state "Expanded"/"Collapsed" |
| Card: underlined words | The change, why, Accept, Teach me, star, assumptions | `HighlightedText` + `ExplanationPanel`: tap a word, and the panel animates in under the text. Spacing panels are collapsed rows that open in place | Selection-driven in place. One panel at a time, scrolled into view | Back closes the popup | "Why" click label, polite live region on the panel |
| Card: assumptions / clarify | Clarify editor (choices + field) | `ClarifyLink` (`›`) and `AssumptionInset` (`›`), replaced in place by `ClarifyBlock` | In place, but the end chevron suggests a page | Cancel button. Back closes the popup | Click label "Clarify …". No expanded state |
| Card: Teach me | A lesson with a quiz | `TeachMe` (`ClarifyLink` "Teach me ›"), loading, then the lesson in place | In place, with a request behind it | Hide button (on failure). Back closes the popup | Live regions for loading and failure. No expanded state |
| Card: Phrasebook star | Nothing. It saves | `SavePhraseButton` | Toggle | n/a | Toggle state. Not a disclosure |
| Chat: corrected message | What changed under the bubble | Tap the bubble (like a reply's translation flip); History's `SavedBefore` + `SavedSuggestions` open under it (`ChatCorrections.kt`), state in `ChatViewModel.expanded` (one open at a time) | In place | n/a | Bubble click label "Show corrections"/"Hide corrections", state Expanded/Collapsed |
| Chat: partner bubble | Its translation | `Crossfade` in the bubble | Flip: replaces in place | n/a | Click label "Show translation"/"Show original" |
| History: saved check | One saved suggestion's explanation | `SavedSuggestion` row with a `▾` text glyph, no animation | Accordion per row, saveable | n/a | Click label, state "Expanded"/"Collapsed" |
| Phrasebook / Memory rows | Row actions (Open check, Delete, Edit) | Whole row toggles a `FlowRow` of buttons | In place | n/a | Click label "Show/Hide actions". Also custom actions |
| Conjugations: verbs | The full list | "Show all verbs" text button | One-way "show more" | n/a | Button |
| Progress | Words, Patterns, Practice, Conjugations, Phrasebook, Time | `ListRow` → child pages in `Navigation.kt` | Drill-down to a screen | Top-bar back. Scroll position kept by the saveable state holder | Page titles |
| Settings | Each choice | `ListRow` → `ChoicePage` | Drill-down to a screen | Top-bar back | Radio/checkbox semantics |
| Words / Patterns | One word, one pattern | List → `WordDetailScreen` / `PatternDetailScreen` | Drill-down | Top-bar back | Page titles |

Two findings matter:

1. **Outside the card, everything that goes somewhere is a page.** Progress, Settings, Words and Patterns all drill down. They hold a lot of content, have their own actions, are linked from several places, and work well with app navigation and system Back.
2. **Inside the card and in lists, nearly everything opens in place.** The card has explanations, spacing panels, clarify, Teach me and How it lands. Chat has corrections. History has saved suggestions. Phrasebook and Memory have row actions. Meaning is the only page inside the card. In-place opening is already the app's main pattern for secondary detail. It has just never been made into a component, so each place draws its own chevron or none, as `›`, `▾`, `▴` or a rotated `›`, and announces its state in its own way, or not at all.

## Principles

**The popup is not a screen.** It floats over WhatsApp with limited height, often with the keyboard up, and system Back closes it. People glance at it for a few seconds before they send. That gives four constraints:

- **Keep the text in view.** Nearly every detail on the card is *about* the text: why this word, how the message lands, what we assumed it means. When the detail is next to the text, people can compare the two. A page hides the text, so they have to remember it, go back, and look again.
- **Back is expensive.** Inside the popup, a page is a second Back level that system Back has to know about (`NavigationBackHandler`). If someone presses Back once too often, the popup closes and the check is gone. Opening in place adds no Back levels.
- **Scroll position and focus are fragile in a small viewport.** A page swap throws away the suggestions' scroll position, and TalkBack focus jumps to the top of the new page. Opening in place keeps both: the header stays where it was, and focus stays on it.
- **State restoration is cheap in place.** An expanded flag is one saveable boolean. A page inside a card is a second piece of navigation state that has to be keyed to the result (`rememberSaveable(reviewed, settled)`).

**Size and weight decide the rest.** Content belongs in place when it is short to medium in length (roughly up to a screen), is read-only or has a light action (Accept, Retry, a quick clarify), and is about the thing right above it. It deserves a page when it is long, has its own navigation or many actions, is worth linking to from elsewhere, or is a destination someone goes to on purpose (a word, a pattern, Progress, a setting).

## Decision

**Inside the card, and inside list rows, secondary detail opens in place with one shared `Disclosure`. Full pages are for navigation-level content outside the card.**

- The **header stays put** and summarises the detail ("How it lands · Lands well, friendly"). The **chevron points down when closed and up when open**. A chevron that points to the end (`›`) keeps meaning "go somewhere": a page, or an editor that takes over the row (clarify).
- **Several sections can be open at once.** The exception is selection-driven detail, which keeps one open at a time: underlined words, chat corrections. In those cases, opening one item means looking at it, not adding it to the page.
- **Nothing inside the card drills down.** Meaning should become a Disclosure next to How it lands. The other agent's merge does this with this component. That makes the card one surface with one Back meaning: close.
- **Outside the card, pages stay pages.** Progress children, Settings choices, Word and Pattern detail, and History sessions are destinations. An accordion there would make long screens longer and hide items that people want to reach directly. Lists may still open *small* detail in place, such as History's saved suggestions and row actions.
- **Is it risky?** Only when the open content is long or the header scrolls away. The component limits both: opening scrolls just enough to show the new content (never on restore), and the header stays above it. Content longer than a screen belongs on a page.

## Where it applies

Using `Disclosure` now:

- History, saved check: each saved suggestion (`SavedSuggestions.kt`). Before this change it used a `▾` glyph with no motion.
- Chat, corrected message: the bubble itself is the toggle (the "N changes" chip and its own Changes panel are gone). It opens History's saved suggestions, which are Disclosures, under the bubble with the Disclosure motion; the view model keeps one open at a time. See [Chat](chat.md).

Should adopt it later. Each is left alone here because another agent is reworking it or because it needs a separate decision:

- **Card, Meaning + How it lands**: done. They are one `Disclosure`, Meaning, at the card's foot, and the Meaning page, its Back level and the separate How it lands row are gone. See [Meaning](meaning-lands.md).
- **Card, change explanations / spacing panels** (`ExplanationPanel`'s `expanded`/`onToggle`): use `disclosureToggle` for the collapsed spacing rows once the review list rework has landed.
- **Teach me**: done. "Teach me" is a Disclosure whose content handles loading, failure and the lesson. Hide is collapse. See [Meaning](meaning-lands.md#teach-me).
- **Phrasebook and Memory rows**: revealing row actions is closer to a selection than to a disclosure. Keep the whole-row tap, but take the state semantics from `disclosureToggle` so TalkBack says Expanded/Collapsed.
- **Conjugations "Show all verbs"**: a one-way "show more" is fine as a button. Leave it.

Stays a page: Progress children, Settings choice pages, Word and Pattern detail, History session, Chat threads.

Stays something else: the translation flip (replaces in place: same size, same place, a different version of the same text) and the Phrasebook star (a toggle).

## The component

```kotlin
// Hoisted state; the usual saveable state comes from rememberDisclosureState(keys).
@Composable fun Disclosure(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    header: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    expandLabel: String? = null,      // default "Show details"
    collapseLabel: String? = null,    // default "Hide details"
    headerPadding: PaddingValues = PaddingValues(vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
)

// Title + muted summary header, own saveable state keyed by `key`.
@Composable fun Disclosure(title: String, modifier: Modifier = Modifier, summary: String? = null,
    key: Any? = null, expandLabel: String? = null, collapseLabel: String? = null,
    headerPadding: PaddingValues = ..., content: @Composable ColumnScope.() -> Unit)

@Composable fun rememberDisclosureState(vararg keys: Any?, initiallyExpanded: Boolean = false): MutableState<Boolean>
@Composable fun Modifier.disclosureToggle(expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    expandLabel: String? = null, collapseLabel: String? = null): Modifier
@Composable fun DisclosureChevron(expanded: Boolean, modifier: Modifier = Modifier, size: Int = 22)
object DisclosureMotion { EXPAND_MILLIS = 220; COLLAPSE_MILLIS = 160 }
```

Use `disclosureToggle` + `DisclosureChevron` when the trigger and the content are laid out apart, such as a chip under a chat bubble. Use `Disclosure` everywhere else.

## Accessibility rules

- **One target, the whole header**, at least 48dp tall. The chevron is decorative (`clearAndSetSemantics {}`).
- **Semantics:** a click action labelled with what it does ("Show how it lands" / "Hide how it lands", default "Show details" / "Hide details"), a state description of "Expanded" or "Collapsed", and the platform `expand` / `collapse` actions, so TalkBack's actions menu and switch access work.
- **Don't speak the action as the state.** The state describes the section, and the label describes the tap.
- **Focus stays on the header** when the section opens or closes. Content follows the header in reading order, so swiping right reads it next. No live region is needed for static content. Content that loads after opening (Teach me) announces its own loading and failure in a polite live region.
- **Never hide the only way to act.** If the detail holds the only Accept, the header must say there is more (as the change rows do).
- **Saveable**: state survives rotation and process death (`rememberSaveable` keyed by the item). A restored open section does not scroll.

## Motion

- Open: grow from the top plus fade in, 220ms, `FastOutSlowInEasing`. Close: shrink plus fade out, 160ms. The chevron turns over the same time.
- After opening, the card or screen scrolls just enough to show the section (`bringIntoView` on the whole disclosure, so if the content is taller than the viewport the header lands at the top). There is no scroll on restore or on close.
- Compose scales these durations with the system animator scale, so turning animations off in Android settings makes them instant.

## Limits

- `Disclosure` does not decide how many sections can be open at once. Keep the state in a view model (as Chat does) for one-at-a-time.
- The History strings `history_expanded` / `history_collapsed` are no longer used by the code. They were left in place to keep shared string files untouched, and can be removed in a cleanup.
