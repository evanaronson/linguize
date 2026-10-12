# Localization

Linguize uses Android string and plural resources. The default English catalog is
`app/src/main/res/values/strings.xml`; related resources may live in other XML files
in the same directory. Android merges those files into one resource catalog. New
translations belong in qualified directories such as `values-ca/strings.xml`.
A JSON catalog would duplicate Android's locale selection, fallback, plural rules,
and support for platform labels.

Resolve text when it is displayed: Compose uses `stringResource` and
`pluralStringResource`, while activity callbacks use `getString`. Keep ViewModel
state and persisted history as domain values and stable tokens. Shared resource
mappings live in `ui/text/Labels.kt`. This lets a locale change update current
results and past checks without modifying saved data.

- Use complete sentences with positional placeholders (`%1$s`, `%2$d`) so a
  translation can reorder the arguments. Do not concatenate sentence fragments.
- Use `<plurals>` for counts. Pass the count both as the quantity and as a format
  argument; languages do not all have English's singular/plural rules.
- Resolve accessibility descriptions, custom actions, toasts, and chooser titles
  from resources as well as visible text. Decorative glyphs have no spoken label.
- Follow the current resource configuration for dates, number formatting, and
  language names. Keep Android's 12/24-hour clock preference.
- The `native_language` translation is the English name of the translated UI
  language, as used in the AI prompt. Brand names, sample Catalan text, model IDs,
  provider responses, protocol tokens, MIME types, and diagnostic stage codes are
  not translated. User text and AI responses are content, not resource keys.
- Escape apostrophes in Android XML (`\'`). Quoted resource text preserves spaces
  in list separators. `docs/copy-style.md` defines the English copy conventions.

The history date tests verify both relative dates and locale-specific date order.
Run Android unit tests and lint when changing resources or resource formatting.
