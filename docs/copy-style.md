# Copy style

How Linguize talks. Use it for every string a person can see or hear: screens, the card, toasts, Android's settings pages, and screen-reader labels.

## Who reads it

The owner and friends. Not developers. Most are writing in a language that isn't their first, whichever it is, often in the middle of a conversation in another app. They want help that is fast, easy to trust, and gets out of the way.

## Method

Before writing a string, answer four questions:

1. **Moment.** When does this appear, and what did the person just do?
2. **What they know.** What can they see? What do they not know yet?
3. **Goal.** What are they trying to get done right now?
4. **The one thing to say.** Usually what happened, or what to do next. Say that, and nothing else.

If a string needs two ideas, use two short sentences, not a semicolon.

## Words

One word per idea, everywhere.

| Idea | Say | Don't say |
|---|---|---|
| Sending text to be looked at | **check** (verb and noun) | scan, review, verify |
| Correcting a mistake | **fix**, fixes | correction, error |
| Making it sound more natural | **rewording**, rewordings | naturalize, ways to sound more natural |
| Either of the two | **change**, changes | edit, suggestion (in counts) |
| Saying yes to a change on the card | **accept**, accepted | apply, take, taken |
| Text going back into the other app | **put back** | applied, sent, went back |
| The card's last button | **Replace**; **Send** in the chat | Apply, Done, OK |
| Text kept on the phone | **saved**; the feature and the Home list are **History** | kept, remembered, recorded |
| What you write in the app's editor | **note**, notes; the tab is **Notes** | draft, Write (as a place) |
| Removing it | **delete** | clear, remove |
| The user's credential | **API key** in titles and field labels, then **key** | token, secret |
| The app doing the work | **Linguize**, or simply describe what happened | AI, LLM |
| The exact model a user picks | **model**, only in Settings next to the model picker | |
| Named services | **Gemini**, **OpenAI**, when the person needs to know which | |
| Languages | The language's name: **Catalan**, **Spanish**, **French** | Castilian, castellano, a country |
| Where Linguize appears in other apps | **selection menu** | text menu, context menu, PROCESS_TEXT |

**Linguize** and the language brands are the product's names. Every language gets one in the same pattern, the language plus "-ize" (**Catalanize**, **Castilianize**, **Gallicize**, **Germanize**; the full list is the [language catalog](lab/language-catalog.md)). They appear in the selection-menu entries ("… X Claude", today Linguize, Catalanize and Castilianize) and in the app's language selector, which says them plainly: **Linguize** (Auto, all languages, the default), then the writer's languages in their order, each with what it means under it in the menu ("All languages", "Catalan", "French"). The popup card's language picker, which only shows over other apps, uses **Auto Detect** for automatic detection and keeps the brand names for the named choices. In sentences, name the language, not the brand: "No messages in Catalan yet", "In French. Change it at the top." Don't write copy that assumes a particular language: an example or a sample names one language as an example, and a feature that only covers some languages says so ("Your conjugations isn't available for French yet"). Inside the app, point at the selector by its brand name ("Choose Linguize at the top to detect the language"), never at a picker that isn't there. The popup app button uses the Linguize wordmark image.

Linguize owns its help and its failures. Avoid “AI” in user-facing copy. Name the model or service only when it helps someone configure the app or understand where their text goes. Preserve necessary proper names such as OpenAI and Google AI Studio.

## Patterns

**Card messages.** Everything the check card says instead of suggestions (a failure, an unclear text, the wrong language, a limit) has the same three parts, in this order, and [card messages](lab/card-messages.md) lists them all.

1. **Title: what happened**, as a state in a few words ("No connection", "API key rejected", "Linguize couldn't tell what this means"). Titles also show on a past check in History ("Couldn't check: No connection") and in Settings' model test ("Didn't work: Took too long"), so no instructions in titles ("No API key", not "Add an API key").
2. **Hint: what to do**, starting with a verb ("Pick another model in settings", "Wait a moment, then retry"). It names the next step in the host the card is in, or one that is true in all of them ("Check a shorter part", not "Select a shorter part"). Leave it out only when the button says it all and a detail line says why.
3. **Detail, when there is one: why**, one sentence, in quieter type ("Linguize checks up to 3,000 characters at a time", "The result was cut off before the end"). It doesn't repeat the button: no "Try again" above Retry. It gives a step only when the step differs from the button ("Paste the key again in settings").

Then **one action**: Retry, Open settings, or Clarify what you mean. No button when there is nothing to do here (a limit), and then the hint carries the step. A bare instruction is never the whole message: "Check a full sentence" read as an error code, so the unclear card says what happened first.

**Buttons.** A verb, plus an object when the verb alone is unclear. Three words at most: "Accept all", "Copy with fixes", "Open settings", "Delete key".

**The card's last step.** The card is the same everywhere, so its actions are worded the same everywhere. Only the verb for what finally happens differs, and it says what will happen: **Replace** where the checked text goes back in place of what you wrote (another app's field, the clipboard, Try it), **Send** in the chat, where the message goes. Same row, same place: "2 changes accepted, Undo, Replace" or "… Send". Accept, Accept all, Undo and the counts never change. With nothing accepted, the chat offers "Send as written", matching the "Sent as written" it shows afterwards. History records both the same way: a change is Accepted or Not accepted.

**Captions and helper text.** One idea, about 12 words or fewer. Don't repeat the heading.

**Case and punctuation.** Sentence case everywhere. No period on titles, labels, buttons or single-phrase status lines. Periods on full sentences, and on any toast or caption with two sentences. Use a comma between parallel facts ("3 fixes, 1 rewording"); no middle dots. Use a colon for "and here's the thing" ("Didn't work: …"). No semicolons, no exclamation marks, no emoji.

