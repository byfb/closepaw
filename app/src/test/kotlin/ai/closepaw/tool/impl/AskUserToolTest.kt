package ai.closepaw.tool.impl

import com.google.common.truth.Truth.assertThat
import ai.closepaw.agent.AgentEventDispatcher
import ai.closepaw.protocol.SessionId
import ai.closepaw.protocol.AskUser
import ai.closepaw.protocol.AskUserType
import ai.closepaw.session.UserResponseChannel
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ValidationResult
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import ai.closepaw.session.TaskQuestionRouting
import ai.closepaw.session.UnansweredVoiceQuestion
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.json.JSONObject
import org.junit.Test

class AskUserToolTest {

    private fun buildContext(callId: String? = "call-1"): ToolExecutionContext {
        val cid = callId
        return object : ToolExecutionContext {
            override val callId: String? = cid
            override val platform = FakeAndroidPlatform()
            override val currentSnapshot = null
            override fun isCancelled(): Boolean = false
        }
    }

    private fun buildDispatcher(): AgentEventDispatcher =
        AgentEventDispatcher(SessionId("test-session")) { /* no-op emitter */ }

    private fun validParams() = JSONObject()
        .put("type", "question")
        .put("message", "What is your name?")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `voice question accepts a spoken reply within the deadline`() = runTest {
        val channel = UserResponseChannel()
        val dispatcher = AgentEventDispatcher(SessionId("test-session")) { event ->
            val ask = event as AskUser
            assertThat(ask.type).isEqualTo(AskUserType.VOICE_QUESTION)
            assertThat(channel.pendingPrompt).isEqualTo(UserResponseChannel.Prompt(ask.callId, ask.type, ask.message))
        }
        val tool = AskUserTool(channel, dispatcher, byVoice = true)
        assertThat(tool.name).isEqualTo("ask_by_voice")
        val result = async { tool.createInvocation(validParams()).execute(buildContext()) }
        advanceTimeBy(39_000)
        assertThat(result.isCompleted).isFalse()
        channel.deliver("call-1", "Alice")
        runCurrent()
        assertThat((result.await() as ToolExecutionResult.Success).output).isEqualTo("User answered: Alice")
        assertThat(channel.pendingPrompt).isNull()
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `switching off screen questions moves the pending voice-origin question to speech`() = runTest {
        val setting = MutableStateFlow(true)
        val routing = TaskQuestionRouting({ setting.value }, setting).apply { fromLiveVoice = true }
        val channel = UserResponseChannel()
        val events = mutableListOf<AskUserType>()
        val dispatcher = AgentEventDispatcher(SessionId("voice")) { events += (it as AskUser).type }
        val tool = AskUserTool(channel, dispatcher, routing = routing)
        val result = async { tool.createInvocation(validParams()).execute(buildContext()) }
        runCurrent()
        assertThat(channel.pendingPrompt?.type).isEqualTo(AskUserType.QUESTION)
        setting.value = false
        runCurrent()
        assertThat(channel.pendingPrompt?.type).isEqualTo(AskUserType.VOICE_QUESTION)
        assertThat(events).containsExactly(AskUserType.QUESTION, AskUserType.VOICE_QUESTION).inOrder()
        assertThat(channel.deliver("call-1", "Alice")).isTrue()
        assertThat((result.await() as ToolExecutionResult.Success).output).contains("Alice")
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `physical handoffs are withdrawn for voice tasks and kept for text tasks`() = runTest {
        val setting = MutableStateFlow(true)
        val routing = TaskQuestionRouting({ setting.value }, setting).apply { fromLiveVoice = true }
        val channel = UserResponseChannel()
        val tool = AskUserTool(channel, buildDispatcher(), routing = routing)
        val params = validParams().put("type", "action")
        val pending = async { tool.createInvocation(params).execute(buildContext()) }
        runCurrent()
        setting.value = false
        assertThat(pending.await()).isInstanceOf(ToolExecutionResult.Failure::class.java)
        assertThat(channel.hasPending).isFalse()
        assertThat(tool.validate(params)).isInstanceOf(ValidationResult.Invalid::class.java)

        routing.fromLiveVoice = false
        assertThat(tool.validate(params)).isEqualTo(ValidationResult.Valid)
        val text = async { tool.createInvocation(params).execute(buildContext()) }
        runCurrent()
        assertThat(channel.pendingPrompt?.type).isEqualTo(AskUserType.ACTION)
        channel.deliver("call-1", "done")
        assertThat(text.await()).isInstanceOf(ToolExecutionResult.Success::class.java)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `unanswered spoken question emits one reminder and expires with the question`() = runTest {
        val channel = UserResponseChannel()
        val deadlines = mutableListOf<UserResponseChannel.VoiceDeadline>()
        backgroundScope.launch { channel.voiceDeadlines.collect { deadlines += it } }
        val tool = AskUserTool(channel, buildDispatcher(), byVoice = true)
        val result = async { runCatching { tool.createInvocation(validParams()).execute(buildContext()) } }
        advanceTimeBy(40_000)
        runCurrent()
        assertThat(deadlines.map { it.expired }).containsExactly(false)
        assertThat(channel.hasPending).isTrue()
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(deadlines.map { it.expired }).containsExactly(false, true).inOrder()
        val error = result.await().exceptionOrNull()
        assertThat(error).isInstanceOf(UnansweredVoiceQuestion::class.java)
        assertThat(error?.message).contains("What is your name?")
        assertThat(channel.hasPending).isFalse()
    }

    @Test
    fun `second ask_user request rejected while first is pending`() = runTest {
        val channel = UserResponseChannel()
        val tool = AskUserTool(channel, buildDispatcher())

        // First invocation: start and let it suspend.
        val first = async {
            tool.createInvocation(validParams()).execute(buildContext("call-1"))
        }
        // Yield until the first coroutine has registered as pending.
        while (!channel.hasPending) delay(1)

        // Second validate should reject due to pending.
        val secondValidation = tool.validate(validParams())
        assertThat(secondValidation).isInstanceOf(ValidationResult.Invalid::class.java)
        assertThat((secondValidation as ValidationResult.Invalid).errors.joinToString())
            .contains("pending")

        // Clean up first request.
        channel.cancel()
        first.await()
    }

    @Test
    fun `timeout produces formatted timeout output`() = runTest {
        val channel = UserResponseChannel()
        val tool = AskUserTool(channel, buildDispatcher())

        val invocation = tool.createInvocation(validParams())
        val result = invocation.execute(buildContext("call-timeout"))

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val output = (result as ToolExecutionResult.Success).output
        assertThat(output).contains("did not respond")
        assertThat(output).contains("timeout")
        assertThat(channel.hasPending).isFalse()
    }

    @Test
    fun `cancellation maps to Cancelled result`() = runTest {
        val channel = UserResponseChannel()
        val tool = AskUserTool(channel, buildDispatcher())

        val deferred = async {
            tool.createInvocation(validParams()).execute(buildContext("call-cancel"))
        }
        while (!channel.hasPending) delay(1)

        channel.cancel()
        val result = deferred.await()

        assertThat(result).isInstanceOf(ToolExecutionResult.Cancelled::class.java)
        assertThat((result as ToolExecutionResult.Cancelled).reason).contains("ask_user")
    }

    @Test
    fun `successful user response forwarded as tool output`() = runTest {
        val channel = UserResponseChannel()
        val tool = AskUserTool(channel, buildDispatcher())

        val deferred = async {
            tool.createInvocation(validParams()).execute(buildContext("call-ok"))
        }
        while (!channel.hasPending) delay(1)

        assertThat(channel.deliver("call-ok", "Alice")).isTrue()
        val result = deferred.await()

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val output = (result as ToolExecutionResult.Success).output
        assertThat(output).contains("Alice")
        assertThat(output).contains("User answered")
    }

    @Test
    fun `successful action response signals completion`() = runTest {
        val channel = UserResponseChannel()
        val tool = AskUserTool(channel, buildDispatcher())
        val params = JSONObject()
            .put("type", "action")
            .put("message", "Please complete captcha")

        val deferred = async {
            tool.createInvocation(params).execute(buildContext("call-action"))
        }
        while (!channel.hasPending) delay(1)

        channel.deliver("call-action", "done")
        val result = deferred.await()

        assertThat(result).isInstanceOf(ToolExecutionResult.Success::class.java)
        val output = (result as ToolExecutionResult.Success).output
        assertThat(output).contains("completed the requested action")
    }
}
