package com.evanaronson.linguize.data

import com.evanaronson.linguize.codec.Tokens

/** Placement of both loading and result cards over another app. */
enum class PopupPosition(val token: String) {
    Bottom("bottom"),
    Middle("middle"),
    Top("top");

    companion object {
        val tokens = Tokens(entries, PopupPosition::token)
    }
}
