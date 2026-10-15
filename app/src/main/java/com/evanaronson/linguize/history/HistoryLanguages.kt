package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.Verdict
import java.util.Locale
import kotlinx.serialization.SerializationException

/** Stable ISO 639-1 partition keys, never translated UI labels or model prose. */
internal fun languageCode(value: String?): String? {
    val name = value?.trim()?.substringBefore('(')?.trim()?.lowercase(Locale.ROOT) ?: return null
    return languageCodes[name]
}

private val languageCodes: Map<String, String> by lazy {
    buildMap {
        Locale.getISOLanguages().forEach { code ->
            put(code, code)
            put(Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT), code)
        }
        put("castilian", "es")
    }
}

/** Only a readable, successful answer is evidence of detection; unclear isn't a guess. */
internal fun detectedLanguage(raw: String?, failure: String?): String? {
    if (failure != null || raw == null) return null
    val verdict = try {
        historyJson.decodeFromString<Verdict>(raw)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    return if (verdict.status == Verdict.Status.Unclear) null else languageCode(verdict.language)
}
