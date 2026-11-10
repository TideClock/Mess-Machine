package com.tideclock.messmachine

import com.tideclock.messmachine.ui.chordOnScreen
import org.junit.Assert.assertEquals
import org.junit.Test

/** Tyler's note: chord tones showed for the root note only. A one-row Padiano shows about an octave. */
class ChordShowTest {
    private val window = (0..12).toList() // C to the next C

    @Test fun rootChordShowsWhereItIs() {
        assertEquals(setOf(4, 7), chordOnScreen(listOf(0, 4, 7), setOf(0), window))
    }

    @Test fun higherChordFoldsOntoTheScreen() {
        // G major pressed on G (7): B (11) is on screen, D (14) is not, so it lights the D at 2
        assertEquals(setOf(11, 2), chordOnScreen(listOf(7, 11, 14), setOf(7), window))
    }

    @Test fun pressedNotesAreNotRelit() {
        assertEquals(setOf(9), chordOnScreen(listOf(5, 9, 12), setOf(5, 12), window))
    }
}
