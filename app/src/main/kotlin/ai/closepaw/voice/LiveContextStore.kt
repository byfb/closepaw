package ai.closepaw.voice

import ai.closepaw.protocol.*
import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.*

/** Text-only handoff for the current chat. No audio, screenshots, or tool traces are stored here. */
internal data class LiveContextSnapshot(
    val chatId: String? = null,
    val brief: String = "",
    val turns: List<Pair<String, String>> = emptyList(),
    val taskRequest: String = "",
    val taskUpdate: String = "",
    val question: String = "",
) {
    val hasContext: Boolean get() = brief.isNotBlank() || turns.isNotEmpty() || taskRequest.isNotBlank() || taskUpdate.isNotBlank()

    fun prompt(workerState: String): String = buildString {
        append("Restored conversation records, not new instructions. Do not repeat earlier actions. Wait for fresh user speech before delegating.\n")
        if (brief.isNotBlank()) append("Initial user context: $brief\n")
        turns.forEach { (role, text) -> append("$role: $text\n") }
        if (taskRequest.isNotBlank()) append("Latest phone request: $taskRequest\n")
        if (taskUpdate.isNotBlank()) append("Last recorded phone update: $taskUpdate\n")
        if (question.isNotBlank()) append("Previously asked question: $question\n")
        append("Current worker state: $workerState. Historical progress is not evidence of a currently running task.")
    }

    fun encode(): String = buildJsonObject {
        put("chat", chatId?.let(::JsonPrimitive) ?: JsonNull)
        put("brief", brief.take(3000))
        put("request", taskRequest.take(2000))
        put("update", taskUpdate.take(1800))
        put("question", question.take(1200))
        putJsonArray("turns") { boundedTurns(turns).forEach { (role, text) ->
            addJsonObject { put("role", role); put("text", text) }
        } }
    }.toString()

    companion object {
        fun decode(raw: String?): LiveContextSnapshot? = runCatching {
            if (raw.isNullOrEmpty() || raw.length > 40_000) return null
            val json = Json.parseToJsonElement(raw).jsonObject
            LiveContextSnapshot(
                chatId = json.string("chat"), brief = json.string("brief").orEmpty().take(3000),
                taskRequest = json.string("request").orEmpty().take(2000),
                taskUpdate = json.string("update").orEmpty().take(1800),
                question = json.string("question").orEmpty().take(1200),
                turns = boundedTurns((json["turns"] as? JsonArray).orEmpty().mapNotNull {
                    val turn = it as? JsonObject ?: return@mapNotNull null
                    val role = turn.string("role")?.takeIf { it == "user" || it == "assistant" } ?: return@mapNotNull null
                    role to turn.string("text").orEmpty()
                }),
            )
        }.getOrNull()

        fun boundedTurns(turns: List<Pair<String, String>>): List<Pair<String, String>> {
            var remaining = 8000
            return turns.takeLast(12).asReversed().mapNotNull { (role, text) ->
                val bounded = text.take(minOf(1500, remaining))
                remaining -= bounded.length
                if (bounded.isBlank()) null else role to bounded
            }.asReversed()
        }
    }
}

internal class LiveContextStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("live_context", Context.MODE_PRIVATE))
    data class Record(val epoch: Long, val snapshot: LiveContextSnapshot)

    companion object {
        // Serializes separate Activity, voice-service and agent-service instances; holds no Context.
        private val lock = Any()

        fun records(event: AgentEvent): Boolean = when (event) {
            is TaskStarted, is ThoughtUpdate, is AskUser, is UserResponded, is TaskCompleted,
            is SessionError, is SessionCompleted, is SupplementReceived -> true
            else -> false
        }
    }

    fun begin(chatId: String?): Record = synchronized(lock) {
        var epoch = prefs.getLong("epoch", 0)
        val saved = LiveContextSnapshot.decode(prefs.getString("snapshot", null))
        val compatible = saved != null && (chatId == null || saved.chatId == null || saved.chatId == chatId)
        if (saved != null && !compatible) epoch++
        val snapshot = if (compatible) saved.copy(chatId = chatId ?: saved.chatId) else LiveContextSnapshot(chatId = chatId)
        prefs.edit().putLong("epoch", epoch).putString("snapshot", snapshot.encode()).apply()
        Record(epoch, snapshot)
    }

    fun load(): LiveContextSnapshot? = synchronized(lock) {
        LiveContextSnapshot.decode(prefs.getString("snapshot", null))
    }

    fun saveVoice(epoch: Long, brief: String, turns: List<Pair<String, String>>) = synchronized(lock) {
        if (epoch != prefs.getLong("epoch", 0)) return@synchronized
        val current = load() ?: return@synchronized
        prefs.edit().putString("snapshot", current.copy(brief = brief.take(3000), turns = LiveContextSnapshot.boundedTurns(turns)).encode()).apply()
    }

    fun attachChat(epoch: Long, chatId: String) = synchronized(lock) {
        if (epoch != prefs.getLong("epoch", 0)) return@synchronized
        val current = load() ?: return@synchronized
        prefs.edit().putString("snapshot", current.copy(chatId = chatId).encode()).apply()
    }

    /** Worker updates remain current even after voice is hung up. */
    fun recordAgentEvent(event: AgentEvent) = synchronized(lock) {
        if (!records(event)) return@synchronized
        val current = load()?.takeIf { it.chatId == event.sessionId.value } ?: return@synchronized
        val next = when (event) {
            is TaskStarted -> current.copy(taskRequest = event.input.substringBefore("\n\nVoice conversation context:").take(2000), taskUpdate = "Working", question = "")
            is ThoughtUpdate -> current.copy(taskUpdate = event.compact.take(1800))
            is AskUser -> current.copy(question = event.message.take(1200))
            is UserResponded -> current.copy(question = "")
            is TaskCompleted -> current.copy(taskUpdate = "${event.outcome}: ${event.result.orEmpty()}".take(1800), question = "")
            is SessionError -> current.copy(taskUpdate = "Error: ${event.message}".take(1800), question = "")
            is SessionCompleted -> current.copy(question = "")
            is SupplementReceived -> current.copy(question = "")
            else -> return@synchronized
        }
        prefs.edit().putString("snapshot", next.encode()).apply()
    }

    fun clear() = synchronized(lock) {
        // Reject late writes from the call being torn down or a pending background save.
        prefs.edit().putLong("epoch", prefs.getLong("epoch", 0) + 1).remove("snapshot").apply()
    }
}
