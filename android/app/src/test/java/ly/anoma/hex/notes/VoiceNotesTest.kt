package ly.anoma.hex.notes

import ly.anoma.hex.diagnostics.wavSamples
import ly.anoma.hex.speech.SpeechEdges
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceNotesTest {
    @Test fun recordingWavPreservesOriginalSamples() {
        val samples = shortArrayOf(0, 0, Short.MIN_VALUE, -1, 1, Short.MAX_VALUE, 0, 0)
        assertArrayEquals(samples, wavSamples(voiceNoteWav(samples)))
    }

    @Test fun searchUsesOnlyQuotedWordPrefixes() {
        assertEquals("\"Café*\" \"OR*\" \"fig*\" \"123*\"", noteSearch("Café OR \"fig*\" (123)"))
        assertEquals("", noteSearch("\"*:!()"))
    }

    @Test fun pauseRequiresSpeechAndIgnoresAnIsolatedClick() {
        val edges = SpeechEdges(1_000)
        assertFalse(edges.paused(6_000, 3_000))
        edges.block(0, 40, true)
        edges.block(40, 40, true)
        edges.block(80, 40, true)
        edges.block(3_000, 40, true)
        assertFalse(edges.paused(3_119, 3_000))
        assertTrue(edges.paused(3_120, 3_000))
        assertFalse(edges.paused(3_120, 0))
    }
}
