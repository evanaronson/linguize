package com.evanaronson.linguize.core

import kotlinx.serialization.Serializable

/** Reading assistance only: there are no edits, decisions, or replacement text. */
@Serializable
data class Understood(val translation: String, val notes: List<UnderstandingNote>)

/** A source excerpt anchors each explanation instead of mixing commentary into translation. */
@Serializable
data class UnderstandingNote(val quote: String, val explanation: String)
