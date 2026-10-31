package ai.closepaw.voice

import kotlinx.serialization.json.JsonObject

/** Watches actual input awaiting a reply; quiet listening and delegated work have no deadline. */
internal class LiveResponseWatchdog(private val timeoutMs: Long = 30_000) {
    private var awaitingSince: Long? = null
    private var microphoneActive = false

    fun microphone(active: Boolean, nowMs: Long) {
        microphoneActive = active
        if (active || awaitingSince != null) awaitingSince = nowMs
    }

    fun event(event: JsonObject, nowMs: Long) {
        when (event.string("type")) {
            "input_transcript.added" -> if (!event.obj("item")?.string("text").isNullOrBlank()) awaitingSince = nowMs
            "output_transcript.added", "delegation.created" -> awaitingSince = null
            "turn.done" -> when (event.obj("turn")?.string("role")) {
                "user" -> if (!event.obj("turn")?.string("transcript").isNullOrBlank()) awaitingSince = nowMs
                "assistant" -> awaitingSince = null
            }
        }
    }

    fun playback() { awaitingSince = null }
    fun reset() { awaitingSince = null; microphoneActive = false }
    fun expired(nowMs: Long): Boolean = !microphoneActive && awaitingSince?.let { nowMs - it >= timeoutMs } == true
}

/** A conservative energy gate, not transcription. Audio bytes are never retained. */
internal class MicrophoneActivity {
    private var soundSince: Long? = null
    private var lastSound = 0L
    private var active = false

    fun accept(pcm16: ByteArray, nowMs: Long): Boolean? {
        var energy = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm16.size) {
            val sample = ((pcm16[i].toInt() and 255) or (pcm16[i + 1].toInt() shl 8)).toShort().toInt()
            energy += sample.toDouble() * sample
            count++; i += 2
        }
        if (count > 0 && energy / count >= 600.0 * 600.0) {
            if (soundSince == null) soundSince = nowMs
            lastSound = nowMs
        } else if (nowMs - lastSound > 800) soundSince = null
        val next = soundSince?.let { nowMs - it >= 300 } == true
        if (next == active) return null
        active = next
        return next
    }
}
