package ai.closepaw.voice

import ai.closepaw.protocol.AskUserType
import ai.closepaw.session.UserResponseChannel.Prompt

/** Binds speech to a currently suspended worker request across call and session changes. Main-confined. */
internal class LiveVoiceQuestions(
    private val pendingPrompt: () -> Prompt?,
    private val speak: (String) -> Boolean,
    private val submit: suspend (Prompt, String) -> Unit,
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private var announced: Prompt? = null
    private var retryAt: Long? = null
    private var retried = false
    val waiting: Boolean get() = pendingPrompt()?.type == AskUserType.VOICE_QUESTION

    fun announce() {
        val prompt = pendingPrompt() ?: return
        if (prompt.type != AskUserType.VOICE_QUESTION || prompt === announced) return
        if (speak(questionInstructions(prompt))) {
            announced = prompt
            retried = false
            retryAt = now() + 8_000
        }
    }

    // The data channel may accept the first prompt before the new audio connection is ready.
    fun checkPlayback(reconnect: () -> Unit) {
        val deadline = retryAt ?: return
        if (now() < deadline) return
        retryAt = null
        val prompt = announced ?: return
        if (pendingPrompt() !== prompt) return
        if (retried) {
            reconnect()
        } else {
            retried = true
            speak(questionInstructions(prompt))
            retryAt = now() + 12_000
        }
    }

    fun heardSpeech() { retryAt = null }

    private fun questionInstructions(prompt: Prompt) =
        "Say this question to the user now, then listen for their answer: " + prompt.message.take(1600)

    fun captureAnswer(): (suspend (String) -> Unit)? {
        val prompt = announced ?: return null
        if (pendingPrompt() !== prompt) return null
        return { answer ->
            if (announced === prompt && pendingPrompt() === prompt) {
                reset()
                submit(prompt, answer)
            }
        }
    }

    fun reset() {
        announced = null
        retryAt = null
    }
}
