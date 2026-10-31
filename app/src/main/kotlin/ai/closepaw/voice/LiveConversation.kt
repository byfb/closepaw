package ai.closepaw.voice

import kotlinx.serialization.json.*

/** The Codex GPT-Live v3 wire adapter. Kept separate from Android and task execution. */
internal class LiveConversation {
    data class Delegation(val id: String, val text: String)
    private val handled = linkedSetOf<String>()
    private val history = ArrayDeque<Pair<String, String>>()
    private var awaitingFreshUser = false
    private var pendingUser = ""
    private var pendingAssistant = ""
    var initialBrief: String = ""
        private set
    var transcript: String = ""
        private set

    fun receive(raw: String): Delegation? {
        val event = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
        when (event.string("type")) {
            "input_transcript.added" -> {
                if (!event.obj("item")?.string("text").isNullOrBlank()) awaitingFreshUser = false
                pendingUser += event.obj("item")?.string("text").orEmpty()
                pendingUser = pendingUser.takeLast(6000)
                transcript = pendingUser
            }
            "output_transcript.added" -> {
                pendingAssistant += event.obj("item")?.string("text").orEmpty()
                pendingAssistant = pendingAssistant.takeLast(6000)
                transcript = pendingAssistant
            }
            "turn.done" -> {
                val turn = event.obj("turn") ?: return null
                val role = turn.string("role") ?: return null
                val text = turn.string("transcript").orEmpty().take(6000)
                if (role == "user" && text.isNotBlank()) {
                    awaitingFreshUser = false
                    if (initialBrief.isBlank()) initialBrief = text.take(3000)
                }
                if (role == "user" || role == "assistant") {
                    if (text.isNotBlank()) history.addLast(role to text)
                    while (history.size > 12) history.removeFirst()
                    if (role == "user") pendingUser = "" else pendingAssistant = ""
                    transcript = text
                }
            }
            "delegation.created" -> {
                if (awaitingFreshUser) return null
                val item = event.obj("item") ?: return null
                if (item.string("type") != "delegation" || item.string("target") != "client") return null
                val id = item.string("id")?.takeIf { it.isNotBlank() } ?: return null
                if (!handled.add(id)) return null
                // Bound memory without allowing the server to replay a recent task.
                if (handled.size > 512) handled.remove(handled.first())
                val request = (item["content"] as? JsonArray).orEmpty().mapNotNull {
                    (it as? JsonObject)?.takeIf { part -> part.string("type") == "input_text" }?.string("text")
                }.joinToString("").trim().take(12000)
                val context = buildList {
                    history.forEach { (role, text) -> add("$role: $text") }
                    if (pendingUser.isNotBlank()) add("user: $pendingUser")
                    if (pendingAssistant.isNotBlank()) add("assistant: $pendingAssistant")
                }.joinToString("\n").takeLast(12000)
                // Metadata alone must never start an empty or guessed task.
                if (request.isBlank()) return Delegation(id, "")
                return Delegation(id, buildString {
                    append(request)
                    if (context.isNotBlank()) append("\n\nVoice conversation context:\n$context")
                })
            }
        }
        return null
    }

    fun historySnapshot(): List<Pair<String, String>> = LiveContextSnapshot.boundedTurns(history.toList())

    fun restore(snapshot: LiveContextSnapshot) {
        initialBrief = snapshot.brief
        history.clear()
        history.addAll(snapshot.turns)
        awaitingFreshUser = snapshot.hasContext
    }

    fun reconnectContext(): String {
        awaitingFreshUser = true
        pendingUser = ""
        pendingAssistant = ""
        return buildString {
            append("Voice reconnected. Earlier requests are context only; do not repeat them. Wait for a new user request before delegating.\n")
            append(history.joinToString("\n") { (role, text) -> "$role: $text" }.takeLast(8000))
        }
    }

    fun append(delegationId: String?, text: String, spoken: Boolean): List<String> =
        utf8Chunks(text, 500).map { chunk ->
            buildJsonObject {
                put("type", if (delegationId == null) "session.context.append" else "delegation.context.append")
                if (delegationId != null) put("delegation_item_id", delegationId)
                // Codex v3 uses commentary for quiet context, speakable for voiced results.
                put("channel", if (spoken) "speakable" else "commentary")
                putJsonArray("content") { addJsonObject { put("type", "input_text"); put("text", chunk) } }
            }.toString()
        }

    private fun utf8Chunks(text: String, limit: Int): List<String> {
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val cp = Character.codePointAt(text, index)
            val value = String(Character.toChars(cp))
            val length = value.toByteArray(Charsets.UTF_8).size
            if (bytes + length > limit) {
                chunks += current.toString(); current = StringBuilder(); bytes = 0
            }
            current.append(value); bytes += length; index += Character.charCount(cp)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
