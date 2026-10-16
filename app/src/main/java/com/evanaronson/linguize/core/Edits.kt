package com.evanaronson.linguize.core

import com.evanaronson.linguize.core.Alignment.Type
import java.text.Normalizer

/** Constructs complete repairs, then validates and maps explicitly reported rewordings. */
internal object Edits {
    data class Result(val edits: List<Edit>, val rejected: Boolean)

    fun fixes(original: String, corrected: String, reported: List<VerdictChange>): Result {
        val components = repairs(original, corrected)
        fun ownsComponents(span: Span) = components.none { part ->
            cuts(original, span.start, span.end, part.start, part.end) ||
                cuts(corrected, span.targetStart, span.targetEnd, part.targetStart, part.targetEnd)
        }
        val declared = located(original, corrected, reported)?.takeIf { it.all(::ownsComponents) }
            ?.map { span -> trimUnchangedContext(span).takeIf(::ownsComponents) ?: span }
            ?.takeIf { spans ->
                val sourceSentences = Sentences.ranges(original)
                val targetSentences = Sentences.ranges(corrected)
                // Structural scope policy across retained sentence stops, not a
                // classifier of grammatical dependence within a sentence.
                spans.none { span -> wording(span.from, span.replacement) &&
                    sentenceStops(span.from) == sentenceStops(span.replacement) &&
                    Sentences.crosses(sourceSentences, span.start, span.end) &&
                    Sentences.crosses(targetSentences, span.targetStart, span.targetEnd)
                }
            }
        val spans = if (declared != null) {
            declared.flatMap { span ->
                // The model supplies linguistic decisions. Only mechanically equivalent
                // orthography/formatting can be divided without inventing new decisions.
                if (wording(span.from, span.replacement)) listOf(span)
                else {
                    val parts = mechanicalRepairs(span.from, span.replacement)
                    parts.map { part ->
                        part.copy(start = span.start + part.start, end = span.start + part.end,
                            // A rating of the whole report does not assess each independent
                            // repair produced by splitting it. Keep the raw report as evidence.
                            assessment = span.assessment.takeIf { parts.size == 1 }, reportId = span.reportId,
                            assumptionIndices = span.assumptionIndices.takeIf { parts.size == 1 }.orEmpty(),
                            why = span.why.takeIf { Alignment.same(part.from, span.from) &&
                                Alignment.same(part.replacement, span.replacement) })
                    }
                }
            }
        } else if (!wording(original, corrected)) {
            groupBoundaryCase(original, components)
        } else {
            // An inconsistent answer cannot authorize inferred grammar or word choice.
            // Keep complete accent/word-spacing repairs whose case and letters agree;
            // isolated punctuation/case fragments may depend on the rejected rewrite.
            components.filter {
                wordCount(it.from) > 0 && wordCount(it.replacement) > 0 &&
                    letters(it.from, ignoreCase = false) == letters(it.replacement, ignoreCase = false)
            }
        }
        val fixes = toEdits(groupBoundaryCase(original, spans.sortedBy { it.start }), reported, EditKind.Fix, 0)
        val placed = place(fixes).associateBy { it.fix.id }
        val ordered = fixes.map { it.copy(order = 2 * placed.getValue(it.id).baseStart + 1) }
        return Result(ordered, !Alignment.same(render(original, ordered).text, corrected))
    }

    /**
     * A reported decision may contain components, but cannot divide their ownership.
     * Outer separators belong to mechanical repair ranges, not lexical ownership:
     * a complete inserted word can be reported with either adjacent separator.
     * Internal separators still belong to a broken/joined word's component.
     */
    private fun cuts(text: String, start: Int, end: Int, componentStart: Int, componentEnd: Int): Boolean {
        var left = componentStart
        var right = componentEnd
        while (left < right && text[left].isWhitespace()) left++
        while (right > left && text[right - 1].isWhitespace()) right--
        return left < start && start < right || left < end && end < right
    }

    /** Split mechanical repairs, keeping a sentence stop with its required initial case. */
    private fun mechanicalRepairs(original: String, corrected: String) =
        groupBoundaryCase(original, repairs(original, corrected))

