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
2. Open **Linguize**, tap the settings gear, pick Google Gemini or OpenAI, paste that provider's API key ([Gemini](https://aistudio.google.com/apikey), [OpenAI](https://platform.openai.com/api-keys)) and tap Save. Keys are stored encrypted on the phone and are never in the code.
3. Try a sentence in **Try it** on Home. Then select text you've written in any app and look for **Linguize** in the selection menu (on Samsung it can be behind **⋮** in the menu).

Home, the screen Linguize opens to, has **Check your text** and, below it, **History**: your last five checks, newest first. When there are more, **View all** opens History, which loads older checks as you scroll. Tap any check to open it; Back returns to the list you came from, at your previous position. Swipe a row to delete it with Undo. Settings is behind the gear. The popup's wordmark button, to the left of **Auto Detect**, opens the app without closing the check or losing accepted changes. Meaning stays inside the popup; Settings belongs to the app. Its **History** section turns saving off (on by default; saved checks stay), shows how much is saved, and has **Delete all** and **Export** (a JSON Lines file). History is stored on the phone; Export lets you share it with another app.

That's all most apps need. Later builds install over the previous one because CI signs each one with the same private key.

**Installed Linguize before 9 October 2026?** Builds until then were signed with a key that was public, so they were removed and the key was replaced. An app signed with the new key can't update one signed with the old key: uninstall Linguize once (Settings → Apps → Linguize → Uninstall; this deletes your history and saved keys), then install the latest APK. Later builds update normally again.

To check a download, compare its signing certificate with the SHA-256 fingerprint in its release notes (`apksigner verify --print-certs linguize.apk`), or check where it was built with `gh attestation verify linguize.apk --repo evanaronson/linguize`.

### Optional: apps without the menu entry (e.g. Telegram, the Claude app…)

Some apps don't show other apps' entries in their selection menu. For those, Linguize can add an accessibility button instead: while typing, tap the button and the same card floats over the app, then accepted changes are written back into the text field. It reads the focused text field only when you tap the button and skips fields that look like passwords, codes, numbers or email addresses. Back, or a tap outside the card, closes it.

Android makes this a few taps because the app wasn't installed from the Play Store:

1. **Settings → Accessibility → Installed apps → Linguize** and try to turn it on. Android blocks it with a "Restricted setting" message the first time; that's expected.
2. **Settings → Apps → Linguize → ⋮ (top right) → Allow restricted settings**, and confirm.
3. Back in **Settings → Accessibility → Installed apps → Linguize**: turn it on, along with its shortcut. A floating Linguize button appears (on Samsung you can choose between the floating button and the navigation-bar button).

The **When Linguize isn't in the menu** section on Home has the same steps and a button straight to Accessibility settings.

## Code layout

The app has one Gradle module, `:app`. Production Kotlin lives under
[app/src/main/java/com/evanaronson/linguize/](app/src/main/java/com/evanaronson/linguize/),
with regression tests in [app/src/test/](app/src/test/). Start with the
[documentation index](docs/README.md) for current contracts, or the
[architecture guide](docs/architecture.md) for ownership and the reasons behind
this structure.

Dependencies between major packages run one way: `codec` imports nothing else
from the app; `core` only `codec`; `llm` and `history` only those two; and `data`
only `codec`, `core`, and `llm` (plus generated resources). Root classes compose
them; `ui` uses root and below; `menu` and `accessibility` are entry points above
`ui` and do not import each other. `codec` and `core` stay free of Android imports.

| Package | Role |
|---|---|
| `codec/` | Stable tokens for saved enums and serialization |
| `core/` | Check options, verdicts, text alignment, anchored edits, and accepting or undoing changes |
| `llm/` | Prompt contract, Gemini/OpenAI clients, HTTP transport, and check failures |
| `history/` | Recording, SQLite storage, reuse, and replay; see [History today](docs/history.md) |
| `data/` | Settings, encrypted API keys, selection-menu configuration, and temporary export files |
| root | `App` creates shared services; `Checker` runs checks; `CheckContext` captures the settings used |
| `ui/card/` | The shared result card, actions, and view model used by all three check hosts |
| `ui/home/`, `ui/history/`, `ui/settings/` | App screens and their state; `HomeActivity` owns the full app's navigation |
| `ui/popup/` | Opening the app from a popup and returning to the existing check |
| `ui/components/`, `ui/text/`, `ui/theme/` | Shared controls, localized labels, and theme |
| `menu/`, `accessibility/` | Selection-menu and accessibility hosts, source-field handling, and replacement |

