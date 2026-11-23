package ai.closepaw.tool.impl

import ai.closepaw.agent.AgentEventDispatcher
import ai.closepaw.protocol.AskUserType
import ai.closepaw.session.UserResponseChannel
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolName
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import kotlinx.coroutines.CancellationException
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
 * Spoken questions wait until answered or cancelled. On-screen requests time out after five minutes.
 */
class AskUserTool(
    private val responseChannel: UserResponseChannel,
    private val eventDispatcher: AgentEventDispatcher,
    private val byVoice: Boolean = false,
) : ToolSpec {

    companion object {
        internal const val TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }

    override val name = if (byVoice) ToolName.AskByVoice.raw else ToolName.AskUser.raw

    override val description = """
Ask the user for help. Two types:
- question: ${if (byVoice) "GPT Live will ask aloud and wait for the user's spoken answer." else "text answer."}
- action: physical intervention (CAPTCHA, biometric, login).

Use only when truly blocked by missing info or required physical action. The system handles per-app approval automatically — do not duplicate it.
""".trimIndent()

    override val parameterSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("agent_thought", JSONObject().apply {
                put("type", "string")
                put("description", "Brief reason for asking the user")
            })
            put("type", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("question", "action")))
                put("description", "Use question for missing information or action for a physical step the user must complete.")
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
    override val toolName: String,
) : ToolInvocation {

    override fun getDescription(): String {
        val thought = params.optString("agent_thought", "").trim()
        val message = params.optString("message", "")
        return if (thought.isNotEmpty()) thought else "Asking user: ${message.take(40)}"
    }

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        val typeStr = params.getString("type")
        val type = when (typeStr) {
            "question" -> if (byVoice) AskUserType.VOICE_QUESTION else AskUserType.QUESTION
            "action" -> AskUserType.ACTION
            else -> return ToolExecutionResult.Failure("Invalid $toolName type: $typeStr")
        }
        val message = params.getString("message")
        val callId = context.callId ?: java.util.UUID.randomUUID().toString()

        return try {
            suspend fun awaitReply() = responseChannel.awaitResponse(UserResponseChannel.Prompt(callId, type, message)) {
                eventDispatcher.emitAskUser(type, message, callId)
            }
            val response = if (type == AskUserType.VOICE_QUESTION) awaitReply()
                else withTimeoutOrNull(AskUserTool.TIMEOUT_MS) { awaitReply() }

            if (response != null) {
                val observation = when (type) {
                    AskUserType.QUESTION, AskUserType.VOICE_QUESTION -> "User answered: $response"
                    AskUserType.ACTION -> "User completed the requested action. Capture fresh screen to see result."
                }
                ToolExecutionResult.Success(
                    output = observation
                )
            } else {
                // Timeout
                ToolExecutionResult.Success(
                    output = "User did not respond within the timeout. Consider continuing without their input or trying a different approach."
                )
            }
        } catch (e: CancellationException) {
            ToolExecutionResult.Cancelled("$toolName cancelled")
        }
    }
}
