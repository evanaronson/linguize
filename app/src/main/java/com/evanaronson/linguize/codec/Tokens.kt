package com.evanaronson.linguize.codec

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * How an enum is written wherever it's kept: one stable lowercase token per constant,
 * the same in the database, the export, settings and the prompt. Each kept enum spells
 * its tokens out on its constants (`token`) and has one [Tokens] for them, so renaming a
 * constant can't change what's stored, and there is one place that says what a token is.
 * Reading is lenient: a token this build doesn't know (written by a newer one, or since
 * retired) reads as null, never as a crash.
 *
 * This package imports nothing of the app, so every layer can use it.
 */
class Tokens<E : Enum<E>>(private val entries: List<E>, private val token: (E) -> String) {
    init {
        val tokens = entries.map(token)
        require(tokens.toSet().size == tokens.size) { "Tokens must be unique: $tokens" }
        require(tokens.all { it.isNotEmpty() && it == it.lowercase() }) { "Tokens must be lowercase: $tokens" }
    }

    fun encode(value: E): String = token(value)

    /** The constant [stored] stands for, or null when it isn't one of this build's tokens. */
    fun decode(stored: String?): E? = stored?.let { s -> entries.firstOrNull { token(it) == s } }

    /**
     * Like [decode], but also accepts a constant's name: what was written before there were
     * tokens (settings, preference keys). History rows written that way are rewritten when
     * the database is upgraded, so history reads tokens only.
     */
    fun decodeOrName(stored: String?): E? = decode(stored) ?: stored?.let { s -> entries.firstOrNull { it.name == s } }

    /** Each constant's name mapped to its token, where the two differ: what an upgrade rewrites. */
    fun renames(): Map<String, String> = entries.associate { it.name to token(it) }.filter { (name, token) -> name != token }
}

/**
 * An enum as its token in JSON, so a record's serialized shape is its row's shape. Reading
 * an unknown token fails with a [SerializationException].
 */
abstract class TokenSerializer<E : Enum<E>>(name: String, private val tokens: () -> Tokens<E>) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.evanaronson.linguize.codec.$name", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(tokens().encode(value))

    override fun deserialize(decoder: Decoder): E {
        val stored = decoder.decodeString()
        return tokens().decode(stored) ?: throw SerializationException("Unknown ${descriptor.serialName} \"$stored\"")
    }
}