`python3 scripts/check_architecture.py` checks production imports and package
declarations before Gradle runs in CI. It treats `ui/` as one layer so hosts can
share the card and controls. Fully qualified references and dependencies introduced
by libraries still need code review.

The [prompt and schema](app/src/main/assets/) are shared with the eval; unit tests
check the prompt's examples. English UI strings and plurals use Android resources
in `app/src/main/res/values/strings*.xml`; see [localization](docs/localization.md)
and [copy style](docs/copy-style.md). Model IDs, stored tokens, and the prompt are
separate contracts.

History supplies stored evidence for later analysis. The [architecture guide](docs/architecture.md#evidence-first-derived-learning-later)
explains what is still needed before those observations can support learning claims.

## Tuning the judgments

The prompt is where most of the product lives. To test a prompt change against
curated sentences, including whether it nags about text that's already fine, and
to measure latency:

```sh
GEMINI_API_KEY=... python3 eval/run.py
OPENAI_API_KEY=... python3 eval/run.py --provider openai
```

Add a reviewed case to `eval/cases.jsonl` when the app gets something wrong. See
the [eval guide](eval/README.md) for running and interpreting these checks.

## Brand

The wordmark is **lingu·ize**: the raised dot (the Catalan *punt volat*, as in col·legi) marks where *-ize* snaps onto a word, as in catalan·ize and castilian·ize. The launcher icon is "·ize", cut straight from the wordmark. It's ink on paper, and cobalt (`#3340F0`, lighter `#7C84FF` in dark mode) is the one colour, used on the wordmark's dot and as the app's accent.

[brand/](brand/) holds the wordmarks, icon variants, and construction studies.
Every letter is drawn in code from one geometric kit in `brand/source/`;
`python3 brand/source/build.py` rebuilds the SVGs. The app's copies are separate
vector drawables: `wordmark.xml`, `ic_launcher_foreground.xml`, and
`ic_launcher_monochrome.xml`. The generator does not update those XML files.

## Build locally

You need JDK 21 (the Robolectric tests need it) and the Android SDK. `python3 scripts/check_architecture.py` checks package boundaries without the SDK. `./gradlew testDebugUnitTest lintRelease` runs the regression tests and Android lint; `./gradlew installDebug` puts a debug build on a connected phone. `./gradlew assembleRelease` writes an unsigned APK to `app/build/outputs/apk/release/`; add `-PdebugSignedRelease` to sign it with your debug key so it installs. A local build has version 1 and isn't signed with the release key, so it won't install over a build from CI without uninstalling first.

The repository tooling also has offline tests, with no provider credentials needed:

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s eval -p 'test_*.py'
```

### How releases are built and signed

`.github/workflows/build.yml` builds in two jobs. **build** checks architecture, runs the tests and `lintRelease`, then runs `assembleRelease`, with no secrets and read-only access. It uploads the unsigned APK and keeps test/lint reports in a `check-reports` artifact, including on failures. **sign-and-publish** runs no code from the repo: it downloads that APK, signs it with the runner's own `apksigner`, verifies the signature, attests its provenance and publishes the release, with the certificate's SHA-256 fingerprint in the notes. Its key lives only in the `signing` GitHub environment (`SIGNING_KEYSTORE_BASE64`, a base64 PKCS12 keystore, plus `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`), which only `main` and `feature/*` may use. Without them the job stops with an error saying which are missing. Pull requests only run the unsigned job; each PR or branch has its own concurrency group, while running main builds finish before another main build begins.

Gradle checks what it downloads against two files: `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties` pins the Gradle distribution, and `gradle/verification-metadata.xml` pins the SHA-256 of every plugin and library. To write them, and again after changing the Gradle version or any dependency, run the workflow by hand with **pin_build_inputs** checked (`gh workflow run build.yml --ref <branch> -f pin_build_inputs=true`); it uploads both files, regenerated, as the `build-inputs` artifact to review and commit.

[The eval workflow](.github/workflows/eval.yml) runs offline evaluation-tool tests
for eval changes on `main`, including pushes that do not need an APK release.
The APK workflow runs these tests on every pull request and app build as well.
