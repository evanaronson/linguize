# Testing

`./gradlew testDebugUnitTest` runs the JVM and Robolectric regressions in
[app/src/test/](../app/src/test/). They run in one process, one test after another.

## Compose UI tests use their own frame clock

Compose UI tests compose on their own frame clock through
[OwnComposition](../app/src/test/java/com/evanaronson/linguize/testing/OwnComposition.kt).
They never call `activity.setContent`, and never set content on a `ComposeView` without
their own recomposer.

`activity.setContent` recomposes on the process-wide Compose frame dispatcher. In a full
Robolectric run an earlier test's window can leave that dispatcher stalled, so no
recomposition happens: the test passes alone and fails in CI. `OwnComposition` gives each
test a `BroadcastFrameClock` and `Recomposer` and sends every frame itself.

- `show { … }` replaces the activity's content with a fresh composition and settles it.
- `settle(frames)` sends frames 16 ms apart, with the main looper idled and the view
  measured each frame. Pass more frames when the content waits or animates longer.
- `nodes()` returns the semantics tree, for assertions and accessibility actions.
- `dispose()` in `@After`, before the activity is destroyed.

Measure size is a constructor parameter. The width is exact by default; pass
`exactWidth = false` to let `Modifier.width` narrow the content, as `setContent`'s
wrap-content host does.

Tests that need no views compose into a `Composition` with an empty applier, on a
`MonotonicFrameClock` of their own (see `NavigationStateTest`).

Tests that drive an activity whose content the app sets itself, such as `CheckActivity`
or `HomeActivity`, still use the shared dispatcher. Keep their UI assertions few, and
check state through the view model where possible.
