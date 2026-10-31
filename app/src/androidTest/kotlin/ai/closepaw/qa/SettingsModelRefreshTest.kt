package ai.closepaw.qa

import ai.closepaw.app.AppSettingsStore
import ai.closepaw.app.AppSettingsState
import ai.closepaw.auth.CodexHeaders
import ai.closepaw.llm.ModelCatalogRepository
import ai.closepaw.llm.ModelCatalogRepositoryHolder
import ai.closepaw.llm.ModelDiscovery
import ai.closepaw.llm.ModelDiscoveryCache
import ai.closepaw.llm.LLMProvider
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.ui.settings.LlmAuthSettingsPage
import ai.closepaw.ui.settings.ModelLoadingStatus
import ai.closepaw.ui.settings.OpenAiAuthUiState
import ai.closepaw.ui.theme.ClosePawTheme
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsModelRefreshTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var files: File

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "model-refresh-${System.nanoTime()}"
        files = File(app.cacheDir, prefix).apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = files
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("$prefix-$name", mode)
        }
    }

    @After fun tearDown() {
        ModelCatalogRepositoryHolder.resetForTest()
        files.deleteRecursively()
    }

    @Test fun signed_out_refresh_is_disabled() {
        showPage(signedIn = false)
        compose.onNodeWithText("Refresh models").assertIsNotEnabled()
    }

    @Test fun refresh_loads_models_and_selection_survives_reopening() {
        val result = CompletableDeferred<Unit>()
        val store = AppSettingsStore(context)
        val repo = ModelCatalogRepository(context, store, ModelDiscoveryCache(context),
            codexHeaderSupplier = { CodexHeaders("fixture-token", "fixture-account", null) },
            discoverCodexFn = {
                result.await()
                ModelDiscovery.parse(LLMProvider.OPENAI_CODEX, ModelDiscovery.CODEX_BASE_URL,
                    """{"models":[{"slug":"gpt-refresh-test","display_name":"Refresh Test Model",
                        "visibility":"list","input_modalities":["text","image"],
                        "supported_reasoning_levels":[{"effort":"low"},{"effort":"high"}]}]}""")
            })
        ModelCatalogRepositoryHolder.setForTest(repo)
        var pageOpen by mutableStateOf(true)
        store.saveModel("gpt-5.2-codex")
        val settings = AppSettingsState(store).apply { load() }
        compose.setContent {
            val catalog by repo.catalog.collectAsStateWithLifecycle()
            ClosePawTheme {
                if (pageOpen) {
                    LlmAuthSettingsPage(
                        llmBackend = LLMBackendType.OPENAI,
                        onBackendChange = {},
                        selectedModel = settings.selectedModel,
                        onModelChange = settings::updateModel,
                        reasoningEffort = settings.reasoningEffort,
                        onReasoningEffortChange = settings::updateReasoningEffort,
                        modelCatalog = catalog,
                        selectedLocalModel = "LFM2.5-1.2B-Instruct",
                        onLocalModelChange = {},
                        modelLoadingStatus = ModelLoadingStatus.Idle,
                        openAiAuthUiState = OpenAiAuthUiState.SignedIn(null),
                        onStartOAuth = {}, onCancelOAuth = {}, onSignOut = {},
                        onBack = {}, onClose = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Refresh models").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Refreshing…").assertIsNotEnabled()
        result.complete(Unit)
        compose.waitUntil(5_000) { repo.discoveryState.value.lastFetchedAt.containsKey(LLMProvider.OPENAI_CODEX) }
        compose.onNodeWithText("Search models").performScrollTo().performTextInput("Refresh Test")
        compose.onNodeWithText("Refresh Test Model").performClick().assertIsSelected()
        compose.onNodeWithText("Reasoning effort").performScrollTo().performClick()
        compose.onNodeWithText("High").performClick()
        compose.runOnIdle {
            assertEquals("openai_codex:gpt-refresh-test", settings.selectedModel)
            assertEquals("high", settings.reasoningEffort)
            assertEquals("high", AppSettingsStore(context).load().reasoningEfforts[settings.selectedModel])
            pageOpen = false
        }
        compose.waitForIdle()
        compose.runOnIdle { pageOpen = true }
        compose.onNodeWithText("Refresh Test Model").assertIsSelected()
        compose.onNodeWithText("High").assertExists()
    }

    private fun showPage(signedIn: Boolean) {
        val repo = ModelCatalogRepository(context, AppSettingsStore(context), ModelDiscoveryCache(context))
        ModelCatalogRepositoryHolder.setForTest(repo)
        compose.setContent {
            var selected by remember { mutableStateOf("gpt-5.2-codex") }
            val catalog by repo.catalog.collectAsStateWithLifecycle()
            ClosePawTheme {
                LlmAuthSettingsPage(
                    llmBackend = LLMBackendType.OPENAI, onBackendChange = {},
                    selectedModel = selected, onModelChange = { selected = it },
                    modelCatalog = catalog,
                    selectedLocalModel = "LFM2.5-1.2B-Instruct", onLocalModelChange = {},
                    modelLoadingStatus = ModelLoadingStatus.Idle,
                    openAiAuthUiState = if (signedIn) OpenAiAuthUiState.SignedIn(null) else OpenAiAuthUiState.SignedOut,
                    onStartOAuth = {}, onCancelOAuth = {}, onSignOut = {}, onBack = {}, onClose = {},
                )
            }
        }
    }
}
