# Documentation

Start with the [project README](../README.md) for installation, local builds, and releases.

| Document | Use it for |
|---|---|
| [Architecture](architecture.md) | Package ownership, dependency direction, repository navigation, and how to extend the app |
| [History today](history.md) | What is recorded, decision meanings, reuse, replay, storage, and evidence limits |
| [Accessibility shortcut reliability](accessibility-spec.md) | Current lookup and replacement contracts, plus the handset evidence behind them |
| [Copy style](copy-style.md) | English wording for screens, toasts, platform labels, and screen readers |
| [Localization](localization.md) | Android resource conventions and locale-aware display |
| [Checker repair](checker-repair-plan.md) | Implemented edit construction, atomicity, rejection and input contracts |
| [Scoring and writing preferences](scoring-v1.md) | Ratings, per-language profiles and shared inline clarification |
| [Evaluating checks](../eval/README.md) | Curated cases, running the model eval, and interpreting its results |

The [October 2026 History design](archive/history-design-2026-10.md) is an archive.
It preserves migration and design rationale; it does not define current behavior.

The shipped [prompt](../app/src/main/assets/check_prompt.md) and
[answer schema](../app/src/main/assets/check_schema.json) live with the Android app.
The eval reads those same assets. [Brand sources and SVGs](../brand/) are design
deliverables; Android uses the vector drawables in
[app/src/main/res/drawable/](../app/src/main/res/drawable/).
