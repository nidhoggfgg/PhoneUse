package ai.nidhoggfgg.phoneuse.voice

/** One held gesture. A recorder must stay on the same route/session until the last frame drains. */
internal class VoicePlayback(private val byteCount: Int, sampleRate: Int, private val queuedBytes: Int = byteCount) {
    private val timeoutMs = 5000L + byteCount * 500L / sampleRate
    private var session: Int? = null
    private var written = 0

    fun advance(elapsedMs: Long, recorder: Int?, write: (Int, Int) -> Int, playedFrames: () -> Int): Boolean {
        check(elapsedMs < timeoutMs) { "Playback timed out" }
        if (session == null && recorder == null) {
            check(elapsedMs < 2500) { "Target did not start recording from the injection route" }
            return false
        }
        check(recorder != null && (session == null || session == recorder)) { "Target recording interrupted" }
        session = recorder
        if (written < queuedBytes) {
            val remaining = queuedBytes - written
            val count = write(written, remaining)
            check(count in 0..remaining && count % 2 == 0) { "Audio write failed ($count)" }
            written += count
        }
        return written >= byteCount && playedFrames() >= byteCount / 2
    }
}
