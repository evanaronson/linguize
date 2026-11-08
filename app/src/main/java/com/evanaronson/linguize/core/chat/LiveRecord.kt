package com.evanaronson.linguize.core.chat

import com.evanaronson.linguize.core.EditKind
import com.evanaronson.linguize.core.writingpad.LiveSuggestion
import com.evanaronson.linguize.core.writingpad.LiveSuggestions

/**
 * Lab Chat, live checking: remembers which parts of the draft came from accepted underlines,
 * so the sent message still keeps what the writer typed. Each accepted change is a marker over
 * its replacement in the draft; typing moves markers with the text, and typing into one makes
 * that part the writer's own again. Pure.
 */
class LiveRecord {
    /** Each marker reuses [LiveSuggestion]: [LiveSuggestion.from] is the accepted text now in the draft, [LiveSuggestion.replacement] what the writer had typed. */
    var markers: List<LiveSuggestion> = emptyList()
        private set
    private var text = ""
    private var nextKey = 1L

    /** The draft changed from what this last saw to [newText] by typing. */
    fun edited(newText: String) {
        if (newText == text) return
        markers = LiveSuggestions.remap(text, newText, markers, sentenceScope = false)
        text = newText
    }

    /** [suggestion] (on [before]) was accepted, which made [after]. */
    fun accepted(before: String, after: String, suggestion: LiveSuggestion) {
        edited(before)
        val delta = suggestion.replacement.length - (suggestion.end - suggestion.start)
        val moved = markers.mapNotNull { marker ->
            when {
                marker.end <= suggestion.start -> marker
                marker.start >= suggestion.end -> marker.copy(start = marker.start + delta, end = marker.end + delta)
                else -> null
            }
        }
        // A change over an earlier accepted one: the writer's own text there is still the first.
        val inside = markers.filter { it !in moved && it.start >= suggestion.start && it.end <= suggestion.end }
        val typed = if (inside.isEmpty()) suggestion.from else revert(suggestion.from, inside.map {
            it.copy(start = it.start - suggestion.start, end = it.end - suggestion.start)
        }).first
        val kind = if (inside.any { it.kind == EditKind.Fix }) EditKind.Fix else suggestion.kind
        markers = (moved + LiveSuggestion(nextKey++, kind, suggestion.start, suggestion.start + suggestion.replacement.length,
            from = suggestion.replacement, replacement = typed, why = suggestion.why)).sortedBy { it.start }
        text = after
    }

    /** What to send for [draft]: as typed, with each accepted change kept against that text. */
    fun outgoing(draft: String): Outgoing {
        edited(draft)
        val trimmedStart = draft.length - draft.trimStart().length
        val sent = draft.trim()
        val inText = markers.mapNotNull { marker ->
            val start = marker.start - trimmedStart
            val end = marker.end - trimmedStart
            if (start < 0 || end > sent.length) null else marker.copy(start = start, end = end)
        }
        val (raw, changes) = revert(sent, inText)
        val real = changes.filter { it.from != it.to }
        return Outgoing(raw, sent, real, if (real.isEmpty()) SentCheck.None else SentCheck.Corrected)
    }

    /** Clears everything, as after a send. */
    fun reset() {
        markers = emptyList()
        text = ""
    }

    companion object {
        /** [text] with each marker turned back into what was typed, and the changes against that raw text. */
        internal fun revert(text: String, markers: List<LiveSuggestion>): Pair<String, List<ChatChange>> {
            val raw = StringBuilder()
            val changes = mutableListOf<ChatChange>()
            var pos = 0
            for (marker in markers.sortedBy { it.start }) {
                if (marker.start < pos || marker.end > text.length || text.substring(marker.start, marker.end) != marker.from) continue
                raw.append(text, pos, marker.start)
                val start = raw.length
                raw.append(marker.replacement)
                changes += ChatChange(marker.kind, start, raw.length, marker.replacement, marker.from, marker.why)
                pos = marker.end
            }
            raw.append(text, pos, text.length)
            return raw.toString() to changes
        }
    }
}
