package com.evanaronson.linguize.core.language

import java.util.Locale

/**
 * Lab, Composer language: the language a field is written in, as a BCP 47 tag for the
 * keyboard's hint locale ("es-MX", "ca-ES-valencia", "fr"), and the Chat composer's placeholder
 * in that language. Pure: the UI turns the tag into `KeyboardOptions.hintLocales`.
 * See docs/lab/chat.md#the-composer-speaks-the-language.
 */
object KeyboardLanguage {
    private val REGION = Regex("[a-z]{2}")
    private val NOT_LETTER = Regex("[^\\p{L}]+")

    /**
     * The hint tag for [code] (any ISO 639-1 code; null or not a language gives null: no hint,
     * as under Auto-detect). With the writer's profile [variety] and the [catalog], a variety
     * the catalog lists (with its own flag) adds that region ("Mexican" Spanish is "es-MX", "Brazilian"
     * Portuguese "pt-BR"). Catalan's varieties carry Spain's region subdivision flags, so they
     * add "ES", and Valencian its registered variant ("ca-ES-valencia").
     */
    fun tag(code: String?, variety: String? = null, catalog: LanguageCatalog? = null): String? {
        val language = LanguageCodes.normalize(code) ?: return null
        if (variety.isNullOrBlank() || catalog == null) return language
        // Words of the variety that start with one of the catalog's match words: "Mexican" finds
        // "mexic", while "Australian" doesn't find American's "us" inside it.
        val words = LanguageCatalog.fold(variety).split(NOT_LETTER).filter { it.isNotEmpty() }
        val flag = catalog.find(language)?.varieties
            ?.firstOrNull { v -> v.match.any { match -> words.any { it.startsWith(LanguageCatalog.fold(match)) } } }?.flag
            ?: return language
        // A flag key is a country ("mx") or a region of one ("es-vc"): its country is the region.
        val country = flag.substringBefore('-')
        if (!REGION.matches(country)) return language
        val region = country.uppercase(Locale.ROOT)
        return if (flag == "es-vc" && language == "ca") "$language-$region-valencia" else "$language-$region"
    }

    /**
     * "Write a message" in the language of [tag], informal (the tú/tu form where the language
     * has one, as in a chat with a friend), or null when there is none bundled: the caller then
     * shows the app's own string. A region-specific wording wins over the language's ("pt-BR").
     */
    fun composerPlaceholder(tag: String?): String? {
        val normal = tag?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() } ?: return null
        val parts = normal.split('-')
        val language = LanguageCodes.normalize(parts[0]) ?: return null
        val region = parts.getOrNull(1)?.takeIf { it.length == 2 }?.uppercase(Locale.ROOT)
        return region?.let { PLACEHOLDERS["$language-$it"] } ?: PLACEHOLDERS[language]
    }

    /** The languages (and regions) with a bundled placeholder. */
    val placeholderTags: Set<String> get() = PLACEHOLDERS.keys

    /**
     * Bundled, not strings.xml: it is data keyed by the conversation's language, not the app's
     * UI locale. Informal where the language distinguishes; a neutral input label where a chat
     * placeholder never addresses the reader (Japanese, Korean, Chinese, Thai, Hebrew's gender-
     * neutral form). Languages we couldn't word with confidence are left out on purpose.
     */
    private val PLACEHOLDERS: Map<String, String> = mapOf(
        "af" to "Skryf 'n boodskap",
        "ar" to "اكتب رسالة",
        "bg" to "Напиши съобщение",
        "bn" to "বার্তা লেখো",
        "bs" to "Napiši poruku",
        "ca" to "Escriu un missatge",
        "cs" to "Napiš zprávu",
        "cy" to "Ysgrifenna neges",
        "da" to "Skriv en besked",
        "de" to "Schreib eine Nachricht",
        "el" to "Γράψε ένα μήνυμα",
        "en" to "Write a message",
        "eo" to "Skribu mesaĝon",
        "es" to "Escribe un mensaje",
        "et" to "Kirjuta sõnum",
        "eu" to "Idatzi mezu bat",
        "fa" to "یک پیام بنویس",
        "fi" to "Kirjoita viesti",
        "fr" to "Écris un message",
        "ga" to "Scríobh teachtaireacht",
        "gl" to "Escribe unha mensaxe",
        "he" to "כתיבת הודעה",
        "hi" to "संदेश लिखो",
        "hr" to "Napiši poruku",
        "hu" to "Írj egy üzenetet",
        "id" to "Tulis pesan",
        "is" to "Skrifaðu skilaboð",
        "it" to "Scrivi un messaggio",
        "ja" to "メッセージを入力",
        "ko" to "메시지 입력",
        "lt" to "Parašyk žinutę",
        "lv" to "Uzraksti ziņu",
        "mk" to "Напиши порака",
        "ms" to "Tulis mesej",
        "nb" to "Skriv en melding",
        "nl" to "Schrijf een bericht",
        "no" to "Skriv en melding",
        "pl" to "Napisz wiadomość",
        "pt" to "Escreve uma mensagem",
        "pt-BR" to "Escreva uma mensagem",
        "ro" to "Scrie un mesaj",
        "ru" to "Напиши сообщение",
        "sk" to "Napíš správu",
        "sl" to "Napiši sporočilo",
        "sq" to "Shkruaj një mesazh",
        "sr" to "Напиши поруку",
        "sv" to "Skriv ett meddelande",
        "sw" to "Andika ujumbe",
        "th" to "พิมพ์ข้อความ",
        "tl" to "Magsulat ng mensahe",
        "tr" to "Bir mesaj yaz",
        "uk" to "Напиши повідомлення",
        "ur" to "پیغام لکھو",
        "vi" to "Viết tin nhắn",
        "zh" to "输入消息",
    )
}
