# Card messages

Every message the check card shows instead of, or above, its suggestions: what happened, why,
and what to do next. Slug `card-copy`. The pattern is in [copy style](../copy-style.md) under
**Card messages**. This page is the inventory and the before and after.

## Why

The owner asked why the unclear card said "Check a full sentence". The title above it said
what happened, but the line under it was a bare instruction that read like an error code, and
it didn't mention the way out the card offered just below it ("Say what you meant"). The other
messages had the same gaps in places: a timeout and a busy service gave no next step, the
too-long hint told Notes and Chat writers to "select" text, several details ended in "Try
again" right above a Retry button, and the chat's wrong-language line stopped at "It looks
like French."

## The pattern

The card renders every message the same way (`Outcome`, then `Failure` in `ui/card/ResultCard.kt`):

| Part | Says | Where it comes from |
|---|---|---|
| Title | What happened, as a state. Also read in History and Settings, so never an instruction. | `Reason.titleResource`, or the unclear and wrong-language strings |
| Hint | What to do, starting with a verb, true in every host. | `Reason.hintResource`, `unclear_hint`, `wrongLanguageHint` |
| Detail | Why, in one sentence. No "Try again" above Retry. | `CheckFailure.Message.textResource`, or a host's own detail (`menu_check_not_sent`) |
| Action | One: Retry, Open settings, or Clarify what you mean. None for a limit. | `Failure`'s `when (reason)` |

The provider's own words, when it sent any, follow the detail as before.

## Before and after

Hosts: **P** the popup over another app (selection menu, accessibility button, share,
clipboard), **C** the chat, **N** Notes. The card is the same `ResultCard` in all of them;
only the wrong-language hint differs per host (`LanguageHint`).

### Results that aren't suggestions

| State | Hosts | Before | After |
|---|---|---|---|
| Unclear | all | Linguize couldn't tell what this means / Check a full sentence / Say what you meant › | Linguize couldn't tell what this means / Try writing it as a full sentence, or clarify what you mean. / Clarify what you mean › |
| Unclear, read-only card (no clarify) | any host without `onClarify` | … / Check a full sentence | … / Try writing it as a full sentence. |
| Wrong language, found | P | This isn't Catalan / It looks like French. Choose Auto Detect to detect the language. | This isn't Catalan / It looks like French. Choose Auto Detect to check it in that language. |
| Wrong language, not found | P | … / Choose Auto Detect to detect the language. | … / Choose Auto Detect to check it in any language. |
| Wrong language, found | N | … / It looks like French. Choose Linguize at the top to detect the language. | … / It looks like French. Choose Linguize at the top to check it in that language. |
| Wrong language, not found | N | … / Choose Linguize at the top to detect the language. | … / Choose Linguize at the top to check it in any language. |
| Wrong language, found | C | … / It looks like French. | … / It looks like French. Write in this conversation's language, or start a new conversation. |
| Wrong language, not found | C | … / This conversation is in one language. | … / Write in this conversation's language, or start a new conversation. |

"Detect the language" undersold what happens: choosing Auto Detect in the popup checks the text
again straight away, in the language it is in.

### The clarify action

| Where | Before | After |
|---|---|---|
| Unclear card | Say what you meant › | Clarify what you mean › |
| Meaning section, before a clarification | Not what you mean › | Clarify what you mean › |

Both rows open the same editor (`ClarifyBlock`, "What did you mean?", Clarify, Cancel), so they
now share one string, `clarify_what_you_mean`. The verb matches the editor's Clarify button.
Screen readers hear the row's text and its existing click label, "Clarify meaning".

### Failures

Title / hint / detail / action. Unchanged parts are marked "same".

