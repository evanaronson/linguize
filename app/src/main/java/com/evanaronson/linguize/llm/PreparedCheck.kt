package com.evanaronson.linguize.llm

/**
 * One check's outbound contract and fresh-answer reader. The reader stays the base prompt:
 * optional additions such as `landing` must not make an ordinary correction unreadable.
 * [compatibilityHash] retains History's existing identity, independently of request bytes.
 */
class PreparedCheck(
    val generation: GenerationRequest,
    val compatibilityHash: String,
    private val baseReader: Prompt,
) {
    /** Evaluation metadata describes the reader actually used, not the stricter outbound schema. */
    val parserPolicy: String = "base-fresh-v1"
    val baseReaderHash: String = baseReader.hash

    /** Reads a fresh answer with the existing base contract and preserves raw text on failure. */
    fun read(raw: String): ModelAnswer = baseReader.read(raw)
}
