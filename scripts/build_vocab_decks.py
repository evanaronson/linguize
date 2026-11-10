#!/usr/bin/env python3
"""Builds the bundled Words frequency decks from their CC BY-SA 4.0 sources.

    python3 -I scripts/build_vocab_decks.py \
        --spanish path/to/spanish_data/frequency.csv \
        --catalan path/to/FrequencyWords/content/2018/ca/ca_50k.txt \
        --out app/src/main/assets

Sources:
- Spanish: frequency.csv from doozan/spanish_data (count,spanish,pos,flags,usage).
- Catalan: content/2018/ca/ca_50k.txt from hermitdave/FrequencyWords ("form count" lines).

Output (deterministic: same input, same bytes):
- lab_vocab_es.csv (rank,lemma,pos,count,forms): the top 5,000 Spanish lemmas. Rows flagged
  DUPLICATE, proper nouns (pos "prop") and lemmas that aren't lowercase Spanish letters ("OK",
  "ADN", "sr.") are left out; pos "none" becomes "other". Forms are the lemma's usage forms
  made of Spanish letters with a count of at least 50, at most 40, in source order. After
  these filters the source lists each lemma once, so the lexeme and lemma views both have
  5,000 rows.
- lab_vocab_ca.csv (rank,form,count): the top 15,000 Catalan word forms made only of Catalan
  letters. The app groups them into lexemes with its own morphology at run time and keeps the top
  5,000 lexemes and lemmas, so this ships three times as many forms as it shows: grouping
  15,000 forms leaves about 11,400 lexemes and 10,000 lemmas, and their top 5,000 match the
  top 5,000 from all 50,000 forms for about 99.5% of rows (the rest are ties near the cut).

The app reads at most 20,000 rows per deck (DeckParser.MAX_ENTRIES).
"""
import argparse
import csv
import os
import re

SPANISH_ROWS = 5000
CATALAN_FORMS = 15000
FORM_MIN_COUNT = 50
FORMS_PER_LEMMA = 40
SPANISH_WORD = re.compile(r"^[a-záéíñóúü]+$")
CATALAN_WORD = re.compile(r"^[a-zàçèéíïòóúü]+$")


def spanish(path):
    rows = []
    with open(path, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            lemma, pos = row["spanish"], row["pos"]
            if row["flags"] == "DUPLICATE" or pos == "prop" or not SPANISH_WORD.match(lemma):
                continue
            usage = [part.split(":", 1) for part in row["usage"].split("|") if ":" in part]
            forms = [form for count, form in usage
                     if int(count) >= FORM_MIN_COUNT and SPANISH_WORD.match(form)][:FORMS_PER_LEMMA]
            rows.append((lemma, "other" if pos == "none" else pos, int(row["count"]), forms))
            if len(rows) == SPANISH_ROWS:
                break
    return rows


def catalan(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) != 2 or not CATALAN_WORD.match(parts[0]):
                continue
            rows.append((parts[0], int(parts[1])))
            if len(rows) == CATALAN_FORMS:
                break
    return rows


def write(path, header, rows):
    with open(path, "w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)  # RFC 4180 line ends (CRLF), as the assets have always had
        writer.writerow(header)
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--spanish", required=True)
    parser.add_argument("--catalan", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    es = spanish(args.spanish)
    write(os.path.join(args.out, "lab_vocab_es.csv"), ["rank", "lemma", "pos", "count", "forms"],
          [(i + 1, lemma, pos, count, ";".join(forms)) for i, (lemma, pos, count, forms) in enumerate(es)])
    ca = catalan(args.catalan)
    write(os.path.join(args.out, "lab_vocab_ca.csv"), ["rank", "form", "count"],
          [(i + 1, form, count) for i, (form, count) in enumerate(ca)])
    print(f"lab_vocab_es.csv: {len(es)} lemmas; lab_vocab_ca.csv: {len(ca)} forms")


if __name__ == "__main__":
    main()
