package ai.closepaw.voice

import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.LLMToolCall
import ai.closepaw.tool.impl.EndGptLiveCallTool
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveCallControlTest {
    private fun done(role: String, text: String) = buildJsonObject {
        put("type", "turn.done")
        putJsonObject("turn") { put("role", role); put("transcript", text) }
    }

    private fun partial(text: String) = buildJsonObject {
        put("type", "input_transcript.added")
        putJsonObject("item") { put("text", text) }
    }

    @Test fun `only final user speech goes to the model and the handoff waits for its decision`() = runTest {
        val response = CompletableDeferred<JSONObject?>()
        val inputs = mutableListOf<String>()
        var ended = false
        val control = LiveCallControl(this, { inputs += it; response.await() }, { ended = true }, { error("unexpected") })
        val handoff = control.handoffAllowed
        control.receive(partial("掛斷"))
        control.receive(done("assistant", "已經掛斷了"))
        runCurrent()
        assertThat(inputs).isEmpty()
        control.receive(done("user", "我要掛斷的是跟你的對話"))
        runCurrent()
        assertThat(inputs).containsExactly("我要掛斷的是跟你的對話")
        assertThat(handoff.isCompleted).isFalse()
        assertThat(ended).isFalse()
        response.complete(JSONObject().put("call_id", "current"))
        runCurrent()
        assertThat(handoff.await()).isFalse()
        assertThat(ended).isTrue()
    }

    @Test fun `an earlier hangup in a phone task handoff cannot control the current call`() = runTest {
        val inputs = mutableListOf<String>()
        val control = LiveCallControl(this, { inputs += it; null }, { error("unexpected hangup") }, {})
        control.receive(done("user", "打開手機設定"))
        control.receive(buildJsonObject {
            put("type", "delegation.created")
            putJsonObject("item") {
                put("type", "delegation"); put("target", "client"); put("id", "task")
                putJsonArray("content") { addJsonObject {
                    put("type", "input_text"); put("text", "打開手機設定\nVoice conversation context:\nuser: 掛斷吧")
                } }
            }
        })
        runCurrent()
        assertThat(control.handoffAllowed.await()).isTrue()
        assertThat(inputs).containsExactly("打開手機設定")
    }

    @Test fun `barge-in and disconnect invalidate a late decision`() = runTest {
        val response = CompletableDeferred<JSONObject?>()
        var executions = 0
        val control = LiveCallControl(this, { response.await() }, { executions++ }, {})
        control.receive(done("user", "掛斷吧"))
        val oldHandoff = control.handoffAllowed
        runCurrent()
        control.receive(partial("等等"))
        assertThat(oldHandoff.await()).isFalse()
        control.receive(done("user", "不要掛斷"))
        val nextHandoff = control.handoffAllowed
        runCurrent()
        control.reset()
        response.complete(JSONObject().put("call_id", "current"))
        advanceUntilIdle()
        assertThat(nextHandoff.await()).isFalse()
        assertThat(executions).isEqualTo(0)
    }

    @Test fun `timeout releases ordinary handoffs and leaves voice open`() = runTest {
        var unavailable = 0
        val control = LiveCallControl(this, { awaitCancellation() }, { error("unexpected") }, { unavailable++ })
        control.receive(done("user", "幫我開設定"))
        advanceUntilIdle()
        assertThat(control.handoffAllowed.await()).isTrue()
        assertThat(unavailable).isEqualTo(1)
    }

    @Test fun `only a completed native call for this call ID can select hangup`() = runTest {
        val client = mockk<LLMClient>()
        val tool = EndGptLiveCallTool("current") { error("select only") }
        val selector = LiveCallToolSelector(client, "test-model", tool)
        val call = LLMToolCall("call-1", "end_gpt_live_call", """{"call_id":"current"}""")
        suspend fun selects(vararg events: LLMStreamEvent): Boolean {
            every { client.chatWithToolsStreaming(any(), any(), any(), any()) } returns flowOf(*events)
            return selector.select("掛斷吧") != null
        }
        val invoked = LLMStreamEvent.ToolCallDone(call)
        val completed = LLMStreamEvent.Completed
        assertThat(selects(LLMStreamEvent.TextDelta("end_gpt_live_call {}"), completed)).isFalse()
        assertThat(selects(LLMStreamEvent.ToolCallDone(call.copy(arguments = "oops")), completed)).isFalse()
        assertThat(selects(LLMStreamEvent.ToolCallDone(call.copy(arguments = """{"target":"other"}""")), completed)).isFalse()
        assertThat(selects(LLMStreamEvent.ToolCallDone(call.copy(name = "some_other_tool")), completed)).isFalse()
        assertThat(selects(LLMStreamEvent.ToolCallDone(call.copy(arguments = """{"call_id":"old"}""")), completed)).isFalse()
        assertThat(selects(LLMStreamEvent.ToolCallDone(call.copy(arguments = "{}")), completed)).isFalse()
        assertThat(selects(invoked, LLMStreamEvent.Created("retry"), completed)).isFalse()
        assertThat(runCatching { selects(invoked) }.isFailure).isTrue()
        assertThat(runCatching { selects(invoked, LLMStreamEvent.Failed("offline")) }.isFailure).isTrue()
        assertThat(selects(invoked, completed)).isTrue()
    }
}
