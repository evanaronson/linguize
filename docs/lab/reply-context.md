# Replying to

Most of what people check is a reply. "Sí, a las 5 me va bien" can be perfect Spanish
and still be wrong: they asked about Tuesday, they wrote to you with *usted*, or "la"
should be "lo" because the thing they mentioned is masculine. A check that sees only the
reply can't tell. Replying to lets the writer attach the message they are answering, so the
check reads the reply in context.

## The moment

The writer has just read a message in WhatsApp or Telegram, typed an answer, and wants a
gut check before sending. People usually copy the incoming message anyway (to translate
it, or to quote it), so the copied text is already the context.

## How to use it

- **On the card** (selection menu or accessibility button): under the result, *Replying to
  a message?* offers **Use copied text**, which attaches what is on the clipboard in one tap,
  and **Type it**, which opens a field to paste or type the message. The card checks again
  in context. Accepted changes that still apply stay accepted.
- **In Try it** on Write: the same entry sits above the text box, so a message can be
  attached before the first check, or while a card is open (which checks again).
- Once attached, the message shows compactly above the result (two lines; tap to show all)
  with **Remove**. Removing it checks again without context.

The attached message stays with the card (or Try it) until removed or the card closes.
It is not saved anywhere: not in History, not in Lab storage.

If the clipboard is empty or can't be read (the accessibility overlay doesn't always have
focus), the field opens with a note so the writer can paste or type instead.

## What the check does with it

The model judges only the writer's text, with every shipped rule still in force, and uses
the message as evidence:

- **Answer.** If the reply plainly answers something else, or leaves out what was asked
  (a time when a day was asked; "yes" to a negative question), it lists an assumption the
  writer can settle on Meaning.
- **Form of address.** *tú/usted*, *vosotros/ustedes*, *tu/vostè*, *vosaltres/vostès*: a
  reply that doesn't match how the other person addressed the writer gets a rewording that
  names the form; mixing forms within the reply is a fix.
- **References.** Pronouns, gender and number, people, days, times and things the message
  names. A clear contradiction is a fix; an ambiguous reference the message settles is
  read that way without asking.
- **Meaning.** Still a faithful translation of the reply alone. The message only decides
  the reading of pronouns, ellipsis and tense.

## How it works

- `llm/lab/ReplyPrompt` composes the request only when a message is attached: the shipped
  `check_prompt.md`, then `assets/lab_reply_context_prompt.md`; and one
  `Replying to: {"message": …}` JSON line in front of the shipped user message, which
  follows byte for byte. JSON keeps a line break or tag in the message from forging a
  setting or the text, and the writer's text stays last as the shipped prompt requires.
- The answer uses the shipped schema and is read by the shipped parser
  (`Prompt.read`, fresh-contract checks included), so the card, History and replay treat it
  as any other check. `llm/lab/ReplyingClient` wraps the provider for that one check, so the
  repair retry keeps the context too.
- `Checker.check(…, replyingTo)` and `Checker.context(replyingTo)` take the message. With
  `null` (the default, and every existing caller) the checker calls `client.check` with the
  shipped request exactly as before. With a message, the context's `promptHash` becomes
  `<shipped hash>-r<hash of the rules and message>`. History never reuses an answer across
  different contexts or with none, and it stores only that hash, never the message. No
  History schema change.
- `CheckViewModel.replyingTo` / `replyTo()` hold the message for a card and check again
  when it changes. `ui/replycontext` has the views and the entry's state (`ReplyDraft`).
  `CardActions.replyContext` brings it to `ResultCard`. A past check in History has none.

## Limits

- One message, up to 3,000 characters. Not a thread. Paste the last message or two.
- The clipboard is read only when **Use copied text** is tapped. In the accessibility
  overlay Android may refuse, and the writer pastes instead.
- The rules are a Lab prompt with no eval cases yet. Judgments about answers and register
  are suggestions to weigh, like any rewording.
- A check with a message costs a slightly longer prompt; the latency budget is unchanged
  otherwise.

## Tests

- `llm/lab/ReplyPromptTest`: composition with and without a message, that the shipped
  request is unchanged, forged lines, schema and hash.
- `ReplyCheckerTest`: the default path calls the shipped check; with a message the
  composed request is sent, parsed with the shipped schema, kept on repair retries.
- `ui/replycontext/ReplyContextViewModelTest`: attaching before and during a check,
  removing, the stale-settings check, and that History keeps the answer but not the message.
- `ui/replycontext/ReplyDraftTest`: the entry's clipboard, typing and card states.
