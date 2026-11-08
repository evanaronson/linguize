# Help me reply

Lab experiment, branch `lab/reply-drafter`.

## What it is

"Understand copied text" explains a native speaker's message in the writer's own
language. Help me reply closes the loop: after reading the message, the writer
says roughly what they want to answer, in their own language, or picks one of two
or three suggested answers ("Accept", "Decline politely", "Ask for another day").
Linguize drafts a short reply in the message's language, at the writer's register,
in simple words a learner could have written. Each sentence comes with what it
says in the writer's language, so they know exactly what they're sending. They can
edit it, ask for a simpler version, then copy it or put it back in the message
field.

## The moment it serves

The writer has just understood a message and is stuck on the answer. Today they'd
switch to a translator, type their reply in English and paste back whatever comes
out: often too formal, too idiomatic, or something they couldn't have written and
can't vouch for. Help me reply keeps them in the language and in control:

- The reply is in their register profile (casual, everyday or formal, and their
  variety), and matches the message's tú/vostè.
- It favours common words and short sentences, and a **Make simpler** button asks
  again for shorter sentences and commoner words.
- Every sentence has a gloss, and up to four **Words to learn** are listed with
  their meaning here.
- It writes only what the writer asked for: no invented times, excuses or promises.

## How to use it

- **From the accessibility button.** Tap the button in an empty message field (or
  tap **Understand copied text** from a check). Linguize reads the copied message
  and explains it. Tap **Help me reply**, say what you want to answer or pick a
  suggestion, and read the draft. **Put back** writes it into the empty message
  field you started from; **Copy reply** copies it. Back returns to the
  explanation.
- **From Home.** Under **Reply to a message**, open **Help me reply**, paste the
  message (or tap **Paste**), tap **Understand**, then **Help me reply**. Here the
  reply is copied.

Nothing is saved: no History records, no Lab database.

## How it works

| Piece | Where |
|---|---|
| Prompt (both tasks) | `app/src/main/assets/lab_reply-drafter_prompt.md` |
| Schemas (`intents`, `reply`) | `app/src/main/assets/lab_reply-drafter_schema.json` |
| Request building and answer validation | `llm/reply/ReplyPrompt.kt` |
| Domain types (draft, sentences, words, intents) | `core/reply/Reply.kt` |
| Settings, key and provider plumbing | `ReplyDrafter.kt` (root, beside `Understander`) |
| State: suggestions, describing, drafting, edits, late-answer handling | `ui/reply/ReplyViewModel.kt` |
| The reply page; Home entry and page | `ui/reply/ReplyCard.kt`, `ui/reply/ReplyScreen.kt` |
| Writing the reply into the empty field | `accessibility/ReplyPutBack.kt` |

Two small requests go through the existing Gemini/OpenAI clients' `generate` path
with their own schema, never the check contract:

1. **`intents`**, when the reply page opens: the message, the translation the
   writer read, their native language and writing profiles. Returns two or three
   `{label, instruction}` pairs in the native language. If it fails, the page just
   shows the text field.
2. **`reply`**: the same, plus the writer's `intent` (and `simplerThan`, the
   previous draft, for **Make simpler**). Returns the reply's `language`, its
   `sentences` (`text`, `gloss`, `lineBreak`) and up to four `words`.

The user message is one JSON object, so a hostile message or intent can't forge
another field; the prompt tells the model both are data. The reply text is built
by joining the sentences, so the gloss always lines up with what is shown. A
sentence without a gloss makes the answer unusable; a "word to learn" that isn't
in the reply is dropped. A request replaced by another (new message, Say something
else, Make simpler) can't overwrite the screen when its answer arrives late.

Put back writes only into the field the card opened from, only when that field
was empty (nothing to check) and still holds what it held then. Otherwise the
reply is copied and a toast says so.

Shared files touched, each with a one-line or short additive hook: `App.kt`
(the `replyDrafter`), `UnderstandCard.kt` (`onHelpReply`, `scrollable`),
`LinguizeAccessibilityService.kt` (the reply page and Put back), `HomeScreen.kt`,
`HomeActivity.kt` and `Navigation.kt` (the Home entry and `Screen.LabReply`).

## Limits

- The gloss describes the draft. Once the writer edits the reply, the page says so
  and keeps the draft's gloss; edits are not checked. (Checking the edit with the
  normal check would be a natural next step.)
- Suggestions see only this one message, not the conversation before it.
- The register comes from the writing profile for the message's language;
  languages without a profile get everyday register.
- From a check (a field with text in it), Put back isn't offered, so the writer's
  own draft is never replaced; the reply can be copied.
- In the app, the Home page has no accessibility field, so it can only copy.
- Not covered by the eval: reply quality depends on the model and was not measured
  against curated cases.
