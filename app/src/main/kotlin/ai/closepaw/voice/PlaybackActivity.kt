package ai.closepaw.voice

/** Reads PCM energy without retaining audio. Called only on WebRTC's playback thread. */
internal class PlaybackActivity {
    private var lastSoundAt: Long? = null
    private var active = false

    fun accept(pcm16: ByteArray, nowMs: Long): Boolean? {
        var i = 0
        while (i + 1 < pcm16.size) {
            val sample = ((pcm16[i].toInt() and 255) or (pcm16[i + 1].toInt() shl 8)).toShort().toInt()
            if (kotlin.math.abs(sample) >= 256) { lastSoundAt = nowMs; break }
            i += 2
        }
        val next = lastSoundAt?.let { nowMs - it < 800 } == true
        if (next == active) return null
        active = next
        return next
    }
}
