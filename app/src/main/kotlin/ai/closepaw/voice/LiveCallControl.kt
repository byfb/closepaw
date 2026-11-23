package ai.closepaw.voice

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/** Coordinates a completed user turn with any phone handoff emitted for the same speech. Main-confined. */
internal class LiveCallControl(
    private val scope: CoroutineScope,
    private val selectTools: suspend (String) -> LiveToolSelection,
    private val executeEndCall: suspend (JSONObject) -> Unit,
    private val unavailable: () -> Unit,
    private val executeMemory: suspend (List<JSONObject>) -> Boolean = { true },
    private val captureVoiceAnswer: () -> (suspend (String) -> Unit)? = { null },
) {
    private var decision = CompletableDeferred<Boolean>()
    private var submitted = false
    private var selection: Job? = null
    private var memoryHandledDecision: Deferred<Boolean>? = null
    private var speechStarted = false
    private var voiceAnswer: (suspend (String) -> Unit)? = null

    /** Capture this before enqueueing the handoff: the next user may speak while it is queued. */
    val handoffAllowed: Deferred<Boolean> get() = decision
    fun handledMemory(allowed: Deferred<Boolean>): Boolean = memoryHandledDecision === allowed

    fun receive(event: JsonObject) {
        if (event.string("type") == "input_transcript.added") {
            if (!event.obj("item")?.string("text").isNullOrBlank()) {
                if (submitted) reset()
                beginSpeech()
            }
            return
        }
        if (event.string("type") != "turn.done") return
        val turn = event.obj("turn") ?: return
        if (turn.string("role") != "user") return
        if (submitted) reset() // A completed turn can also arrive without any partials.
        submitted = true
        val text = turn.string("transcript").orEmpty().trim()
        beginSpeech()
        val answer = voiceAnswer
        if (text.isEmpty()) {
            decision.complete(answer == null)
            return
        }
        val current = decision
        selection = scope.launch {
            val arguments = try {
                withTimeout(SELECTION_TIMEOUT_MS) { selectTools(text.take(6000)) }
            } catch (_: TimeoutCancellationException) {
                unavailable()
                LiveToolSelection()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                unavailable()
                LiveToolSelection()
            }
            if (decision !== current) return@launch
            if (answer != null) {
                // A reply belongs only to the captured question, including when a redundant
                // delegation arrives or the worker is cancelled during call-control selection.
                selection = null
                current.complete(false)
                if (arguments.endCall != null) executeEndCall(arguments.endCall) else answer(text.take(6000))
                return@launch
            }
            // Persist memory before releasing a related phone task, so its first turn sees it.
            val memorySucceeded = try {
                arguments.memoryUpdates.isEmpty() || executeMemory(arguments.memoryUpdates)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                unavailable()
                false
            }
            if (decision !== current) return@launch
            selection = null
            val handledMemory = arguments.endCall == null && arguments.memoryHandlesTurn && memorySucceeded
            if (handledMemory) memoryHandledDecision = current
            current.complete(arguments.endCall == null && !handledMemory)
            arguments.endCall?.let { executeEndCall(it) }
        }
    }

    private fun beginSpeech() {
        if (speechStarted) return
        speechStarted = true
        voiceAnswer = captureVoiceAnswer()
        if (voiceAnswer != null) decision.complete(false)
    }

    /** Superseded speech must neither hang up nor send its pending handoff to the worker. */
    fun reset() {
        selection?.cancel()
        selection = null
        memoryHandledDecision = null
        decision.complete(false)
        decision = CompletableDeferred()
        submitted = false
        speechStarted = false
        voiceAnswer = null
    }

    companion object {
        private const val SELECTION_TIMEOUT_MS = 12_000L
        // Also bound a handoff that arrived without a completed user turn.
        const val HANDOFF_TIMEOUT_MS = SELECTION_TIMEOUT_MS + 2_000L
    }
}
