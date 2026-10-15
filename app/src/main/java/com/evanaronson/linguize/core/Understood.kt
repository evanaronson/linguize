package com.evanaronson.linguize.core

import kotlinx.serialization.Serializable

/** Reading assistance only: there are no edits, decisions, or replacement text. */
@Serializable
data class Understood(val meaning: String)
