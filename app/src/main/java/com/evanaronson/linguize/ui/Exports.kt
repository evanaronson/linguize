package com.evanaronson.linguize.ui

import com.evanaronson.linguize.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Retires shared exports after a deletion, even when its screen has gone away. */
fun App.forgetExports() {
    exports.invalidate()
    appScope.launch(Dispatchers.IO) { exports.clear() }
}
