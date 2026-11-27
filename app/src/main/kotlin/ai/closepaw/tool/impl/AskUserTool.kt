package ai.closepaw.tool.impl

import ai.closepaw.agent.AgentEventDispatcher
import ai.closepaw.protocol.AskUserType
import ai.closepaw.session.TaskQuestionRouting
import ai.closepaw.session.UserResponseChannel
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolName
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * AskUserTool — agent asks the user for help.
 *
 * Two types:
 * - **question**: The selected tool asks through the capsule or GPT Live.
 * - **action**: Agent needs user to operate the phone. Capsule shows instruction.
 *
 * Spoken questions allow one minute for a reply. On-screen requests time out after five minutes.
 */
class AskUserTool(
    private val responseChannel: UserResponseChannel,
    private val eventDispatcher: AgentEventDispatcher,
    private val byVoice: Boolean = false,
    private val routing: TaskQuestionRouting = TaskQuestionRouting(),
) : ToolSpec {

    companion object {
        internal const val TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }

    override val name = if (byVoice) ToolName.AskByVoice.raw else ToolName.AskUser.raw

    override val description = if (byVoice)
        "Ask aloud for missing information or a decision. The user answers by voice. Operate the phone yourself; report a physical-only blocker in the task result."
    else "Ask the user on screen for missing information (question) or physical intervention (action). The runtime handles app approval."

    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("agent_thought", JSONObject().apply {
                put("type", "string")
                put("description", "Brief reason for asking the user")
            })
            put("type", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(if (byVoice) listOf("question") else listOf("question", "action")))
                put("description", if (byVoice) "A question answered by speech." else "Use question for information or action for a physical step.")
            })
            put("message", JSONObject().apply {
                put("type", "string")
                put("description", "The question to ask or instruction for the user. Be clear and specific.")
            })
        })
        put("required", JSONArray(listOf("type", "message")))
        put("additionalProperties", false)
    }

    override fun validate(params: JSONObject): ValidationResult {
        val type = params.optString("type", "")
        if ((byVoice || routing.handsFree) && type == "action") return ValidationResult.Invalid(HANDS_FREE_ACTION)
        if (type !in listOf("question", "action"))
            return ValidationResult.Invalid("type must be 'question' or 'action'")
        if (params.optString("message", "").isBlank())
            return ValidationResult.Invalid("message is required")
        if (responseChannel.hasPending)
            return ValidationResult.Invalid("Another user request is already pending")
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        return AskUserInvocation(
            params = params,
            responseChannel = responseChannel,
            eventDispatcher = eventDispatcher,
            byVoice = byVoice,
            routing = routing,
            toolName = name,
        )
    }
}

/**
 * Invocation for ask_user — suspends until user responds or timeout.
 */
private class AskUserInvocation(
    override val params: JSONObject,
    private val responseChannel: UserResponseChannel,
    private val eventDispatcher: AgentEventDispatcher,
    private val byVoice: Boolean,
    private val routing: TaskQuestionRouting,
    override val toolName: String,
) : ToolInvocation {

    override fun getDescription(): String {
        val thought = params.optString("agent_thought", "").trim()
        val message = params.optString("message", "")
        return if (thought.isNotEmpty()) thought else "Asking user: ${message.take(40)}"
    }

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        return try {
            awaitAnswer(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ToolExecutionResult.Cancelled("$toolName cancelled")
        }
    }

    private suspend fun awaitAnswer(context: ToolExecutionContext): ToolExecutionResult {
        val action = params.optString("type") == "action"
        val message = params.getString("message")
        val callId = context.callId ?: java.util.UUID.randomUUID().toString()

        if (!byVoice) {
            val screenResult = routing.onScreen {
                val type = if (action) AskUserType.ACTION else AskUserType.QUESTION
                val response = withTimeoutOrNull(AskUserTool.TIMEOUT_MS) {
                    responseChannel.awaitResponse(UserResponseChannel.Prompt(callId, type, message)) {
                        eventDispatcher.emitAskUser(type, message, callId)
                    }
                }
                ToolExecutionResult.Success(when {
                    response == null -> "User did not respond within the timeout. Try another approach if available."
                    action -> "User completed the requested action. Capture fresh screen to see result."
                    else -> "User answered: $response"
                })
            }
            if (screenResult != null) return screenResult
        }

        if (action) return ToolExecutionResult.Failure(HANDS_FREE_ACTION)
        val prompt = UserResponseChannel.Prompt(callId, AskUserType.VOICE_QUESTION, message)
        val reply = responseChannel.awaitSpoken(prompt) { eventDispatcher.emitAskUser(prompt.type, message, callId) }
        return ai.closepaw.tool.textToolSuccess("User answered: $reply")
    }
}

private const val HANDS_FREE_ACTION = "Hands-free task: operate the phone yourself. Ask aloud for a decision or report the physical-only blocker."
