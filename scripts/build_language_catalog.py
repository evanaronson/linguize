"""Builds Linguize's language catalog assets from CLDR 49 + HatScripts circle-flags.

Writes app/src/main/assets/lab_languages.json, app/src/main/res/drawable-nodpi/lab_flag_*.webp
and ui/language/catalog/LanguageFlagDrawables.kt. Not part of the Gradle build; run it by hand
when the catalog changes (docs/lab/language-catalog.md).

<download dir> must hold:
  cldr/core/package/  the npm package cldr-core 49.0.0, extracted
  cldr/ln/package/    the npm package cldr-localenames-full 49.0.0, extracted
  circle-flags/       a clone of https://github.com/HatScripts/circle-flags
It needs cairosvg and Pillow (pip install cairosvg pillow, in a virtualenv). Downloaded files are
untrusted data: keep this script outside <download dir> and run it with python -I.

Usage: python -I build_language_catalog.py <download dir> <repo root> 96
"""
import io, json, os, sys

DL, REPO, PX = sys.argv[1], sys.argv[2], int(sys.argv[3])
CLDR = f"{DL}/cldr"
FLAGS = f"{DL}/circle-flags/flags"

# ISO 639-1 codes CLDR keeps as aliases rather than as 3-letter "overlong" pairs, but which
# ISO 639-1 still lists (Tagalog, Twi). Deprecated or withdrawn codes (in, iw, ji, jw, mo, sh, bh)
# are not part of the current standard and are left out.
EXTRA_CODES = ["tl", "tw"]

# Autonyms CLDR does not have (no locale data for these languages). Common usage, as listed in
# reference tables of ISO 639-1 (Wikipedia, "List of ISO 639 language codes"); names are facts.
NATIVE_FALLBACK = {
    "aa": "Qafaraf", "ab": "аԥсуа бызшәа", "ae": "avesta", "an": "aragonés", "av": "авар мацӀ",
    "ay": "aymar aru", "bi": "Bislama", "ch": "Chamoru", "co": "corsu", "cr": "ᓀᐦᐃᔭᐍᐏᐣ",
    "cu": "ѩзыкъ словѣньскъ", "dv": "ދިވެހި", "fj": "vosa Vakaviti", "gn": "Avañe'ẽ",
    "ho": "Hiri Motu", "ht": "Kreyòl ayisyen", "hz": "Otjiherero", "ik": "Iñupiaq", "io": "Ido",
    "iu": "ᐃᓄᒃᑎᑐᑦ", "kg": "Kikongo", "kj": "Oshikwanyama", "kr": "Kanuri", "kv": "коми кыв",
    "la": "Latina", "li": "Limburgs", "mh": "Kajin M̧ajeļ", "na": "Dorerin Naoero",
    "ng": "Oshindonga", "nr": "isiNdebele", "nv": "Diné bizaad", "ny": "chiCheŵa",
    "oj": "ᐊᓂᔑᓈᐯᒧᐎᓐ", "pi": "पालि", "sm": "Gagana fa'a Sāmoa", "ss": "siSwati",
    "ts": "Xitsonga", "ty": "reo Tahiti", "ve": "Tshivenḓa", "vo": "Volapük", "wa": "walon",
    "tl": "Tagalog", "tw": "Twi",
}