    private fun groupBoundaryCase(original: String, parts: List<Span>): List<Span> {
        val result = mutableListOf<Span>()
        for (part in parts) {
            val boundary = result.indices.lastOrNull { index ->
                boundaryCase(result[index], part) &&
                    Alignment.units(original.substring(result[index].end, part.start)).none { it.type == Type.Word }
            }
            if (boundary == null) {
                result += part
                continue
            }
            val dependent = result.subList(boundary, result.size).toList() + part
            val start = dependent.first().start
            val end = part.end
            val from = original.substring(start, end)
            val local = dependent.map { it.copy(start = it.start - start, end = it.end - start) }
            val replacement = render(from, toEdits(local, emptyList(), EditKind.Fix, 0)).text
            result.subList(boundary, result.size).clear()
            val commonReport = dependent.first().reportId?.takeIf { report -> dependent.all { it.reportId == report } }
            result += Span(start, end, from, replacement,
                assessment = dependent.first().assessment.takeIf { commonReport != null }, reportId = commonReport,
                assumptionIndices = dependent.first().assumptionIndices.takeIf { commonReport != null }.orEmpty())
        }
        return result
    }

    /** Adding/removing a sentence stop requires the following word's corresponding case. */
    private fun boundaryCase(mark: Span, word: Span): Boolean {
        val before = mark.from.any { it in ".!?…" }
        val after = mark.replacement.any { it in ".!?…" }
        if (before == after || wordCount(mark.from) != 0 || wordCount(mark.replacement) != 0 ||
            wordCount(word.from) != 1 || wordCount(word.replacement) != 1 || wording(word.from, word.replacement)) return false
        val a = word.from.codePointAt(0)
        val b = word.replacement.codePointAt(0)
        return if (before) Character.isUpperCase(a) && Character.isLowerCase(b)
        else Character.isLowerCase(a) && Character.isUpperCase(b)
    }

    private fun wordCount(text: String) = Alignment.units(text).count { it.type == Type.Word }

    fun naturalResult(
        original: String,
        fixes: List<Edit>,
        natural: String,
        reported: List<VerdictChange>,
        firstId: Int = 0,
    ): Result {
        val base = render(original, fixes).text
        if (!wording(base, natural)) return Result(emptyList(), !Alignment.same(base, natural))
        if (Links.hasNewLink(base, natural)) return Result(emptyList(), true)
        val sentences = Sentences.ranges(base)
        val originalSentences = Sentences.ranges(original)
        val targetSentences = Sentences.ranges(natural)
        if (targetSentences.size != sentences.size) return Result(emptyList(), true)
        val candidates = located(base, natural, reported) ?: return Result(emptyList(), true)
        val placed = place(fixes)
        fun touchesDeletedWord(start: Int, end: Int) = placed.any { p ->
            wordCount(p.fix.replacement) == 0 && wordCount(p.fix.from) > 0 &&
                Alignment.units(base.substring(minOf(p.baseStart, end), maxOf(p.baseStart, start))).all { it.type == Type.Space }
        }
        var rejected = false
        val accepted = mutableListOf<Span>()
        for (candidate in candidates) {
            if (!wording(candidate.from, candidate.replacement)) {
                rejected = true
                continue
            }
            if (Sentences.crosses(sentences, candidate.start, candidate.end) ||
                Sentences.crosses(targetSentences, candidate.targetStart, candidate.targetEnd) ||
                digits(candidate.from) != digits(candidate.replacement)) {
                rejected = true
                continue
            }
            val change = trimUnchangedContext(candidate)
            // Preserve the reported decision as a whole. Character-diff fragments do
            // not establish which parts of a construction can be accepted separately.
            if (hasSeparateOrthographicChange(change.from, change.replacement) ||
                sentenceStops(change.from) != sentenceStops(change.replacement)) {
                rejected = true
                continue
            }
            if (change.replacement.isNotEmpty() &&
                (!wholeWordEdge(base, change.start, change.replacement.codePointAt(0)) ||
                    !wholeWordEdge(base, change.end, change.replacement.codePointBefore(change.replacement.length), after = true))) {
                rejected = true
                continue
            }
            // Unchanged report context does not establish ownership of a removed
            // word, including when a space separates its deletion from this change.
            if (touchesDeletedWord(change.start, change.end) || change.start == change.end && placed.any {
                    change.start in it.baseStart..it.baseEnd &&
                        (wordCount(it.fix.from) > 0 || wordCount(it.fix.replacement) > 0) && wording(it.fix.from, it.fix.replacement)
                }) {
                rejected = true
                continue
            }
            val (start, end) = widened(change.start, change.end, placed)
            if (touchesDeletedWord(start, end) || Sentences.crosses(sentences, start, end)) {
                rejected = true
                continue
            }
            val mapped = toOriginal(change, base, original, placed)
            if (Sentences.crosses(originalSentences, mapped.start, mapped.end)) {
                rejected = true
                continue
            }
            if (!Alignment.same(mapped.from, mapped.replacement)) accepted += mapped
        }
        val edits = toEdits(accepted, emptyList(), EditKind.Natural, firstId)
        // Widening to a corrected word can make otherwise distinct candidates conflict.
        // Withhold both; merging them would change what either Accept action means.
        val conflicts = edits.filter { edit -> edits.any { it.id != edit.id &&
            (edit.overlaps(it) || (edit.includes intersect it.includes).isNotEmpty()) } }.map { it.id }.toSet()
        return Result(edits.filterNot { it.id in conflicts }, rejected || conflicts.isNotEmpty())
    }

