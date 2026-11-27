package ai.closepaw.session

import ai.closepaw.protocol.AskUserType
import ai.closepaw.protocol.ApprovalDecision
import ai.closepaw.voice.LiveIdleTimeout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/** Holds the one outstanding user request. The session delivers a matching reply via [deliver]
 * or calls [cancel] when the task stops. The question tool owns any on-screen timeout. */
class UserResponseChannel {

    data class Prompt(val callId: String, val type: AskUserType, val message: String, val approval: Boolean = false)

    data class VoiceDeadline(val prompt: Prompt, val expired: Boolean)
    private val deadlines = MutableSharedFlow<VoiceDeadline>(extraBufferCapacity = 2)
    val voiceDeadlines = deadlines.asSharedFlow()

    /** The worker owns the deadline even if the call disconnects or the service restarts. */
    suspend fun awaitSpoken(prompt: Prompt, onPending: suspend () -> Unit): String = coroutineScope {
        val response = async(start = CoroutineStart.UNDISPATCHED) { awaitResponse(prompt, onPending) }
        val reminder = launch {
            delay(LiveIdleTimeout.REMIND_AFTER_MS)
            if (pendingPrompt === prompt) deadlines.emit(VoiceDeadline(prompt, expired = false))
        }
        try {
            withTimeoutOrNull(LiveIdleTimeout.END_AFTER_MS) { response.await() }?.let { return@coroutineScope it }
            // A final transcript received before the deadline may still be in the bounded
            // call-control decision that distinguishes an answer from a hang-up.
            pending.get()?.takeIf { it.prompt === prompt }?.processingReply?.join()
            if (pendingPrompt !== prompt) return@coroutineScope response.await()
            deadlines.emit(VoiceDeadline(prompt, expired = true))
            throw UnansweredVoiceQuestion(prompt.message)
        } finally {
            reminder.cancel()
            response.cancel()
        }
    }

    private data class PendingRequest(
        val prompt: Prompt,
        val deferred: CompletableDeferred<String>,
        @Volatile var processingReply: Job? = null,
    )

    private val pending = AtomicReference<PendingRequest?>(null)

    internal fun processingSpokenReply(prompt: Prompt, processing: Job) {
        pending.get()?.takeIf { it.prompt === prompt }?.processingReply = processing
    }

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

class UnansweredVoiceQuestion(question: String) : Exception("No spoken reply within 60 seconds: $question")

/** Only an explicit reply to the pending approval authorizes its one action. */
internal fun spokenApproval(answer: String): ApprovalDecision? {
    val words = answer.trim().trimEnd('.', '!', '?', '。', '！', '？', '，', ',').trim().lowercase()
    return when (words) {
        "approve", "approved", "yes", "yes please", "yes, please", "go ahead", "allow", "confirm",
        "同意", "我同意", "批准", "允許", "允许", "確認", "确认", "可以", "可以呀", "好", "好呀", "好的", "係", "是" -> ApprovalDecision.APPROVED
        "deny", "decline", "no", "reject", "no thanks",
        "拒絕", "拒绝", "不同意", "唔同意", "唔好", "唔可以", "不可以", "不要", "不批准", "不" -> ApprovalDecision.DENIED
        else -> null
    }
}
