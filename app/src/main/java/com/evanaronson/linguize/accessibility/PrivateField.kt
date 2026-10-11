package com.evanaronson.linguize.accessibility

import android.text.InputType
import java.text.Normalizer

/**
 * Whether a text field looks like it holds private details rather than writing:
 * passwords, one-time codes, PINs, card and phone numbers, email addresses. The
 * accessibility button doesn't read such a field, so its contents never reach the
 * model or history. Judged from the field's input type, and from its hint and view
 * id for fields typed as plain text (many code and card fields are).
 */
internal object PrivateField {
    fun matches(inputType: Int, hint: CharSequence?, viewId: String?): Boolean =
        privateType(inputType) || listOfNotNull(hint?.toString(), viewId?.substringAfter(":id/")).any(::soundsPrivate)

    private fun privateType(inputType: Int): Boolean = when (inputType and InputType.TYPE_MASK_CLASS) {
        // Numbers and phone numbers are never prose: codes, PINs, card numbers, amounts.
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> true
        InputType.TYPE_CLASS_TEXT -> (inputType and InputType.TYPE_MASK_VARIATION) in PRIVATE_TEXT
        else -> false
    }

    /**
     * "otp_input", "cardNumber" and "Enter the verification code" all name what they hold;
     * so do "Contrasenya" and "Número de tarjeta", for the writers Linguize is made for.
     */
    private fun soundsPrivate(name: String): Boolean {
        // Accents go first, or "contraseña" and "código" would break apart at the letter.
        val words = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
        return PRIVATE_WORDS.containsMatchIn(words)
    }

    private val PRIVATE_TEXT = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
    )

    /**
     * Words only private fields are named with. Not "email" or "phone": "Compose email"
     * is a message; those fields are recognised by their input type instead.
     */
    private val PRIVATE_WORDS = Regex(
        """\b(otp|one ?time (code|password|pin)|passcode|password|passwd|pin|cvv|cvc|iban|card ?(number|num|no)|cc ?(number|num)|(verification|security|confirmation|auth|sms) ?code|contrasenya|contrasena|(codigo|codi) (de )?(verificacion|verificacio|seguridad|seguretat|confirmacion|confirmacio|sms)|(numero|num) (de )?(tarjeta|targeta))\b""",
    )
}