    private data class Span(
        val start: Int,
        val end: Int,
        val from: String,
        val replacement: String,
        val why: String? = null,
        val includes: Set<Int> = emptySet(),
        val order: Int = 0,
        val targetStart: Int = -1,
        val targetEnd: Int = -1,
        val assessment: SuggestionAssessment? = null,
        val reportId: Int? = null,
        val assumptionIndices: List<Int> = emptyList(),
    )

    private fun toEdits(spans: List<Span>, reported: List<VerdictChange>, kind: EditKind, firstId: Int): List<Edit> {
        val unused = reported.toMutableList()
        return spans.mapIndexed { index, span ->
            // A located report owns its reason, including an intentionally blank one.
            val why = if (span.reportId != null) span.why else {
                val match = unused.indexOfFirst {
                    Alignment.same(it.from, span.from) && Alignment.same(it.to, span.replacement)
                }
                span.why ?: if (match >= 0) unused.removeAt(match).why.trim().ifEmpty { null } else null
            }
            Edit(firstId + index, kind, span.start, span.end, span.from, span.replacement, why, span.includes, span.order,
                span.assessment, span.assumptionIndices)
        }
    }

    /** Remove only complete, unchanged lexical context; never trim letters out of a changed word. */
    private fun trimUnchangedContext(span: Span): Span {
        val from = Alignment.units(span.from)
        val to = Alignment.units(span.replacement)
        var prefix = 0
        while (prefix < minOf(from.size, to.size) && from[prefix].normal == to[prefix].normal) prefix++
        var suffix = 0
        while (suffix < minOf(from.size, to.size) - prefix &&
            from[from.lastIndex - suffix].normal == to[to.lastIndex - suffix].normal) suffix++
        val fromStart = from.getOrNull(prefix - 1)?.end ?: 0
        val toStart = to.getOrNull(prefix - 1)?.end ?: 0
        val fromEnd = if (suffix == 0) span.from.length else from[from.size - suffix].start
        val toEnd = if (suffix == 0) span.replacement.length else to[to.size - suffix].start
        return span.copy(start = span.start + fromStart, end = span.start + fromEnd,
            from = span.from.substring(fromStart, fromEnd), replacement = span.replacement.substring(toStart, toEnd),
            targetStart = if (span.targetStart < 0) -1 else span.targetStart + toStart,
            targetEnd = if (span.targetStart < 0) -1 else span.targetStart + toEnd)
    }

    /** Changes count as wording only when something beyond case, accents or formatting differs. */
    private fun wording(from: String, to: String) = letters(from) != letters(to)

    private fun letters(text: String, ignoreCase: Boolean = true) = buildString {
        Normalizer.normalize(if (ignoreCase) text.lowercase() else text, Normalizer.Form.NFD).codePoints().forEach { codePoint ->
            val type = Character.getType(codePoint)
            if (Character.isLetterOrDigit(codePoint) || type in Character.MATH_SYMBOL.toInt()..Character.OTHER_SYMBOL.toInt())
                appendCodePoint(codePoint)
        }
    }

    private fun digits(text: String) = buildString {
        text.codePoints().filter(Character::isDigit).forEach { appendCodePoint(it) }
    }

    /**
     * Unchanged words can separate an unrelated formatting repair from a rewording.
     * They cannot establish whether lexical changes form one grammatical decision.
     */
    private fun hasSeparateOrthographicChange(from: String, to: String): Boolean {
        val anchors = Alignment.wordAnchors(Alignment.units(from), Alignment.units(to)).ordered
        var source = 0
        var target = 0
        fun orthographic(before: String, after: String) =
            !Alignment.same(before, after) && !wording(before, after)
        for ((left, right) in anchors) {
            if (orthographic(from.substring(source, left.start), to.substring(target, right.start))) return true
            source = left.end
            target = right.end
        }
        return orthographic(from.substring(source), to.substring(target))
    }

