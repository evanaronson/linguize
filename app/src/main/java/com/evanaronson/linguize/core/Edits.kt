package com.evanaronson.linguize.core

import com.evanaronson.linguize.core.Alignment.Type
import java.text.Normalizer

/** Constructs complete repairs, then validates and maps explicitly reported rewordings. */
internal object Edits {
    data class FixResult(val edits: List<Edit>, val rejected: Boolean)

    fun fixes(original: String, corrected: String, reported: List<VerdictChange>): FixResult {
        val runs = correctionRuns(original, corrected)
        val units = repairs(original, corrected)
        val sentences = Sentences.ranges(original)
        val declared = located(original, corrected, reported).orEmpty().map(::trimUnchangedContext)
        // A join that also substitutes letters can belong to a grammatical construction.
        // Beside another changed word, neither fragment establishes its own dependency.
        val joins = units.indices.filter { dependentJoin(units[it]) }.toSet()
        val grammarPairs = (0 until units.lastIndex).mapNotNull { index ->
            val left = units[index]
            val right = units[index + 1]
            if ((index in joins || index + 1 in joins) && wording(left.from, left.replacement) &&
                wording(right.from, right.replacement) && original.substring(left.end, right.start).all(Char::isWhitespace) &&
                !Sentences.crosses(sentences, left.start, right.end)) index to index + 1 else null
        }
        val boundaryPairs = units.indices.flatMap { rightIndex ->
            val right = units[rightIndex]
            if (wordCount(right.from) != 1 || wordCount(right.replacement) != 1) emptyList()
            else (rightIndex - 1 downTo 0).takeWhile { leftIndex ->
                Alignment.units(original.substring(units[leftIndex].end, right.start)).none { it.type == Type.Word }
            }.mapNotNull { leftIndex ->
                if (boundaryCase(units[leftIndex], right)) leftIndex to rightIndex else null
            }
        }
        val constructions = declared.mapNotNull { span ->
            val covered = units.indices.filter { units[it].start >= span.start && units[it].end <= span.end }
            if (covered.size != 2) return@mapNotNull null
            val pair = covered[0] to covered[1]
            // A report can declare one adjacent grammatical dependency or a boundary
            // and its initial case. It cannot merge independently derived spelling fixes.
            val grammar = pair in grammarPairs && covered.count { it in joins } == 1 &&
                covered.filterNot { it in joins }.all { wordCount(units[it].from) == 1 && wordCount(units[it].replacement) == 1 } &&
                punctuation(span.from) == punctuation(span.replacement)
            val boundary = pair in boundaryPairs &&
                Alignment.same(units[pair.second].from.lowercase(), units[pair.second].replacement.lowercase())
            if (!grammar && !boundary) return@mapNotNull null
            val local = covered.map { units[it].copy(start = units[it].start - span.start, end = units[it].end - span.start) }
            if (!Alignment.same(render(span.from, toEdits(local, emptyList(), EditKind.Fix, 0)).text, span.replacement))
                return@mapNotNull null
            span to covered
        }
        val unconfirmedBoundaries = boundaryPairs.filter { pair -> constructions.none { pair.first in it.second && pair.second in it.second } }
        val withheld = unconfirmedBoundaries.flatMap { listOf(it.first, it.second) }.toSet()
        val covered = constructions.flatMap { it.second }.toSet()
        val constructed = constructions.filter { (_, parts) -> parts.none { it in withheld } }.map { it.first }.toSet()
        val spans = (units.filterIndexed { index, _ -> index !in covered && index !in withheld } +
            constructed).sortedBy { it.start }
        val sourceWords = Alignment.units(original).filter { it.type == Type.Word }.groupBy { it.normal }
        val targetWords = Alignment.units(corrected).filter { it.type == Type.Word }.groupBy { it.normal }
        fun singleWords(span: Span) = Alignment.units(span.from).singleOrNull()?.type == Type.Word &&
            Alignment.units(span.replacement).singleOrNull()?.type == Type.Word
        fun corroborated(span: Span): Boolean {
            if (!singleWords(span)) return false
            if (declared.any { it.start == span.start && it.end == span.end && Alignment.same(it.replacement, span.replacement) }) return true
            val source = sourceWords[Normalizer.normalize(span.from, Normalizer.Form.NFC)]?.singleOrNull() ?: return false
            val target = targetWords[Normalizer.normalize(span.replacement, Normalizer.Form.NFC)]?.singleOrNull() ?: return false
            return source.start == span.start && source.end == span.end && target.start == span.targetStart && target.end == span.targetEnd &&
                reported.any { Alignment.same(it.from, span.from) && Alignment.same(it.to, span.replacement) }
        }
        val unsupported = runs.filter { run ->
            val children = units.filter(run::touches)
            val mixedBoundary = children.size > 1 && children.any {
                (wordCount(it.from) == 0) != (wordCount(it.replacement) == 0)
            } && children.any { punctuation(it.from) != punctuation(it.replacement) }
            // Replacing a word with punctuation (or the reverse) can be one boundary
            // decision. Independently accepting its projected fragments is not safe.
            if (run.reordered || mixedBoundary) true
            else if (maxOf(wordCount(run.from), wordCount(run.replacement)) <= 1 || !wording(run.from, run.replacement)) false
            else {
                // Count all lexical children before withholding anything. One
                // remaining fragment beside a rejected dependency is not evidence.
                val lexical = units.indices.filter { run.touches(units[it]) && wording(units[it].from, units[it].replacement) }
                if (lexical.size == 1 && singleWords(units[lexical.single()])) false
                else lexical.any { it !in covered && !corroborated(units[it]) }
            }
        }
        val offered = spans.filter { span ->
            unsupported.none { run -> run.touches(span) &&
                // Keep complete case-preserving orthographic repairs. Case can
                // depend on a withheld sentence boundary ("y tengo" -> ". Tengo").
                // Punctuation and spacing fragments cannot salvage an opaque rewrite.
                (run.reordered || wordCount(span.from) == 0 || wordCount(span.replacement) == 0 ||
                    letters(span.from, ignoreCase = false) != letters(span.replacement, ignoreCase = false)) }
        }
        val fixes = toEdits(offered, reported, EditKind.Fix, 0)
        val placed = place(fixes).associateBy { it.fix.id }
        return FixResult(fixes.map { it.copy(order = 2 * placed.getValue(it.id).baseStart + 1) },
            withheld.isNotEmpty() || offered.size != spans.size)
    }

