package com.evanaronson.linguize.core.chat

/**
 * Lab, Translation reliance: tells a translation the writer needed from an accidental tap.
 * Pure; [clock] is a monotonic clock in milliseconds (never wall time, which can jump).
 *
 * A partner reply's translation was **needed** once it has been readable for [thresholdMs] in
 * one showing: flipped to its translation, with the chat on screen ([visible]) and the bubble
 * itself on screen ([onScreen]). Leaving the app, the screen going off or scrolling the bubble
 * away pauses that showing's time; coming back resumes it. Flipping back ends the showing: if
 * it ends short of the threshold, nothing counts, and the next flip starts from zero (quick taps
 * don't add up). A message counts at most once, however often it's flipped.
 *
 * The host reports each change, then takes the newly needed messages with [drain]; while a
 * showing is running it should call [drain] again after [remainingMs].
 */
class TranslationDwell(
    private val clock: () -> Long,
    val thresholdMs: Long = NEEDED_MS,
) {
    /** One showing of one translation: time banked while it was readable, and since when it has been. */
    private class Showing(var bankedMs: Long = 0L, var since: Long? = null)

    private val showings = HashMap<String, Showing>()
    private val counted = HashSet<String>()
    private val fresh = ArrayList<String>()
    private var appVisible = true

    /** The bubbles on screen; null until the host first says (then every bubble counts as on screen). */
    private var laidOut: Set<String>? = null

    /** [messageId] now shows its translation ([showing]), or its original again. */
    fun shown(messageId: String, showing: Boolean) {
        settle()
        if (showing) {
            if (messageId in counted || messageId in showings) return
            showings[messageId] = Showing().also { if (readable(messageId)) it.since = clock() }
        } else {
            // Flipped back: a showing short of the threshold is dropped, never added to the next one.
            showings.remove(messageId)
        }
    }

    /** The chat is on screen (app in the foreground, screen on), or isn't. */
    fun visible(visible: Boolean) {
        settle()
        if (visible == appVisible) return
        appVisible = visible
        restart()
    }

    /** The bubbles currently on screen, by message id. */
    fun onScreen(messageIds: Set<String>) {
        settle()
        if (messageIds == laidOut) return
        laidOut = messageIds
        restart()
    }

    /** Messages whose translation became needed since the last call, oldest first. */
    fun drain(): List<String> {
        settle()
        return fresh.toList().also { fresh.clear() }
    }

    /** How long until the next showing could count, or null when none is running. */
    fun remainingMs(): Long? {
        val now = clock()
        return showings.values.mapNotNull { showing ->
            showing.since?.let { (thresholdMs - showing.bankedMs - (now - it)).coerceAtLeast(0L) }
        }.minOrNull()
    }

    /** Whether [messageId] has already counted in this session. */
    fun isCounted(messageId: String) = messageId in counted

    private fun readable(messageId: String) = appVisible && laidOut?.contains(messageId) != false

    /** Banks the running showings up to now and counts any that reached the threshold. */
    private fun settle() {
        val now = clock()
        val iterator = showings.entries.iterator()
        while (iterator.hasNext()) {
            val (id, showing) = iterator.next()
            showing.since?.let { since ->
                showing.bankedMs += (now - since).coerceAtLeast(0L)
                showing.since = now
            }
            if (showing.bankedMs >= thresholdMs) {
                iterator.remove()
                if (counted.add(id)) fresh.add(id)
            }
        }
    }

    /** After visibility changed: each showing runs exactly while its bubble is readable. */
    private fun restart() {
        val now = clock()
        showings.forEach { (id, showing) ->
            val readable = readable(id)
            if (readable && showing.since == null) showing.since = now
            if (!readable) showing.since = null
        }
    }

    companion object {
        /**
         * 2.5 s. An accidental tap is undone by a tap back within about a second (seeing the
         * tint change, then reacting), and even a slow one stays under two. Reading a typical
         * short reply (8 to 15 words) in one's own language takes about 2 to 4 s. 2.5 s sits
         * above any accidental flip and at the low end of real reading, so a glance at a
         * three-word reply may go uncounted, but a mistaken tap never counts.
         */
        const val NEEDED_MS = 2_500L
    }
}
