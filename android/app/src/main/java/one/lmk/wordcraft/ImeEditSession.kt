package one.lmk.wordcraft

/**
 * Pure Kotlin shadow model + transaction translator bridging Android [android.view.inputmethod.InputConnection]
 * semantics (composing text, composing regions, batch edits, API 34+ `replaceText`, surrounding text
 * queries, and `setSelection`) onto WordCraft's atomic native IME queue.
 */
data class ImeSnapshot(
    val text: String,
    val selStart: Int,
    val selEnd: Int,
    val compStart: Int,
    val compEnd: Int,
)

sealed class ImeOp {
    data class Preedit(val text: String, val cursorStartCp: Int, val cursorEndCp: Int) : ImeOp()
    data class Commit(val text: String) : ImeOp()
    data class DeleteSurrounding(val beforeCp: Int, val afterCp: Int) : ImeOp()
    data class SetSelectionRelative(val deltaAnchorCp: Int, val deltaFocusCp: Int) : ImeOp()
    data class TapKeyCode(val keyCode: Int) : ImeOp()
}

data class ImeBatchFlush(
    val seq: Long,
    val ops: List<ImeOp>,
)

class ImeEditSession(
    private val keyCodeBackspace: Int = 67, // KeyEvent.KEYCODE_DEL
) {
    private var docText: String = ""
    private var docSelStart: Int = 0
    private var docSelEnd: Int = 0

    private var preeditActive: Boolean = false
    private var preeditText: String = ""
    private var preeditCursorU16: Int = 0

    private var regionStart: Int = -1
    private var regionEnd: Int = -1

    private var batchDepth: Int = 0
    private val pendingOps: MutableList<ImeOp> = mutableListOf()
    private var latestSentSeq: Long = 0L

    fun currentSeq(): Long = latestSentSeq

    fun isInBatch(): Boolean = batchDepth > 0

    fun isBusyWithImeEdit(): Boolean = preeditActive || batchDepth > 0 || hasValidRegion()

    fun resetConnectionState() {
        preeditActive = false
        preeditText = ""
        preeditCursorU16 = 0
        regionStart = -1
        regionEnd = -1
        batchDepth = 0
        pendingOps.clear()
    }

    fun virtualSnapshot(): ImeSnapshot {
        if (preeditActive) {
            val len = docText.length
            val s = docSelStart.coerceIn(0, len)
            val e = docSelEnd.coerceIn(s, len)
            val vText = docText.substring(0, s) + preeditText + docText.substring(e)
            val cur = (s + preeditCursorU16).coerceIn(0, vText.length)
            return ImeSnapshot(
                text = vText,
                selStart = cur,
                selEnd = cur,
                compStart = s,
                compEnd = s + preeditText.length,
            )
        }
        val len = docText.length
        val s = docSelStart.coerceIn(0, len)
        val e = docSelEnd.coerceIn(s, len)
        val (cs, ce) = if (hasValidRegion()) regionStart to regionEnd else -1 to -1
        return ImeSnapshot(text = docText, selStart = s, selEnd = e, compStart = cs, compEnd = ce)
    }

    /**
     * Synchronizes committed document text & selection arriving from Rust.
     *
     * Returns `true` when [seq] is up to date with the latest sent transaction and the caller
     * should propagate the resulting [virtualSnapshot] to `InputMethodManager.updateSelection`.
     */
    fun syncFromRust(rustText: String, rustSelStart: Int, rustSelEnd: Int, seq: Long): Boolean {
        if (seq < latestSentSeq || batchDepth > 0) {
            return false
        }
        val clampedStart = rustSelStart.coerceIn(0, rustText.length)
        val clampedEnd = rustSelEnd.coerceIn(clampedStart, rustText.length)
        if (rustText != docText || clampedStart != docSelStart || clampedEnd != docSelEnd) {
            docText = rustText
            docSelStart = clampedStart
            docSelEnd = clampedEnd
            preeditActive = false
            preeditText = ""
            preeditCursorU16 = 0
            regionStart = -1
            regionEnd = -1
        }
        return true
    }

    fun beginBatchEdit(): ImeBatchFlush? {
        batchDepth += 1
        return null
    }

    fun endBatchEdit(): Pair<Boolean, ImeBatchFlush?> {
        if (batchDepth > 0) {
            batchDepth -= 1
        }
        val stillInBatch = batchDepth > 0
        val flush = if (!stillInBatch) drainPendingFlush() else null
        return stillInBatch to flush
    }

    fun setComposingText(text: CharSequence?, newCursorPosition: Int): ImeBatchFlush? {
        val raw = text?.toString().orEmpty()
        if (!preeditActive && hasValidRegion()) {
            val rStart = regionStart
            val rEnd = regionEnd
            regionStart = -1
            regionEnd = -1
            emitDeleteDocRange(rStart, rEnd)
        }
        if (!preeditActive && docSelStart < docSelEnd) {
            // Erase non-collapsed document selection first so preedit always sits at a collapsed caret.
            pendingOps.add(ImeOp.TapKeyCode(keyCodeBackspace))
            docText = docText.substring(0, docSelStart) + docText.substring(docSelEnd)
            docSelEnd = docSelStart
        }
        val clampedCursorU16 = if (newCursorPosition > 0) raw.length else 0
        val cursorCp = safeCodePointCount(raw, 0, clampedCursorU16)
        if (raw.isEmpty()) {
            if (preeditActive) {
                preeditActive = false
                preeditText = ""
                preeditCursorU16 = 0
            }
            pendingOps.add(ImeOp.Preedit("", 0, 0))
        } else {
            preeditActive = true
            preeditText = raw
            preeditCursorU16 = clampedCursorU16
            pendingOps.add(ImeOp.Preedit(raw, cursorCp, cursorCp))
        }
        return maybeFlush()
    }

    fun setComposingRegion(start: Int, end: Int): ImeBatchFlush? {
        val snap = virtualSnapshot()
        var a = minOf(start, end).coerceIn(0, snap.text.length)
        var b = maxOf(start, end).coerceIn(a, snap.text.length)
        a = clampU16BoundaryDown(snap.text, a)
        b = clampU16BoundaryDown(snap.text, b).coerceAtLeast(a)

        if (preeditActive) {
            val pStart = docSelStart
            val pEnd = docSelStart + preeditText.length
            if (a == pStart && b == pEnd && a < b) {
                // Already composing this exact span as preedit.
                return null
            }
            // Fold existing preedit into committed text before re-marking a region in doc.
            val folded = preeditText
            preeditActive = false
            preeditText = ""
            preeditCursorU16 = 0
            if (folded.isNotEmpty()) {
                pendingOps.add(ImeOp.Commit(folded))
                docText = docText.substring(0, docSelStart) + folded + docText.substring(docSelEnd)
                val endPos = docSelStart + folded.length
                docSelStart = endPos
                docSelEnd = endPos
            } else {
                pendingOps.add(ImeOp.Preedit("", 0, 0))
            }
        }

        if (a == b) {
            regionStart = -1
            regionEnd = -1
        } else {
            regionStart = a.coerceIn(0, docText.length)
            regionEnd = b.coerceIn(regionStart, docText.length)
        }
        return maybeFlush()
    }

    fun finishComposingText(): ImeBatchFlush? {
        regionStart = -1
        regionEnd = -1
        if (!preeditActive) {
            return null
        }
        val pending = preeditText
        preeditActive = false
        preeditText = ""
        preeditCursorU16 = 0
        if (pending.isNotEmpty()) {
            docText = docText.substring(0, docSelStart) + pending + docText.substring(docSelEnd)
            val newPos = docSelStart + pending.length
            docSelStart = newPos
            docSelEnd = newPos
            pendingOps.add(ImeOp.Commit(pending))
        } else {
            pendingOps.add(ImeOp.Preedit("", 0, 0))
        }
        return maybeFlush()
    }

    fun commitText(text: CharSequence?, newCursorPosition: Int): ImeBatchFlush? {
        val raw = text?.toString().orEmpty()
        if (preeditActive) {
            val hadPreedit = preeditText.isNotEmpty()
            preeditActive = false
            preeditText = ""
            preeditCursorU16 = 0
            regionStart = -1
            regionEnd = -1
            replaceDocRange(docSelStart, docSelEnd, raw, newCursorPosition, preeditWasActive = hadPreedit)
            return maybeFlush()
        }
        if (hasValidRegion()) {
            val rStart = regionStart
            val rEnd = regionEnd
            regionStart = -1
            regionEnd = -1
            replaceDocRange(rStart, rEnd, raw, newCursorPosition, preeditWasActive = false)
            return maybeFlush()
        }
        replaceDocRange(docSelStart, docSelEnd, raw, newCursorPosition, preeditWasActive = false)
        return maybeFlush()
    }

    fun replaceText(start: Int, end: Int, text: CharSequence?, newCursorPosition: Int): ImeBatchFlush? {
        val snap = virtualSnapshot()
        var a = minOf(start, end).coerceIn(0, snap.text.length)
        var b = maxOf(start, end).coerceIn(a, snap.text.length)
        a = clampU16BoundaryDown(snap.text, a)
        b = clampU16BoundaryDown(snap.text, b).coerceAtLeast(a)
        val raw = text?.toString().orEmpty()

        regionStart = -1
        regionEnd = -1

        if (preeditActive) {
            val pStart = docSelStart
            val pEnd = docSelStart + preeditText.length
            if (a <= pStart && b >= pEnd) {
                // Replacement covers the entire active preedit plus any adjacent doc span.
                val mappedEnd = (docSelEnd + (b - pEnd)).coerceIn(docSelEnd, docText.length)
                val hadPreedit = preeditText.isNotEmpty()
                preeditActive = false
                preeditText = ""
                preeditCursorU16 = 0
                replaceDocRange(a.coerceIn(0, docSelStart), mappedEnd, raw, newCursorPosition, preeditWasActive = hadPreedit)
                return maybeFlush()
            }
            // Partial or disjoint edit against active preedit: commit preedit into doc first.
            val folded = preeditText
            preeditActive = false
            preeditText = ""
            preeditCursorU16 = 0
            if (folded.isNotEmpty()) {
                pendingOps.add(ImeOp.Commit(folded))
                docText = docText.substring(0, docSelStart) + folded + docText.substring(docSelEnd)
                val newPos = docSelStart + folded.length
                docSelStart = newPos
                docSelEnd = newPos
            } else {
                pendingOps.add(ImeOp.Preedit("", 0, 0))
            }
        }

        replaceDocRange(a.coerceIn(0, docText.length), b.coerceIn(a, docText.length), raw, newCursorPosition, preeditWasActive = false)
        return maybeFlush()
    }

    fun deleteSurroundingText(beforeU16: Int, afterU16: Int): ImeBatchFlush? {
        if (beforeU16 <= 0 && afterU16 <= 0) {
            return null
        }
        val bReq = beforeU16.coerceAtLeast(0)
        val aReq = afterU16.coerceAtLeast(0)
        return deleteSurroundingCommon(bReq, aReq, inCodePoints = false)
    }

    fun deleteSurroundingTextInCodePoints(beforeCp: Int, afterCp: Int): ImeBatchFlush? {
        if (beforeCp <= 0 && afterCp <= 0) {
            return null
        }
        val bReq = beforeCp.coerceAtLeast(0)
        val aReq = afterCp.coerceAtLeast(0)
        return deleteSurroundingCommon(bReq, aReq, inCodePoints = true)
    }

    fun setSelection(start: Int, end: Int): ImeBatchFlush? {
        val snap = virtualSnapshot()
        var a = clampU16BoundaryDown(snap.text, start.coerceIn(0, snap.text.length))
        var b = clampU16BoundaryDown(snap.text, end.coerceIn(0, snap.text.length))

        if (preeditActive) {
            val pStart = docSelStart
            val pEnd = docSelStart + preeditText.length
            if (a == b && a in pStart..pEnd) {
                val rel = (a - pStart).coerceIn(0, preeditText.length)
                preeditCursorU16 = rel
                val cp = safeCodePointCount(preeditText, 0, rel)
                pendingOps.add(ImeOp.Preedit(preeditText, cp, cp))
                return maybeFlush()
            }
            val folded = preeditText
            preeditActive = false
            preeditText = ""
            preeditCursorU16 = 0
            if (folded.isNotEmpty()) {
                pendingOps.add(ImeOp.Commit(folded))
                docText = docText.substring(0, docSelStart) + folded + docText.substring(docSelEnd)
                val endPos = docSelStart + folded.length
                docSelStart = endPos
                docSelEnd = endPos
            } else {
                pendingOps.add(ImeOp.Preedit("", 0, 0))
            }
            a = clampU16BoundaryDown(docText, start.coerceIn(0, docText.length))
            b = clampU16BoundaryDown(docText, end.coerceIn(0, docText.length))
        }

        val anchorDelta = signedCodePointDistance(docText, docSelStart, a)
        val focusDelta = signedCodePointDistance(docText, docSelStart, b)
        docSelStart = minOf(a, b)
        docSelEnd = maxOf(a, b)
        if (hasValidRegion()) {
            regionStart = regionStart.coerceIn(0, docText.length)
            regionEnd = regionEnd.coerceIn(regionStart, docText.length)
        }
        pendingOps.add(ImeOp.SetSelectionRelative(anchorDelta, focusDelta))
        return maybeFlush()
    }

    private fun deleteSurroundingCommon(bReq: Int, aReq: Int, inCodePoints: Boolean): ImeBatchFlush? {
        if (preeditActive) {
            val pCur = preeditCursorU16.coerceIn(0, preeditText.length)
            val preBeforeDelStart = if (inCodePoints) offsetByCodePointsClamped(preeditText, pCur, -bReq) else clampU16BoundaryDown(preeditText, (pCur - bReq).coerceAtLeast(0))
            val preAfterDelEnd = if (inCodePoints) offsetByCodePointsClamped(preeditText, pCur, aReq) else clampU16BoundaryUp(preeditText, (pCur + aReq).coerceAtMost(preeditText.length))

            val consumedBeforePre = if (inCodePoints) safeCodePointCount(preeditText, preBeforeDelStart, pCur) else (pCur - preBeforeDelStart)
            val consumedAfterPre = if (inCodePoints) safeCodePointCount(preeditText, pCur, preAfterDelEnd) else (preAfterDelEnd - pCur)

            val docBeforeRem = (bReq - consumedBeforePre).coerceAtLeast(0)
            val docAfterRem = (aReq - consumedAfterPre).coerceAtLeast(0)

            val newPreedit = preeditText.substring(0, preBeforeDelStart) + preeditText.substring(preAfterDelEnd)
            val newPreCur = preBeforeDelStart

            if (docBeforeRem > 0 || docAfterRem > 0) {
                emitDeleteFromDocEnds(docBeforeRem, docAfterRem, inCodePoints)
            }
            if (newPreedit.isEmpty()) {
                preeditActive = false
                preeditText = ""
                preeditCursorU16 = 0
                pendingOps.add(ImeOp.Preedit("", 0, 0))
            } else {
                preeditText = newPreedit
                preeditCursorU16 = newPreCur
                val cp = safeCodePointCount(newPreedit, 0, newPreCur)
                pendingOps.add(ImeOp.Preedit(newPreedit, cp, cp))
            }
            return maybeFlush()
        }

        emitDeleteFromDocEnds(bReq, aReq, inCodePoints)
        return maybeFlush()
    }

    private fun emitDeleteFromDocEnds(beforeReq: Int, afterReq: Int, inCodePoints: Boolean) {
        val bStart = if (inCodePoints) {
            offsetByCodePointsClamped(docText, docSelStart, -beforeReq)
        } else {
            clampU16BoundaryDown(docText, (docSelStart - beforeReq).coerceAtLeast(0))
        }
        val aEnd = if (inCodePoints) {
            offsetByCodePointsClamped(docText, docSelEnd, afterReq)
        } else {
            clampU16BoundaryUp(docText, (docSelEnd + afterReq).coerceAtMost(docText.length))
        }

        val availBeforeCp = safeCodePointCount(docText, bStart, docSelStart)
        val availAfterCp = safeCodePointCount(docText, docSelEnd, aEnd)

        val extraBefore = if (inCodePoints) (beforeReq - availBeforeCp).coerceAtLeast(0) else if (beforeReq > docSelStart) beforeReq - docSelStart else 0
        val extraAfter = if (inCodePoints) (afterReq - availAfterCp).coerceAtLeast(0) else if (docSelEnd + afterReq > docText.length) (docSelEnd + afterReq) - docText.length else 0

        val totalBeforeCp = availBeforeCp + extraBefore
        val totalAfterCp = availAfterCp + extraAfter
        if (totalBeforeCp <= 0 && totalAfterCp <= 0) {
            return
        }

        val origSelStart = docSelStart
        val origSelEnd = docSelEnd
        val removedBeforeLen = origSelStart - bStart
        val removedAfterLen = aEnd - origSelEnd
        val oldSelLen = origSelEnd - origSelStart
        docText = docText.substring(0, bStart) + docText.substring(origSelStart, origSelEnd) + docText.substring(aEnd)
        docSelStart = bStart
        docSelEnd = bStart + oldSelLen

        if (!preeditActive && regionStart >= 0 && regionEnd > regionStart) {
            // Adjust or clear composing region based on how the deleted spans relate to [regionStart, regionEnd).
            val overlapsBefore = removedBeforeLen > 0 && regionStart < origSelStart && regionEnd > bStart
            val overlapsAfter = removedAfterLen > 0 && regionStart < aEnd && regionEnd > origSelEnd
            if (overlapsBefore || overlapsAfter) {
                regionStart = -1
                regionEnd = -1
            } else if (regionEnd <= bStart) {
                // Entirely before deleted before-range: offsets stay unchanged.
            } else if (origSelStart <= regionStart && regionEnd <= origSelEnd) {
                val rLen = regionEnd - regionStart
                regionStart = (regionStart - removedBeforeLen).coerceIn(0, docText.length)
                regionEnd = (regionStart + rLen).coerceIn(regionStart, docText.length)
            } else if (regionStart >= aEnd) {
                val totalShift = removedBeforeLen + removedAfterLen
                val rLen = regionEnd - regionStart
                regionStart = (regionStart - totalShift).coerceIn(0, docText.length)
                regionEnd = (regionStart + rLen).coerceIn(regionStart, docText.length)
            } else {
                regionStart = -1
                regionEnd = -1
            }
            if (regionStart >= regionEnd) {
                regionStart = -1
                regionEnd = -1
            }
        }

        pendingOps.add(ImeOp.DeleteSurrounding(totalBeforeCp, totalAfterCp))
    }

    private fun emitDeleteDocRange(docStart: Int, docEnd: Int) {
        val a = docStart.coerceIn(0, docText.length)
        val b = docEnd.coerceIn(a, docText.length)
        if (a == b) {
            return
        }
        replaceDocRange(a, b, "", 1, preeditWasActive = false)
    }

    private fun replaceDocRange(
        docStart: Int,
        docEnd: Int,
        replacement: String,
        newCursorPosition: Int,
        preeditWasActive: Boolean,
    ) {
        val a = clampU16BoundaryDown(docText, docStart.coerceIn(0, docText.length))
        val b = clampU16BoundaryUp(docText, docEnd.coerceIn(a, docText.length)).coerceAtLeast(a)
        val newDocText = docText.substring(0, a) + replacement + docText.substring(b)
        val rawTargetCur = if (newCursorPosition > 0) {
            a + replacement.length + (newCursorPosition - 1)
        } else {
            a + newCursorPosition
        }
        val targetCur = clampU16BoundaryDown(newDocText, rawTargetCur.coerceIn(0, newDocText.length))
        val curEndAfterEdit = a + replacement.length

        if (a == docSelStart && b == docSelEnd) {
            if (replacement.isNotEmpty()) {
                pendingOps.add(ImeOp.Commit(replacement))
            } else if (docSelStart < docSelEnd) {
                if (preeditWasActive) {
                    pendingOps.add(ImeOp.Preedit("", 0, 0))
                }
                pendingOps.add(ImeOp.TapKeyCode(keyCodeBackspace))
            } else if (preeditWasActive) {
                pendingOps.add(ImeOp.Preedit("", 0, 0))
            }
        } else if (docSelStart == docSelEnd && a <= docSelStart && docSelStart <= b) {
            val beforeCp = safeCodePointCount(docText, a, docSelStart)
            val afterCp = safeCodePointCount(docText, docSelStart, b)
            if (beforeCp > 0 || afterCp > 0) {
                if (preeditWasActive) {
                    pendingOps.add(ImeOp.Preedit("", 0, 0))
                }
                pendingOps.add(ImeOp.DeleteSurrounding(beforeCp, afterCp))
            }
            if (replacement.isNotEmpty()) {
                pendingOps.add(ImeOp.Commit(replacement))
            } else if (preeditWasActive && beforeCp == 0 && afterCp == 0) {
                pendingOps.add(ImeOp.Preedit("", 0, 0))
            }
        } else {
            if (preeditWasActive) {
                pendingOps.add(ImeOp.Preedit("", 0, 0))
            }
            val dAnchor = signedCodePointDistance(docText, docSelStart, a)
            val dFocus = signedCodePointDistance(docText, docSelStart, b)
            pendingOps.add(ImeOp.SetSelectionRelative(dAnchor, dFocus))
            if (replacement.isNotEmpty()) {
                pendingOps.add(ImeOp.Commit(replacement))
            } else if (a < b) {
                pendingOps.add(ImeOp.TapKeyCode(keyCodeBackspace))
            }
        }

        if (targetCur != curEndAfterEdit) {
            val postDelta = signedCodePointDistance(newDocText, curEndAfterEdit, targetCur)
            if (postDelta != 0) {
                pendingOps.add(ImeOp.SetSelectionRelative(postDelta, postDelta))
            }
        }

        docText = newDocText
        docSelStart = targetCur
        docSelEnd = targetCur
    }

    private fun hasValidRegion(): Boolean =
        !preeditActive && regionStart >= 0 && regionEnd > regionStart && regionEnd <= docText.length

    private fun maybeFlush(): ImeBatchFlush? = if (batchDepth == 0) drainPendingFlush() else null

    private fun drainPendingFlush(): ImeBatchFlush? {
        if (pendingOps.isEmpty()) {
            return null
        }
        latestSentSeq += 1L
        val opsCopy = pendingOps.toList()
        pendingOps.clear()
        return ImeBatchFlush(seq = latestSentSeq, ops = opsCopy)
    }

    companion object {
        fun safeCodePointCount(text: String, start: Int, end: Int): Int {
            val a = start.coerceIn(0, text.length)
            val b = end.coerceIn(a, text.length)
            return if (a == b) 0 else Character.codePointCount(text, a, b)
        }

        fun signedCodePointDistance(text: String, from: Int, to: Int): Int {
            val a = from.coerceIn(0, text.length)
            val b = to.coerceIn(0, text.length)
            return if (b >= a) {
                Character.codePointCount(text, a, b)
            } else {
                -Character.codePointCount(text, b, a)
            }
        }

        fun clampU16BoundaryDown(text: String, idx: Int): Int {
            val i = idx.coerceIn(0, text.length)
            return if (i in 1 until text.length && Character.isLowSurrogate(text[i]) && Character.isHighSurrogate(text[i - 1])) {
                i - 1
            } else {
                i
            }
        }

        fun clampU16BoundaryUp(text: String, idx: Int): Int {
            val i = idx.coerceIn(0, text.length)
            return if (i in 1 until text.length && Character.isLowSurrogate(text[i]) && Character.isHighSurrogate(text[i - 1])) {
                i + 1
            } else {
                i
            }
        }

        fun offsetByCodePointsClamped(text: String, index: Int, codePointOffset: Int): Int {
            var pos = clampU16BoundaryDown(text, index)
            if (codePointOffset > 0) {
                var remaining = codePointOffset
                while (remaining > 0 && pos < text.length) {
                    val cp = Character.codePointAt(text, pos)
                    pos += Character.charCount(cp)
                    remaining -= 1
                }
            } else if (codePointOffset < 0) {
                var remaining = -codePointOffset
                while (remaining > 0 && pos > 0) {
                    val cp = Character.codePointBefore(text, pos)
                    pos -= Character.charCount(cp)
                    remaining -= 1
                }
            }
            return pos.coerceIn(0, text.length)
        }
    }
}
