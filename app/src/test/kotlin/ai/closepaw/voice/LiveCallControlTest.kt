package ai.closepaw.voice

import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.LLMToolCall
import ai.closepaw.tool.impl.EndGptLiveCallTool
import ai.closepaw.protocol.AskUserType
import ai.closepaw.session.UserResponseChannel
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
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

    @Test fun `only the final spoken answer resumes the waiting worker without a delegation`() = runTest {
        val channel = UserResponseChannel()
        val spoken = mutableListOf<String>()
        val questions = LiveVoiceQuestions({ channel.pendingPrompt }, { spoken.add(it) },
            { prompt, answer -> assertThat(channel.deliver(prompt.callId, answer)).isTrue() })
        val prompt = UserResponseChannel.Prompt("q1", AskUserType.VOICE_QUESTION, "Which station?")
        val result = async { channel.awaitResponse(prompt, questions::announce) }
        runCurrent()
        questions.announce()
        assertThat(spoken).hasSize(1)
        assertThat(spoken.single()).contains(prompt.message)
        val control = LiveCallControl(this, { LiveToolSelection() }, { error("unexpected hangup") }, {},
            captureVoiceAnswer = questions::captureAnswer)
        val handoff = control.handoffAllowed
        control.receive(done("assistant", "Which station?"))
        control.receive(partial("Central"))
        runCurrent()
        assertThat(result.isCompleted).isFalse()
        control.receive(done("user", "Central Station"))
        runCurrent()
        assertThat(result.await()).isEqualTo("Central Station")
        assertThat(handoff.await()).isFalse()
        assertThat(control.handoffAllowed.await()).isFalse()
        assertThat(questions.waiting).isFalse()
    }

    @Test fun `spoken approval rejects ambiguity and accepts a direct reply without waiting for call control`() = runTest {
        val prompt = UserResponseChannel.Prompt("a", AskUserType.VOICE_QUESTION, "Approve opening Maps?", approval = true)
        val spoken = mutableListOf<String>()
        val replies = mutableListOf<String>()
        val questions = LiveVoiceQuestions({ prompt }, { spoken.add(it) }, { _, answer -> replies += answer })
        questions.announce()
        questions.captureAnswer()!!.invoke("Maybe")
        assertThat(replies).isEmpty()
        assertThat(spoken.last()).contains("approve or deny")
        val control = LiveCallControl(this, { error("unexpected tool selection") }, { error("unexpected hangup") }, {},
            captureVoiceAnswer = questions::captureAnswer)
        control.receive(done("user", "同意。"))
        assertThat(replies).containsExactly("同意。")
        assertThat(control.handoffAllowed.await()).isFalse()
    }

    @Test fun `reconnection reasks only an outstanding voice question and rejects stale speech`() = runTest {
        var pending: UserResponseChannel.Prompt? = null
        var connected = false
        val spoken = mutableListOf<String>()
        val answers = mutableListOf<String>()
        val questions = LiveVoiceQuestions({ pending }, { connected && spoken.add(it) }, { _, text -> answers += text })
        val first = UserResponseChannel.Prompt("1", AskUserType.VOICE_QUESTION, "First question")
        pending = first
        questions.announce()
        assertThat(questions.captureAnswer()).isNull()
        connected = true
        questions.announce()
        val stale = requireNotNull(questions.captureAnswer())
        questions.reset()
        questions.announce()
        assertThat(spoken).hasSize(2)
        pending = first.copy(callId = "2", message = "Next question")
        questions.announce()
        stale("Old reply")
        requireNotNull(questions.captureAnswer())("Fresh reply")
        assertThat(answers).containsExactly("Fresh reply")
        questions.reset()
        for (type in listOf(AskUserType.QUESTION, AskUserType.ACTION)) {
            pending = first.copy(type = type)
            questions.announce()
            assertThat(questions.captureAnswer()).isNull()
        }
        pending = null
        questions.announce()
        assertThat(spoken).hasSize(3)
    }

    @Test fun `hangup while answering leaves the worker question pending`() = runTest {
        var ended = false
        val prompt = UserResponseChannel.Prompt("q", AskUserType.VOICE_QUESTION, "Which station?")
        val control = LiveCallControl(this, { LiveToolSelection(endCall = JSONObject()) }, { ended = true }, {},
            captureVoiceAnswer = { LiveVoiceQuestions.Answer(prompt) { error("Hangup is not an answer") } })
        control.receive(done("user", "End this call"))
        runCurrent()
        assertThat(ended).isTrue()
        assertThat(control.handoffAllowed.await()).isFalse()
    }

    @Test fun `transcript received before the deadline counts while call control finishes`() = runTest {
        val channel = UserResponseChannel()
        val prompt = UserResponseChannel.Prompt("q", AskUserType.VOICE_QUESTION, "Which station?")
        val questions = LiveVoiceQuestions({ channel.pendingPrompt }, { true },
            { request, answer -> channel.deliver(request.callId, answer) })
        val result = async { channel.awaitSpoken(prompt, questions::announce) }
        val control = LiveCallControl(this, { kotlinx.coroutines.delay(4_000); LiveToolSelection() }, {}, {},
            captureVoiceAnswer = questions::captureAnswer, processingVoiceReply = channel::processingSpokenReply)
        advanceTimeBy(59_000)
        control.receive(done("user", "Central Station"))
        assertThat(result.await()).isEqualTo("Central Station")
    }

    @Test fun `a silent connection gets one repeat and reconnects only while the question is unheard`() {
        val pending = UserResponseChannel.Prompt("q", AskUserType.VOICE_QUESTION, "Which station?")
        var now = 0L
        var spoken = 0
        var reconnects = 0
        val questions = LiveVoiceQuestions({ pending }, { spoken++; true }, { _, _ -> }, now = { now })
        questions.announce()
        now += 10_000
        questions.checkPlayback { reconnects++ }
        questions.checkPlayback { reconnects++ }
        assertThat(spoken).isEqualTo(2)
        assertThat(reconnects).isEqualTo(0)
        now += 12_000
        questions.checkPlayback { reconnects++ }
        questions.checkPlayback { reconnects++ }
        assertThat(reconnects).isEqualTo(1)
        questions.reset()
        questions.announce()
        questions.heardSpeech()
        now += 30_000
        questions.checkPlayback { reconnects++ }
        assertThat(spoken).isEqualTo(3)
        assertThat(reconnects).isEqualTo(1)
    }

    @Test fun `only final user speech goes to the model and the handoff waits for its decision`() = runTest {
        val response = CompletableDeferred<LiveToolSelection>()
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
        response.complete(LiveToolSelection(endCall = JSONObject().put("call_id", "current")))
        runCurrent()
        assertThat(handoff.await()).isFalse()
        assertThat(ended).isTrue()
    }

    @Test fun `an earlier hangup in a phone task handoff cannot control the current call`() = runTest {
        val inputs = mutableListOf<String>()
        val control = LiveCallControl(this, { inputs += it; LiveToolSelection() }, { error("unexpected hangup") }, {})
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
        val response = CompletableDeferred<LiveToolSelection>()
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
        response.complete(LiveToolSelection(endCall = JSONObject().put("call_id", "current")))
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

    @Test fun `memory is committed before releasing the phone task and never ends the call`() = runTest {
        val committed = CompletableDeferred<Unit>()
        val update = JSONObject().put("scope", "user")
        val control = LiveCallControl(this,
            { LiveToolSelection(memoryUpdates = listOf(update)) },
            { error("unexpected hangup") }, {},
            executeMemory = { assertThat(it).containsExactly(update); committed.await(); true })
        control.receive(done("user", "以後用繁體中文"))
        runCurrent()
        assertThat(control.handoffAllowed.isCompleted).isFalse()
        committed.complete(Unit)
        runCurrent()
        assertThat(control.handoffAllowed.await()).isTrue()
    }

    @Test fun `successful memory-only turn consumes duplicate handoff but a failed write allows fallback`() = runTest {
        var saved = true
        val control = LiveCallControl(this,
            { LiveToolSelection(memoryUpdates = listOf(JSONObject()), memoryHandlesTurn = true) },
            { error("unexpected hangup") }, {}, executeMemory = { saved })
        control.receive(done("user", "請忘記我的水果偏好"))
        val handled = control.handoffAllowed
        runCurrent()
        assertThat(handled.await()).isFalse()
        assertThat(control.handledMemory(handled)).isTrue()
        control.reset()
        assertThat(control.handledMemory(handled)).isFalse()
        saved = false
        control.receive(done("user", "請重新保存偏好"))
        runCurrent()
        assertThat(control.handoffAllowed.await()).isTrue()
        assertThat(control.handledMemory(control.handoffAllowed)).isFalse()
    }
}
