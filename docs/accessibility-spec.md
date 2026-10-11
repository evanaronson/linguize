# Accessibility shortcut

Status: draft, not implemented. Based on main after the history merge (997f5da76c4e329266d46cf39478f05a960f127f). The current code wins where this document describes existing behaviour.

## The problem

The Linguize accessibility shortcut works in WhatsApp on the owner's phone, but tapping it while writing in ChatGPT produces no visible response. We have not inspected that phone's accessibility tree or logs. Neither an inaccessible editor nor a missing shortcut callback has been established.

The shortcut currently registers AccessibilityButtonController.onClicked, then asks findFocus(FOCUS_INPUT) for one editable node. It filters private fields, reads the selection or whole field, starts a check and shows an OverlayWindow. Failed field lookup produces a toast. The service ignores accessibility events.

A tap should either open a check or explain what prevented it. Better field detection should cover more editors without checking a conversation, a search box or another app's text by mistake.

## Decisions for the first version

| Question | Proposed decision | Consequence |
|---|---|---|
| When to inspect an editor | After a shortcut tap | No background capture of writing |
| Which app to inspect | The foreground app window associated with the tap | Exclude the keyboard, system UI and Linguize's own overlays |
| Missing focus | Search that app's exposed editable nodes | Never use arbitrary visible text as an editor |
| Multiple possible editors | Ask which field to check | No guess based only on screen position or the longest text |
| No exposed editor | Explain and offer a manual paste route | No promise that every app will work |
| Feedback | Persistent, dismissible local feedback for failures | Toasts alone are insufficient |
| Replacement | Revalidate the same field and original text before writing | No overwriting a newer draft or another field |
| History | Keep the merged history contracts | One session per check popup; no session for lookup failures |
| Contextual floating button | A separate later phase, off by default | Requires a new event-observation design |

These decisions are proposals for implementation, not claims that they fix ChatGPT.

## Goals

- Preserve the existing working WhatsApp path.
- Cover apps that expose an editor but fail to report it through the current focus lookup.
- Make every callback received by Linguize produce a visible result or an actionable failure.
- Distinguish shortcut delivery, field lookup, text extraction, checking, overlay display and replacement failures.
- Preserve selections, private-field exclusions, text-change protection and accurate history outcomes.

## Non-goals

- Reading entire chats, message history, notifications or arbitrary screen text.
- Sending messages, pressing another app's Send button or navigating on the writer's behalf.
- Screenshots, OCR, simulated typing, automatic paste or clipboard reads.
- Background grammar checks or model requests.
- App-specific coordinate scripts.
- A guarantee that editors hidden from Android accessibility can be read.

## First diagnose the silence

On the owner's phone, reproduce the same action in WhatsApp and ChatGPT with an ordinary draft. Record the installed Linguize build, Android version, phone model, app versions and shortcut type (floating button, navigation button or other Android shortcut).

Add an opt-in local diagnostic view before broadening field detection. Record only stages and reason codes:

1. Service connected and shortcut availability reported.
2. Shortcut callback received.
3. Foreground app window found.
4. Focused editor found, fallback used, ambiguity or no candidate.
5. Private-field or empty-text exclusion.
6. Overlay creation and display succeeded or failed.
7. Check started, finished or failed.
8. Replacement attempted, confirmed or copied instead.

Diagnostics must not contain draft text, selected text, hints, resource IDs, model responses, API keys or accessibility-tree dumps. Package names and versions may be displayed locally for reproduction; exporting them is an explicit user action. Keep a small bounded log and a Delete action. Diagnostics remain separate from History and never go to the AI service.

If no callback arrives, field detection changes cannot repair that failure. Inspect service enablement, shortcut assignment and Android/OEM shortcut delivery. A service cannot display feedback for a tap it never receives. If the callback does arrive, locate the last completed stage before changing assumptions about ChatGPT's editor.

Expected lookup failures are ordinary results. Unexpected Android/service exceptions get a stage-specific local failure and a sanitized diagnostic code, not silent returns or a catch-all success.

## Finding the field

Keep Android handling in accessibility/, with a small resolver and testable candidate-selection rules. Do not put AccessibilityNodeInfo in core/ or history/.