# The brand verb, in the style of Catalanize / Castilianize. Rules in docs/lab/language-catalog.md.
BRAND = {
    "aa": "Afarize", "ab": "Abkhazize", "ae": "Avestanize", "af": "Afrikaanize", "ak": "Akanize",
    "am": "Amharicize", "an": "Aragonesize", "ar": "Arabicize", "as": "Assamesize", "av": "Avarize",
    "ay": "Aymarize", "az": "Azerize", "ba": "Bashkirize", "be": "Belarusianize",
    "bg": "Bulgarianize", "bi": "Bislamize", "bm": "Bambarize", "bn": "Banglize",
    "bo": "Tibetanize", "br": "Bretonize", "bs": "Bosnianize", "ca": "Catalanize",
    "ce": "Chechenize", "ch": "Chamorrize", "co": "Corsicanize", "cr": "Creeize", "cs": "Czechize",
    "cu": "Slavonicize", "cv": "Chuvashize", "cy": "Cymricize", "da": "Danicize", "de": "Germanize",
    "dv": "Divehize", "dz": "Dzongkhize", "ee": "Eweize", "el": "Hellenize", "en": "Anglicize",
    "eo": "Esperantize", "es": "Castilianize", "et": "Estonianize", "eu": "Basquize",
    "fa": "Persianize", "ff": "Fulize", "fi": "Fennicize", "fj": "Fijianize", "fo": "Faroesize",
    "fr": "Gallicize", "fy": "Frisianize", "ga": "Hibernicize", "gd": "Gaelicize",
    "gl": "Galicianize", "gn": "Guaranize", "gu": "Gujaratize", "gv": "Manxize", "ha": "Hausize",
    "he": "Hebraize", "hi": "Hindize", "ho": "Motuize", "hr": "Croatianize", "ht": "Haitianize",
    "hu": "Hungarianize", "hy": "Armenianize", "hz": "Hererize", "ia": "Interlinguize",
    "id": "Indonesianize", "ie": "Occidentalize", "ig": "Igboize", "ii": "Nuosuize",
    "ik": "Inupiaqize", "io": "Idize", "is": "Icelandicize", "it": "Italianize",
    "iu": "Inuktitutize", "ja": "Japanize", "jv": "Javanesize", "ka": "Georgianize",
    "kg": "Kongize", "ki": "Kikuyuize", "kj": "Kuanyamize", "kk": "Kazakhize",
    "kl": "Greenlandicize", "km": "Khmerize", "kn": "Kannadize", "ko": "Koreanize",
    "kr": "Kanurize", "ks": "Kashmirize", "ku": "Kurdicize", "kv": "Komize", "kw": "Cornicize",
    "ky": "Kyrgyzize", "la": "Latinize", "lb": "Luxembourgicize", "lg": "Gandize",
    "li": "Limburgicize", "ln": "Lingalize", "lo": "Laoize", "lt": "Lithuanianize",
    "lu": "Lubize", "lv": "Latvianize", "mg": "Malagasize", "mh": "Marshallesize",
    "mi": "Māorize", "mk": "Macedonianize", "ml": "Malayalamize", "mn": "Mongolianize",
    "mr": "Marathize", "ms": "Malayize", "mt": "Maltesize", "my": "Burmesize",
    "na": "Nauruanize", "nb": "Bokmålize", "nd": "Sindebelize", "ne": "Nepalize",
    "ng": "Ndongize", "nl": "Dutchize", "nn": "Nynorskize", "no": "Norskize", "nr": "Ndebelize",
    "nv": "Navajize", "ny": "Nyanjize", "oc": "Occitanize", "oj": "Ojibwize", "om": "Oromize",
    "or": "Odize", "os": "Ossetize", "pa": "Punjabize", "pi": "Palize", "pl": "Polonize",
    "ps": "Pashtize", "pt": "Portuguesize", "qu": "Quechuize", "rm": "Romanshize",
    "rn": "Rundize", "ro": "Romanianize", "ru": "Russianize", "rw": "Kinyarwandize",
    "sa": "Sanskritize", "sc": "Sardinianize", "sd": "Sindhize", "se": "Samize",
    "sg": "Sangize", "si": "Sinhalaize", "sk": "Slovakize", "sl": "Slovenize", "sm": "Samoanize",
    "sn": "Shonize", "so": "Soomaalize", "sq": "Albanianize", "sr": "Serbianize",
    "ss": "Swatize", "st": "Sesothize", "su": "Sundanesize", "sv": "Swedicize",
    "sw": "Swahilize", "ta": "Tamilize", "te": "Telugize", "tg": "Tajikize", "th": "Thaize",
    "ti": "Tigrinyize", "tk": "Turkmenize", "tl": "Tagalogize", "tn": "Tswanize",
    "to": "Tonganize", "tr": "Turkicize", "ts": "Tsongize", "tt": "Tatarize", "tw": "Twize",
    "ty": "Tahitianize", "ug": "Uyghurize", "uk": "Ukrainianize", "ur": "Urdize",
    "uz": "Uzbekize", "ve": "Vendize", "vi": "Vietnamesize", "vo": "Volapükize",
    "wa": "Walloonize", "wo": "Wolofize", "xh": "Xhosize", "yi": "Yiddishize", "yo": "Yorubize",
    "za": "Zhuangize", "zh": "Sinicize", "zu": "Zulize",
}

