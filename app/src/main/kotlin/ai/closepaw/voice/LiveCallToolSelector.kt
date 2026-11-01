package ai.closepaw.voice

import ai.closepaw.app.AppSettingsStore
import ai.closepaw.auth.AuthStore
import ai.closepaw.llm.CodexResponseClient
import ai.closepaw.llm.LLMClient
import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.LLMStreamEvent
import ai.closepaw.llm.LLMToolCall
import ai.closepaw.llm.ModelCatalogRepositoryHolder
import ai.closepaw.llm.ModelReasoning
import ai.closepaw.tool.ToolRegistry
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.impl.EndGptLiveCallTool
import android.content.Context
import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseInputItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/** Native function selection for Codex v3, independent of whether the voice model delegates. */
internal class LiveCallToolSelector(
    private val client: LLMClient, private val model: String, private val tool: EndGptLiveCallTool,
) {
    private val schema = ToolRegistry().apply { register(tool) }.generateResponsesApiTools()

    suspend fun select(transcript: String): JSONObject? {
        val input = ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
            .role(EasyInputMessage.Role.USER).content(transcript).build())
        val calls = mutableListOf<LLMToolCall>()
        client.chatWithToolsStreaming(instructions, listOf(input), schema, model)
            .flowOn(Dispatchers.IO).first { event ->
                when (event) {
                    is LLMStreamEvent.Created -> calls.clear() // A retried stream starts afresh.
                    is LLMStreamEvent.ToolCallDone -> calls.add(event.toolCall)
                    is LLMStreamEvent.Failed -> error("Voice tool selection failed")
                    else -> Unit
                }
                event == LLMStreamEvent.Completed
            }
        val call = calls.singleOrNull()?.takeIf { it.name == tool.name && it.callId.isNotBlank() } ?: return null
        val arguments = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull() ?: return null
        return JSONObject(arguments.toString()).takeIf { tool.validate(it) == ValidationResult.Valid }
    }

    suspend fun close() = client.cleanup()

    companion object {
        private val instructions = """
            Decide whether the speaker is asking to end the current ClosePaw GPT-Live conversation
            now. Call end_gpt_live_call only when the utterance expresses that intent. A hang-up
            request with no other target refers to this conversation. Merely referring to the
            conversation, asking how to end it or whether it has ended, quoting someone, negating
            a request, asking about another caller or app, or stopping a phone task leaves voice
            open. When there is no clear request to end this call, return 'continue'.
        """.trimIndent()

        suspend fun create(context: Context, auth: AuthStore, tool: EndGptLiveCallTool) = withContext(Dispatchers.IO) {
            val repo = ModelCatalogRepositoryHolder.get(context)
            repo.refreshCodex(force = false)
            val catalog = repo.catalog.value
            val selected = AppSettingsStore(context).load().selectedModel
            val model = catalog.resolveOrNull(selected)?.takeIf { it.provider == LLMProvider.OPENAI_CODEX }
                ?: catalog.preferredModelFor(LLMProvider.OPENAI_CODEX)
                ?: error("No ChatGPT model is available for voice call control")
            val client = CodexResponseClient(
                headerSupplier = { auth.codexHeaders(LLMProvider.OPENAI_CODEX) },
                reasoningEffort = ModelReasoning.effectiveEffort(model, "low"),
                logContent = false,
            )
            LiveCallToolSelector(client, model.modelId, tool)
        }
    }
}