On a tap:

1. Identify the foreground app window and its package. Validate that any focused node belongs to that window.
2. Prefer the input-focused editable node. If its metadata marks it private, stop with the private-field message; do not search for a different field to get around that exclusion.
3. If focus lookup fails, traverse the foreground app root with bounded node/time limits. Consider only visible, enabled editable nodes.
4. Prefer a unique input-focused eligible candidate in that tree. If none is focused and there is one eligible editor, choose it. If several remain, show a chooser before reading their text.
5. Apply isPassword and PrivateField.matches before reading text. Exclude hints. Then use Selection.of with the reported selection offsets.
6. If no text can be read, show the appropriate failure and manual route.

The chooser uses field metadata such as a safe label and position, not draft excerpts. Do not read candidate text just to decide which field wins. Protected fields cannot be selected. A chooser selection is still part of this tap's interaction, not a background read.

Refresh nodes before use. Handle null roots, stale nodes and an app switch during discovery. Store a field reference with source package/window identity and the original full text only after an eligible field has been chosen. If the foreground app changes, cancel lookup rather than reading the new app.

First attempt this with the existing canRetrieveWindowContent and flagReportViewIds configuration. Add flagRetrieveInteractiveWindows only if device evidence shows it is necessary for window disambiguation; exclude IME, system and Linguize overlay windows. Broadening retrieval must update the service description and README. Do not enable flagIncludeNotImportantViews without a demonstrated need and an explicit design decision.

No hardcoded ChatGPT or WhatsApp editor IDs in the first implementation.

## Visible feedback and manual checking

Reuse OverlayWindow and the app's theme where practical. A lookup failure card is local UI: it does not instantiate a recorded check or send anything to the AI. It closes with Back or Dismiss and remains readable with the keyboard open. If the overlay itself cannot be shown, use a supported notification or an explicit activity fallback and record the display failure; a toast alone must not be the only evidence.

Draft copy follows docs/copy-style.md:

| State | Title | Hint/action |
|---|---|---|
| No editor | No writing field found | Tap the field you wrote in, then retry / Retry |
| Empty editor | No text to check | Write something in the field, then retry / Retry |
| Multiple editors | Choose a field | Metadata-only field choices / Dismiss |
| Protected editor | This field looks private | Check ordinary writing in another field / Dismiss |
| Text not exposed | Couldn't read this field | Copy your text and paste it in Linguize / Open Linguize |
| App changed | The app changed | Return to your draft, then retry / Retry |

Retry repeats discovery against the foreground source app, not against the failure card or keyboard. Open Linguize uses the existing manual Try it route; pasting is performed by the writer and checking requires Check. Try it remains excluded from History. Do not pull clipboard contents automatically or claim that copied text has reached the source app.

A local failure message must not claim the app blocks accessibility merely because one lookup returned null.

## Putting text back

The service refreshes the original field, compares its text with Selection.full and tries ACTION_SET_TEXT. Preserve those protections and the cursor calculation. Accept must not change the clipboard: Copy is an explicit, separate action.

The history branch also made the overlay take key focus for Back handling. Replacement must run after detaching that overlay, while the check's view model is still alive to report the outcome. The WhatsApp regression reported after the merge is consistent with this ordering problem, but needs device confirmation.

The pre-history and merged card contracts apply accepted changes on Accept all (when resolved), Done or dismissal. An individual Accept updates the preview until Done. Changing individual Accept to write live would also require write-back for Undo, tracking the last written draft for conflict checks, and revised History semantics; that is an open product decision, not part of the ordering repair.

Improve this path as follows:

- Record whether the chosen field exposes ACTION_SET_TEXT. If not, make Copy the expected completion route and explain it before the writer finishes.
- Before writing, revalidate the original node, package/window identity, private-field metadata and unchanged original full text. Do not rerun the resolver and write to whatever editor it finds now.
- Use selection.with(replacement) to preserve the rest of the draft.
- Treat an action's true return value as acceptance, then refresh and verify the expected full text where the app permits it.
- If the action reports success but verification is unavailable, explain that the result could not be confirmed. Do not retry automatically: a delayed action may still finish.
- Confirmed replacement is applied=true for History. A rejected, unavailable or unconfirmed replacement is applied=false.
- Preserve copyToClipboard's sensitive-clipboard handling for explicit Copy. Never invoke it as a side effect of a failed Accept. In the selection case, clearly say that only the checked portion was copied.
- Do not simulate keyboard entry, tap Send, automatically paste or restore focus by coordinates.