# Why a brand or flag is what it is, where that isn't the plain rule.
NOTES = {
    "ar": "Arabicize (adapt to Arabic, a linguistic term), not Arabize: Arabization also names forced-assimilation policies.",
    "am": "Amharicize, not Amharize: Amharization names an assimilation policy.",
    "hu": "Hungarianize, not Magyarize: Magyarization names a forced-assimilation policy.",
    "ru": "Russianize (the pattern form), not Russify: Russification is the forced-assimilation sense.",
    "tr": "Turkicize, not Turkify: Turkification names a forced-assimilation policy.",
    "bg": "Bulgarianize, not Bulgarize: Bulgarization names the 1980s forced renaming of Bulgarian Turks.",
    "no": "Norskize, not Norwegianize: Norwegianization (fornorsking) names the assimilation policy toward the Sami and Kven.",
    "vi": "Vietnamesize, not Vietnamize: Vietnamization names a war policy.",
    "my": "Burmesize, not Burmanize: Burmanization names an assimilation policy toward minorities.",
    "si": "Sinhalaize, not Sinhalize: Sinhalization names state settlement and language policies.",
    "so": "Soomaalize, from the autonym Soomaali: Somalization is used for state collapse.",
    "ps": "Pashtize, not Pashtunize: Pashtunization names an assimilation policy.",
    "ro": "Romanianize, not Romanize (which means writing in Latin script).",
    "pl": "Polonize: the established English word, mostly cultural and linguistic.",
    "zh": "Sinicize: the established English word, also a linguistic term (Sinicized vocabulary).",
    "de": "Germanize: both the established word and the pattern form.",
    "ja": "Japanize: the established English word.",
    "ie": "Occidentalize: Interlingue's original name was Occidental; Interlinguize is Interlingua's.",
    "nd": "Sindebelize, from Sindebele, Zimbabwe's name for North Ndebele; Ndebelize is South Ndebele's.",
    "ae": "No flag: a classical language. Emblem: a flame, the sacred fire of the Zoroastrian scripture written in Avestan.",
    "cr": "No flag: Cree is spoken by many First Nations, each with its own flag. Emblem: ᐃ, a syllabic of Cree syllabics (first of ᐃᓂᓂᒧᐎᓐ, ininîmowin).",
    "cu": "No flag: a classical, liturgical language. Emblem: Ѧ (little yus), a letter of early Cyrillic.",
    "ff": "No flag: spoken across many states. Emblem: Ɓ, the hooked b of Fula's Latin alphabet.",
    "ik": "No state flag. Emblem: the Inuit Circumpolar Council's flag, shared by the Inuit languages.",
    "iu": "No state flag. Emblem: the Inuit Circumpolar Council's flag, shared by the Inuit languages.",
    "nv": "No state flag. Emblem: a simplified Navajo Nation flag (the four sacred mountains under the rainbow).",
    "oj": "No flag: Ojibwe is spoken by many First Nations and tribes, each with its own flag. Emblem: ᐊ, first syllabic of ᐊᓂᔑᓈᐯᒧᐎᓐ (Anishinaabemowin).",
    "os": "No flag: the set's Ossetian flag is South Ossetia's, a partially recognised state; most speakers live elsewhere. Emblem: Ӕ, the letter of Ossetian's Cyrillic alphabet.",
    "pi": "No flag: a classical, liturgical language. Emblem: the Dhamma wheel of the Theravada texts written in Pali.",
}

