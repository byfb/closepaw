package ai.closepaw.ui.settings

import ai.closepaw.llm.LLMProvider
import ai.closepaw.llm.OtherBaseUrlValidator

/**
 * Pure refresh-button gating logic — when discovery's prerequisites are met
 * for [provider]. Designed so the Compose layer renders a disabled button
 * with a tooltip naming the missing piece without re-doing validation.
 *
 * - `OPENAI_API` needs a non-blank API key (the URL is fixed).
 * - `OTHER` needs a non-blank API key AND a base URL that passes
 *   [OtherBaseUrlValidator] (validated against debug-vs-release policy).
 */
object RefreshButtonGate {

    sealed interface State {
        data object Enabled : State
        data class Disabled(val reason: String) : State
    }

    fun evaluate(
        provider: LLMProvider,
        apiKey: String,
        otherBaseUrl: String,
        allowDebugHttp: Boolean,
        signedIn: Boolean = false,
    ): State {
        return when (provider) {
            LLMProvider.OPENAI_CODEX -> {
                if (signedIn) State.Enabled else State.Disabled("Sign in to refresh models")
            }
            LLMProvider.OPENAI_API -> {
                if (apiKey.isBlank()) State.Disabled("Enter your OpenAI API key first")
                else State.Enabled
            }
            LLMProvider.OTHER -> {
                if (apiKey.isBlank()) State.Disabled("Enter your API key first")
                else {
                    val urlResult = OtherBaseUrlValidator.validate(otherBaseUrl, allowDebugHttp)
                    if (urlResult.isFailure) State.Disabled(
                        urlResult.exceptionOrNull()?.message ?: "Base URL is invalid"
                    ) else State.Enabled
                }
            }
            LLMProvider.LOCAL_LFM -> State.Disabled("Local models do not need refreshing")
        }
    }
}
