# Linguize Lab

Linguize Lab is the experimental build on `feature/bonanza`. It is a separate app
(`com.evanaronson.linguize.lab`, named "Linguize Lab") that installs beside
Linguize. Android gives each app its own private storage, so Lab never reads,
migrates or deletes Linguize's history, settings or encrypted API keys. Removing
Lab removes only Lab's data. `main` is unchanged.

Each experiment has its own page in this folder.

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
