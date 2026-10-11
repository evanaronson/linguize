# Copy style

How Linguize talks. Use it for every string a person can see or hear: screens, the card, toasts, Android's settings pages, and screen-reader labels.

## Who reads it

The owner and friends. Not developers. Most are writing in a second language, often in the middle of a conversation in another app. They want help that is fast, easy to trust, and gets out of the way.

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
| Text kept on the phone | **saved**; the feature is **History**; the Home list is **Recent** | kept, remembered, recorded |
| Removing it | **delete** | clear, remove |
| The user's credential | **API key** in titles and field labels, then **key** | token, secret |
| The thing that checks | **AI**; **AI service** for the company side | model, provider, LLM |
| The exact model a user picks | **model**, only in Settings next to the model picker | |
| Named services | **Gemini**, **OpenAI**, when the person needs to know which | |
| Languages | **Catalan**, **Spanish** | Castilian, castellano |
| Where Linguize appears in other apps | **selection menu** | text menu, context menu, PROCESS_TEXT |

**Linguize**, **Catalanize** and **Castilianize** are names. Use them only for the selection-menu entries and the language picker that copies them, never in running text.

## Patterns

**Failures.** Title = the state, in a few words ("No connection", "API key rejected"). Hint = what to do, starting with a verb ("Pick another model in settings"). Leave the hint out only when the button below says it all ("Retry"). A failure with no button always has a hint. Titles must also read correctly on a past check in History, so no instructions in titles ("No API key", not "Add an API key").

**Buttons.** A verb, plus an object when the verb alone is unclear. Three words at most: "Accept all", "Copy with fixes", "Open settings", "Delete key".

**Captions and helper text.** One idea, about 12 words or fewer. Don't repeat the heading.

**Case and punctuation.** Sentence case everywhere. No period on titles, labels, buttons or single-phrase status lines. Periods on full sentences, and on any toast or caption with two sentences. Use " · " between parallel facts ("3 fixes · 1 rewording"). Use a colon for "and here's the thing" ("Didn't work: …"). No semicolons, no exclamation marks, no emoji.

**Counts.** Number first, singular and plural: "1 fix", "2 fixes". The same noun in headers, status lines and History.

**True in every state.** A label must be true in every state it can show in, including loading, off, empty and failed. If one line can't be true in all of them, write one line per state. Before "Done", changes are *accepted*, not put back.

**No blame.** When the AI fails, say the AI failed: "The AI couldn't tell what this means", not "Can't tell what this means". "No text to check", not "Type something first".

**No promises.** Describe what the app does today. History lists past checks. It does not analyse mistakes, so don't say it will.

**History pages speak in the past.** Don't tell people to do things they can't do on that page.

**Screen readers.** Labels name the object and the effect: "Open check", "Delete check", "Show how the AI read it", never a bare "Open" or "Close". Glyphs that only decorate (✓ ✗ ‹ › ▾ ⓘ) are hidden from screen readers, or replaced by words. Write changes in words ("cami becomes camí"), not arrows.

**Platform limits.**
- Selection-menu labels: one short line, often cut off at around 18 characters. Keep the three names.
- Toasts: about 3 seconds. About 45 characters for a short toast, about 80 for a long one. Say what happened and the next step.
- Accessibility-service description (Android's settings page, shown before a frightening system warning): say what it reads, when it reads it, and where the text goes (the AI service the user set up). Say what it skips as "looks like", because it guesses. Never claim "never".

## Before and after

| Before | After | Why |
|---|---|---|
| Can't tell what this means | The AI couldn't tell what this means / Check a full sentence in Catalan or Spanish | Blamed the text. Gave no next step. |
| 2 changes applied | 2 changes accepted | Nothing is put back until Done. |
| 1 way to sound more natural | 1 rewording | Same name as in Settings and History. |
| Add an API key | No API key / Add one in settings | A title is a state, and History shows it too. |
| Rate limited / Try again in a moment | AI usage limit reached / Wait a minute, then retry | Jargon, and too close to "Too many checks". |
| Checks you make will show up here. | Checks from other apps will appear here. | Try it checks are never saved. |
| 3 fixes · 1 rewording · 2 taken | 3 fixes · 1 rewording · 2 accepted | One verb for accepting. |
| Sent | Put back in the app | "Sent" reads as "message sent". |
| Kept on this phone only, so Linguize can later show you the mistakes you tend to make. | Your text and the suggestions are saved on this phone only. | Promised a feature that doesn't exist. |
| Fields for passwords, codes, numbers and email addresses are never read. | It skips fields that look like passwords, codes, numbers or email addresses. | The check is a guess. Don't overclaim. |
