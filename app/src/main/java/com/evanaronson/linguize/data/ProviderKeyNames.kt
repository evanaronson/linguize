package com.evanaronson.linguize.data

import android.content.SharedPreferences
import com.evanaronson.linguize.llm.Provider

/**
 * Moves what earlier builds saved under a provider's constant name ([key] of [Provider.name])
 * to its token ([key] of [Provider.token]), so renaming a constant can never lose a saved
 * value. A value already under the token wins; the old entry goes either way. Values are
 * moved as they're stored (an encrypted key stays encrypted). Does nothing once done.
 */
internal fun SharedPreferences.moveProviderNamesToTokens(key: (String) -> String) {
    val moves = Provider.entries.map { key(it.name) to key(it.token) }.filter { (old, new) -> old != new && contains(old) }
    if (moves.isEmpty()) return
    val edit = edit()
    for ((old, new) in moves) {
        if (!contains(new)) edit.putString(new, getString(old, null))
        edit.remove(old)
    }
    edit.apply()
}
