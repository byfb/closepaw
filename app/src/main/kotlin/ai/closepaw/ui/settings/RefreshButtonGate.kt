package ai.closepaw.ui.settings

import ai.closepaw.R

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
        data class Disabled(val reason: Int, val detail: String? = null) : State
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
                if (signedIn) State.Enabled else State.Disabled(R.string.ui_sign_in_to_refresh_models)
            }
            LLMProvider.OPENAI_API -> {
                if (apiKey.isBlank()) State.Disabled(R.string.ui_enter_your_openai_api_key_first)
                else State.Enabled
            }
            LLMProvider.OTHER -> {
                if (apiKey.isBlank()) State.Disabled(R.string.ui_enter_your_api_key_first)
                else {
                    val urlResult = OtherBaseUrlValidator.validate(otherBaseUrl, allowDebugHttp)
                    if (urlResult.isFailure) State.Disabled(
                        R.string.ui_base_url_is_invalid, urlResult.exceptionOrNull()?.message
                    ) else State.Enabled
                }
            }
            LLMProvider.LOCAL_LFM -> State.Disabled(R.string.ui_local_models_do_not_need_refreshing)
        }
    }
}
