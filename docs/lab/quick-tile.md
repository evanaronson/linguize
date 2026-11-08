# Quick tile and shortcuts

A Quick Settings tile and three launcher shortcuts that check or explain **copied**
text, for the apps where neither the selection menu nor the accessibility button
works, and for people who simply copy.

## The moment

The writer is in an app whose text field hides the selection menu (some games,
banking chats, web views) and the accessibility button can't find the field. Or a
message arrives that they want to understand before answering. Copying works
everywhere, so copy, pull down the shade, tap **Linguize Lab**.

## How to use it

- **Tile.** Pull down Quick Settings, tap the pencil (edit), and drag **Linguize Lab**
  ("Copied text") into the active tiles. Copy some text, then tap the tile. The card
  shows the copied text and asks: **Check my text** or **Understand it**.
- **Launcher shortcuts.** Long-press the Linguize Lab icon:
  - **Check copied text** checks it straight away.
  - **Understand copied text** translates and explains it straight away.
  - **Try it** opens Home, where the Try it box is.
  Shortcuts can be dragged to the home screen. They appear after the app has been
  opened once.
- **Accepted changes go on the clipboard.** There's no field to put changes back
  into. When the card closes through Apply, Back or a tap outside, the copied text is
  replaced on the clipboard with the accepted changes, and a toast says "Copied with
  your changes. Paste it where you need it." Closing without accepting anything
  leaves the clipboard as it was.
- From a check, **Understand copied text** in the card's footer switches to
  Understand, and back returns to the check, as in the accessibility popup.

## How it works

- `menu/ClipboardTileService` is the tile. It is a button (always inactive). A tap
  on the lock screen first asks to unlock (`unlockAndRun`). On Android 14+ it starts
  the card with `startActivityAndCollapse(PendingIntent)` (an immutable
  `PendingIntent`); older versions use the `Intent` overload, which Android 14 no
  longer allows.
- `menu/ClipboardActivity` is a transparent overlay activity in a task of its own
  (`taskAffinity=""`, excluded from Recents), so closing the card returns to the app
  underneath. Android 10+ lets only the focused app read the clipboard, so it reads
  the clipboard once, in `onWindowFocusChanged(true)`, never before and never again
  unless the writer taps Retry.
- `menu/CopiedText.kt` holds the clipboard logic. `Copied.of(clip)` reads the first
  item only; URIs and intents are never resolved (so a copied file or link can't leak
  into a request); blank text is "Nothing copied". Surrounding whitespace is kept
  outside the check and restored when the changes are copied back
  (`core/Selection`). `ClipboardStep.after(job, copied)` routes the result to the
  check, Understand or the choice card. `putBackOnClipboard` marks the clip
  sensitive, like the card's Copy, and always shows the toast, because Android 13's
  own "Copied" doesn't say the changes are in it.
- `ui/quicktile/QuickLaunch.kt` defines the three jobs as intent actions on this
  package, builds the launcher shortcuts and publishes them with
  `pushDynamicShortcut` (which leaves other shortcuts alone) off the main thread when
  Home opens. Shortcut intents name an action rather than the activity class, so `ui`
  doesn't depend on `menu`. Dynamic, not static XML, shortcuts are used because the
  Lab and release builds have different application ids.
- The card is the shared one: `FloatingPopup`, `ResultCard` with the language picker,
  and `UnderstandCard`. Settings and the wordmark open the app on top of the card, in
  its task, so Back returns to it and the check reruns if settings changed.
- **No key yet:** the check or Understand card shows "No API key" with Open settings,
  as everywhere else.

## Limits

- Checks from the tile or shortcuts are not saved in History (they use the Try it
  origin). History holds checks whose changes were put back in another app, and
  nothing is put back from here. Adding an origin would change what History stores.
- The tile can't tell whose words were copied, so it asks rather than guesses. The
  shortcuts skip the question.
- Android doesn't let an app add itself to Quick Settings without asking, so the tile
  has to be added by hand once.
- Only the first clipboard item is read; images and files show "Copied item isn't
  text".
- The changed text replaces whatever is on the clipboard at close, including
  something the writer copied from the card's Copy button in the meantime.
