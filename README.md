# Linguize

Linguize is the gut check that your Spanish was Spanish enough. Or that your Catalan was Catalan enough.

Even when you're fluent or able to communicate in your target language, you often want to make sure before you hit send: does this make sense, is anything wrong, would a native speaker say it this way?

You could do Google Translate, but it's built for the opposite job: translating *into* the language you're learning. Paste your own text in and translate it back, and you'll find out whether it's roughly understandable. You won't find out what you got wrong. A good translator reads right past your mistakes and awkward phrasing, because that's what makes it a good translator.

Checking your writing isn't a translation task. Linguize does just that one job. Select what you wrote in any app, tap **Linguize** in the selection menu, and a card floats over the app with:

- **Fixes:** the actual mistakes, each highlighted, with the smallest change and a short reason.
- **More natural:** how a native speaker would say it, only when it's clearly better.
- **Meaning:** what your text says, translated into your own language, so you can confirm it says what you meant.

Replace what you want, in-line, and you're back where you were.

## Install

**[Download the latest APK](https://github.com/evanaronson/linguize/releases/latest/download/linguize.apk)**. Every push to `main` that changes the app builds a new one; older builds are under [Releases](https://github.com/evanaronson/linguize/releases).

1. Open the link on your phone and open the downloaded file. When Android asks, allow your browser to install unknown apps.
2. Open **Linguize**, pick Google Gemini or OpenAI, paste that provider's API key ([Gemini](https://aistudio.google.com/apikey), [OpenAI](https://platform.openai.com/api-keys)) and tap Save. Keys are stored encrypted on the phone and are never in the code.
3. Try a sentence in **Try it** on the same screen. Then select text you've written in any app and look for **Linguize** in the selection menu (on Samsung it can be behind **⋮** in the menu).

Home, the screen Linguize opens to, has Try it and, below it, **Recent**: the checks you've made, newest first. Tap one to see it as the card showed it; swipe it away to delete it. Settings is behind the gear. Its **History** section turns remembering off (on by default; what's already kept stays), shows how much is kept, and has **Clear** and **Export** (a JSON Lines file). History stays on the phone.

That's all most apps need. Later builds install over the previous one because CI signs each one with the same private key.

**Installed Linguize before 9 October 2026?** Builds until then were signed with a key that was public, so they were removed and the key was replaced. An app signed with the new key can't update one signed with the old key: uninstall Linguize once (Settings → Apps → Linguize → Uninstall; this deletes your history and saved keys), then install the latest APK. Later builds update normally again.

To check a download, compare its signing certificate with the SHA-256 fingerprint in its release notes (`apksigner verify --print-certs linguize.apk`), or check where it was built with `gh attestation verify linguize.apk --repo evanaronson/linguize`.

### Optional: apps without the menu entry (e.g. Telegram, the Claude app…)

Some apps don't show other apps' entries in their selection menu. For those, Linguize can add an accessibility button instead: while typing, tap the button and the same card floats over the app, then accepted changes are written back into the text field. It reads the focused text field only when you tap the button, and never a field for passwords, codes, numbers or email addresses. Back, or a tap outside the card, closes it.

Android makes this a few taps because the app wasn't installed from the Play Store:

1. **Settings → Accessibility → Installed apps → Linguize** and try to turn it on. Android blocks it with a "Restricted setting" message the first time; that's expected.
2. **Settings → Apps → Linguize → ⋮ (top right) → Allow restricted settings**, and confirm.
3. Back in **Settings → Accessibility → Installed apps → Linguize**: turn it on, along with its shortcut. A floating Linguize button appears (on Samsung you can choose between the floating button and the navigation-bar button).

The **Apps without the menu** section of Linguize's settings has the same steps and a button straight to Accessibility settings.

## Code layout

Everything is under `app/src/main/java/com/evanaronson/linguize/`. Dependencies run one way, checked by reading every `import`: `codec` imports nothing of the app, `core` only `codec`, `llm` only those, `history` only `codec` and `core`, and `data` only `codec`, `core` and `llm` (and `R`). The root `App`, `CheckContext` and `Checker` sit on all of those, `ui/` on the root and below, and `menu/` and `accessibility/` on top of `ui/`. There are no cycles. Inside `ui/`: `components` and `Clipboard` are the base, then `card`, then `history` and `settings`, then `home`.

