package com.evanaronson.linguize.core.lab

import com.evanaronson.linguize.codec.Tokens

/**
 * How a message would likely land with a native reader of its language and variety.
 * Read-only advice: nothing here is an edit, and the writer's register is not a mistake.
 */
data class Landing(
    /** The language and regional variety the reading assumes, in the writer's language. */
    val variety: String,
    /** One or two sentences, in the writer's language: the likely impression. */
    val summary: String,
    val risk: LandingRisk,
    /** 1 cold … 5 warm. */
    val warmth: Int,
    /** 1 indirect … 5 blunt. */
    val directness: Int,
    /** 1 casual … 5 formal. */
    val formality: Int,
    /** Exact excerpts of the message that drive the impression. */
    val drivers: List<LandingDriver>,
    /** Pragmatic or cultural notes, in the writer's language. */
    val notes: List<String>,
    /** Up to [MAX_VERSIONS] other versions that keep the meaning and voice. */
    val versions: List<LandingVersion>,
) {
    companion object {
        const val SCALE_MIN = 1
        const val SCALE_MAX = 5
        const val MAX_DRIVERS = 4
        const val MAX_NOTES = 3
        const val MAX_VERSIONS = 2
    }
}

/** How likely the message is to land differently from what the writer intends. */
enum class LandingRisk(val token: String) {
    Fine("fine"),
    WorthALook("worth_a_look"),
    Risky("risky"),
    ;

    companion object {
        val tokens = Tokens(entries, LandingRisk::token)
    }
}

/** A quoted excerpt and the effect it has on a native reader. */
data class LandingDriver(val quote: String, val effect: String)

/** Another version: [label] names its aim ("Warmer"), [why] says what changed. */
data class LandingVersion(val label: String, val text: String, val why: String)
