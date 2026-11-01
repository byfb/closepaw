package ai.closepaw.tool.impl

import ai.closepaw.tool.*
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Only the phone-side voice controller receives this tool, scoped to its active call. */
internal class EndGptLiveCallTool(
    private val callId: String = UUID.randomUUID().toString(),
    private val hangUp: suspend () -> Boolean,
) : ToolSpec {
    override val name = "end_gpt_live_call"
    override val description = "End the current ClosePaw GPT-Live voice conversation when the user wants to disconnect. " +
        "Leaves phone tasks running. Use only for this conversation, not calls in other apps."
    override val parameterSchema = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("call_id", JSONObject().put("type", "string")
            .put("enum", JSONArray().put(callId))))
        .put("required", JSONArray().put("call_id"))
        .put("additionalProperties", false)

    override fun validate(params: JSONObject): ValidationResult =
        if (params.length() == 1 && params.opt("call_id") == callId) ValidationResult.Valid
        else ValidationResult.Invalid("Use the call_id from this call's tool schema")

    suspend fun invoke(params: JSONObject): ToolExecutionResult {
        val invalid = validate(params) as? ValidationResult.Invalid
        if (invalid != null) return ToolExecutionResult.Failure(invalid.errors.joinToString())
        return if (hangUp()) ToolExecutionResult.Success("The GPT-Live voice call ended.")
        else ToolExecutionResult.Failure("This GPT-Live voice call is no longer active.")
    }

    override fun createInvocation(params: JSONObject): ToolInvocation = object : ToolInvocation {
        override val toolName = name
        override val params = params
        override fun getDescription() = "End this GPT-Live voice call"
        override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult =
            if (context.isCancelled()) ToolExecutionResult.Cancelled() else invoke(params)
    }
}
