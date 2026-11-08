package com.evanaronson.linguize.core.chat

/** Lab Chat: which conversation the app opens to, in which language, and when memory learns. Pure. */
object ChatStart {
    /** A conversation quiet for longer than this is over: opening the app starts a new one. */
    const val RESUME_WITHIN_MILLIS = 6 * 60 * 60 * 1000L

    /** A conversation quiet this long is finished enough for memory to learn from. */
    const val QUIET_MILLIS = 20 * 60 * 1000L

    const val FALLBACK_LANGUAGE = "es"

    sealed interface Opening {
        data class Resume(val id: String) : Opening
        data object New : Opening
    }

    /**
     * Opening the chat: the latest conversation when it is still going (recent, or only the
     * partner's opener so far), else a new one.
     */
    fun opening(latest: ConversationSummary?, now: Long, resumeWithin: Long = RESUME_WITHIN_MILLIS): Opening = when {
        latest == null -> Opening.New
        latest.writerMessages == 0 -> Opening.Resume(latest.conversation.id)
        now - latest.conversation.updatedAt < resumeWithin -> Opening.Resume(latest.conversation.id)
        else -> Opening.New
    }

    /**
     * The language a new conversation starts in: the last one the writer chose or used here,
     * else the only language their selection menu names, else the language they check most
     * in History, else Spanish. Every candidate must be in [supported].
     */
    fun language(
        lastUsed: String?,
        menuLanguages: Collection<String>,
        historyMostUsedFirst: List<String>,
        supported: List<String>,
    ): String {
        lastUsed?.takeIf { it in supported }?.let { return it }
        menuLanguages.filter { it in supported }.distinct().singleOrNull()?.let { return it }
        historyMostUsedFirst.firstOrNull { it in supported }?.let { return it }
        return FALLBACK_LANGUAGE.takeIf { it in supported } ?: supported.first()
    }

    /** Languages in [targets] (one per past check, null when unknown), most used first. */
    fun mostUsed(targets: List<String?>): List<String> =
        targets.filterNotNull().groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }

    /**
     * Whether memory should learn from [conversation] now: it has new messages, the writer
     * said something, and it has gone quiet or the writer has [left] it.
     */
    fun shouldLearn(conversation: Conversation, messages: Int, writerMessages: Int, now: Long, left: Boolean): Boolean =
        writerMessages > 0 && messages > conversation.learnedCount &&
            (left || now - conversation.updatedAt >= QUIET_MILLIS)
}
