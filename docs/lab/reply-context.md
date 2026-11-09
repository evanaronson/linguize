# Replying to

Most of what people write is a reply. "Sí, a las 5 me va bien" can be perfect Spanish and
still be wrong: they asked about Tuesday, they wrote to you with *usted*, or "la" should be
"lo" because the thing they mentioned is masculine. A check that sees only the reply can't
tell.

In the [chat](chat.md) the app already has the message being answered. So every chat check
reads the draft as a reply to the partner's most recent message. There is nothing to
attach, tap or remove.

## The moment

The writer has read the partner's message, typed an answer and tapped Send. The check that
runs before it goes is the same [one check](tone-check.md) as everywhere, with the
conversation's last message as context.

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
- **Saying so.** When the message is what makes a change needed, the change's reason says so
  in a few words ("They used usted", "Matches their question"), so the card explains a change
  that the draft alone wouldn't justify.
- **Meaning.** Still a faithful translation of the reply alone. The message only decides
  the reading of pronouns, ellipsis and tense.

## Where it applies

- **Chat only, automatically.** `ui/chat/ChatReplyContext.of` takes the partner's last
  message in the conversation (trimmed; a very long one keeps its end), and `ChatViewModel`
  passes it to the card's check (`DraftCheck.start` → `CheckViewModel.check(…, replyingTo)`).
  The first message in a conversation that has no partner message yet goes without context.
- **Not elsewhere.** The selection menu, the accessibility popup and Notes check the text
  alone: they don't know what it answers, and guessing from the clipboard was a manual step
  that is gone. The card has no "Replying to" header or entry, and Notes has none.

## Privacy and History

The partner's message is context only. It is never shown on the card, never enters the
corpus, and History stores only the writer's text and the answer. The context's
`promptHash` is `<shipped hash>-r<hash of the rules and message>` (then the gap and tone
suffixes), so History never reuses an answer across different contexts or with none, and it
stores only that hash, never the message. No History schema change.

## How it works

- `llm/lab/ReplyPrompt` composes the request: the composed check instructions (shipped, gap
  rules, tone rules; see `llm/lab/CheckComposition`), then `assets/lab_reply_context_prompt.md`;
  and one `Replying to: {"message": …}` JSON line in front of the shipped user message, which
  follows byte for byte. JSON keeps a line break or tag in the message from forging a setting
  or the text, and the writer's text stays last as the shipped prompt requires.
- The answer uses the composed schema and is read by the shipped parser, so the card, History
  and replay treat it as any other check. `llm/lab/ReplyingClient` wraps the provider for that
  one check, so the repair retry keeps the context too.
- `Checker.check(…, replyingTo)` and `Checker.context(replyingTo)` take the message; with
  `null` (every host but the chat) nothing changes. `CheckViewModel` keeps it privately for the
  card's re-checks (retry, clarifications) and drops it with the next fresh check.

## Limits

- One message, up to 3,000 characters. Not the thread.
- The partner is the app's own chat partner; real conversations in other apps get no context.
- The rules are a Lab prompt with no eval cases yet. Judgments about answers and register
  are suggestions to weigh, like any rewording.

## Tests

- `llm/lab/ReplyPromptTest`: composition with and without a message, that the shipped
  request is unchanged, forged lines, schema and hash.
- `ReplyCheckerTest`: the default path calls the shipped check; with a message the
  composed request is sent, parsed with the shipped schema, kept on repair retries.
- `ui/card/ReplyContextCheckTest`: a check with and without a message, re-checks keep it,
  History keeps the writer's text but not the message, and the manual UI is gone.
- `ui/chat/ChatReplyContextTest`: the partner's last message is chosen, Send passes it, and
  no other host passes one.
