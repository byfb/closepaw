package ai.closepaw.ui.settings

import ai.closepaw.ui.theme.uiResources

import ai.closepaw.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.closepaw.BuildConfig
import ai.closepaw.llm.AuthMode
import ai.closepaw.llm.ModelCatalog
import ai.closepaw.platform.AppManager
import ai.closepaw.protocol.AppTier
import ai.closepaw.protocol.ApprovalMode
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.tool.AppClassifier
import ai.closepaw.ui.theme.Fleuron
import ai.closepaw.ui.theme.PageMastheadIdentity
import ai.closepaw.ui.theme.SectionHeader
import ai.closepaw.ui.theme.closePaw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsHomePage(
    llmBackend: LLMBackendType,
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    selectedLocalModel: String,
    modelCatalog: ModelCatalog,
    perceptionMode: String,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    debugMode: Boolean,
    platformMode: PlatformMode,
    effectivePlatformMode: PlatformMode?,
    appClassifier: AppClassifier,
    approvalMode: ApprovalMode,
    onNavigate: (SettingsPage) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = uiResources()
    Column(modifier = Modifier.fillMaxWidth()) {
        PageMastheadIdentity(title = strings.getString(R.string.ui_settings), onClose = onDismiss)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.closePaw.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.closePaw.spacing.sm)
        ) {
            SectionHeader(strings.getString(R.string.ui_personalization))
            SettingsNavigationRow(title = strings.getString(R.string.ui_appearance), subtitle = "", onClick = { onNavigate(SettingsPage.APPEARANCE) })
            SettingsNavigationRow(title = strings.getString(R.string.ui_custom_instructions), subtitle = "", onClick = { onNavigate(SettingsPage.CUSTOM_INSTRUCTIONS) })
            SectionHeader(strings.getString(R.string.ui_behavior))
            SettingsNavigationRow(
                title = strings.getString(R.string.ui_llm_authentication),
                subtitle = llmSubtitle(strings, llmBackend, selectedModel, modelOptions, selectedLocalModel, modelCatalog),
                onClick = { onNavigate(SettingsPage.LLM_AUTH) }
            )
            SettingsNavigationRow(
                title = "GPT-Live",
                subtitle = "",
                onClick = { onNavigate(SettingsPage.GPT_LIVE) }
            )
            SettingsNavigationRow(
                title = strings.getString(R.string.ui_agent_behavior),
                subtitle = agentBehaviorSubtitle(strings, perceptionMode, platformMode, effectivePlatformMode, approvalMode),
                onClick = { onNavigate(SettingsPage.AGENT_BEHAVIOR) }
            )
            SettingsNavigationRow(
                title = strings.getString(R.string.ui_memory),
                subtitle = strings.getString(R.string.ui_user_and_device_knowledge),
                onClick = { onNavigate(SettingsPage.MEMORY) }
            )

            SectionHeader(strings.getString(R.string.ui_access))
            SettingsNavigationRow(
                title = strings.getString(R.string.ui_app_access),
                subtitle = appAccessSubtitle(appClassifier, approvalMode),
                onClick = { onNavigate(SettingsPage.APP_ACCESS) }
            )
            SettingsNavigationRow(
                title = strings.getString(R.string.ui_system_debug),
                subtitle = permissionsSubtitle(strings, isAccessibilityEnabled, isOverlayEnabled, debugMode),
                onClick = { onNavigate(SettingsPage.PERMISSIONS_ADVANCED) }
            )

            SectionHeader(strings.getString(R.string.ui_about))
            SettingsNavigationRow(
                title = strings.getString(R.string.ui_open_source_licenses),
                subtitle = strings.getString(R.string.ui_closepaw_is_apache_2_0_view_third_party_notices),
                onClick = { onNavigate(SettingsPage.OPEN_SOURCE_LICENSES) }
            )

            Fleuron()

            Text(
                text = strings.getString(R.string.ui_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.closePaw.monoSmall,
                color = MaterialTheme.closePaw.inkFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private fun llmSubtitle(
    strings: android.content.res.Resources,
    llmBackend: LLMBackendType,
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    selectedLocalModel: String,
    modelCatalog: ModelCatalog,
): String = if (llmBackend == LLMBackendType.LOCAL) {
    AVAILABLE_LOCAL_MODELS.find { it.id == selectedLocalModel }?.displayName ?: selectedLocalModel
} else {
    val modelName = modelOptions.find { it.first == selectedModel }?.second ?: selectedModel
    val mode = modelCatalog.resolveOrNull(selectedModel)?.provider?.mode
    val authLabel = when (mode) {
        AuthMode.OAuth -> "OAuth"
        AuthMode.ApiKey -> strings.getString(R.string.ui_api_key_subtitle)
        AuthMode.Local, null -> strings.getString(R.string.ui_api_key_subtitle)
    }
    "$modelName · $authLabel"
}

private fun agentBehaviorSubtitle(
    strings: android.content.res.Resources,
    perceptionMode: String,
    platformMode: PlatformMode,
    effectivePlatformMode: PlatformMode?,
    approvalMode: ApprovalMode,
): String {
    val approvalChip = when (approvalMode) {
        ApprovalMode.SMART -> strings.getString(R.string.ui_per_app)
        ApprovalMode.AUTO_APPROVE -> strings.getString(R.string.ui_auto_approve)
        ApprovalMode.ALWAYS_ASK -> strings.getString(R.string.ui_always_ask)
    }
    val perceptionChip = when (perceptionMode) {
        "tree_first" -> strings.getString(R.string.ui_tree_first)
        "hybrid" -> strings.getString(R.string.ui_transcript_image)
        "screenshot_only" -> strings.getString(R.string.ui_image)
        else -> strings.getString(R.string.ui_transcript)
    }
    val displayChip = when (platformMode) {
        PlatformMode.ACCESSIBILITY -> when (effectivePlatformMode) {
            PlatformMode.VIRTUAL_DISPLAY -> strings.getString(R.string.ui_virtual_display_this_session)
            else -> ""
        }
        PlatformMode.VIRTUAL_DISPLAY -> when (effectivePlatformMode) {
            PlatformMode.ACCESSIBILITY -> strings.getString(R.string.ui_virtual_display_next_session)
            else -> strings.getString(R.string.ui_virtual_display_suffix)
        }
    }
    return "$approvalChip · $perceptionChip$displayChip"
}

private fun permissionsSubtitle(
    strings: android.content.res.Resources,
    isAccessibilityEnabled: Boolean,
    isOverlayEnabled: Boolean,
    debugMode: Boolean,
): String {
    val grantedCount = listOf(isAccessibilityEnabled, isOverlayEnabled).count { it }
    val permSummary = when (grantedCount) {
        2 -> strings.getString(R.string.ui_all_granted)
        1 -> strings.getString(R.string.ui_1_of_2_granted)
        else -> strings.getString(R.string.ui_setup_required)
    }
    val debug = if (debugMode) strings.getString(R.string.ui_debug_on) else strings.getString(R.string.ui_debug_off)
    return "$permSummary · $debug"
}

/**
 * Subtitle for the App Access entry on the Settings home page.
 *
 * Counts installed apps per effective tier (allow / ask / reject). The installed
 * app set is effectively constant, but tier classification depends on the live
 * `userOverrides` map, so we re-scan when overrides change. While the IO scan
 * is in flight, render `…` rather than block.
 */
@Composable
private fun appAccessSubtitle(classifier: AppClassifier, approvalMode: ApprovalMode): String {
    val strings = uiResources()
    val context = LocalContext.current
    val overrides by classifier.userOverrides.collectAsState()
    val counts by produceState<Triple<Int, Int, Int>?>(
        initialValue = null, context, classifier, overrides,
    ) {
        value = withContext(Dispatchers.IO) {
            var allow = 0
            var ask = 0
            var reject = 0
            AppManager.getInstalledApps(context.packageManager).forEach { info ->
                when (classifier.classify(info.packageName)) {
                    AppTier.NORMAL -> allow++
                    AppTier.CAUTIOUS -> ask++
                    AppTier.BLOCKED -> reject++
                }
            }
            Triple(allow, ask, reject)
        }
    }
    val modeLabel = when (approvalMode) {
        ApprovalMode.SMART -> strings.getString(R.string.ui_per_app)
        ApprovalMode.AUTO_APPROVE -> strings.getString(R.string.ui_auto_approve)
        ApprovalMode.ALWAYS_ASK -> strings.getString(R.string.ui_always_ask)
    }
    return counts?.let { (a, k, r) -> strings.getString(R.string.ui_allow_ask_reject, modeLabel, a, k, r) }
        ?: "$modeLabel · …"
}
