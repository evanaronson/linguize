# Architecture

Linguize is one Android app with one Gradle module, `:app`. Keep that shape while
its components ship together and share behavior across the app's entry points.
Packages give the edit engine, model clients, history, and Android hosts clear
owners without adding a module or framework for each responsibility.

## Repository map

| Location | Owns |
|---|---|
| [app/src/main/java/com/evanaronson/linguize/](../app/src/main/java/com/evanaronson/linguize/) | Production Kotlin, described below |
| [app/src/main/assets/](../app/src/main/assets/) | The shipped model prompt and answer schema; also read by the eval |
| [app/src/main/res/](../app/src/main/res/) | Android strings, themes, icons, and platform configuration |
| [app/src/test/](../app/src/test/) | JVM and Robolectric regressions, generally mirroring production packages |
| [eval/](../eval/README.md) | Curated linguistic cases and checks against live models |
| [scripts/](../scripts/) | Repository checks and their offline tests |
| [brand/](../brand/) | SVG deliverables and the Python geometry that generates them |
| [gradle/](../gradle/) | Dependency versions, wrapper, and download verification metadata |
| [Build workflow](../.github/workflows/build.yml) | Tests, lint, unsigned build, and a separate signing/publishing job |
| [docs/](README.md) | Current contracts and conventions; historical designs live in `archive/` |

Keep the prompt under app assets so it ships with the app and has one source.
Keep tests next to their platform's normal source set so Gradle and IDEs discover
them. The SVGs include wordmarks, icon variants, and construction studies; they
remain useful design outputs even when Android does not load them. Their generator
writes SVGs; the Android vector drawables are separate copies.

## Package ownership and direction

The package root is `com.evanaronson.linguize`. In the table, a package can also
import itself. `R` and `BuildConfig` are generated resources, separate from the
composition root.

| Package | Responsibility | Other app layers it may import |
|---|---|---|
| `codec` | Stable enum tokens and serialization | None |
| `core` | Check options, verdicts, alignment, suggested edits, and accepting or undoing edits | `codec` |
| `llm` | Prompt formatting/parsing, provider APIs, transport, and check failures | `core`, `codec` |
| `history` | Recorded evidence, recording rules, storage, answer reuse, and replay | `core`, `codec` |
| `data` | Preferences, credentials, selection-menu configuration, temporary export files | `llm`, `core`, `codec`, resources |
| root | `App` composes services; `Checker` runs checks; `CheckContext` captures their settings | All of the above, resources |
| `ui` | View models, shared card, app screens, navigation, and localized presentation | Root and all lower layers |
| `menu`, `accessibility` | Android entry points and returning changes to the source app | `ui`, root, and all lower layers |

`codec` and `core` stay free of Android imports. `llm` and `history` are peers:
the model client does not read history, and stored records do not depend on a
provider implementation. [CheckContext](../app/src/main/java/com/evanaronson/linguize/CheckContext.kt)
adapts a check's settings to the form history stores at the composition boundary.

[The architecture check](../scripts/check_architecture.py) enforces package
declarations and these import directions before Gradle runs in CI. It treats
`ui` as one layer and checks production Kotlin, not tests. It does not detect
fully qualified references or prove that library dependencies are platform-free;
those still need review. Update this document and the guard together when a
real dependency change is needed.

## Where behavior belongs

The three check hosts share
[ui/card](../app/src/main/java/com/evanaronson/linguize/ui/card/).
`CheckViewModel` owns the visible check and accepted edits; `CardActions` gives
hosts the same card actions. The selection-menu activity and accessibility service
own their different Android lifecycles and replacement mechanisms. They do not
import each other.

[ui/home/HomeActivity](../app/src/main/java/com/evanaronson/linguize/ui/home/HomeActivity.kt)
currently hosts Home, History, Settings, and saved-check navigation. Its package
name describes the launcher destination, although it owns the full app scaffold.
Popup navigation and return handling live in `ui/popup`. Moving the app host into
a new package can wait until navigation needs a larger separation.

[History](history.md) owns the meaning of stored decisions. The pure
`SessionRecording` turns card activity into records; `CheckHistory` coordinates
writes and reuse; `HistoryStore` is the storage boundary. SQL, migrations, and row
mapping stay beside that feature. The edit engine does not know recording exists.

`data` owns the app's other persistence. In particular,
[data/Exports](../app/src/main/java/com/evanaronson/linguize/data/Exports.kt)
manages files through an output-stream callback, so it need not know the history
schema. UI code owns sharing and requests cleanup. The files named `Exports.kt`
in these layers have different responsibilities.

Keep Android resources in their native form. `strings.xml`, `strings_lifecycle.xml`,
and `strings_platform.xml` form one catalog; file boundaries do not affect resource
IDs or translation fallback. Follow [localization](localization.md) and
[copy style](copy-style.md) when changing visible text.

## Evidence first, derived learning later

History currently supports browsing, replay, and reuse of a recent answer. It
does not build a writer profile or infer recurring mistakes. Its raw model answers
and final decisions are useful observations, with the [limits described in the
history contract](history.md#what-the-records-can-support).

Two kinds of improvement need different evidence. A wrong linguistic suggestion
belongs in a reviewed eval case with an expected judgment. A lookup failure,
lost replacement, malformed answer, or lifecycle regression belongs in the
corresponding Android or offline test. A transport error is not a writing mistake.
The [eval guide](../eval/README.md) describes how curated cases support prompt and
provider changes.

Before implementing learning, define the claim it should make and how to assess
it. A count of accepted suggestions alone cannot establish correctness, authorship,
or improvement over time. Reused answers and repeated checks also need an explicit
counting rule.

Any future learning output should be derived separately from the source records.
It should identify the sessions, attempts, and suggestions it used, record the
analyser/model/prompt version, and distinguish inferred patterns from reviewed
conclusions. Deleting source history must also remove or invalidate dependent
outputs; a changed analyser must be able to rebuild them. Evaluation needs reviewed
examples and a baseline before such claims appear in the UI.

These are prerequisites, not a proposed implementation. Add a learning package,
storage table, worker, or server only with a concrete feature and its tests. The
same applies to sync: UUIDs and versioned records help, but deletion propagation,
consent, conflict rules, and a versioned network contract are still undecided.
Existing stored fields and migration code should remain compatible while those
decisions are deferred.

## When to split further

A separate Gradle module becomes useful when a component needs enforced build
isolation, a second consumer, or an independent build. A package split becomes
useful when it separates actual owners or removes an import cycle. Neither is
needed merely because another app version is planned.

For now, extend the owning package, keep domain calculations testable without
Android, and reuse the existing card and storage boundaries. See the
[README](../README.md#build-locally) for the checks required by the build.
