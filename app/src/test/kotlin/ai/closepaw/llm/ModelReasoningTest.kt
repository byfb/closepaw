package ai.closepaw.llm

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelReasoningTest {
    private val entry = ModelEntry("test", "Test", LLMProvider.OPENAI_CODEX, ApiType.RESPONSE,
        "gpt-5.4", 128_000)

    @Test fun `advertised efforts override fallback including an explicitly empty list`() {
        val limited = entry.copy(supportedReasoningEfforts = listOf("low", "ultra"))
        assertThat(ModelReasoning.options(limited)).containsExactly("low", "ultra").inOrder()
        assertThat(ModelReasoning.effectiveEffort(limited, "high")).isNull()
        assertThat(ModelReasoning.effectiveEffort(limited, "ultra")).isEqualTo("ultra")
        assertThat(ModelReasoning.options(entry.copy(supportedReasoningEfforts = emptyList()))).isEmpty()
    }

    @Test fun `default omits effort and known OpenAI models offer documented levels`() {
        assertThat(ModelReasoning.effectiveEffort(entry, null)).isNull()
        assertThat(ModelReasoning.options(entry)).containsExactly("none", "low", "medium", "high", "xhigh").inOrder()
        assertThat(ModelReasoning.options(entry.copy(modelId = "gpt-4o"))).isEmpty()
    }

    @Test fun `Codex model metadata supplies exact effort options`() {
        val model = ModelDiscovery.parse(LLMProvider.OPENAI_CODEX, ModelDiscovery.CODEX_BASE_URL,
            """{"models":[{"slug":"codex-test","supported_reasoning_levels":[
                {"effort":"low","description":"Fast"},{"effort":"high"},{"effort":"ultra"}]}]}""")
            .single().entry
        assertThat(ModelReasoning.options(model)).containsExactly("low", "high", "ultra").inOrder()
    }

}
