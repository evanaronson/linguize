package com.evanaronson.linguize.ui.replycontext

import com.evanaronson.linguize.core.CheckResult
import com.evanaronson.linguize.llm.CheckFailure
import com.evanaronson.linguize.ui.ClipboardText
import com.evanaronson.linguize.ui.card.CardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lab: the "Replying to" entry's states, before anything reaches a check. */
class ReplyDraftTest {
    @Test
    fun copiedTextAttachesInOneTap() {
        assertEquals(ReplyStep.Attach("¿Quedamos?"), ReplyDraft().usingClipboard(ClipboardText.Text("  ¿Quedamos?\n")))
    }

    @Test
    fun nothingCopiedOpensTheFieldWithANote() {
        assertEquals(ReplyStep.Show(ReplyDraft(editing = true, notice = ReplyNotice.NoCopiedText)),
            ReplyDraft().usingClipboard(ClipboardText.NoText))
        assertEquals(ReplyStep.Show(ReplyDraft(editing = true, notice = ReplyNotice.CopiedTextUnavailable)),
            ReplyDraft().usingClipboard(ClipboardText.Unavailable))
    }

    @Test
    fun tooMuchCopiedTextIsKeptInTheFieldToShorten() {
        val long = "a".repeat(3001)
        val step = ReplyDraft().usingClipboard(ClipboardText.Text(long)) as ReplyStep.Show
        assertTrue(step.draft.editing)
        assertEquals(long, step.draft.text)
        assertTrue(step.draft.tooLong)
        assertFalse(step.draft.canAdd)
    }

    @Test
    fun typingClearsTheNoteAndAddAttachesTrimmedText() {
        val draft = ReplyDraft(editing = true, notice = ReplyNotice.NoCopiedText).typed(" Hola ")
        assertEquals(null, draft.notice)
        assertTrue(draft.canAdd)
        assertEquals(ReplyStep.Attach("Hola"), draft.submitted())
    }

    @Test
    fun addDoesNothingForBlankText() {
        val draft = ReplyDraft(editing = true, text = "   ")
        assertFalse(draft.canAdd)
        assertEquals(ReplyStep.Show(draft), draft.submitted())
    }

    @Test
    fun typeItOpensTheField() {
        assertTrue(ReplyDraft().typing().editing)
    }

    @Test
    fun thePreviewIsOneLine() {
        assertEquals("¿Vienes mañana? Dime algo", replyPreview("  ¿Vienes mañana?\n\n  Dime algo "))
    }

    @Test
    fun theCardOffersItOnceThereIsSomethingToCheckAgain() {
        assertFalse(offersReplyContext(CardState.Loading("Hola")))
        assertTrue(offersReplyContext(CardState.Done(CheckResult.Unclear)))
        assertFalse(offersReplyContext(CardState.Done(CheckResult.WrongLanguage("Catalan", "Spanish"))))
        assertTrue(offersReplyContext(CardState.Failed(CheckFailure.Reason.Offline, null)))
        assertFalse(offersReplyContext(CardState.Failed(CheckFailure.Reason.TooMany, null)))
        assertFalse(offersReplyContext(CardState.Failed(CheckFailure.Reason.TooLong, null)))
    }
}
