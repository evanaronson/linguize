package com.evanaronson.linguize.llm

private val URL = Regex("""\s*https?://[^\s"'<>)\]]*[^\s"'<>)\].,;:]""")

/** API keys, or the shown part of one: OpenAI's "sk-proj-****abcd", Google's "AIza…", a bearer token. */
private val KEY = Regex("""\bsk-[A-Za-z0-9_*\-]{4,}|\bAIza[A-Za-z0-9_\-]{8,}|\bBearer\s+\S+|\bkey=[A-Za-z0-9_\-]+""", RegexOption.IGNORE_CASE)

/**
 * A provider's error text without anything that could expose an API key: key fragments and
 * every URL (a URL can carry the key as a query parameter) are cut out. Providers echo part of
 * a rejected key in their message, and that text lands on screen and in history.
 */
internal fun scrubSecrets(text: String): String =
    text.replace(URL, "").replace(KEY, "…").replace(Regex("""[ \t]{2,}"""), " ").trim()
