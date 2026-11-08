# Say it in Catalan (language bridge)

## What it is

A way to turn your own message into your other language: **Say it in Catalan**, or
**Say it in Spanish**, and any language in your writing profiles. Linguize keeps your
meaning, tone and formality, writes in the register, variety and punctuation of the
target language's profile, and stays as close to your own wording as that language
allows. It's not a free translation. Then it shows how your words carried over,
phrase by phrase, with notes where the wording had to change and on false friends.

## The moment it serves

People in Catalonia switch between Spanish and Catalan depending on who they write
to, and most are stronger in one. A message to the school is in Catalan, and the one
to the landlord is in Spanish. You know what you want to say and have said it well
in one language. The other one is the problem. A translator gives you a text, but
not how your own sentence maps onto it, so you learn nothing and can't tell what
changed. The bridge shows you the correspondence:

```
te acuestas  →  et fiques      False friend
Catalan acostar-se means to come closer. For going to bed, say ficar-se al llit.
```

## How to use it

- **From the check card.** Under a reviewed result, below Meaning, a row reads
  **Say it in Catalan** (or Spanish). It picks the other language: the one you didn't
  pin, or the one your text doesn't look like. Accepted changes are included.
- **From Try it, on Write.** Next to **Check**, a **Say it in …** button follows what you
  type. Write Spanish and it offers Catalan.

The page shows the result, your text, and **Word by word**. When the message was read
in a third profile language, there are buttons for the other languages too. The page
gives you three ways to use the result:

- **Copy** copies the result.
- **Put back** (in the selection-menu popup, when the app lets Linguize replace text)
  replaces your selection with the result and returns you to the app. In Try it this
  is **Use this**, which replaces the text in the box.
- **Check it** runs the normal check on the result in the target language. In the
  popup, the checked version is then what goes back to the app when the card closes.

Back returns to the check.

## How it works

- `core/languagebridge/Bridged.kt`: the result model (`Bridged`, `Correspondence` and
  its kinds `same`, `close`, `different`, `false_friend`), a surface-marker guess
  between Catalan and Spanish (only used to pick the default target), and target
  selection.
- `assets/lab_language-bridge_prompt.md` and `lab_language-bridge_schema.json`: the
  feature's own prompt and strict JSON schema. The shipped check prompt is never sent.
- `llm/lab/LanguageBridgePrompt.kt` builds the request (target profile, optional pinned
  source, explanation language, and the message as JSON data) and reads the answer.
  The answer must have a valid status and a usable text. A correspondence that doesn't
  quote both texts exactly, or has an unknown kind, is dropped, and the rest are
  ordered by where they occur in your text.
- `LanguageBridge.kt` (root) picks the provider, model, key and target profile and sends
  the request through the existing Gemini and OpenAI clients (`ProviderClient.generate`).
- `ui/languagebridge/`: `BridgeViewModel` (one request at a time, and late answers are
  discarded), `BridgeCard` (the page), and `BridgeEntry` (the card footer row, the Try it
  button and the host glue). Hooks into shared files are small: an optional
  `bridgeEntry` slot on `ResultCard`, a few lines in `TryIt.kt` and `CheckActivity.kt`,
  and one lazy service in `App.kt`.

Nothing is stored. The bridge has no history and no preferences of its own. A check
run with **Check it** is an ordinary check and follows the History rules. In the popup,
that check is of the other-language text.

## Limits

- One model call per request, so it costs the same as a check and is a little slower
  (a longer answer).
- Profiles currently cover Catalan and Spanish, so in practice the bridge goes between
  those two. The code accepts any profile language.
- The default target uses a cheap guess when no language is pinned. If the guess is
  wrong, the page says the text is already in that language and offers the other one.
- The accessibility-button popup doesn't offer the bridge yet: it runs in a service
  without a view model owner. Use the selection menu or Try it.
- Correspondences are the model's reading. They're anchored to exact text in both
  versions but aren't a dictionary. Notes on false friends may be missing or
  imperfect.
- The Try it button's label changes as the guess changes while you type.

Tests: `core/languagebridge/BridgedTest`, `llm/lab/LanguageBridgePromptTest`,
`llm/lab/LanguageBridgeTransportTest` (fake HTTP for both providers), and
`ui/languagebridge/BridgeViewModelTest`.
