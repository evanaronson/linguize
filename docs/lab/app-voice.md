# Per-app voice

The same person writes casually in WhatsApp, carefully in Gmail, and in Catalan in
the neighbours' Telegram group. Per-app voice lets each source app carry its own
language, register and suggestions, so a check from that app starts right without
touching the language picker or Settings.

## The moment

The writer is in another app, about to send. They tap the accessibility button or
pick Linguize in the selection menu. Today every check uses the same global
choices, so a careful email gets "Everyday" advice and a chat to the neighbours is
auto-detected even though it is always Catalan. With a rule, the check already uses
the app's voice, and the card says so in one short line.

## How to use it

- **Settings › Writing preferences › Per-app voice.** The page lists apps with a
  rule, then apps from recent History, then installed messaging and email apps.
- Tap an app to set:
  - **Language**: Auto-detect or one of your languages.
  - **Register**: Same as settings, Casual, Everyday or Formal.
  - **What to suggest**: Same as settings, Fixes and rewordings, Fixes only or
    Rewordings only.
  - **Reset rule** puts the app back on Settings. **Reset all rules** (with a
    confirmation) does that for every app.
- **On the card.** When a rule applies, a small button beside the language picker
  reads, for example, "Formal, Gmail rule". Tap it to pick another register or
  suggestions for this check only ("Casual, this check"); the card checks again and
  keeps accepted changes, as after any settings change. "Save as Gmail rule" makes
  the choice stick. The button doesn't appear when the app has no rule.

The copy uses a comma rather than a middle dot ("Formal, Gmail rule"), as
[copy style](../copy-style.md) asks.

## Precedence

| Choice | Strongest first |
|---|---|
| Language | Catalanize or Castilianize entry, or the card's language picker › the app's rule › Auto-detect |
| Register | Choice on the card for this check › the app's rule › each language's writing profile |
| What to suggest | Choice on the card for this check › the app's rule › Settings |

A rule field left at "Same as settings" falls through. A rule that sets nothing is
deleted.

## How it works

- **Source app.** The accessibility service uses the focused field's package. The
  selection menu uses `callingPackage`, which Android provides only when the app
  starts the menu for a result (editable text, usually). Read-only selections have
  no caller, so no rule applies there. These are the same values History already
  records as the check's app.
- **Resolution** is pure Kotlin in `core/AppVoice.kt` (`AppRule`, `VoiceOverride`,
  `AppVoice.resolve`, `AppVoice.language`).
- **Snapshot.** `CheckContext.withVoice` (root, `AppVoiceContext.kt`) applies the
  voice to the context a check runs with: the register replaces the `register` of
  every `WritingProfile` (id, name, language, variety and punctuation stay), and
  the suggestions replace `judgments`. `CheckViewModel.adjustContext` is the one
  hook. The card's voice view model sets it, so the first check, re-checks and the
  "settings changed?" comparison all use the adjusted context. History stores that
  context's `SessionSettings` as before. The profile register and judgments of each
  attempt record the rule exactly as it applied, with no schema change, and a later
  rule edit never reinterprets a past check. Exact settings take part in answer
  reuse, so a Formal check never reuses a Casual answer.
- **Storage.** `data/appvoice/AppRules.kt` keeps rules in Lab's own preferences
  file `lab_app_voice`, one key per package (`rule:<package>`) holding stable
  tokens (`ca`, `formal`, `fix`). Unknown tokens fall back to Settings, and values a
  build can't read are skipped, not deleted. Settings, keys and History are never
  written.
- **UI.** `ui/appvoice/` holds the card button and menu (`AppVoiceChip`,
  `CardVoiceViewModel`) and the Settings pages (`AppVoiceScreens`,
  `AppVoiceSettingsViewModel`). Apps are named through History's `AppLabels`. They
  are visible through the manifest's existing launcher `<queries>`.

## Limits

- History doesn't record *that* a rule applied, only the resulting settings.
  Telling a rule-driven Formal check from a Settings-driven one would need a schema
  change.
- The register applies to every language's profile. A per-language register for
  each app is not offered.
- The selection menu often has no caller (read-only text, some apps), so the rule
  only applies when Android names the app.
- The messaging list is a fixed set of common package names, shown when installed.
  Any other app appears once it has been checked and saved to History.
- Without a rule, the card shows no voice button, so a rule can't be started from
  the card. That keeps the card quiet. Rules start in Settings.

## Tests

- `core/AppVoiceTest`: precedence of card choice, rule and Settings, language
  precedence, profile and judgments application, and saving.
- `AppVoiceContextTest`: the stored snapshot changes only in judgments and profile
  register.
- `data/appvoice/AppRulesTest`: storage round-trip, tokens, reset and reset all,
  unknown values, and no writes to Settings.
- `ui/appvoice/CardVoiceViewModelTest`: on a real `Checker` and `CheckHistory`
  with a fake provider, a rule reaches the request and the recorded settings, and a
  card choice beats it on re-check.
- `ui/appvoice/AppEntriesTest` and `AppVoiceSettingsViewModelTest`: list order,
  editing, reset, and saved navigation state.