    /** Rewording may need commas or other internal punctuation, but cannot move sentence stops. */
    private fun sentenceStops(text: String) = text.filter { it in ".!?…" }

    /**
     * Place each report against both complete texts, in source order. The unchanged
     * prefix and declared replacement must match the target, including for insertions
     * and repeated source words. An ambiguous placement is withheld, never guessed.
     */
    private fun located(base: String, target: String, reported: List<VerdictChange>): List<Span>? {
        if (reported.isEmpty()) return null
        fun indexed(text: String): Pair<String, Map<Int, Int>> {
            val positions = mutableMapOf(0 to 0)
            val normalized = buildString {
                for (range in Alignment.characters(text)) {
                    append(Normalizer.normalize(text.substring(range), Normalizer.Form.NFC))
                    positions[length] = range.last + 1
                }
            }
            return normalized to positions
        }
        val (normalized, positions) = indexed(base)
        val (wanted, targetPositions) = indexed(target)
        val sourceUnits = Alignment.units(base)
        val targetUnits = Alignment.units(target)
        val spans = mutableListOf<Span>()
        var source = 0
        var destination = 0
        for ((reportId, change) in reported.withIndex()) {
            if (Alignment.same(change.from, change.to)) continue
            val from = Normalizer.normalize(change.from, Normalizer.Form.NFC)
            val to = Normalizer.normalize(change.to, Normalizer.Form.NFC)
            var shared = 0
            while (source + shared < normalized.length && destination + shared < wanted.length &&
                normalized[source + shared] == wanted[destination + shared]) shared++
            val matches = positions.keys.filter { at ->
                if (at !in source..source + shared || !normalized.startsWith(from, at)) return@filter false
                val targetAt = destination + at - source
                if (!wanted.startsWith(to, targetAt)) return@filter false
                val end = positions[at + from.length] ?: return@filter false
                val targetStart = targetPositions[targetAt] ?: return@filter false
                val targetEnd = targetPositions[targetAt + to.length] ?: return@filter false
                ownsWords(sourceUnits, positions.getValue(at), end) && ownsWords(targetUnits, targetStart, targetEnd)
            }
            if (matches.size != 1) return null
            val at = matches.single()
            val start = positions.getValue(at)
            val end = positions.getValue(at + from.length)
            val targetAt = destination + at - source
            val targetStart = targetPositions.getValue(targetAt)
            val targetEnd = targetPositions.getValue(targetAt + to.length)
            spans += Span(start, end, base.substring(start, end), target.substring(targetStart, targetEnd),
                change.why.trim().ifEmpty { null }, targetStart = targetStart, targetEnd = targetEnd,
                assessment = change.assessment, reportId = reportId, assumptionIndices = change.assumptionIndices)
            destination = targetAt + to.length
            source = at + from.length
        }
        return spans.takeIf { normalized.substring(source) == wanted.substring(destination) }
    }

    /** Both boundaries own complete lexical units, including an insertion's location. */
    private fun ownsWords(units: List<Alignment.Token>, start: Int, end: Int): Boolean =
        units.none { it.type == Type.Word &&
            (it.start < start && start < it.end || it.start < end && end < it.end) }

    private fun wholeWordEdge(text: String, at: Int, edge: Int, after: Boolean = false): Boolean {
        fun word(codePoint: Int) = Character.isLetterOrDigit(codePoint) ||
            Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt()
        if (!word(edge)) return true
        val adjacent = if (after) {
            if (at == text.length) return true
            text.codePointAt(at)
        } else {
            if (at == 0) return true
            text.codePointBefore(at)
        }
        return !word(adjacent)
    }

