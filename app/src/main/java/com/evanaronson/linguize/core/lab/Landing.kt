package com.evanaronson.linguize.core.lab

import com.evanaronson.linguize.codec.Tokens

/**
 * Lab, How it lands: how the checked message would likely come across to a native reader,
 * read from the same check answer as the fixes (its optional `landing`). Read-only: nothing
 * here is an edit. When tone needs help, the check offers it as ordinary rewordings.
 */
data class Landing(
    /** The text it was judged on: the answer's corrected text, or the original when nothing needed fixing. */
    val text: String,
    val overall: LandingOverall,
    /** One short line in the writer's own language ("friendly, a bit direct"). */
    val summary: String,
    /** 1 cold … 5 warm. */
    val warmth: Int,
    /** 1 indirect … 5 blunt. */
    val directness: Int,
    /** 1 casual … 5 formal. */
    val formality: Int,
    /** Exact excerpts of [text] that most shape the impression. */
    val drivers: List<String> = emptyList(),
    /** Short pragmatic or cultural notes, in the writer's language. */
    val notes: List<String> = emptyList(),
) {
    companion object {
        const val SCALE_MIN = 1
        const val SCALE_MAX = 5
        const val MAX_DRIVERS = 4
        const val MAX_NOTES = 3
    }
}

/** The overall reading. Each [token] is its value in the answer's JSON. */
enum class LandingOverall(val token: String) {
    LandsWell("lands_well"),
    CouldLandDifferently("could_land_differently"),
    CouldLandBadly("could_land_badly"),
    ;

    companion object {
        val tokens = Tokens(entries, LandingOverall::token)
    }
}