ALIASES = {
    "ab": ["Abkhaz"], "az": ["Azeri"], "bn": ["Bengali"], "ca": ["Valencian"], "cu": ["Old Church Slavonic", "Church Slavonic", "Old Bulgarian"],
    "dv": ["Dhivehi", "Maldivian"], "el": ["Modern Greek"], "es": ["Castilian"], "fa": ["Farsi"],
    "ff": ["Fulah", "Fulani"], "fy": ["Frisian"], "gd": ["Gaelic"], "gn": ["Guaraní"], "ht": ["Haitian"],
    "ie": ["Occidental"], "ii": ["Nuosu", "Yi"], "ki": ["Gikuyu"], "kj": ["Kwanyama"], "kl": ["Greenlandic"],
    "kv": ["Komi-Zyrian"], "ky": ["Kirghiz"], "lb": ["Letzeburgesch"], "lg": ["Luganda"], "li": ["Limburgan"],
    "lu": ["Luba"], "nd": ["Sindebele"], "nl": ["Flemish"], "nv": ["Navaho"], "ny": ["Chichewa", "Chewa"],
    "oc": ["Provençal"], "or": ["Oriya"], "os": ["Ossetian"], "pa": ["Panjabi"], "ps": ["Pushto"],
    "rn": ["Kirundi"], "ro": ["Moldavian", "Moldovan"], "rw": ["Rwanda"], "se": ["Sami", "Saami"],
    "si": ["Sinhalese"], "ss": ["Swazi"], "st": ["Sesotho"], "tl": ["Filipino"], "tn": ["Setswana"],
    "to": ["Tonga"], "ug": ["Uighur"], "ve": ["Tshivenda"], "za": ["Chuang"],
}

CLASSICAL = {"ae", "cu", "la", "pi", "sa"}
CONSTRUCTED = {"eo", "ia", "ie", "io", "vo"}
MACROLANGUAGES = {"ak", "ar", "ay", "cr", "et", "fa", "ff", "gn", "ik", "iu", "kg", "kr", "ku", "kv", "lv",
                  "mg", "mn", "ms", "ne", "no", "oj", "om", "or", "ps", "qu", "sc", "sq", "sw", "uz",
                  "yi", "za", "zh"}
MEMBER_OF = {"nb": "no", "nn": "no", "tw": "ak"}

# Language -> country/region flag when circle-flags has no language flag: the country where the
# language is official or chiefly written. None: no single fair flag (classical, or spread across
# many states, or Indigenous languages a state flag would misrepresent): the UI draws its EMBLEM.
FLAG_FALLBACK = {
    "ae": None, "cr": None, "cu": None, "ff": None, "hz": "na", "ii": "cn", "ik": None, "iu": None,
    "kj": "na", "li": "nl", "ng": "na", "nv": None, "oj": None, "pi": None, "sa": "in", "ts": "za",
    "tw": "gh", "ve": "za", "wa": "be", "wo": "sn", "za": "cn",
}
FLAG_OVERRIDE = {"os": None}

# Languages without a flag get an emblem Linguize draws (res/drawable/lab_emblem_<key>.xml), never a
# code badge. docs/lab/language-catalog.md says why each one is what it is.
EMBLEM = {"ae": "ae", "cr": "cr", "cu": "cu", "ff": "ff", "ik": "inuit", "iu": "inuit", "nv": "nv", "oj": "oj", "os": "os", "pi": "pi"}