    private data class CorrectionRun(val start: Int, val end: Int, val from: String, val replacement: String, val reordered: Boolean) {
        fun touches(span: Span) = if (span.start == span.end) span.start in start..end else span.start < end && start < span.end
    }

    /**
     * An opaque changed run can hide a broken word even when its word count stays
     * equal: "el s" -> "els tinc" must not become two plausible-looking repairs.
     * Keep this evidence before projection divides the run into lexical children.
     * Reports may corroborate existing children; they never determine alignment.
     */
    private fun correctionRuns(original: String, corrected: String): List<CorrectionRun> {
        val anchors = Alignment.wordAnchors(Alignment.units(original), Alignment.units(corrected))
        val runs = mutableListOf<CorrectionRun>()
        var source = 0
        var target = 0
        for ((left, right) in anchors.ordered) {
            runs += CorrectionRun(source, left.start, original.substring(source, left.start), corrected.substring(target, right.start),
                anchors.crossed.any { it.first.start in source until left.start })
            source = left.end
            target = right.end
        }
        runs += CorrectionRun(source, original.length, original.substring(source), corrected.substring(target),
            anchors.crossed.any { it.first.start >= source })
        return runs
    }

    /** Removing/adding a sentence stop and changing its next word's case is one boundary operation. */
    private fun boundaryCase(mark: Span, word: Span): Boolean {
        fun marks(text: String) = text.filter { it in ".!?…" }
        val before = marks(mark.from)
        val after = marks(mark.replacement)
        if (before.isEmpty() == after.isEmpty() || maxOf(before.length, after.length) != 1 ||
            wordCount(mark.from) != 0 || wordCount(mark.replacement) != 0) return false
        val a = Normalizer.normalize(word.from, Normalizer.Form.NFC)
        val b = Normalizer.normalize(word.replacement, Normalizer.Form.NFC)
        if (Alignment.units(a).singleOrNull()?.type != Type.Word || Alignment.units(b).singleOrNull()?.type != Type.Word ||
            a == b) return false
        val source = a.codePointAt(0)
        val target = b.codePointAt(0)
        return if (before.isNotEmpty()) Character.isUpperCase(source) && Character.isLowerCase(target)
        else Character.isLowerCase(source) && Character.isUpperCase(target)
    }

