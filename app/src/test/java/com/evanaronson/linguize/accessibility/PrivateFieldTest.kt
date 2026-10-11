package com.evanaronson.linguize.accessibility

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which fields the accessibility button leaves unread. */
class PrivateFieldTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val message = text or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

    @Test
    fun ordinaryTextIsRead() {
        assertFalse(PrivateField.matches(message, "Message", "org.telegram.messenger:id/chat_input"))
        assertFalse(PrivateField.matches(text, null, null))
        assertFalse(PrivateField.matches(message, "Compose email", "com.google.android.gm:id/body"))
        assertFalse(PrivateField.matches(text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, "Subject", null))
        assertFalse(PrivateField.matches(message, "Pinned note", "pinned_note"))
        assertFalse(PrivateField.matches(0, null, null))
    }

    @Test
    fun numbersAndPhoneNumbersAreNotRead() {
        assertTrue(PrivateField.matches(InputType.TYPE_CLASS_NUMBER, null, null))
        assertTrue(PrivateField.matches(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, null, null))
        assertTrue(PrivateField.matches(InputType.TYPE_CLASS_PHONE, null, null))
    }

    @Test
    fun passwordsAndEmailAddressesAreNotRead() {
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )) {
            assertTrue("variation $variation", PrivateField.matches(text or variation, null, null))
        }
    }

    @Test
    fun codeAndCardFieldsTypedAsTextAreNotRead() {
        assertTrue(PrivateField.matches(text, null, "com.bank:id/otp_input"))
        assertTrue(PrivateField.matches(text, null, "com.shop:id/cardNumber"))
        assertTrue(PrivateField.matches(text, "Enter the verification code", null))
        assertTrue(PrivateField.matches(text, "One-time password", null))
        assertTrue(PrivateField.matches(text, "CVV", null))
        assertTrue(PrivateField.matches(text, "PIN", null))
    }

    @Test
    fun catalanAndSpanishNamesAreRecognisedWithOrWithoutAccents() {
        assertTrue(PrivateField.matches(text, "Contrasenya", null))
        assertTrue(PrivateField.matches(text, "Contraseña", null))
        assertTrue(PrivateField.matches(text, "Código de verificación", null))
        assertTrue(PrivateField.matches(text, "Codi de verificació", null))
        assertTrue(PrivateField.matches(text, "Número de tarjeta", null))
        assertTrue(PrivateField.matches(text, null, "com.banc:id/num_targeta"))
        assertFalse(PrivateField.matches(message, "Escriu un missatge", "com.chat:id/entry"))
        assertFalse(PrivateField.matches(message, "Código postal de la tienda", null))
    }
}
