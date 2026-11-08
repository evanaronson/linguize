# Linguize Lab

Linguize Lab is the experimental build on `feature/bonanza`. It installs as a
separate app, "Linguize X Claude" (`com.evanaronson.linguize.xclaude`), beside
Linguize. Android gives each app its own private storage, so Lab never reads,
migrates or deletes Linguize's history, settings or encrypted API keys. Removing
Lab removes only Lab's data. `main` is unchanged.

Each experiment has its own page in this folder.

## Experiments

| Moment | Experiment | Where |
|---|---|---|
| Stuck mid-sentence | [Fill the gaps](gap-filler.md): write with [native words] where stuck; only the gaps are filled | Home; card row when text has gaps |
| Before sending | [How it lands](tone-check.md): warmth, directness, formality, and warmer or more polite versions | Card row; Try it |
| Before sending | [Replying to](reply-context.md): attach the incoming message so the check reads the reply in context | Card; Try it |
| Before sending | [Second opinion](second-opinion.md): rerun the check on the other provider and compare each change | Card; Settings |
| Before sending | [Per-app voice](app-voice.md): language, register and suggestions per source app | Settings; card chip |
| Before sending | [Say it in Catalan](language-bridge.md): turn the message into another profile language, phrase by phrase | Card row; Try it |
| Composing longer text | [Writing pad](writing-pad.md): drafts checked as you pause, with inline underlines | Home |
| Hearing and speaking | [Hear it](hear-it.md): play text, changes and Meaning aloud; dictate into Try it | Card; Try it |
| Reading what arrives | [Help me reply](reply-drafter.md): from Understand, draft a simple glossed reply | Understand card; Home |
| Reading what arrives | [Share and photo](share-photo.md): share text in; understand a photo of a letter or sign | Share sheet; Home |
| Anywhere | [Quick tile](quick-tile.md): Quick Settings tile and launcher shortcuts for copied text | System |
| Learning over time | [Teach me](teach-me.md): a one-minute lesson and quiz for any change | Change explanation |
| Learning over time | [Phrasebook](phrasebook.md): star natural phrasings and keep them | Change explanation; Home |
| Learning over time | [Your patterns](patterns.md): recurring fixes grouped from History, with sources | Home; History |
| Learning over time | [Practice](practice.md): spaced-repetition drills from your own applied fixes | Home |
| Learning over time | [Your words](words.md): every word you typed, counted, with tried words kept apart | Home; History |

## What Linguize is, from first principles

**The person.** An adult who lives in, or regularly writes in, a language that is
not their first: today Spanish and Catalan, with English as the usual native
language. They can already communicate. Their problem is not "how do I say this
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