    private fun wordCount(text: String) = Alignment.units(text).count { it.type == Type.Word }

    private fun dependentJoin(span: Span): Boolean {
        val source = wordCount(span.from)
        val target = wordCount(span.replacement)
        if (source <= 1 || target !in 1 until source || span.from.none(Char::isWhitespace)) return false
        val before = letters(span.from)
        val after = letters(span.replacement)
        fun subsequence(part: String, whole: String): Boolean {
            val expected = part.codePoints().toArray()
            var at = 0
            whole.codePoints().forEach { if (at < expected.size && expected[at] == it) at++ }
            return at == expected.size
        }
        // Adding/removing letters while joining a broken word, including elision,
        // still describes a complete lexical repair. Substitution is less conclusive.
        return !subsequence(before, after) && !subsequence(after, before)
    }

    data class NaturalResult(val edits: List<Edit>, val rejected: Boolean)

    fun naturalResult(
        original: String,
        fixes: List<Edit>,
        natural: String,
        reported: List<VerdictChange>,
        firstId: Int = 0,
    ): NaturalResult {
        val base = render(original, fixes).text
        if (!wording(base, natural)) return NaturalResult(emptyList(), false)
        if (Links.hasNewLink(base, natural)) return NaturalResult(emptyList(), true)
        val sentences = Sentences.ranges(base)
        if (Sentences.ranges(natural).size != sentences.size) return NaturalResult(emptyList(), true)
        val candidates = located(base, natural, reported) ?: return NaturalResult(emptyList(), true)
        val placed = place(fixes)
        fun touchesDeletedWord(start: Int, end: Int) = placed.any { p ->
            wordCount(p.fix.replacement) == 0 && wordCount(p.fix.from) > 0 &&
                Alignment.units(base.substring(minOf(p.baseStart, end), maxOf(p.baseStart, start))).all { it.type == Type.Space }
        }
        var rejected = false
        val accepted = mutableListOf<Span>()
        for (candidate in candidates) {
            if (!wording(candidate.from, candidate.replacement)) continue
            if (Sentences.crosses(sentences, candidate.start, candidate.end) ||
                digits(candidate.from) != digits(candidate.replacement)) {
                rejected = true
                continue
            }
            val change = trimUnchangedContext(candidate)
            // Preserve the reported decision as a whole. Character-diff fragments do
            // not establish which parts of a construction can be accepted separately.
            if (!supportedConstruction(change.from, change.replacement) ||
                punctuation(change.from) != punctuation(change.replacement)) {
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
            if (Sentences.crosses(Sentences.ranges(original), mapped.start, mapped.end)) {
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
        return NaturalResult(edits.filterNot { it.id in conflicts }, rejected || conflicts.isNotEmpty())
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
    )

    private fun toEdits(spans: List<Span>, reported: List<VerdictChange>, kind: EditKind, firstId: Int): List<Edit> {
        val unused = reported.toMutableList()
        return spans.mapIndexed { index, span ->
            val match = unused.indexOfFirst {
                Alignment.same(it.from, span.from) && Alignment.same(it.to, span.replacement)
            }
            val why = span.why ?: if (match >= 0) unused.removeAt(match).why.trim().ifEmpty { null } else null
            Edit(firstId + index, kind, span.start, span.end, span.from, span.replacement, why, span.includes, span.order)
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
            from = span.from.substring(fromStart, fromEnd), replacement = span.replacement.substring(toStart, toEnd))
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
     * A conservative structural scope, not a semantic proof. Keep unchanged words
     * as anchors and admit one changed region. Opaque many-to-many rewrites and
     * changes on both sides of an anchor need evidence the report cannot provide.
     */
    private fun supportedConstruction(from: String, to: String): Boolean {
        val a = Alignment.units(from).filter { it.type == Type.Word }.map { it.folded }
        val b = Alignment.units(to).filter { it.type == Type.Word }.map { it.folded }
        val uniqueA = a.withIndex().groupBy { it.value }.filterValues { it.size == 1 }
        val uniqueB = b.withIndex().groupBy { it.value }.filterValues { it.size == 1 }
        val anchors = a.withIndex().mapNotNull { (index, word) ->
            if (word in uniqueA) uniqueB[word]?.single()?.index?.let { index to it } else null
        }
        if (anchors.zipWithNext().any { (left, right) -> left.second >= right.second }) return false
        var source = -1
        var target = -1
        var regions = 0
        for ((nextSource, nextTarget) in anchors + (a.size to b.size)) {
            val removed = nextSource - source - 1
            val added = nextTarget - target - 1
            if (removed > 0 || added > 0) {
                if (++regions > 1 || minOf(removed, added) > 1) return false
            }
            source = nextSource
            target = nextTarget
        }
        return true
    }

    private fun punctuation(text: String) = Alignment.units(text).filter { token ->
        token.type == Type.Punct && Character.getType(token.text.codePointAt(0)) in setOf(
            Character.DASH_PUNCTUATION.toInt(), Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(), Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(), Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        )
    }.map { it.text }

    /**
     * Place each report against both complete texts, in source order. The unchanged
     * prefix and declared replacement must match the target, including for insertions
     * and repeated source words. An ambiguous placement is withheld, never guessed.
     */
    private fun located(base: String, target: String, reported: List<VerdictChange>): List<Span>? {
        if (reported.isEmpty()) return null
        val positions = mutableMapOf(0 to 0)
        val normalized = buildString {
            for (range in Alignment.characters(base)) {
                append(Normalizer.normalize(base.substring(range), Normalizer.Form.NFC))
                positions[length] = range.last + 1
            }
        }
        val wanted = Normalizer.normalize(target, Normalizer.Form.NFC)
        val spans = mutableListOf<Span>()
        var source = 0
        var destination = 0
        for (change in reported) {
            if (Alignment.same(change.from, change.to)) continue
            val from = Normalizer.normalize(change.from, Normalizer.Form.NFC)
            val to = Normalizer.normalize(change.to, Normalizer.Form.NFC)
            var shared = 0
            while (source + shared < normalized.length && destination + shared < wanted.length &&
                normalized[source + shared] == wanted[destination + shared]) shared++
            val matches = positions.keys.filter { at ->
                at in source..source + shared && normalized.startsWith(from, at) &&
                    wanted.startsWith(to, destination + at - source) && positions.containsKey(at + from.length) &&
                    (from.isEmpty() || wholeWordEdge(base, positions.getValue(at), from.codePointAt(0)) &&
                        wholeWordEdge(base, positions.getValue(at + from.length), from.codePointBefore(from.length), after = true))
            }
            if (matches.size != 1) return null
            val at = matches.single()
            val start = positions.getValue(at)
            val end = positions.getValue(at + from.length)
            spans += Span(start, end, base.substring(start, end), change.to, change.why.trim().ifEmpty { null })
            destination += at - source + to.length
            source = at + from.length
        }
        return spans.takeIf { normalized.substring(source) == wanted.substring(destination) }
    }

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
            for (unit in Alignment.units(text)) {
                if (unit.type != Type.Punct) join(at[unit.start], at[unit.end - 1])
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
        var position = 0
        for ((index, op) in ops.withIndex()) {
            before[index] = position
            op.a?.let { position = it.end }
        }
        return ops.indices.groupBy(::root).values.mapNotNull { group ->
            if (group.all { ops[it].isMatch }) return@mapNotNull null
            val removed = group.mapNotNull { ops[it].a }
            val added = group.mapNotNull { ops[it].b }
            val start = removed.firstOrNull()?.start ?: before[group.first()]
            val end = removed.lastOrNull()?.end ?: start
            val replacement = group.mapNotNull { index ->
                val op = ops[index]
                if (op.isMatch) op.a?.text else op.b?.text
            }.joinToString("")
            Span(start, end, base.substring(start, end), replacement,
                targetStart = added.firstOrNull()?.start ?: -1, targetEnd = added.lastOrNull()?.end ?: -1)
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
            span.why, includes, 2 * start + if (start < end) 1 else 0)
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
