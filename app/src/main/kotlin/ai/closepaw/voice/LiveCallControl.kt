package ai.closepaw.voice

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/** Coordinates a completed user turn with any phone handoff emitted for the same speech. Main-confined. */
internal class LiveCallControl(
    private val scope: CoroutineScope,
    private val selectEndCall: suspend (String) -> JSONObject?,
    private val executeEndCall: suspend (JSONObject) -> Unit,
    private val unavailable: () -> Unit,
) {
    private var decision = CompletableDeferred<Boolean>()
    private var submitted = false
    private var selection: Job? = null

    /** Capture this before enqueueing the handoff: the next user may speak while it is queued. */
    val handoffAllowed: Deferred<Boolean> get() = decision

    fun receive(event: JsonObject) {
        if (event.string("type") == "input_transcript.added") {
            if (submitted && !event.obj("item")?.string("text").isNullOrBlank()) reset()
            return
        }
        if (event.string("type") != "turn.done") return
        val turn = event.obj("turn") ?: return
        if (turn.string("role") != "user") return
        if (submitted) reset() // A completed turn can also arrive without any partials.
        submitted = true
        val text = turn.string("transcript").orEmpty().trim()
        if (text.isEmpty()) {
            decision.complete(true)
            return
        }
        val current = decision
        selection = scope.launch {
            val arguments = try {
                withTimeout(SELECTION_TIMEOUT_MS) { selectEndCall(text.take(6000)) }
            } catch (_: TimeoutCancellationException) {
                unavailable()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                unavailable()
                null
            }
            if (decision !== current) return@launch
            selection = null
            current.complete(arguments == null)
            if (arguments != null) executeEndCall(arguments)
        }
    }

    /** Superseded speech must neither hang up nor send its pending handoff to the worker. */
    fun reset() {
        selection?.cancel()
        selection = null
        decision.complete(false)
        decision = CompletableDeferred()
        submitted = false
    }

    companion object {
        private const val SELECTION_TIMEOUT_MS = 12_000L
        // Also bound a handoff that arrived without a completed user turn.
        const val HANDOFF_TIMEOUT_MS = SELECTION_TIMEOUT_MS + 2_000L
    }
}