**Counts.** Number first, singular and plural: "1 fix", "2 fixes". The same noun in headers, status lines and History.

**True in every state.** A label must be true in every state it can show in, including loading, off, empty and failed. If one line can't be true in all of them, write one line per state. Before "Replace", changes are *accepted*, not put back.

**No blame.** Describe what Linguize could not do, without blaming the writer or deflecting to the model: "Linguize couldn't tell what this means". "No text to check", not "Type something first".

**No promises.** Describe what the app does today. History lists past checks. It does not analyse mistakes, so don't say it will.

**History pages speak in the past.** Don't tell people to do things they can't do on that page.

**Screen readers.** Labels name the object and the effect: "Open check", "Delete check", "Show how your message was read", never a bare "Open" or "Close". Glyphs that only decorate (✓ ✗ ‹ › ▾) are hidden from screen readers, or replaced by words. Write changes in words ("cami becomes camí"), not arrows.

**Platform limits.**
- Selection-menu labels: one short line, often cut off at around 18 characters. Keep the product names.
- Toasts: about 3 seconds. About 45 characters for a short toast, about 80 for a long one. Say what happened and the next step.
- Accessibility-service description (Android's settings page, shown before a frightening system warning): say what it reads, when it reads it, and where the text goes (the service the user set up). Say what it skips as "looks like", because it guesses. Never claim "never".

## Before and after

| Before | After | Why |
|---|---|---|
| Can't tell what this means | Linguize couldn't tell what this means / Try writing it as a full sentence, or clarify what you mean. | Own the limitation, and say what to do. A bare "Check a full sentence" read as an error code. |
| Say what you meant, Not what you mean › | Clarify what you mean › | One name for the one action, matching its Clarify button. |
| The response took too long (no hint) | Took too long / Wait a moment, then retry | Every failure says what to do. |
| The result couldn't be used. Try again. (above Retry) | The result came back in a form Linguize couldn't read. | The detail says why. The button already says Retry. |
| Couldn't check · No connection | Couldn't check: No connection | No middle dots. |
| 2 changes applied | 2 changes accepted | Nothing is put back until Replace. |
| 1 way to sound more natural | 1 rewording | Same name as in Settings and History. |
| Add an API key | No API key / Add one in settings | A title is a state, and History shows it too. |
| Rate limited / Try again in a moment | Service usage limit reached / Wait a minute, then retry | Jargon, and too close to "Too many checks". |
| Write (the tab), New draft, Drafts | Notes, New note, Other notes | A tab is a place; what it keeps are notes. |
| Language: Catalan (a switch), Auto Detect by Check, Language ▾ in the new-conversation sheet, All languages ▾ in History | One selector: Linguize, then the writer's languages (Catalanize, Castilianize, …) | One language choice, in the product's own names. |
| Checks you make will show up here. | Changes you apply in other apps will appear here. | Analysis and Copy alone are not saved; Try it remains excluded. |
| 3 fixes, 1 rewording, 2 taken | 3 fixes, 1 rewording, 2 accepted | One verb for accepting. |
| Sent | Put back in the app | "Sent" reads as "message sent". |
| Apply | Replace (Send in the chat) | Say what will happen to the text. |
| Applied / Not applied (History) | Accepted / Not accepted | One verb for accepting, true for checks and chat messages. |
| Kept on this phone only, so Linguize can later show you the mistakes you tend to make. | Your text and the suggestions are saved on this phone only. | Promised a feature that doesn't exist. |
| Fields for passwords, codes, numbers and email addresses are never read. | It skips fields that look like passwords, codes, numbers or email addresses. | The check is a guess. Don't overclaim. |

## Visual language

Copy and type work together on the card. An underline is always a change you can accept: solid in proof vermilion for a fix, dotted grey for a rewording. A full-width row with a chevron opens clarification ("Clarify what you mean ›", "Assumed you meant: The meeting is tomorrow ›"). Readings use upright text; the chevron and tap target distinguish interactive rows from read-only information. A colon separates UI framing from the model's reading, preserving its capitalization and tense in live results and History. Proofmark red also identifies destructive actions: the swipe-to-delete background and every Delete or Remove action, including confirmation buttons. The swipe background uses the theme's contrasting foreground.

A checkmark with text identifies a completed review: "Sounds natural" or "All changes accepted". Accepted changes are still waiting for Replace (Send in the chat); the checkmark does not imply they were put back in the source app. Clarification updates the result directly without a separate confirmation banner.

An incomplete check groups its explanation and Check again action above the available suggestions. This describes unusable parts of the model's reply, not failure to apply the writer's accepted changes. Keep the notice neutral and usable suggestions available.

**Filters are not buttons.** A control that chooses what a page shows (a language, a source, a deck, Used or Not yet, a sort) is a quiet dropdown: the choice and "▾", opening a menu with the current one ticked (`ui/components/FilterDropdown`). In a row of several it names what it chooses ("Sort: Newest"). Buttons, filled, tonal, outlined or text, are only for actions, so a filter never looks like something that will happen. The app's language selector is the same dropdown with an icon (Linguize's "A" tile or a flag), in the top bar of the four tabs only; screen readers hear the brand name and the language ("Language: Catalanize, Catalan"), never a country. There is one: no page, sheet or in-app card gets a language dropdown of its own.

**Titles.** A tab's top bar shows the wordmark, never the tab's name: the bottom bar says it. A page opened from a tab shows its own title once, in the bar. Don't repeat it as a heading in the page.