| Reason | Why it happens | Before | After |
|---|---|---|---|
| NoKey | No key saved for the chosen service | No API key / Add one in settings / Open settings | same |
| BadKey | The service refused the key | API key rejected / Add a working key in settings / Open settings | same |
| BadKey + InvalidKey | The saved key has characters a key can't have | … / The saved key contains invalid characters. Paste the key again in settings. | same |
| BadModel | The chosen model is gone or not allowed for this key | Model not available / Pick another model in settings / Open settings | same |
| Offline | No network | No connection / Connect to the internet, then retry / Retry | same |
| Timeout | No answer in time | The response took too long / (none) / Retry | Took too long / Wait a moment, then retry / Retry |
| Server | The service had a problem (5xx) | Service unavailable / (none) / Retry | Service unavailable / Wait a moment, then retry / Retry |
| RateLimited | The service's own limit for this key (429) | Usage limit reached / Wait a minute, then retry / Retry | Service usage limit reached / same / same |
| TooMany | The selection menu's own limit; nothing was sent | Too many checks in a minute / Wait a minute, then select the text again / This check wasn't started. / (no button) | same |
| TooLong | Over Linguize's 3,000-character limit; nothing was sent | Text too long / Select a shorter part, then check again / Linguize checks up to 3,000 characters at a time. / (no button) | Text too long / Check a shorter part / same / same |
| BadResponse | An answer came but couldn't be used | No usable result / (none) / Retry | same, with the details below |
| + EmptyReply | | The result was empty. Try again. | The result came back empty. |
| + InvalidReply | | The result couldn't be used. Try again. | The result came back in a form Linguize couldn't read. |
| + TruncatedReply | | The result was cut off. Try again. | The result was cut off before the end. |
| + IncompleteReply | | The result was incomplete. Try again. | The result was missing parts. |
| + ReplyTooLarge | | The result was too long to use. Try a shorter selection. | The result was too long to use. Check a shorter part. |
| + Declined | The service wouldn't answer for this text | Linguize couldn't help with this text. | Linguize couldn't help with this text. Change it, then check again. |

"Service usage limit reached" is what copy style's before-and-after table already promised; the
string still said "Usage limit reached", which sat too close to "Too many checks in a minute".
"Took too long" reads correctly after a colon in History ("Couldn't check: Took too long") and
Settings ("Didn't work: Took too long"), where the old sentence-like title didn't.

The details are shared: the Teach me lesson, Patterns' explanation and Settings' model test show
them too. Each of those has its own Retry or Test button, so dropping "Try again" holds there.

### Around the card

| Where | Before | After |
|---|---|---|
| History, a failed check | Couldn't check · No connection | Couldn't check: No connection |
| Notes, empty text (under the field) | No text to check. | same |
| Accessibility button, empty field (toast) | No text to check. Tap the field you wrote in, then try again. | same |
| Success and partial results | Looks good / Nothing to fix, sounds natural; Some suggestions are unavailable / Linguize couldn't prepare all the suggestions. Check again for a new result. | same |

History saving has no card message: a check is saved after Replace, silently, and the card never
reports a saving error. History's own labels ("End not saved", "History is off. New checks aren't
saved.") already follow the pattern and are unchanged.

## What changed in code

Only strings and which string is shown. No behaviour, layout or recorded data changed.

- `strings.xml`: the strings above. `check_full_sentence` became `unclear_hint` and
  `unclear_hint_read_only`; `say_what_you_meant` and `not_what_you_mean` became
  `clarify_what_you_mean`; new `hint_wait_moment` for Timeout and Server.
- `strings_lab_consistency.xml`: the Notes and Chat wrong-language hints.
- `ui/text/Labels.kt`: Timeout and Server get `hint_wait_moment`.
- `ui/card/ResultCard.kt`: the unclear card picks its hint by whether it can clarify, and uses
  the default link text.
- `ui/card/Understanding.kt`: the clarify link's default is `clarify_what_you_mean`.

## Limits

- The order on the card is title, hint, then detail, so "why" reads after "what to do". Moving
  the detail up is a layout change, left out here.
- The Chat's translation bubble says "Tap to try again" under a failure title. That is the
  chat's own string and stays with the chat work.