VARIETIES = {
    "es": [("Peninsular", ["peninsular", "spain", "españa"], "es"), ("Mexican", ["mexic", "méxic"], "mx"),
           ("Rioplatense", ["argentin", "rioplat"], "ar"), ("Colombian", ["colombia"], "co")],
    "en": [("British", ["british", "uk", "england"], "gb"), ("American", ["american", "us"], "us"),
           ("Australian", ["australia"], "au"), ("Canadian", ["canad"], "ca"),
           ("Irish", ["irish", "ireland"], "ie"), ("New Zealand", ["zealand"], "nz")],
    "pt": [("European", ["european", "portugal"], "pt"), ("Brazilian", ["brazil", "brasil"], "br")],
    "fr": [("France", ["france"], "fr"), ("Canadian", ["canad", "québec", "quebec"], "ca"),
           ("Belgian", ["belg"], "be"), ("Swiss", ["swiss", "suisse"], "ch")],
    "de": [("Germany", ["germany"], "de"), ("Austrian", ["austria", "österreich"], "at"),
           ("Swiss", ["swiss", "schweiz"], "ch")],
    "ca": [("Central", ["central"], "es-ct"), ("Valencian", ["valencia"], "es-vc"),
           ("Balearic", ["balear", "mallorc"], "es-ib")],
}


def resolve(name):
    """A flag file name -> canonical key (symlinks resolved), or None if absent."""
    p = os.path.join(FLAGS, name + ".svg")
    if not os.path.lexists(p):
        return None
    real = os.path.realpath(p)
    assert real.startswith(os.path.realpath(FLAGS) + os.sep), real
    rel = os.path.relpath(real, os.path.realpath(FLAGS))
    return rel[:-4].replace(os.sep, "/")


def res_name(key):
    return "lab_flag_" + key.replace("-", "_").replace("/", "_").lower()


