package com.evanaronson.linguize.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Complete writing choices for one language. Profiles never inherit from other profiles. */
@Serializable
data class WritingProfile(
    val id: String,
    val name: String,
    /** ISO 639-1 language code, independent of the profile's display name. */
    val language: String,
    /** Regional vocabulary and written conventions; null means no variety preference. */
    val variety: String?,
    val register: WritingRegister,
    val punctuation: Punctuation,
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(name.isNotBlank() && name.length <= 128)
        require(language.matches(Regex("[a-z]{2}")))
        require(variety == null || (variety.isNotBlank() && variety.length <= 256))
    }

    companion object {
        /** Stable identity and existing regional defaults; called once when migrating settings. */
        fun defaultFor(language: Language, punctuation: Punctuation = Punctuation.Moderate) = WritingProfile(
            id = "default-${language.code}",
            name = language.name,
            language = language.code,
            variety = language.variety,
            register = WritingRegister.Everyday,
            punctuation = punctuation,
        )
    }
}

/** Register is separate from punctuation: informal wording can still use strict punctuation. */
@Serializable
enum class WritingRegister(val token: String) {
    @SerialName("casual") Casual("casual"),
    @SerialName("everyday") Everyday("everyday"),
    @SerialName("formal") Formal("formal"),
}
