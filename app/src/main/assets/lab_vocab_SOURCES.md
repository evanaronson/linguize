# Vocabulary frequency lists (Linguize Lab)

These files are adapted from openly licensed frequency lists built from the OpenSubtitles2018
corpus (OPUS, http://opus.nlpl.eu/OpenSubtitles2018.php). As adaptations of CC BY-SA
material, they are shared under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).

- `lab_vocab_es.csv`: the 5,000 most frequent Spanish lemmas (proper nouns and duplicate rows
  removed) of the source's 25,019 rows, with part of speech, corpus count and the word forms
  counted toward each lemma.
  Adapted from `frequency.csv` in doozan/spanish_data (https://github.com/doozan/spanish_data),
  itself derived from hermitdave/FrequencyWords, with part-of-speech tagging by FreeLing.
- `lab_vocab_ca.csv`: the 15,000 most frequent Catalan word forms (of the source's 50,000)
  with corpus counts (tokens with digits or symbols removed). Adapted from
  `content/2018/ca/ca_50k.txt` in hermitdave/FrequencyWords
  (https://github.com/hermitdave/FrequencyWords), CC BY-SA 4.0. These are word forms, not
  lemmas; the app groups them into lexemes and lemmas with its own morphology and shows the
  5,000 most frequent of each.

Both files are made by `scripts/build_vocab_decks.py` in the Linguize source.

Subtitle frequencies reflect spoken, informal language; they are a guide, not a syllabus.
