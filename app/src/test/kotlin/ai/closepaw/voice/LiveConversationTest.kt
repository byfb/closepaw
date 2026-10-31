package ai.closepaw.voice

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.*
import org.junit.Test

class LiveConversationTest {
    private fun delegation(id: String, text: String, target: String = "client") = buildJsonObject {
        put("type", "delegation.created")
        putJsonObject("item") {
            put("type", "delegation"); put("target", target); put("id", id)
            putJsonArray("content") { addJsonObject { put("type", "input_text"); put("text", text) } }
        }
    }.toString()

    @Test fun `speech alone never executes and explicit delegation executes only once`() {
        val live = LiveConversation()
        assertThat(live.receive("""{"type":"input_transcript.added","item":{"text":"Open Settings"}}""")).isNull()
        assertThat(live.transcript).isEqualTo("Open Settings")
        val event = delegation("work1", "Open Settings on this phone")
        val work = live.receive(event)!!
        assertThat(work.id).isEqualTo("work1")
        assertThat(work.text).startsWith("Open Settings on this phone")
        assertThat(work.text).contains("user: Open Settings")
        assertThat(live.receive(event)).isNull()
    }

    @Test fun `turn completion replaces partial history without duplicating it`() {
        val live = LiveConversation()
        live.receive("""{"type":"input_transcript.added","item":{"text":"Open"}}""")
        live.receive("""{"type":"input_transcript.added","item":{"text":" Settings"}}""")
        live.receive("""{"type":"turn.done","turn":{"role":"user","transcript":"Open Settings"}}""")
        val work = live.receive(delegation("work", "Do it"))!!
        assertThat(work.text.split("user: Open Settings")).hasSize(2)
    }

    @Test fun `reconnection restores context without allowing old requests to be replayed`() {
        val live = LiveConversation()
        live.receive("""{"type":"turn.done","turn":{"role":"user","transcript":"Open Settings"}}""")
        live.receive(delegation("done", "Open Settings"))
        assertThat(live.reconnectContext()).contains("user: Open Settings")
        assertThat(live.receive(delegation("new-id", "Open Settings"))).isNull()
        live.receive("""{"type":"turn.done","turn":{"role":"user","transcript":"Read this page"}}""")
        assertThat(live.receive(delegation("done", "Open Settings"))).isNull()
        assertThat(live.receive(delegation("next", "Read this page"))!!.text).startsWith("Read this page")
    }

    @Test fun `malformed and foreign events cannot create tasks`() {
        val live = LiveConversation()
        listOf("not json", "[]", "null", "{}", """{"type":{}}""",
            """{"type":"delegation.created","item":[]} """,
            delegation("", "do it"), delegation("foreign", "do it", "responses")
        ).forEach { assertThat(live.receive(it)).isNull() }
    }

    @Test fun `missing task text is reported as empty instead of guessing from old speech`() {
        val live = LiveConversation()
        live.receive("""{"type":"turn.done","turn":{"role":"user","transcript":"Delete a file"}}""")
        assertThat(live.receive(delegation("empty", ""))!!.text).isEmpty()
    }

    @Test fun `feedback preserves delegation and splits unicode at byte boundary`() {
        val live = LiveConversation()
        val text = "已完成 📱".repeat(100)
        val chunks = live.append("task_1", text, spoken = true).map { Json.parseToJsonElement(it).jsonObject }
        val rebuilt = chunks.joinToString("") {
            assertThat(it.string("type")).isEqualTo("delegation.context.append")
            assertThat(it.string("delegation_item_id")).isEqualTo("task_1")
            assertThat(it.string("channel")).isEqualTo("speakable")
            val content = it["content"]!!.jsonArray.single().jsonObject.string("text")!!
            assertThat(content.toByteArray().size).isAtMost(500)
            content
        }
        assertThat(rebuilt).isEqualTo(text)
        val quiet = Json.parseToJsonElement(live.append(null, "Still running", spoken = false).single()).jsonObject
        assertThat(quiet.string("type")).isEqualTo("session.context.append")
        assertThat(quiet.string("channel")).isEqualTo("commentary")
        assertThat(quiet.containsKey("delegation_item_id")).isFalse()
    }
}
