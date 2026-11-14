package one.lmk.wordcraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeEditSessionTest {
    @Test
    fun composingRegionThenCommitReplacesWordAtomically() {
        val session = ImeEditSession()
        assertTrue(session.syncFromRust("Moze", 4, 4, 0L))

        val regFlush = session.setComposingRegion(0, 4)
        assertNull(regFlush)
        assertEquals(ImeSnapshot("Moze", 4, 4, 0, 4), session.virtualSnapshot())

        val flush = session.commitText("Może", 1)
        assertNotNull(flush)
        assertEquals(1L, flush?.seq)
        assertEquals(
            listOf(
                ImeOp.DeleteSurrounding(beforeCp = 4, afterCp = 0),
                ImeOp.Commit("Może"),
            ),
            flush?.ops,
        )
        assertEquals(ImeSnapshot("Może", 4, 4, -1, -1), session.virtualSnapshot())
    }

    @Test
    fun replaceTextOverActivePreeditCommitsWithoutExtraDeletes() {
        val session = ImeEditSession()
        assertTrue(session.syncFromRust("", 0, 0, 0L))

        val preFlush = session.setComposingText("Moze", 1)
        assertEquals(listOf(ImeOp.Preedit("Moze", 4, 4)), preFlush?.ops)
        assertEquals(ImeSnapshot("Moze", 4, 4, 0, 4), session.virtualSnapshot())

        val replaceFlush = session.replaceText(0, 4, "Może", 1)
        assertEquals(2L, replaceFlush?.seq)
        assertEquals(listOf(ImeOp.Commit("Może")), replaceFlush?.ops)
        assertEquals(ImeSnapshot("Może", 4, 4, -1, -1), session.virtualSnapshot())
    }

    @Test
    fun committedSentenceComposingRegionSetComposingTextAndFinishComposing() {
        val session = ImeEditSession()
        assertTrue(session.syncFromRust("Ala Moze?", 8, 8, 0L))

        assertNull(session.setComposingRegion(4, 8))
        val setFlush = session.setComposingText("Może", 1)
        assertEquals(
            listOf(
                ImeOp.DeleteSurrounding(beforeCp = 4, afterCp = 0),
                ImeOp.Preedit("Może", 4, 4),
            ),
            setFlush?.ops,
        )
        assertEquals(ImeSnapshot("Ala Może?", 8, 8, 4, 8), session.virtualSnapshot())

        val finishFlush = session.finishComposingText()
        assertEquals(listOf(ImeOp.Commit("Może")), finishFlush?.ops)
        assertEquals(ImeSnapshot("Ala Może?", 8, 8, -1, -1), session.virtualSnapshot())
    }

    @Test
    fun replaceTextAwayFromCaretRepositionsAndRespectsNewCursorPosition() {
        val session = ImeEditSession()
        assertTrue(session.syncFromRust("Ala Moze?", 9, 9, 0L))

        val flushEnd = session.replaceText(4, 8, "Może", 1)
        assertEquals(
            listOf(
                ImeOp.SetSelectionRelative(deltaAnchorCp = -5, deltaFocusCp = -1),
                ImeOp.Commit("Może"),
            ),
            flushEnd?.ops,
        )
        assertEquals(ImeSnapshot("Ala Może?", 8, 8, -1, -1), session.virtualSnapshot())

        assertTrue(session.syncFromRust("Ala Moze?", 9, 9, flushEnd!!.seq))
        val flushPlusOne = session.replaceText(4, 8, "Może", 2)
        assertEquals(
            listOf(
                ImeOp.SetSelectionRelative(deltaAnchorCp = -5, deltaFocusCp = -1),
                ImeOp.Commit("Może"),
                ImeOp.SetSelectionRelative(deltaAnchorCp = 1, deltaFocusCp = 1),
            ),
            flushPlusOne?.ops,
        )
        assertEquals(ImeSnapshot("Ala Może?", 9, 9, -1, -1), session.virtualSnapshot())
    }

    @Test
    fun composingRegionFollowedByFinishComposingDoesNotDuplicateText() {
        val session = ImeEditSession()
        assertTrue(session.syncFromRust("Moze", 4, 4, 0L))

        assertNull(session.setComposingRegion(0, 4))
        assertNull(session.finishComposingText())
        assertEquals(ImeSnapshot("Moze", 4, 4, -1, -1), session.virtualSnapshot())
    }

    @Test
    fun batchEditCombinesDeleteSurroundingAndCommitIntoSingleFlush() {
        val session = ImeEditSession()
        assertTrue(session.syncFromRust("Moze", 4, 4, 0L))

        assertNull(session.beginBatchEdit())
        assertTrue(session.isInBatch())
        assertNull(session.deleteSurroundingText(1, 0))
        assertNull(session.commitText("ę", 1))

        val (stillInBatch, flush) = session.endBatchEdit()
        assertFalse(stillInBatch)
        assertNotNull(flush)
        assertEquals(
            listOf(
                ImeOp.DeleteSurrounding(beforeCp = 1, afterCp = 0),
                ImeOp.Commit("ę"),
            ),
            flush?.ops,
        )
        assertEquals(ImeSnapshot("Mozę", 4, 4, -1, -1), session.virtualSnapshot())
    }

    @Test
    fun utf16SurrogatePairsConvertAccuratelyToCodePointsAndDeleteInsideWord() {
        val session = ImeEditSession()
        val emoji = "\uD83D\uDE00" // U+1F600 (2 UTF-16 code units, 1 code point)
        val initial = "${emoji}Moze"
        // Caret right between "Mo" and "ze": 2 (emoji) + 2 ("Mo") = 4
        assertTrue(session.syncFromRust(initial, 4, 4, 0L))

        assertNull(session.setComposingRegion(2, 6))
        val flush = session.commitText("Może", 1)
        assertEquals(
            listOf(
                ImeOp.DeleteSurrounding(beforeCp = 2, afterCp = 2),
                ImeOp.Commit("Może"),
            ),
            flush?.ops,
        )
        assertEquals(ImeSnapshot("${emoji}Może", 6, 6, -1, -1), session.virtualSnapshot())

        // Delete the emoji from right after it: 2 UTF-16 code units = 1 Unicode code point
        val moveFlush = session.setSelection(2, 2)
        assertEquals(listOf(ImeOp.SetSelectionRelative(deltaAnchorCp = -4, deltaFocusCp = -4)), moveFlush?.ops)
        val delEmoji = session.deleteSurroundingText(2, 0)
        assertEquals(listOf(ImeOp.DeleteSurrounding(beforeCp = 1, afterCp = 0)), delEmoji?.ops)
        assertEquals(ImeSnapshot("Może", 0, 0, -1, -1), session.virtualSnapshot())
    }

    @Test
    fun staleRustSyncSeqIsIgnoredAfterTransaction() {
        val session = ImeEditSession()
        assertTrue(session.syncFromRust("Moze", 4, 4, 0L))

        assertNull(session.setComposingRegion(0, 4))
        val flush = session.commitText("Może", 1)!!
        assertEquals(1L, flush.seq)

        // Frame rendered before Rust applied seq=1 arrives with stale seq=0: must be dropped!
        assertFalse(session.syncFromRust("Moze", 4, 4, 0L))
        assertEquals(ImeSnapshot("Może", 4, 4, -1, -1), session.virtualSnapshot())

        // Matching seq=1 from Rust is accepted.
        assertTrue(session.syncFromRust("Może", 4, 4, 1L))
    }

    @Test
    fun deleteBeforeComposingRegionShiftsRegionInsteadOfClearingIt() {
        val session = ImeEditSession()
        assertTrue(session.syncFromRust("Ab Może", 1, 1, 0L))
        assertNull(session.setComposingRegion(3, 7))

        val del = session.deleteSurroundingText(1, 0)
        assertEquals(listOf(ImeOp.DeleteSurrounding(beforeCp = 1, afterCp = 0)), del?.ops)
        assertEquals(ImeSnapshot("b Może", 0, 0, 2, 6), session.virtualSnapshot())

        // Deleting into an active composing region invalidates the region.
        val moveIntoRegion = session.setSelection(4, 4)
        assertEquals(listOf(ImeOp.SetSelectionRelative(deltaAnchorCp = 4, deltaFocusCp = 4)), moveIntoRegion?.ops)
        session.deleteSurroundingText(1, 0)
        assertEquals(ImeSnapshot("b Mże", 3, 3, -1, -1), session.virtualSnapshot())
    }

    @Test
    fun replaceTextSpanningCommittedPrefixAndPreeditClearsPreeditBeforeDeletingDoc() {
        val session = ImeEditSession()
        assertTrue(session.syncFromRust("Ala ", 4, 4, 0L))
        session.setComposingText("Moze", 1)

        val flush = session.replaceText(0, 8, "Ela Może", 1)
        assertEquals(
            listOf(
                ImeOp.Preedit("", 0, 0),
                ImeOp.DeleteSurrounding(beforeCp = 4, afterCp = 0),
                ImeOp.Commit("Ela Może"),
            ),
            flush?.ops,
        )
        assertEquals(ImeSnapshot("Ela Może", 8, 8, -1, -1), session.virtualSnapshot())
    }
}
