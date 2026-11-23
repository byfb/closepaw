package ai.closepaw.session

import ai.closepaw.protocol.AskUserType
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

/** Holds the one outstanding user request. The session delivers a matching reply via [deliver]
 * or calls [cancel] when the task stops. The question tool owns any on-screen timeout. */
class UserResponseChannel {

    data class Prompt(val callId: String, val type: AskUserType, val message: String)

    private data class PendingRequest(
        val prompt: Prompt,
        val deferred: CompletableDeferred<String>
    )

    private val pending = AtomicReference<PendingRequest?>(null)

    suspend fun awaitResponse(callId: String): String =
        awaitResponse(Prompt(callId, AskUserType.QUESTION, "")) {}

    /**
     * Register the request before notifying observers so an immediate answer can be delivered.
     *
     * @throws IllegalStateException if another request is already pending
     * @throws kotlinx.coroutines.CancellationException if cancelled (stop/timeout)
     */
    suspend fun awaitResponse(prompt: Prompt, onPending: suspend () -> Unit): String {
        val deferred = CompletableDeferred<String>()
        val request = PendingRequest(prompt, deferred)
        check(pending.compareAndSet(null, request)) { "Only one pending ask_user request allowed" }
        return try {
            onPending()
            deferred.await()
        } finally {
            pending.compareAndSet(request, null)
        }
    }

    /**
     * Deliver the user's response. Called by AgentSession on Op.UserResponse.
     *
     * @return true if delivered, false if no matching pending request.
     */
    fun deliver(callId: String, response: String): Boolean {
        val request = pending.get() ?: return false
        if (request.prompt.callId != callId) return false
        if (!pending.compareAndSet(request, null)) return false
        return request.deferred.complete(response)
    }

    /** Cancel any pending request (called on stop/shutdown). */
    fun cancel() {
        pending.getAndSet(null)?.deferred?.cancel()
    }

    val pendingPrompt: Prompt? get() = pending.get()?.prompt
    val pendingCallId: String? get() = pendingPrompt?.callId

    val hasPending: Boolean get() = pending.get() != null
}
