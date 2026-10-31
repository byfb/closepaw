package ai.closepaw.voice

import ai.closepaw.protocol.*
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class LiveContextStoreTest {
    private fun prefs(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers { values[firstArg()] = secondArg<String?>(); editor }
        every { editor.putLong(any(), any()) } answers { values[firstArg()] = secondArg<Long>(); editor }
        every { editor.remove(any()) } answers { values.remove(firstArg<String>()); editor }
        return mockk<SharedPreferences> {
            every { edit() } returns editor
            every { getLong(any(), any()) } answers { values[firstArg()] as? Long ?: secondArg() }
            every { getString(any(), any()) } answers { values[firstArg()] as? String ?: secondArg() }
        }
    }

    @Test fun `manual reconnect restores voice context and worker result written after hangup`() {
        val prefs = prefs()
        val voice = LiveContextStore(prefs)
        val record = voice.begin(null)
        voice.saveVoice(record.epoch, "Use the family shopping list.", listOf("user" to "Find the milk."))
        voice.attachChat(record.epoch, "chat-a")
        val agent = LiveContextStore(prefs)
        agent.recordAgentEvent(TaskStarted(SessionId("chat-a"), 1, "task", "Find milk"))
        agent.recordAgentEvent(TaskCompleted(SessionId("chat-a"), 2, "task", "Milk is on the list", TaskOutcome.GOAL_ACHIEVED))
        val restored = LiveContextStore(prefs).begin("chat-a").snapshot
        assertThat(restored.brief).isEqualTo("Use the family shopping list.")
        assertThat(restored.turns).containsExactly("user" to "Find the milk.")
        assertThat(restored.taskUpdate).contains("Milk is on the list")
        assertThat(restored.prompt("Idle")).contains("Do not repeat earlier actions")
    }

    @Test fun `clearing prevents late voice or worker writes from restoring old context`() {
        val store = LiveContextStore(prefs())
        val record = store.begin("chat-a")
        store.clear()
        store.saveVoice(record.epoch, "Old context", listOf("user" to "Old request"))
        store.recordAgentEvent(TaskCompleted(SessionId("chat-a"), 1, "task", "Old result", TaskOutcome.GOAL_ACHIEVED))
        assertThat(store.load()).isNull()
        assertThat(store.begin(null).snapshot.hasContext).isFalse()
    }

    @Test fun `different chats never inherit an earlier chat's voice memory`() {
        val store = LiveContextStore(prefs())
        val old = store.begin("chat-a")
        store.saveVoice(old.epoch, "Context A", emptyList())
        val next = store.begin("chat-b")
        store.saveVoice(old.epoch, "Late A", emptyList())
        assertThat(next.snapshot.hasContext).isFalse()
        assertThat(store.load()!!.chatId).isEqualTo("chat-b")
        assertThat(store.load()!!.brief).isEmpty()
    }

    @Test fun `stale voice save cannot overwrite a newer worker update`() {
        val store = LiveContextStore(prefs())
        val record = store.begin("chat-a")
        store.recordAgentEvent(TaskCompleted(SessionId("chat-a"), 1, "task", "Finished", TaskOutcome.GOAL_ACHIEVED))
        store.saveVoice(record.epoch, "Background", listOf("assistant" to "I am working"))
        assertThat(store.load()!!.taskUpdate).contains("Finished")
    }

    @Test fun `restored conversation retains initial brief and requires fresh speech before delegation`() {
        val original = LiveConversation()
        original.receive("""{"type":"turn.done","turn":{"role":"user","transcript":"My favorite color is blue"}}""")
        val snapshot = LiveContextSnapshot(brief = original.initialBrief, turns = original.historySnapshot())
        val restored = LiveConversation().apply { restore(LiveContextSnapshot.decode(snapshot.encode())!!) }
        assertThat(restored.initialBrief).contains("blue")
        val delegation = """{"type":"delegation.created","item":{"type":"delegation","target":"client","id":"old","content":[{"type":"input_text","text":"Repeat the old action"}]}}"""
        assertThat(restored.receive(delegation)).isNull()
    }

    @Test fun `long contexts stay bounded and malformed saved data is ignored`() {
        val original = LiveContextSnapshot(brief = "b".repeat(10_000), turns = List(50) { "user" to "t".repeat(6000) })
        val restored = LiveContextSnapshot.decode(original.encode())!!
        assertThat(restored.brief.length).isEqualTo(3000)
        assertThat(restored.turns.sumOf { it.second.length }).isAtMost(8000)
        assertThat(restored.turns.size).isAtMost(12)
        assertThat(LiveContextSnapshot.decode("not json")).isNull()
    }
}
