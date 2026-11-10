# Linguize Lab

Linguize Lab is the experimental build on `feature/bonanza`. It installs as a
separate app, "Linguize X Claude" (`com.evanaronson.linguize.xclaude`), beside
Linguize. Android gives each app its own private storage, so Lab never reads,
migrates or deletes Linguize's history, settings or encrypted API keys. Removing
Lab removes only Lab's data. `main` is unchanged.

Each experiment has its own page in this folder. [Home and navigation](home-ia.md)
explains where things are: a bottom bar with Chat, Notes, Progress and History, and
Settings behind the gear.

## Experiments

| Moment | Experiment | Where |
|---|---|---|
| Stuck mid-sentence | [Gaps in every check](gaps-in-check.md): leave [native words] where stuck; every check fills them as fixes | Every check: card, Chat, Notes |
| Before sending | [How it lands](tone-check.md): part of every check; how the message will come across, with tone fixes as ordinary rewordings | Card row, everywhere |
| Before sending | [Replying to](reply-context.md): every chat check reads the draft as a reply to the partner's last message | Chat (automatic) |
| Before sending | [Per-app voice](app-voice.md): language, register and suggestions per source app | Settings; card chip |
| Writing a message | [Notes](notes.md) (once Write): a list of notes, each on its own page, checked with the same card as everywhere; Copy or Share when done (replaced Try it and the Writing pad) | Notes |
| Anywhere | [Share text](share-text.md): share text in from another app to check it or understand it | Share sheet |
| Anywhere | [Quick tile](quick-tile.md): Quick Settings tile and launcher shortcuts for copied text | System; the Notes shortcut opens Notes |
| Learning over time | [Teach me](teach-me.md): a one-minute lesson and quiz for any change | Change explanation |
| Learning over time | [Phrasebook](phrasebook.md): star natural phrasings and keep them | Change explanation; Progress |
| Learning over time | [Your patterns](patterns.md): recurring fixes grouped from History, with sources | Progress |
| Learning over time | [What Linguize knows](memory.md): short notes about you, learned from chats or added, all editable | Settings, About you |
| Learning over time | [Words](words.md): a frequency list and every word you typed, in one list: used, not yet, tried; coverage over time ([decks](vocab-gaps.md)) | Progress |
| Every day | [Chat](chat.md): the app opens to a conversation (Settings › Start on can pick another tab); each message is checked before it goes, saved in History, and the partner steers toward your gaps or stands in for someone real | App start; bottom bar; History; Settings |
| Learning over time | [Your conjugations](conjugations.md): the tenses and persons you use, from your own text; Spanish and Catalan morphology | Progress |
| Learning over time | [Time spent](time-spent.md): time writing and reviewing, measured in the chat and on the card, estimated for other apps | Progress |
| Looking back | [One list of messages](history-flat.md): History is every message you wrote, chat or app alike, one row each under day headers, filterable by source | History tab |
| Everywhere | [Language scope](language-scope.md): one language selector in the top bar (Linguize, then the writer's languages by brand name, such as Catalanize or Gallicize); Chat, Notes, Progress and History follow it. [Home and navigation](home-ia.md): one top bar on every tab | Top bar |
| Everywhere | [Expand pattern](expand-pattern.md): secondary detail opens in place with one shared Disclosure; pages only for destinations | History, Chat, the card |
| Everywhere | [Any language](language-generic.md): any ISO 639-1 language works end to end; the writer's languages are the selector's short list, with one writing profile each | Top bar; Settings; card picker |
| Before sending | [Meaning](meaning-lands.md): what the text says and how it lands, as one section that opens in place at the card's foot | Card, History |

## Language scope

The writer's current language is app-level context. One selector in the top bar of the
four tabs sets it: **Linguize** (Auto, the default: every language), or one of the writer's
languages by its brand name (**Catalanize**, **Castilianize**, **Gallicize**, …). The Chat list and new conversations, Notes' checks, Progress and History
follow it; no screen has a language choice of its own. Checks from other apps aren't scoped:
they detect the language (or use the Catalanize or Castilianize menu entry) and file under
it. Data with no known language shows only under Linguize. See
[Language scope](language-scope.md) and [Home and navigation](home-ia.md).

## What Linguize is, from first principles

**The person.** An adult who lives in, or regularly writes in, a language that is
not their first: any language, from Spanish or Catalan to French, German or Japanese,
and any native language. They can already communicate. Their problem is not "how do I say this
at all" but "is what I wrote right, and does it sound like a person from here?"
The writing is short, social and consequential: a WhatsApp to a landlord, a
message to a colleague, a reply in a neighbours' group, an email to the school.

**The moment.** The second before sending. The person is inside another app, with
text they wrote themselves. Their attention and confidence are both thin. Anything
that pulls them out of that app, rewrites their voice, or makes them doubt correct
writing costs more than it gives.

**The job.** A gut check, not a translation. Three questions, in order:
1. *Is anything wrong?* Smallest repairs, each one separately acceptable (Fixes).
2. *Would a native say it this way?* Only when clearly better (More natural).
3. *Does it say what I meant?* The meaning, back in my own language (Meaning).

Then: replace in place, and return to the conversation.

**What the product believes.**
- *The writer's voice is the asset.* Minimal edits, one decision per change,
  register respected; casual writing is not wrong.
- *Trust is earned with evidence.* Every change has a reason, confidence and
  impact; uncertain readings are surfaced as assumptions the writer can settle.
- *Not nagging is a feature.* Correct text should come back clean.
- *Privacy by construction.* The person brings their own model key; it is
  encrypted on the phone; history stays on the phone and is opt-out.
- *Stay out of the way.* Selection menu, accessibility button, a floating card,
  fast cold start.
- *Evidence first, learning later.* History records what happened; any learning
  derived from it must name its sources and disappear when they are deleted.

**Adjacent jobs it already touches.** Understanding incoming text ("Understand
copied text"), clarifying intended meaning, and a History of applied checks.

**Where it can grow without betraying that.** The person's life in the language
has more moments than "before send": understanding what arrives, replying when
stuck, sounding right for *this* recipient, hearing how it sounds, and slowly
needing the check less. The Lab experiments explore those moments.

## Ground rules for experiments

- On brand: serves a learner writing to, or reading from, native speakers.
- Additive: new files and packages under the existing layers; small hooks into
  shared files. The shipped check prompt and schema are not changed; an
  experiment that talks to a model brings its own prompt asset.
- Lab-local storage only: no changes to the History schema or migrations. An
  experiment that stores data uses its own file or database, and one that derives
  data from History reads it and stays deletable.
- Tested: JVM or Robolectric tests for the logic; the full build passes.
