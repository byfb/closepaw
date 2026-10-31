package ai.closepaw.ui.settings

import ai.closepaw.llm.LLMProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RefreshButtonGateTest {

    @Test
    fun `OTHER disabled when key OR url blank`() {
        val keyBlank = RefreshButtonGate.evaluate(
            provider = LLMProvider.OTHER,
            apiKey = "",
            otherBaseUrl = "https://api.example.com/v1",
            allowDebugHttp = false,
        )
        assertThat(keyBlank).isInstanceOf(RefreshButtonGate.State.Disabled::class.java)

        val urlBlank = RefreshButtonGate.evaluate(
            provider = LLMProvider.OTHER,
            apiKey = "sk-x",
            otherBaseUrl = "",
            allowDebugHttp = false,
        )
        assertThat(urlBlank).isInstanceOf(RefreshButtonGate.State.Disabled::class.java)
    }

    @Test
    fun `OTHER disabled when url fails validation, enabled when valid`() {
        val invalid = RefreshButtonGate.evaluate(
            provider = LLMProvider.OTHER,
            apiKey = "sk-x",
            otherBaseUrl = "not-a-url",
            allowDebugHttp = false,
        )
        assertThat(invalid).isInstanceOf(RefreshButtonGate.State.Disabled::class.java)

        val valid = RefreshButtonGate.evaluate(
            provider = LLMProvider.OTHER,
            apiKey = "sk-x",
            otherBaseUrl = "https://api.example.com/v1",
            allowDebugHttp = false,
        )
        assertThat(valid).isEqualTo(RefreshButtonGate.State.Enabled)
    }
}
