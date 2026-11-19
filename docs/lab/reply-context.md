# Replying to

Most of what people write is a reply. "Sí, a las 5 me va bien" can be perfect Spanish and
still be wrong: they asked about Tuesday, they wrote to you with *usted*, or "la" should be
"lo" because the thing they mentioned is masculine. A check that sees only the reply can't
tell.

In the [chat](chat.md) the app already has the message being answered. So every chat check
reads the draft as a reply to the partner's most recent message. There is nothing to
attach, tap or remove. In other apps the writer can add the message themselves, but only
when a check couldn't tell what their text means ([from other apps](#from-other-apps)).

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
- **Other apps, only when unclear and only by choice.** A check from the selection menu, the
  accessibility button, share or the quick tile checks the text alone. When it comes back as
  "Linguize couldn't tell what this means", the card offers to add the message being
  answered ([below](#from-other-apps)). Nothing is attached automatically.
- **Not in Notes.** Notes checks the text alone and offers nothing.

## From other apps

A short reply in WhatsApp ("Sí, a les 5", "La del dimarts") often can't be read alone, and the
check says so. The writer knows what it answers; the card lets them say it.

- **Where.** On the unclear card from another app only: below the hint and **Clarify what you
  mean**, a section headed **What are you replying to?** Not on a reviewed or failed card, not
  in the chat (which already sends its context), not in Notes. Hosts opt in by passing
  `ReplyContextActions` in `CardActions.of(…, replyContext = …)`: `menu/CheckActivity`,
  `menu/ShareActivity`, `menu/ClipboardActivity` and the accessibility service.
- **Pick their message.** Only on the accessibility button's card, where the service is running
  and knows the field the text came from. On the tap, and only then, it reads the field's window
  (`accessibility/ConversationOnScreen`): visible, non-editable nodes with text that end above
  the field, in screen order, skipping buttons, fields and text with no letters (times). The last
  four become rows under **Tap their message**; it doesn't guess which are the other person's.
  Nothing readable: **No messages found. Copy it, then tap Paste.** The selection menu, share and
  quick tile cards are activities covering the app; the service can't read behind them without
  window-retrieval flags, which [the accessibility spec](../accessibility-spec.md) rules out, so
  they offer Paste only.
- **Paste.** Reads the clipboard on the tap (the card is in front). No text: **Copy their message
  first.**
- **Then.** The chosen message appears under **Replying to** in quotes, with a × (heard as
  "Remove") and **Check again**, which calls `CheckViewModel.checkReplyingTo`: the same text is
  checked again (`Run.Again`, the same History session) with the message as `replyingTo`, through
  exactly the chat's path (`Checker.check(…, replyingTo)`, `ReplyPrompt`, the
  `lab_reply_context_prompt.md` addendum). The new result replaces the card. The card's later
  re-checks keep the message; the next check drops it. No setting.
- **Kept nowhere.** What was read sits in the section's Compose state (`ReplyPickState`, not
  saveable) and is gone with the card. The message is sent only on Check again, is never corrected
  or shown anywhere else, and History stores only the reply hash, as for the chat.
- **The model isn't told.** The shipped `check_prompt.md` and `check_schema.json` are unchanged, and
  nothing in any prompt says that an unclear answer can bring more context.

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
- `CheckComposition.prepare` returns the complete request, its compatibility hash and the
  base fresh-answer reader in `PreparedCheck`. `Checker` sends it through the provider's
  `generate` API; the repair request retains the same message and captured settings.
  Fresh answers use the base parser, while History reuse keeps the existing lenient
  parser for older answers.
- `Checker.check(…, replyingTo)` and `Checker.context(replyingTo)` take the message; with
  `null` (every host but the chat) nothing changes. `CheckViewModel` keeps it privately for the
  card's re-checks (retry, clarifications) and drops it with the next fresh check.

## Limits

- One message, up to 3,000 characters. Not the thread.
- In other apps, context comes only from the writer, after an unclear check. Picking from the
  screen works only on the accessibility button's card, and reads whatever the app exposes to
  accessibility (some apps expose little; a name or title can appear among the rows).
- Offline contracts cover the composed request; dedicated live cases for reply quality
  are still absent. Judgments about answers and register are suggestions to weigh, like
  any rewording.

## Tests

- `llm/lab/ReplyPromptTest`: composition with and without a message, that the shipped
  request is unchanged, forged lines, schema and hash.
- `ReplyCheckerTest`: the default path sends the unchanged base request; with a message
  the composed request is sent, parsed with the base contract and kept on repair retries.
- `llm/lab/PreparedCheckContractTest` and `ComposedCheckContractTest`: fixed request
  bytes and compatibility hashes, including forged reply lines, repair and parser policy.
- `ui/card/ReplyContextCheckTest`: a check with and without a message, re-checks keep it,
  History keeps the writer's text but not the message, and the manual UI is gone.
- `ui/chat/ChatReplyContextTest`: the partner's last message is chosen, Send passes it, and
  no other host passes one.
- `ui/card/ReplyingToSectionTest`: the section sits below Clarify on an unclear card with reply
  actions only (not without them, as in Chat and Notes, nor on a reviewed card), Pick only when the
  screen can be read, the picker's rows, the empty-screen and empty-clipboard messages, the preview
  with Remove and Check again, and which hosts offer it.
- `ui/card/ReplyingToCheckTest`: Check again sends the message through the reply path and the
  result replaces the card, later re-checks keep it and a new card drops it, and History keeps
  the text but never the message.
- `accessibility/ConversationOnScreenTest`: rows from a fake node tree (above the field, screen
  order, no buttons, times, hidden nodes or fields), an empty screen, and a field that went away.
