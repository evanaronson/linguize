package com.evanaronson.linguize

import com.evanaronson.linguize.core.AppVoice
import com.evanaronson.linguize.core.Voice

/**
 * This context with an app's [voice] applied. The result is the snapshot the request is
 * sent with and history keeps ([CheckContext.stored]), exactly like a change in Settings:
 * the register lands in the writing profiles and the suggestions in [CheckContext.judgments].
 * No new history field is needed, and later rule edits never reinterpret past checks.
 */
fun CheckContext.withVoice(voice: Voice): CheckContext {
    if (voice.isDefault) return this
    return copy(
        judgments = AppVoice.judgments(judgments, voice),
        writingProfiles = AppVoice.profiles(writingProfiles, voice),
    )
}
