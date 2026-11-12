package com.evanaronson.linguize.llm.lab

import com.evanaronson.linguize.llm.Prompt
import java.security.MessageDigest

/**
 * Lab: gap filling as part of every check (docs/lab/gaps-in-check.md).
 *
 * The shipped [Prompt] is never changed. This composes the instructions a check sends: the
 * shipped `check_prompt.md`, then [addendum] (`lab_gaps_in_check.md`). The user message and
 * the schema are the shipped ones, so the answer is read by the shipped parser and a filled
 * gap is an ordinary fix to the card, the edit engine and History.
 */
class GapsInCheck(val addendum: String) {
    /** The shipped instructions, then the gap rules. */
    fun system(baseSystem: String): String = baseSystem.trimEnd() + "\n\n" + addendum.trim() + "\n"

    /**
     * The context's prompt hash: [baseHash] (the shipped hash, or a reply check's) with a
     * suffix for these rules, so History never reuses an answer from a check without them.
     */
    fun hash(baseHash: String): String = baseHash + "-g" + MessageDigest.getInstance("SHA-256")
        .digest(addendum.toByteArray())
        .take(4)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val ASSET = "lab_gaps_in_check.md"

        /** Reads [ASSET] with [read] (the app's assets). */
        fun load(read: (String) -> String) = GapsInCheck(read(ASSET))
    }
}
