package com.evanaronson.linguize.core.memory

/**
 * Lab: what Linguize remembers about the writer between conversations (who they are, who they
 * write to, what they're working on). The writer can see, change and delete all of it.
 *
 * Every remembered fact names the sources it was learned from, so forgetting a source (for
 * example deleting a conversation) removes what was learned only from it.
 */
interface UserMemory {
    /** Plain-text notes for a model's instructions, or "" when nothing is remembered or memory is off. */
    suspend fun forPrompt(): String

    /**
     * Learns from a finished exchange. [source] identifies it, e.g. "chat:<conversation id>".
     * True only when a valid answer was committed (including a valid no-op); skips and failures
     * return false so callers do not advance their learned-message cursor.
     */
    suspend fun learnFrom(source: String, messages: List<MemoryMessage>): Boolean

    /** Forgets everything learned only from [source]; throws if the deletion cannot be saved. */
    suspend fun forget(source: String)

    /** Remembers nothing. */
    object None : UserMemory {
        override suspend fun forPrompt() = ""
        override suspend fun learnFrom(source: String, messages: List<MemoryMessage>) = false
        override suspend fun forget(source: String) = Unit
    }
}

/** One message of an exchange, for learning. [fromWriter] is false for the model's turns. */
data class MemoryMessage(val fromWriter: Boolean, val text: String)
