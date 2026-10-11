package com.evanaronson.linguize.llm

import com.evanaronson.linguize.codec.Tokens

enum class Provider(
    /** What settings, keys and history keep. */
    val token: String,
    val label: String,
    val keyUrl: String,
    val recommendedModel: String,
) {
    Gemini("gemini", "Google Gemini", "https://aistudio.google.com/apikey", "gemini-3.5-flash-lite"),
    OpenAI("openai", "OpenAI", "https://platform.openai.com/api-keys", "gpt-6-sol"),
    ;

    companion object {
        val tokens = Tokens(entries, Provider::token)
    }
}
