package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.voice.VoicePlayback
import org.junit.Assert.*
import org.junit.Test

class VoicePlaybackTest {
    @Test fun shortClipCanPrimeOlderTrackAndReleaseAtLastVoiceFrame() {
        val voice = VoicePlayback(4, 8000, queuedBytes = 16)
        val writes = mutableListOf<Int>()
        val write: (Int, Int) -> Int = { _, remaining -> writes.add(remaining); remaining }
        assertFalse(voice.advance(100, null, write) { 0 })
        assertFalse(voice.advance(120, 3, write) { 1 })
        assertTrue(voice.advance(130, 3, write) { 2 })
        assertEquals(listOf(16), writes)
    }
    @Test fun waitsForRecorderAndDrainAcrossPartialWrites() {
        val voice = VoicePlayback(8, 8000)
        val offsets = mutableListOf<Int>()
        val write: (Int, Int) -> Int = { offset, _ -> offsets.add(offset); 4 }
        assertFalse(voice.advance(200, null, write) { 0 })
        assertTrue(offsets.isEmpty())
        assertFalse(voice.advance(400, 5, write) { 0 })
        assertFalse(voice.advance(420, 5, write) { 3 })
        assertTrue(voice.advance(440, 5, write) { 4 })
        assertEquals(listOf(0, 4), offsets)
    }
    @Test fun missingOrChangedRecorderNeverReceivesAudio() {
        val voice = VoicePlayback(8, 8000)
        assertThrows(IllegalStateException::class.java) { voice.advance(2500, null, { _, _ -> error("unexpected write") }) { 0 } }
        assertFalse(voice.advance(10, 7, { _, _ -> 2 }) { 0 })
        for (session in listOf(null, 8)) {
            assertThrows(IllegalStateException::class.java) { voice.advance(30, session, { _, _ -> throw AssertionError() }) { 0 } }
        }
    }
}