| Package | Role |
|---|---|
| `codec/` | `Tokens`: how an enum is kept. Every kept enum spells out a fixed lowercase `token` on each constant (the same in the database, the export, settings and the prompt) and has one `Tokens` for them; an unknown token reads as null instead of crashing. |
| `core/` | Pure Kotlin, no Android or network, unit-tested. The check's options (`Language`, with an ISO code and `Language.all`, the list pickers show; `Punctuation`, `Judgments`), the model's answer as the schema defines it (`Verdict`), and the edit engine: `Alignment` and `Edits` turn the answer into `Edit`s anchored to the original text (positions come from aligning the texts, never from the model; each punctuation mark is its own fix; rewordings are found against the corrected text, then mapped back), `Revision` tracks accepting and undoing with the rules written out at its top, and `interpret()` produces a `CheckResult`. `Selection` splits selected text from the whitespace around it. |
| `llm/` | Talking to models. `Prompt` is the contract: the instructions and schema in `assets/`, the user-message format, the output limit and reading answers. `GeminiClient` and `OpenAIClient` implement `ProviderClient` over plain REST (`Http` is the shared call). Adding a provider takes a `Provider` entry (with its token, which is also what its saved key and model are kept under), a client and an entry in `App`'s client map. `CheckFailure` is every way a check can fail. |
| `history/` | What the phone remembers about checks; design and decisions in `docs/history-spec.md`. `HistoryModel` has the records (`SessionRecord`, `SuggestionRecord`, `Attempt`, and the `Origin`, `Decision` and `Outcome` enums), built to be uploaded as-is later. Rows keep enums as their tokens (see `codec/`) and the requested language as its code. `SessionRecording` (pure Kotlin, tested) turns what happens on one card into a session and its suggestions with their decisions. `CheckHistory` is what the card talks to: it opens, records and closes sessions off the main thread and races the model against a kept answer for the same text. `Replay` rebuilds the card a past session ended with, from its decided attempt and its suggestion rows. `HistoryStore` is the storage interface; `SqliteHistoryStore` implements it on the framework's SQLite, with its SQL and row mapping in `HistorySchema` and the JSON (the `attempts` column, the export) in `HistoryJson`. |
| `data/` | What the phone remembers besides history: `Settings`, `ApiKeys` (encrypted with an Android Keystore key, kept under each provider's token; keys saved under a provider's name by earlier builds are moved on first use) and `SelectionMenu` (which menu entries are on, kept as the enabled state of their activity-aliases). `CheckPreferences` and `ProviderKeys` are the small interfaces the checker reads them through. |
| root | `CheckContext` is what a check runs with, read once per run: the one place that says what affects a check. `Checker` runs a check with it, on `CheckPreferences` and `ProviderKeys` (so it's tested on the JVM with fakes); `App` creates the long-lived objects (settings, keys, the prompt, the history store, the checker and its clients, an application-wide scope for writes that outlive a screen). View models get what they need through their `factory`, not by reaching into `App`. |
| `ui/card/` | The result card and its `CheckViewModel`, which owns a check and the accepted changes and reports to `CheckHistory`; `CardActions.of()` gives the card the same behaviour everywhere. |
| `ui/components/` | Shared controls, the language picker (auto-detect and `Language.all`, named as the selection menu names them) and date formatting. |
| `ui/history/` | The Recent list and the page for one past check (the card `Replay` rebuilds, shown with the card's own content), with their view models. |
| `ui/home/` | `HomeActivity`, the launcher screen: Try it, then Recent, with Settings and a past check's page as screens of the same activity. `SettingsReturn` tells the overlay's card that opened Settings when Settings is left. |
| `ui/settings/` | The settings screen (one file per section, History included) and its view model. |
| `ui/theme/` | The brand theme. |
| `menu/` | `CheckActivity`, opened from a selection-menu entry; `CheckRateLimit` caps how many checks other apps can start through it (a few a minute per app). |
| `accessibility/` | The accessibility button: `LinguizeAccessibilityService` reads the focused field and writes accepted changes back; `OverlayWindow` shows the card above other apps. |

The prompt and schema live in `app/src/main/assets/` so the eval script uses exactly what the app sends, and a unit test checks every example in the prompt against the schema.

## Tuning the judgments

The prompt is where most of the product lives. To test a prompt change against real sentences, including how often it nags about text that's already fine, and to measure latency:

```sh
GEMINI_API_KEY=... python3 eval/run.py
OPENAI_API_KEY=... python3 eval/run.py --provider openai
```

Add cases to `eval/cases.jsonl` when the app gets something wrong.

## Brand

The wordmark is **lingu·ize**: the raised dot (the Catalan *punt volat*, as in col·legi) marks where *-ize* snaps onto a word, as in catalan·ize and castilian·ize. The launcher icon is "·ize", cut straight from the wordmark. It's ink on paper, and cobalt (`#3340F0`, lighter `#7C84FF` in dark mode) is the one colour, used on the wordmark's dot and as the app's accent.

`brand/` holds the SVGs. Every letter is drawn in code from one geometric kit in `brand/source/`; `python3 brand/source/build.py` rebuilds them. The app's copies are vector drawables: `wordmark.xml`, `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml`.

## Build locally

You need JDK 21 (the Robolectric tests need it) and the Android SDK. `./gradlew testDebugUnitTest` runs the tests and `./gradlew installDebug` puts a debug build on a connected phone. `./gradlew assembleRelease` writes an unsigned APK to `app/build/outputs/apk/release/`; add `-PdebugSignedRelease` to sign it with your debug key so it installs. A local build has version 1 and isn't signed with the release key, so it won't install over a build from CI without uninstalling first.

### How releases are built and signed

`.github/workflows/build.yml` builds in two jobs. **build** runs the tests and `assembleRelease` with no secrets and read-only access, and uploads the unsigned APK. **sign-and-publish** runs no code from the repo: it downloads that APK, signs it with the runner's own `apksigner`, verifies the signature, attests its provenance and publishes the release, with the certificate's SHA-256 fingerprint in the notes. Its key lives only in the `signing` GitHub environment (`SIGNING_KEYSTORE_BASE64`, a base64 PKCS12 keystore, plus `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`), which only `main` and `feature/*` may use. Without them the job stops with an error saying which are missing.

Gradle checks what it downloads against two files: `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` pins the Gradle distribution, and `gradle/verification-metadata.xml` pins the SHA-256 of every plugin and library. To write them, and again after changing the Gradle version or any dependency, run the workflow by hand with **pin_build_inputs** checked (`gh workflow run build.yml --ref <branch> -f pin_build_inputs=true`); it uploads both files, regenerated, as the `build-inputs` artifact to review and commit.