The existing Outcome contract records put-back versus not put-back; it has no whole-text copied outcome. Keep that contract for this phase. If distinguishing unconfirmed write-back later becomes important, propose a separate history model/schema change rather than mislabelling it as confirmed.

## Integration with History

Continue through CheckViewModel.check with Origin.Button and the chosen editor's package. Start it only after selecting the field and obtaining text.

One visible check popup is one session, including language changes, retries and returning from Settings. Discovery failures and chooser cancellations create no history rows. Private fields produce neither a model request nor a history record.

Retain CheckHistory's current disable/delete fencing and CheckViewModel.dismiss(applied=...). If a popup is discarded after an app switch or service shutdown, close it as not put back. Hidden cards must follow the existing stale-card rules and must not resurface for unrelated fields.

Diagnostics do not reuse SessionRecord or change history retention/export.

## Optional later phase: a button while writing

A contextual Linguize button could appear near a writing field and disappear when that field loses focus. This is a separate feature because it requires observing focus/window events instead of ignoring all events.

Before implementing it, decide:

- Which narrowly scoped events and metadata are required to show or hide the button without reading draft contents.
- How apps are opted in or excluded, and how the button behaves with keyboards, rotation, split screen and TalkBack.
- How to avoid covering Send, keyboard controls or the Android accessibility shortcut.
- How to preserve the rule that draft text is read and sent only after an explicit tap.
- What Android's service description and Linguize's settings need to explain about event observation.

Off by default. No polling of draft contents, automatic checks or background history. Reuse the same resolver and check host; do not create a second checking path.

## Verification and release gates

Automated tests should cover decisions and failure behaviour, not merely mirror traversal code:

- Missing direct focus with one exposed editor; multiple editors require a chooser.
- Focused private field stops fallback; private candidate text is never read.
- IME, system, other-window and Linguize nodes cannot become targets.
- Null/stale nodes, traversal limits and switching apps produce local failures without model/history calls.
- Selection bounds and unchanged-text checks preserve the surrounding draft.
- Unsupported/rejected writes, changed text and mismatched fields never overwrite a different draft.
- Reported success without confirmation never becomes applied=true.
- Discovery failure produces no history session; the successful popup produces one.
- Hidden cards, service destruction and Settings return retain the merged lifecycle behaviour.

Run ./gradlew testDebugUnitTest and ./gradlew assembleRelease after implementation. Android integration checks should use appropriate framework/device coverage for service callbacks, nodes and overlays.

Device verification must include WhatsApp (regression), ChatGPT (reported failure), Telegram, a standard native text editor and a WebView editor. Check selected text and whole-field checks, empty/protected fields, keyboard open/closed, supported shortcuts, multiple editors and changed drafts. Test TalkBack access to failures and the chooser, and Back/dismiss behaviour.

Use dummy text. A ChatGPT success claim requires a device reproduction with matching installed APK version; passing unit tests is insufficient.

## Delivery order

1. Instrument shortcut delivery and visible failure states; reproduce ChatGPT versus WhatsApp.
2. Add bounded field discovery and ambiguity handling driven by those findings.
3. Improve replacement capability feedback and confirmation while preserving History.
4. Verify on the owner's phone and update the README/service description for actual behaviour.
5. Revisit the optional contextual button after the shortcut works reliably.

## Open questions

- Does ChatGPT fail before the callback, during discovery, or during overlay display on the owner's phone?
- Which Android version, device and shortcut type reproduce it?
- Does ChatGPT expose a usable editable node or ACTION_SET_TEXT in that environment?
- Is multi-editor choice needed in practice, or should the first shipped fallback ask the writer to focus the field?
- For write-back that cannot be confirmed, is conservative not-put-back plus Copy sufficient?

The draft establishes the first implementation's boundaries. Device findings should settle the remaining choices before adding app-specific workarounds.
