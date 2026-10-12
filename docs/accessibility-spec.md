# Accessibility shortcut reliability

Status: delivered. The owner-tested lookup behavior comes from build 72
([33fd5fc7](https://github.com/evanaronson/linguize/commit/33fd5fc7346b8624531c097d31c9a7e84c5e39ef)).
The temporary diagnostic probe is absent from the current implementation.
This document keeps the reliability evidence and the contracts that remain in force.

## Evidence and outcome

On the owner's Galaxy S21, WhatsApp and Telegram worked while ChatGPT initially appeared to do nothing. The temporary probe established that ChatGPT's tap reached Linguize, but Android's service-wide input-focus lookup returned a non-editable item, without edit actions or an input-focused flag.

The bounded fallback found a focused editor. Some attempts reached the traversal limit before completing the search. Build 72 checks the returned item's subtree first, then the active source window if that subtree has no candidate. After retrying, the owner confirmed that ChatGPT worked and subsequently confirmed the requested replacement and clipboard checks. This is handset evidence for the tested cases, not a guarantee for every conversation or app version.

Build 65 fixed the separate WhatsApp Accept regression by detaching the overlay before replacement while keeping the check alive to record the result. The owner confirmed that replacement worked and Accept left the clipboard unchanged.

## Delivered behaviour

- Read writing only after an explicit shortcut tap. Keep the existing selection, card and manual Try it interaction.
- Keep the direct input-focus lookup. Recognize an editable node or one exposing Set text.
- For fallback, require the source app and window to match the active root. Search the returned item's subtree first, then the same-window root only if the complete subtree has no candidate.
- Accept only one visible, enabled, input-focused editor. Bound each scan to 128 nodes and 150 ms. A private, ambiguous or incomplete scan stops the attempt. A lone unfocused editor is insufficient.
- Check password and private-field metadata before reading contents. Refresh a fallback candidate and recheck eligibility and the active source window before reading.
- Give a short toast on failure, with instructions to focus the field and retry or paste into Try it. The temporary settings line, tap count, availability state, and stage tracking have been removed.

## Existing contracts

Individual Accept changes the card preview; Done or dismissal puts accepted changes back. Accept all closes when the review is resolved. Explicit Copy copies the chosen version and closes without replacing. Accept never copies, including when replacement fails.

Replacement targets the original field after the overlay detaches. Refresh it and require its full text to match the original before Set text. Preserve surrounding text and cursor placement.

Keep one History session per popup, Origin.Button, the source package and existing outcomes. Lookup failures create no session. Start the check only after display setup succeeds; dispose of partial setup on failure. Replacement uses the text-action return value without post-write polling; see [History today](history.md) for what that outcome can establish.

## Verification and scope

The diagnostic releases passed unit tests and release builds. Regression checks
cover private, ambiguous, hidden, disabled, other-app and other-window nodes,
unfocused editors, scan limits, and a focused subtree within a large conversation.
Current changes must pass `testDebugUnitTest`, `lintRelease`, and `assembleRelease`,
as the [build workflow](../.github/workflows/build.yml) requires. Unit tests cover
the app's rules; handset checks remain necessary to establish behavior in another app.

The owner confirmed the ChatGPT test after being asked to verify replacement, clipboard preservation, a longer conversation and WhatsApp/Telegram. Exact Android and app versions were not captured. Retain bounded failure handling for attempts that exceed the lookup limit.

No background monitoring, new permissions, retrieval flags, screenshots, OCR, simulated typing, automatic clipboard access, field chooser, diagnostic screen, stored logs, notifications or general compatibility framework. Further accessibility features need a separate concrete use case.

## Code and regression tests

- [LinguizeAccessibilityService.kt](../app/src/main/java/com/evanaronson/linguize/accessibility/LinguizeAccessibilityService.kt) owns the shortcut, popup lifecycle, and replacement.
- [FocusedField.kt](../app/src/main/java/com/evanaronson/linguize/accessibility/FocusedField.kt) and [PrivateField.kt](../app/src/main/java/com/evanaronson/linguize/accessibility/PrivateField.kt) own bounded lookup and private-field heuristics.
- [OverlayWindow.kt](../app/src/main/java/com/evanaronson/linguize/accessibility/OverlayWindow.kt) and [UnlockedScreen.kt](../app/src/main/java/com/evanaronson/linguize/accessibility/UnlockedScreen.kt) own display lifecycle and lock-screen visibility.
- [Accessibility tests](../app/src/test/java/com/evanaronson/linguize/accessibility/) preserve the lookup and lifecycle regressions.
