# Accessibility shortcut reliability

Status: delivered. The owner-tested lookup behavior comes from build 72
([33fd5fc7](https://github.com/evanaronson/linguize/commit/33fd5fc7346b8624531c097d31c9a7e84c5e39ef)).
The temporary diagnostic probe is absent from the current implementation.
This document keeps the reliability evidence and the contracts that remain in force.

## Evidence and outcome

The owner's build 143 screenshot showed both taps reaching the service: the first returned a container and hit the bounded lookup limit; the second returned an editor and displayed the card. Build 152 added one fresh query after 100 ms within the original tap and source window. The owner subsequently reported being unable to reproduce the two-tap failure. The production version retains that retry and removes all preview diagnostics.

On the owner's Galaxy S21, WhatsApp and Telegram worked while ChatGPT initially appeared to do nothing. The temporary probe established that ChatGPT's tap reached Linguize, but Android's service-wide input-focus lookup returned a non-editable item, without edit actions or an input-focused flag.

The bounded fallback found a focused editor. Some attempts reached the traversal limit before completing the search. Build 72 checks the returned item's subtree first, then the active source window if that subtree has no candidate. After retrying, the owner confirmed that ChatGPT worked and subsequently confirmed the requested replacement and clipboard checks. This is handset evidence for the tested cases, not a guarantee for every conversation or app version.

Build 65 fixed the separate WhatsApp Accept regression by detaching the overlay before replacement while keeping the check alive to record the result. The owner confirmed that replacement worked and Accept left the clipboard unchanged.

## Delivered behaviour

- Read writing only after an explicit shortcut tap. Keep the existing selection, card and manual Try it interaction.
- Keep the direct input-focus lookup. Recognize an editable node or one exposing Set text.
- On Android 13+, clear the accessibility node cache at the start of each explicit tap. Chat navigation can replace virtual editors while cached input focus still identifies the previous editor; refreshing that node cannot discover the replacement. Older Android versions retain the existing lookup path.
- If the service-wide focus query is missing or returns a container, query input focus within the active source window before scanning. This handles a missing global focus result without requiring a second tap when the window already exposes its focused editor.
- For fallback, require the source app and window to match the active root. Search the returned item's subtree first, then the same-window root only if the complete subtree has no candidate.
- Accept only one visible, enabled, input-focused editor. Bound each scan to 128 nodes and 150 ms. A private, ambiguous or incomplete scan cannot supply writing to Check. A lone unfocused editor is insufficient.
- When lookup reaches its limit, retry the fresh query once after 100 ms within the original tap. A new tap, service destruction, screen lock or source-window change cancels it. Private, ambiguous and genuinely empty fields do not schedule retries; a second limited lookup falls through to clipboard-based Understand.
- Check password and private-field metadata before reading contents. Refresh every candidate, including the direct result, before reading text; cached nodes may still hold the text from before focus changed. Recheck fallback eligibility and the active source window before reading.
- Give a short toast on failure, with instructions to focus the field and retry or paste into Try it. The temporary settings line, tap count, availability state, and stage tracking have been removed.

## Existing contracts

Individual Accept and Accept all change the card preview and keep the card open. Once all suggestions are resolved, the final text stays visible and can be copied. Done or dismissal puts accepted changes back. Explicit Copy copies the chosen version and closes without replacing. Accept never copies, including when replacement fails.

Replacement targets the original field after the overlay detaches. Refresh it and require its full text to match the original before Set text. Preserve surrounding text and cursor placement.

Keep one History session per Check popup, Origin.Button, the source package and existing outcomes. Understand and unavailable inputs create no session. Start the check only after display setup succeeds; dispose of partial setup on failure. Replacement uses the text-action return value without post-write polling; see [History today](history.md) for what that outcome can establish.

## Verification and scope

The diagnostic releases passed unit tests and release builds. Regression checks
cover private, ambiguous, hidden, disabled, other-app and other-window nodes,
unfocused editors, scan limits, and a focused subtree within a large conversation.
Focus-resolution regressions also cover missing service-wide focus, window-local
focus in a large conversation, and private or foreign-window results. These cover
the lookup gap. Retry regressions cover a container-to-editor transition, a persistent lookup limit, cancellation on a new tap or context change, service destruction, and private or empty fields. The owner could not reproduce the chat-switch failure on build 152; other app versions and conversations still require handset checks.
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


## Smart shortcut and Understand

On a deliberate shortcut tap while unlocked, prefer a nonempty eligible editor.
Private fields retain the existing blocked feedback and never trigger clipboard fallback.
Hint-only fields do not start checks or history sessions: reject the platform hint
flag, or text matching the field's hint without evidence of real writing (a
positive cursor/selection offset or a set-selection action). This also covers an
empty editor exposing a 0,0 cursor, while native TextView selection actions keep
genuinely typed text usable even when it matches the hint. No generic-word
blacklist is used. If there is no usable writing, open
Understand and capture plain clipboard text only after the actual overlay window
gains focus. No clipboard listener, background polling, URI coercion, or chat-bubble
scraping is used. A bounded focus failure shows unavailable guidance; missing or
non-text clipboard content explains how to supply input. Clipboard access denial
can also return no text on Android, so the message says unavailable rather than
claiming the clipboard is empty.

Check offers an explicit Understand copied text action because WhatsApp may retain
editor focus after a message is copied. Switching cancels Check, revokes queued
history writes, deletes its provisional session, and never applies accepted text.
A request already sent to the provider cannot be retracted.

Understand uses a separate prompt, structured result, controller and UI content.
It has no history or write-back dependency. Translation appears first, followed by
the original copied text. Optional notes are separate structured quote/explanation
pairs, rendered below the source; every quote must occur in the original text.
No notes heading appears when none are needed. Retries use the captured source.
It shares the existing popup frame, positioning, scrim, loading/errors and Meaning
text treatment. It has no Accept, Apply, correction highlights or persistent history.
The popup header contains no noninteractive Understand label.

The debug-only ClipboardProbeService and scripts/verify_clipboard_overlay.sh
verify the real platform boundary: another app seeds the clipboard, Settings owns
focus, and the actual accessibility overlay must gain focus before its text reader
can capture that source. These fixtures are absent from release builds.
