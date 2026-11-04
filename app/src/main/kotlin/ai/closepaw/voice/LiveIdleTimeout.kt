package ai.closepaw.voice

import kotlinx.serialization.json.JsonObject

/** Monotonic, call-local idle timing. The reminder's own speech never renews its deadline. */
internal class LiveIdleTimeout {
    enum class Action { NONE, REMIND, END }

    private var lastActivity: Long? = null
    private var reminderAt: Long? = null
    private val reminded: Boolean get() = reminderAt != null
    private var microphoneActive = false
    private var playing = false

    fun start(nowMs: Long) {
        lastActivity = nowMs
        reminderAt = null
        microphoneActive = false
        playing = false
    }

    private fun activity(nowMs: Long) {
        if (lastActivity == null) return
        lastActivity = nowMs
        reminderAt = null
    }

    fun microphone(active: Boolean, nowMs: Long) {
        if (active || microphoneActive) activity(nowMs)
        microphoneActive = active
    }

    fun playback(active: Boolean, nowMs: Long) {
        if (!reminded && (active || playing)) activity(nowMs)
        playing = active
    }

    fun event(event: JsonObject, nowMs: Long) {
        when (event.string("type")) {
            "input_transcript.added" -> if (!event.obj("item")?.string("text").isNullOrBlank()) activity(nowMs)
            "output_transcript.added" -> if (!reminded && !event.obj("item")?.string("text").isNullOrBlank()) activity(nowMs)
            "turn.done" -> {
                val turn = event.obj("turn") ?: return
                if (!turn.string("transcript").isNullOrBlank() &&
                    (turn.string("role") == "user" || turn.string("role") == "assistant" && !reminded)) activity(nowMs)
            }
            "delegation.created" -> activity(nowMs)
        }
    }

    fun poll(nowMs: Long, busy: Boolean = false): Action {
        val since = lastActivity ?: return Action.NONE
        if (busy || microphoneActive || playing && !reminded) {
            activity(nowMs)
            return Action.NONE
        }
        val idleMs = nowMs - since
        if (!reminded && idleMs >= REMIND_AFTER_MS) {
            reminderAt = nowMs
            return Action.REMIND
        }
        if (idleMs >= END_AFTER_MS && reminderAt?.let { nowMs - it >= END_AFTER_MS - REMIND_AFTER_MS } == true)
            return Action.END
        return Action.NONE
    }

    companion object {
        const val REMIND_AFTER_MS = 40_000L
        const val END_AFTER_MS = 60_000L
    }
}