    /**
     * Character alignment reveals word splits/joins. Project changes onto complete
     * lexical units in BOTH texts: all fragments of a joined or broken word are one
     * repair. Kept spaces separate independent word repairs. Nothing grows merely to
     * provide a visible highlight; whitespace and deletions remain honest edits.
     */
    private fun repairs(base: String, target: String): List<Span> {
        val ops = Alignment.align(base, target)
        if (ops.isEmpty()) return emptyList()
        val parent = IntArray(ops.size) { it }
        fun root(index: Int): Int {
            var at = index
            while (parent[at] != at) {
                parent[at] = parent[parent[at]]
                at = parent[at]
            }
            return at
        }
        fun join(start: Int, end: Int) {
            val owner = root(start)
            for (at in start + 1..end) parent[root(at)] = owner
        }
        fun project(text: String, original: Boolean) {
            val at = IntArray(text.length)
            for ((index, op) in ops.withIndex()) {
                val token = if (original) op.a else op.b
                if (token != null) for (position in token.start until token.end) at[position] = index
            }
            val units = Alignment.units(text)
            for ((index, unit) in units.withIndex()) {
                join(at[unit.start], at[unit.end - 1])
                // Adjacent sentence stops are one repair; quotes and symbols stay separate.
                // Splitting "!." -> "!" could otherwise offer "." -> "!" alone.
                val previous = units.getOrNull(index - 1)
                if (unit.type == Type.Punct && previous?.type == Type.Punct &&
                    sentenceStops(unit.text).isNotEmpty() && sentenceStops(previous.text).isNotEmpty())
                    join(at[previous.start], at[unit.end - 1])
            }
        }
        project(base, true)
        project(target, false)
        // An inserted/deleted word owns the inserted/deleted separating space. This
        // connects only actual additions/removals, never an unchanged neighbouring word.
        for (index in 1 until ops.size) {
            val left = ops[index - 1]
            val right = ops[index]
            if (!left.isPunct && !right.isPunct &&
                (left.a == null && right.a == null || left.b == null && right.b == null)) join(index - 1, index)
        }
        val before = IntArray(ops.size)
        val targetBefore = IntArray(ops.size)
        var position = 0
        var targetPosition = 0
        for ((index, op) in ops.withIndex()) {
            before[index] = position
            targetBefore[index] = targetPosition
            op.a?.let { position = it.end }
            op.b?.let { targetPosition = it.end }
        }
        return ops.indices.groupBy(::root).values.mapNotNull { group ->
            if (group.all { ops[it].isMatch }) return@mapNotNull null
            val removed = group.mapNotNull { ops[it].a }
            val start = removed.firstOrNull()?.start ?: before[group.first()]
            val end = removed.lastOrNull()?.end ?: start
            val added = group.mapNotNull { ops[it].b }
            val targetStart = added.firstOrNull()?.start ?: targetBefore[group.first()]
            val targetEnd = added.lastOrNull()?.end ?: targetStart
            val replacement = group.mapNotNull { index ->
                val op = ops[index]
                if (op.isMatch) op.a?.text else op.b?.text
            }.joinToString("")
            Span(start, end, base.substring(start, end), replacement,
                targetStart = targetStart, targetEnd = targetEnd)
        }
    }

    // Corrected positions map back once, after scope validation.
    private data class Placed(val fix: Edit, val baseStart: Int, val baseEnd: Int)

    private fun place(fixes: List<Edit>): List<Placed> {
        var delta = 0
        return fixes.sortedWith(editOrder).map { fix ->
            val start = fix.start + delta
            delta += fix.replacement.length - (fix.end - fix.start)
            Placed(fix, start, start + fix.replacement.length)
        }
    }

    private fun widened(start: Int, end: Int, placed: List<Placed>): Pair<Int, Int> {
        var from = start
        var to = end
        for (p in placed) {
            if (p.baseStart < from && from < p.baseEnd) from = p.baseStart
            if (p.baseStart < to && to < p.baseEnd) to = p.baseEnd
        }
        return from to to
    }

    private fun toOriginal(span: Span, base: String, original: String, placed: List<Placed>): Span {
        val (start, end) = widened(span.start, span.end, placed)
        val from = originalPosition(start, placed)
        val to = originalPosition(end, placed)
        val includes = placed.filter { it.baseStart < it.baseEnd && start <= it.baseStart && it.baseEnd <= end }
            .map { it.fix.id }.toSet()
        return Span(from, to, original.substring(from, to),
            base.substring(start, span.start) + span.replacement + base.substring(span.end, end),
            span.why, includes, 2 * start + if (start < end) 1 else 0,
            assessment = span.assessment, reportId = span.reportId, assumptionIndices = span.assumptionIndices)
    }

    private fun originalPosition(position: Int, placed: List<Placed>): Int {
        var delta = 0
        for (p in placed) {
            if (position <= p.baseStart) return position - (p.baseStart - p.fix.start)
            delta = p.baseEnd - p.fix.end
        }
        return position - delta
    }
}
