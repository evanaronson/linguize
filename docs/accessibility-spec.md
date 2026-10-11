# Accessibility shortcut reliability

Status: focused draft. Baseline: build 65, commit 14ee72603328ace254843e12a49d0c017b43c7fe. This document specifies the next bug fix, not a new accessibility feature suite.

## Problem and evidence

The owner reports that Linguize's shortcut works in WhatsApp but produces no visible response in ChatGPT on a Galaxy S21 with the latest installed Android update (exact version not yet recorded). Build 67's probe confirms the ChatGPT tap reaches Linguize, but findFocus(FOCUS_INPUT) returns an item with isEditable=false and the service stops before reading text or showing a card. WhatsApp reaches Check started; Telegram also works. Build 68 confirms that the returned item has no Set text or Set selection action, is not marked input-focused, and has one child. This warrants a bounded same-window focused-editor lookup; it does not yet establish that ChatGPT exposes a usable editor. Shortcut availability observed after returning to Settings does not negate the recorded callback.

Build 65 includes a candidate fix for a separate WhatsApp Accept regression: detach the overlay before replacement while keeping the check alive to record its result. It also separates Accept from Copy. Tests and the release build passed. The owner subsequently confirmed on the S21 that WhatsApp replacement works and Accept leaves the clipboard unchanged.

## Scope

Find where the reported shortcut fails, fix that failure if Linguize can, and give a useful message when it cannot. Keep the current tap-to-check interaction and working WhatsApp path.

| Keep | Exclude |
|---|---|
| Read writing after an explicit tap | Background monitoring or checking |
| Existing focused-field lookup, with a small fallback only if evidence warrants it | Choosing arbitrary unfocused editors or adding a field chooser |
| Existing card, toast and manual Try it route | Diagnostic screens, app-managed log storage/export, notifications or another activity flow |
| Current Accept, Copy and History contracts | Live write-back for individual accepts, confirmation states or schema changes |
| Current accessibility configuration | New retrieval flags, screenshots, OCR, simulated typing or automatic clipboard access |
| One bug fix with relevant checks | A contextual floating button or a general editor compatibility framework |

## Diagnose before adding behaviour

Reproduce on the same phone with build 65 and dummy writing in WhatsApp and ChatGPT. Note Android/device, app versions and shortcut type.

Use temporary developer logging or a diagnostic build, not a product diagnostic feature. For this phone-only reproduction, a temporary line in the existing accessibility settings section may show an in-memory callback count and last stage. Remove it after diagnosis; do not create a screen, saved log or export flow. Capture only whether the service connected, the callback arrived, an eligible focused field was found, and the card was displayed. Add a failure-stage reason code where needed. Never log writing, selection text, model responses, keys, hints, view IDs or whole accessibility trees.

Follow the evidence:

- **No callback:** investigate service/shortcut assignment and delivery. Stop field-detection work; it cannot fix an undelivered tap.
- **Callback, no usable field:** distinguish missing focus, a private field, hint/empty text and unavailable text. Use accurate feedback. Do not claim the app blocks access from one null result.
- **An exposed focused editor is missed:** add the smallest focused-field fallback below.
- **Card display fails:** fix that display path. Build 67 moved check startup after window.show succeeds. Preserve setup cleanup: dispose of any window/view model created and cancel or close any check already started. Do not leave an invisible model request running or compensate with editor scanning.
- **No usable editor is exposed:** explain the limit: open Linguize and paste into Try it. This is an instruction, not a promised direct navigation handoff or a new manual-check flow.

Remove temporary debugging or keep only narrowly scoped developer-only reason logging after the cause is established.

## Field fallback, only if needed

First keep findFocus(FOCUS_INPUT). Recognize the same input-focused node when either isEditable is true or it explicitly exposes ACTION_SET_TEXT; some editors may omit the flag while exposing that action. Keep all private-field and original-text guards. Do not infer editability from selectable text, a class name or a lone unfocused field.

Build 68's device evidence warrants testing the following fallback. Inspect the active source app's root for a visible, enabled, input-focused editable node. Bound traversal. Accept it only when it is unambiguous and belongs to the same source app/window.

If the root is absent, stale, changes app, or yields ambiguous candidates, stop and ask the writer to tap the writing field and try again. A single unfocused editable node is not enough: it might be a search box.

Apply isPassword and PrivateField.matches before reading contents. A focused private field stops the attempt; never search elsewhere to bypass it. Preserve Selection.of, surrounding text and existing field-change checks. Keep Android node handling in accessibility/; do not introduce a general resolver framework.

Do not expand permissions or retrieval flags for this fix. If the evidence requires a broader capability, stop and revise the scope explicitly.

## Feedback and existing contracts

Use existing UI and docs/copy-style.md. Keep messages short and factual, with the next action: focus the field and retry, or paste into Try it. Do not require persistent failure cards unless the reproduction demonstrates that the current toast path is the problem; then reuse the existing overlay for one simple message. Do not add a chooser, retry state machine or notification fallback.

- An individual Accept changes the card preview; Done/dismissal puts accepted changes back. Accept all closes when the review is resolved. Live replacement after every tap is a separate product change.
- Replacement targets the original field only, after the overlay is detached. Refresh it and require its full text to match the original before ACTION_SET_TEXT. Preserve surrounding text and cursor placement.
- Accept never changes the clipboard, including when replacement fails. Explicit Copy copies the chosen version and closes without replacing.
- Keep current replacement success/failure reporting to History. Do not add post-write polling. An unconfirmed write is not proof of failure; treating it as not applied would label accepted edits Undone even if the app changed.
- A check popup remains one History session with Origin.Button and the source package. Lookup failures before a check starts create no session. A display failure after a check has started must close it through the existing not-put-back path; do not change the schema. Keep disable/delete fencing and hidden-card lifecycle behaviour.
- History already has Applied, Copied, None and Failed outcomes. No migration is needed.

## Verification and completion

Add regression tests for the observed cause and any new fallback, including rejecting private, ambiguous or other-app nodes without a model request. Retain existing Accept/Copy, selection, changed-draft and History tests. Run testDebugUnitTest and assembleRelease after code changes.

Verify on the owner's phone:

1. **WhatsApp:** shortcut and replacement still work; Accept leaves the clipboard alone; Copy leaves the draft alone.
2. **ChatGPT:** the reproduced failure is fixed or produces a truthful, actionable message when Linguize receives the tap.
3. For any new UI, check Back/dismissal and TalkBack. Test another editor only if the fix changes shared lookup or display behaviour.

Record the installed build and outcome. Unit tests do not establish ChatGPT compatibility. Stop when the reported failure is understood, the supported path works, and unsupported cases are explained. Further accessibility features need their own demonstrated problem and scope.
