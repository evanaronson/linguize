# Hear it

Read the message aloud, in the right accent, and speak a message instead of typing it.

## The moment

Much everyday messaging in Spain happens in voice notes, and plenty of written
messages are read aloud or said again in person. A learner who has just fixed
"vaig poder" to "podré" gets more from hearing it than from seeing it.
Hearing the corrected sentence at normal speed, then slowly, is how they learn to
say it as well as write it. Dictation serves the opposite moment. The learner can
say the message more easily than type it, and wants to know whether what they
said was right.

## How to use it

- **On a check card**, below the suggestions: **Hear it** plays the message with
  the changes accepted so far. It becomes **Stop** while it plays. **Slow** plays
  at 60% speed and stays on for later cards.
- **On a change**: open a change by tapping its underline. The speaker button plays
  the corrected words ("podré").
- **On Meaning**: the speaker button beside the meaning plays it in the writer's
  own language.
- **In Try it, on Write**: **Speak** opens the phone's speech recognizer in the chosen
  language. What it hears replaces the starter sample or an empty box, or follows
  the text already typed. Tap **Check** to check it.

All three hosts of the check card show it: the selection menu, the accessibility
button overlay and Try it. Past checks in History do not offer it.

## How it works

- `ui/hearit/SpeechLocales.kt` picks the voice. The check's pinned language comes
  first. In auto-detect, a small letter-and-word guess separates Catalan from
  Spanish, because the card doesn't keep the detected language. Spanish is the
  last resort. The writer's variety from their writing profile then picks the
  region: Peninsular → es-ES, Mexican → es-MX, then es-419 and es-US, Rioplatense
  → es-AR, Catalan → ca-ES, Andorran → ca-AD, then ca-ES. An unknown variety
  falls back to Spain. The plain language always comes last. Meaning uses the app's
  interface locale.
- `HearItController` owns one card's playback. Each control has a key, so pressing
  the playing control stops it and pressing another switches. The engine
  (`AndroidSpeechEngine`, a `TextToSpeech` wrapper) is bound on first play, not when
  the card opens, so checks are not slowed down. It tries each locale in turn and
  speaks with the first one that has a voice installed.
- **Missing voices and engines.** When no locale has a voice, the control shows
  "No voice for Spanish (Spain)" and **Open voice settings**. That button opens the
  engine's voice download when the voice can be downloaded, and otherwise Android's
  text-to-speech settings. When no engine binds, the message is "No speech engine".
  The engine is released after any problem, so a voice installed meanwhile is found
  on the next tap.
- **Lifecycle.** `ProvideHearIt` wraps `ResultCard`. Playback stops when the app
  goes to the background (`ON_STOP`, which also covers the overlay). A control that
  leaves the screen stops its own playback, for example on a page change, a new check
  or Undo. The engine is shut down when the card is dismissed, applied or replaced.
- **Screen readers.** Every control names its object and effect ("Hear your
  message", "Stop playing podré", "Hear the meaning", "Play slowly"). Problems are a
  polite live region. Audio uses media/speech attributes.
- **Dictation** launches `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` through the
  Activity Result API (free-form, one result, `EXTRA_LANGUAGE` from the same locale
  choice). The recognizer is another app's activity, so Linguize needs no
  `RECORD_AUDIO` permission and never gets the audio. With no recognizer, a toast
  says "No speech recognition on this phone".
- Storage: one boolean (`slow`) in the Lab-only SharedPreferences file `lab_hear-it`.
- Manifest: one `<queries>` entry for `android.intent.action.TTS_SERVICE`, which
  Android 11+ requires before an app can bind the text-to-speech engine.

## Limits

- Voice quality and accents depend on the phone's engine. Many phones have es-ES
  and es-US voices but not ca-ES until it's downloaded. Valencian is spoken with a
  ca-ES voice.
- The auto-detect guess is a heuristic for Catalan and Spanish only. Very short or
  mixed text falls back to Spanish, or to the writer's Spanish variety.
- A change plays only its replacement words, not the whole sentence around them.
  Pure insertions and deletions have no speaker button.
- Dictation needs a recognizer app (usually Google), and many recognizers need a
  connection. Some ignore the requested language.
- TalkBack and the voice can overlap if the screen reader is still speaking when
  playback starts.

## Tests

- `SpeechLocalesTest`: locale choice by variety, fallback order, native locale,
  Catalan/Spanish guess, dictation merge.
- `HearItControllerTest` (fake engine): lazy binding, toggle and switch, stale
  callbacks, locale fallback, missing voice and no engine, failures, slow
  persistence, shutdown.
- `HearItCardTest` (Robolectric): the card shows and toggles the controls, Meaning
  has its own, the card leaving releases playback, the dictation intent and result,
  and voice settings when no settings screen exists.