def main():
    al = json.load(open(f"{CLDR}/core/package/supplemental/aliases.json"))["supplemental"]["metadata"]["alias"]["languageAlias"]
    codes = sorted({v["_replacement"] for k, v in al.items()
                    if len(k) == 3 and v.get("_reason") == "overlong" and len(v["_replacement"]) == 2} | set(EXTRA_CODES))
    en = json.load(open(f"{CLDR}/ln/package/main/en/languages.json"))["main"]["en"]["localeDisplayNames"]["languages"]
    langs, keys, native_src = [], set(), {"cldr": 0, "fallback": 0}
    for c in codes:
        p = f"{CLDR}/ln/package/main/{c}/languages.json"
        native = None
        if os.path.exists(p):
            native = json.load(open(p))["main"][c]["localeDisplayNames"]["languages"].get(c)
        if native:
            native_src["cldr"] += 1
        else:
            native = NATIVE_FALLBACK[c]
            native_src["fallback"] += 1
        if c in FLAG_OVERRIDE:
            flag = FLAG_OVERRIDE[c]
        else:
            flag = resolve("language/" + c)
            if flag is None:
                fb = FLAG_FALLBACK[c]
                flag = resolve(fb) if fb else None
                assert fb is None or flag, (c, fb)
        entry = {"code": c, "english": en[c], "native": native, "brand": BRAND[c], "flag": flag}
        if not flag:
            entry["emblem"] = EMBLEM[c]
        if flag:
            keys.add(flag)
        kind = "classical" if c in CLASSICAL else "constructed" if c in CONSTRUCTED else "living"
        if kind != "living":
            entry["kind"] = kind
        if c in MACROLANGUAGES:
            entry["macrolanguage"] = True
        if c in MEMBER_OF:
            entry["macro"] = MEMBER_OF[c]
        if c in ALIASES:
            entry["aliases"] = ALIASES[c]
        if c in VARIETIES:
            vs = []
            for name, match, fk in VARIETIES[c]:
                k = resolve(fk)
                assert k, fk
                keys.add(k)
                vs.append({"name": name, "match": match, "flag": k})
            entry["varieties"] = vs
        if c in NOTES:
            entry["notes"] = NOTES[c]
        langs.append(entry)
    assert set(BRAND) == set(codes), set(BRAND) ^ set(codes)
    assert len({l["brand"].lower() for l in langs}) == len(langs)
    out = {
        "version": 1,
        "source": {
            "codes": "ISO 639-1, as the two-letter codes in Unicode CLDR 49 supplemental aliases (cldr-core 49.0.0), plus tl and tw",
            "english": "Unicode CLDR 49 English display names (cldr-localenames-full 49.0.0), Unicode License v3",
            "native": "Unicode CLDR 49 display name of each language in itself; %d without CLDR locale data from common reference usage" % native_src["fallback"],
            "flags": "HatScripts circle-flags (MIT), commit 379588b",
            "brand": "Linguize, docs/lab/language-catalog.md",
            "emblems": "Linguize, vector drawables (res/drawable/lab_emblem_*.xml); docs/lab/language-catalog.md",
        },
        "languages": langs,
    }
    assets = f"{REPO}/app/src/main/assets"
    with open(f"{assets}/lab_languages.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write("\n")

    import cairosvg
    from PIL import Image
    draw = f"{REPO}/app/src/main/res/drawable-nodpi"
    os.makedirs(draw, exist_ok=True)
    total = 0
    import re
    from PIL import ImageDraw
    big = PX * 4
    circle = Image.new("L", (big, big), 0)
    ImageDraw.Draw(circle).ellipse((0, 0, big - 1, big - 1), fill=255)
    for k in sorted(keys):
        svg = open(os.path.join(FLAGS, k + ".svg"), encoding="utf-8").read()
        # cairosvg drops these flags' circle mask, so render the square art and clip it here.
        svg = re.sub(r"<mask\b.*?</mask>", "", svg, flags=re.S)
        svg = re.sub(r'\smask="url\(#[^)]*\)"', "", svg)
        png = cairosvg.svg2png(bytestring=svg.encode("utf-8"), output_width=big, output_height=big, unsafe=False)
        img = Image.open(io.BytesIO(png)).convert("RGBA")
        alpha = Image.new("L", (big, big), 0)
        alpha.paste(img.getchannel("A"), mask=circle)
        img.putalpha(alpha)
        img = img.resize((PX, PX), Image.LANCZOS)
        path = f"{draw}/{res_name(k)}.webp"
        img.save(path, "WEBP", lossless=True, quality=100, method=6)
        total += os.path.getsize(path)
    print("languages", len(langs), "native", native_src, "flags", len(keys), "bytes", total)
    print("with flag", sum(1 for l in langs if l["flag"]), "null", [l["code"] for l in langs if not l["flag"]])

    kt = [
        "package com.evanaronson.linguize.ui.language.catalog",
        "",
        "import androidx.annotation.DrawableRes",
        "import com.evanaronson.linguize.R",
        "",
        "// Generated with the language catalog's assets (docs/lab/language-catalog.md); do not edit by hand.",
        "// Keys are circle-flags file names, as lab_languages.json names them.",
        "",
        "/** The circular flag drawn for a catalog flag key, or null for a key with no drawable. */",
        "@DrawableRes",
        "fun flagDrawable(key: String): Int? = when (key) {",
    ]
    for k in sorted(keys):
        kt.append(f'    "{k}" -> R.drawable.{res_name(k)}')
    kt += ["    else -> null", "}", "", "/** Every key [flagDrawable] knows. */",
           "val flagKeys: Set<String> = setOf(",
           *[f'    "{k}",' for k in sorted(keys)], ")", ""]
    with open(f"{REPO}/app/src/main/java/com/evanaronson/linguize/ui/language/catalog/LanguageFlagDrawables.kt", "w") as f:
        f.write("\n".join(kt))


main()
