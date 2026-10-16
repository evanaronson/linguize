package com.evanaronson.linguize.history

import com.evanaronson.linguize.core.Verdict
import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

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

/** Only readable status/language evidence from a successful answer counts; unclear isn't a guess. */
internal fun detectedLanguage(raw: String?, failure: String?): String? {
    if (failure != null || raw == null) return null
    return try {
        val fields = historyJson.parseToJsonElement(raw) as? JsonObject ?: return null
        val status = (fields["status"] as? JsonPrimitive)?.takeIf { it.isString } ?: return null
        if (historyJson.decodeFromJsonElement<Verdict.Status>(status) == Verdict.Status.Unclear) return null
        val language = (fields["language"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        languageCode(language)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
}
