package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.EditKind

/** Lab, Chat tab IA (docs/lab/ia-chat.md): what the list and a sent message say about themselves. Pure. */
object ChatLabels {
    /** How a sent message with accepted changes is labelled. */
    enum class Sent { Corrected, Reworded }

    /**
     * "Corrected" only when something accepted was a fix; a message whose accepted changes were
     * all rewordings (More natural, how it lands) wasn't wrong, so it reads "Reworded".
     */
    fun sent(changes: List<ChatChange>): Sent =
        if (changes.any { it.kind == EditKind.Fix }) Sent.Corrected else Sent.Reworded

    /**
     * What a screen reader hears for a conversation row: everything the row shows, in its
     * order (title, who the partner plays, language, time, then the last message).
     */
    fun rowDescription(title: String, persona: String?, language: String, time: String, last: String?): String =
        listOfNotNull(title, persona, language, time, last).filter { it.isNotBlank() }.joinToString(", ")
}
