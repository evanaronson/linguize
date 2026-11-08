# Share to Linguize, and understand a photo

## What it is

Two ways to get text into Linguize that the selection menu can't reach:

1. **Share text.** Linguize appears in Android's share sheet for text. Sharing opens a
   small floating chooser over the app you shared from: **Check it** runs the usual
   check card, **Understand it** runs the usual Understand card (translation and notes).
2. **Understand a photo.** Share an image to Linguize ("Understand photo" in the share
   sheet), or tap **Understand a photo** on Write and pick one with the system photo
   picker. Linguize sends the image to your AI provider and shows:
   - what it is (a one-line summary, and the language it's written in),
   - what it asks you to do, with any deadline and amount as the text states them,
   - a faithful translation into your native language,
   - the text in the photo, transcribed,
   - words to know: official terms, abbreviations and idioms, explained.

   The translation and the transcription each have a Copy button, and all text is
   selectable.

## The moment it serves

The learner is not writing; something arrived. A letter from the ajuntament, a notice
from school, a sign at the health centre, a screenshot of the neighbours' group chat.
The text can't be selected, and the questions are urgent and practical: what is this,
do I have to do something, by when, and how much? A plain translation answers the first
question; this screen answers all of them, in the order the person needs them.

Shared text covers the cases where text can be shared but not selected (many reader
apps, email clients and browsers' "Share" for a selection), and lets the person say what
they want: check their own writing, or understand someone else's.

## How to use it

- **Shared text:** in any app, Share → Linguize Lab → Check it or Understand it. The check
  card offers Copy with fixes and Copy with rewordings; shared text can't be put back in
  the source app, so there's no Apply. Back returns to the chooser.
- **Photo from another app:** Share an image → Understand photo.
- **Photo from Write:** Write → Read and reply → Understand a photo → pick an image.
- After a result, **Choose another** reads a different photo. If a key is missing or
  wrong, **Open settings** goes to Settings; coming back retries with the new settings.

## How it works

- Entry points live in `menu`: `ShareActivity` (text/plain, overlay theme, reuses
  `CheckViewModel`, `FloatingCard`, `UnderstandViewModel` and `UnderstandCard`) and
  `PhotoActivity` (image/\*, a normal app screen). `SharedContent` reads the intents.
- Write's row uses `rememberPhotoPicker` (`ui/photo/PhotoScreen.kt`), which uses
  `ActivityResultContracts.PickVisualMedia` with `ImageOnly`. No storage permission:
  the system picker grants access to the one chosen image. Write opens the result on a
  new `Screen.Photo` inside `HomeActivity`.
- `ui/photo/PhotoImage` opens the image with `ImageDecoder` (which applies EXIF
  rotation), decodes it straight to at most 1600 px on the long side, flattens any
  transparency onto white, and encodes JPEG at quality 85, stepping down to 75, 65 and
  50 until it is under 1.5 MB. It all happens in memory.
- `PhotoViewModel` holds the prepared bytes in memory so Retry and returning from
  Settings send the same image without opening it again. They are dropped when another
  photo replaces them or the screen goes away. Nothing is written to storage, and
  nothing is recorded in History.
- `PhotoUnderstander` (root) reads the configured provider, model and native language,
  like `Understander`, and calls a multimodal client in `llm/lab`:
  - `GeminiVisionClient`: `generateContent` with an `inlineData` part, JSON schema
    output, low thinking (dropped if the model refuses it).
  - `OpenAIVisionClient`: Responses API with an `input_image` data URL
    (`detail: high`), strict JSON schema, `store: false`, low reasoning effort (dropped
    if refused).
  Both reuse the existing transport: no redirects for keyed requests, one deadline for
  retries, capped response size, and the same error mapping as checks. The photo client
  has its own 90-second time limit, since reading a page takes longer than a check.
- The prompt and schema are `assets/lab_share_photo_prompt.md` and
  `assets/lab_share_photo_schema.json`. The prompt treats text in the image as untrusted
  data, asks for an exact transcription, a faithful translation, actions with deadlines
  and amounts only as stated, and up to 10 words to know. `PhotoPrompt.read` validates
  the reply (limits, required text, unknown fields) and maps "no legible text" to one
  empty reading, which the screen shows as "Linguize couldn't read text in this photo".
- Safety: any app can send an image to `PhotoActivity` without a tap, so shared photos
  are limited to 3 per app and 6 in all per minute. Shared URIs must be another app's
  `content://` URI; `file://` and this app's own providers (such as the history export)
  are refused, so another app can't make Linguize upload its private files.

## Limits

- One image per share; `ACTION_SEND_MULTIPLE` isn't handled.
- The model can misread small print or handwriting. The screen says to check dates and
  amounts against the original. It gives no legal or financial advice.
- The image goes to the person's AI provider with their key, like the text of a check.
  Linguize itself keeps nothing, but the provider's own retention rules apply.
- A shared-text check is recorded in History as a selection-menu check (no source app is
  known), because History has no separate origin for shares and its schema is out of
  scope for Lab. Understand and photo readings are never recorded.
- The Understand card's labels say "Copied text" even when the text was shared.
- After the process is killed, a photo result is gone (the image was never saved); the
  screen offers Choose photo again.
