package com.evanaronson.linguize.core.time

/**
 * Lab, Time spent: one message's time, for the line under it in the chat and on its History
 * page. Writing is measured in the chat; otherwise it's [WritingEstimator]'s estimate, and
 * [writingEstimated] says so wherever it's shown. [reviewMs] is null when review wasn't timed
 * (a check saved before Time spent): it's never estimated.
 */
data class MessageTime(val writingMs: Long, val writingEstimated: Boolean, val reviewMs: Long?) {
    companion object {
        /** The time of History session [sessionId] among [records] (every record, for the estimate); null when none was recorded. */
        fun of(sessionId: String, records: List<TimeRecord>): MessageTime? {
            val record = records.firstOrNull { it.sessionId == sessionId } ?: return null
            val writing = record.writingMs ?: WritingEstimator(records).writingOf(record)
            return MessageTime(writing, record.writingMs == null, record.reviewMs)
        }

        /**
         * The time of [message], a History session with no record: writing estimated from
         * [records] (every record, as the totals do), review not timed. When [records] has one
         * for it after all, that's used.
         */
        fun of(message: UntimedMessage, records: List<TimeRecord>): MessageTime =
            of(message.sessionId, records) ?: MessageTime(WritingEstimator(records).writingOf(message), true, null)
    }
}
