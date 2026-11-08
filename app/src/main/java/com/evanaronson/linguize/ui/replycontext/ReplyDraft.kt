package com.evanaronson.linguize.ui.replycontext

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.evanaronson.linguize.llm.lab.ReplyPrompt
import com.evanaronson.linguize.ui.ClipboardText

/** Why the editor for the message being replied to is showing a note. */
internal enum class ReplyNotice { NoCopiedText, CopiedTextUnavailable, TooLong }

/** What a tap leads to: attach a message, or show the editor as [draft]. */
internal sealed interface ReplyStep {
    data class Attach(val message: String) : ReplyStep
    data class Show(val draft: ReplyDraft) : ReplyStep
}

/**
 * Lab: the "Replying to" entry before a message is attached. Collapsed, it offers Use copied
 * text (one tap) and Type it; [editing] shows a field. Copied text that can't be used opens
 * the field with a [notice], so the writer can paste or type instead.
 */
internal data class ReplyDraft(
    val editing: Boolean = false,
    val text: String = "",
    val notice: ReplyNotice? = null,
) {
    val tooLong: Boolean get() = text.trim().length > ReplyPrompt.MAX_CHARS

    /** Add is enabled only for a message that can be attached. */
    val canAdd: Boolean get() = ReplyPrompt.normalize(text) != null

    fun typing(): ReplyDraft = copy(editing = true)

    fun typed(value: String): ReplyDraft = copy(text = value, notice = null)

    /** Copied text attaches at once; nothing usable opens the field with a note. */
    fun usingClipboard(clip: ClipboardText): ReplyStep = when (clip) {
        is ClipboardText.Text -> ReplyPrompt.normalize(clip.value)?.let { ReplyStep.Attach(it) }
            ?: ReplyStep.Show(copy(editing = true, text = clip.value, notice = ReplyNotice.TooLong))
        ClipboardText.NoText -> ReplyStep.Show(copy(editing = true, notice = ReplyNotice.NoCopiedText))
        ClipboardText.Unavailable -> ReplyStep.Show(copy(editing = true, notice = ReplyNotice.CopiedTextUnavailable))
    }

    fun submitted(): ReplyStep = ReplyPrompt.normalize(text)?.let { ReplyStep.Attach(it) }
        ?: ReplyStep.Show(copy(notice = if (tooLong) ReplyNotice.TooLong else null))

    companion object {
        val Saver: Saver<ReplyDraft, Any> = listSaver(
            save = { listOf(it.editing, it.text, it.notice?.name.orEmpty()) },
            restore = { saved ->
                ReplyDraft(
                    editing = saved[0] as Boolean,
                    text = saved[1] as String,
                    notice = ReplyNotice.entries.firstOrNull { it.name == saved[2] },
                )
            },
        )
    }
}

/** The message on one line, for the compact view: runs of whitespace and line breaks become one space. */
internal fun replyPreview(message: String): String = message.trim().replace(WHITESPACE, " ")

private val WHITESPACE = Regex("""\s+""")
