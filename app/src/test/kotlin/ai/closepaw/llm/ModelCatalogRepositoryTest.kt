package ai.closepaw.llm

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import ai.closepaw.app.AppSettings
import ai.closepaw.app.AppSettingsStore
import ai.closepaw.auth.CodexHeaders
import ai.closepaw.protocol.LLMBackendType
import ai.closepaw.protocol.PlatformMode
import ai.closepaw.ui.settings.AVAILABLE_LOCAL_MODELS
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test

class ModelCatalogRepositoryTest {

    private val seedAssetFile = File("src/main/assets/llm_models.json")

    @After
    fun tearDown() {
        ModelCatalogRepositoryHolder.resetForTest()
    }

    @Test
    fun `initial seed load matches assets llm_models json`() {
        val seedBytes = seedAssetFile.readBytes()
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes),
            settingsStore = fakeSettingsStore(otherBaseUrl = "", otherModelId = ""),
            discoveryCache = mockk(relaxed = true),
        )

        val catalog = repo.catalog.value
        val expectedKeys = ModelCatalog.fromJson(String(seedBytes)).names()
        assertThat(catalog.names()).isEqualTo(expectedKeys)
        assertThat(catalog.size).isEqualTo(expectedKeys.size)
    }

    @Test
    fun `invalidate emits a new catalog value`() {
        val first = """{"a":{"display_name":"A","provider":"OTHER","api":"chat","model_id":"x/a"}}"""
        val second =
            """{"a":{"display_name":"A","provider":"OTHER","api":"chat","model_id":"x/a"},""" +
                """"b":{"display_name":"B","provider":"OTHER","api":"chat","model_id":"x/b"}}"""
        val context = contextReturningSequentialAssets(first.toByteArray(), second.toByteArray())
        val repo = ModelCatalogRepository(
            context = context,
            settingsStore = fakeSettingsStore(otherBaseUrl = "", otherModelId = ""),
            discoveryCache = mockk(relaxed = true),
        )

        val before = repo.catalog.value
        assertThat(before.names()).containsExactly("a")

        repo.invalidate()

        val after = repo.catalog.value
        assertThat(after.names()).containsExactly("a", "b")
        assertThat(after).isNotSameInstanceAs(before)
    }

    @Test
    fun `resetForTest clears the singleton instance`() {
        // mockkConstructor so the holder's lazy build doesn't touch real
        // SharedPreferences (AppSettingsStore) or filesDir (ModelDiscoveryCache).
        mockkConstructor(AppSettingsStore::class)
        mockkConstructor(ModelDiscoveryCache::class)
        try {
            val seedBytes = seedAssetFile.readBytes()
            val ctxA = contextReturningAsset(seedBytes)
            val ctxB = contextReturningAsset(seedBytes)

            val first = ModelCatalogRepositoryHolder.get(ctxA)
            val cached = ModelCatalogRepositoryHolder.get(ctxA)
            assertThat(cached).isSameInstanceAs(first)

            ModelCatalogRepositoryHolder.resetForTest()

            val second = ModelCatalogRepositoryHolder.get(ctxB)
            assertThat(second).isNotSameInstanceAs(first)
        } finally {
            unmockkConstructor(AppSettingsStore::class)
            unmockkConstructor(ModelDiscoveryCache::class)
        }
    }

    // ── Synthesized OTHER entry ──────────────────────────────────────────

    @Test
    fun `synthesizes other-custom when both otherBaseUrl + otherModelId are non-blank`() {
        val store = fakeSettingsStore(otherBaseUrl = "https://api.example.com/v1", otherModelId = "vendor/model")
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = mockk(relaxed = true),
        )

        val entry = repo.catalog.value.resolveOrNull("other-custom")
        assertThat(entry).isNotNull()
        assertThat(entry!!.provider).isEqualTo(LLMProvider.OTHER)
        assertThat(entry.api).isEqualTo(ApiType.CHAT)
        assertThat(entry.modelId).isEqualTo("vendor/model")
        assertThat(entry.baseUrl).isEqualTo("https://api.example.com/v1")
        assertThat(entry.supportsVision).isFalse()
        assertThat(entry.displayName).isEqualTo("vendor/model")
    }

    @Test
    fun `no other-custom entry when otherBaseUrl is blank`() {
        val store = fakeSettingsStore(otherBaseUrl = "", otherModelId = "vendor/model")
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = mockk(relaxed = true),
        )

        assertThat(repo.catalog.value.resolveOrNull("other-custom")).isNull()
    }

    @Test
    fun `no other-custom entry when otherModelId is blank`() {
        val store = fakeSettingsStore(otherBaseUrl = "https://api.example.com/v1", otherModelId = "")
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = mockk(relaxed = true),
        )

        assertThat(repo.catalog.value.resolveOrNull("other-custom")).isNull()
    }

    @Test
    fun `invalidate re-reads settings and adds other-custom on next read`() {
        val seed = seedBytes()
        var baseUrl = ""
        var modelId = ""
        val store = mockk<AppSettingsStore>(relaxed = true)
        every { store.load() } answers { defaultSettings(otherBaseUrl = baseUrl, otherModelId = modelId) }
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seed),
            settingsStore = store,
            discoveryCache = mockk(relaxed = true),
        )

        assertThat(repo.catalog.value.resolveOrNull("other-custom")).isNull()

        baseUrl = "https://api.example.com/v1"
        modelId = "vendor/model"
        repo.invalidate()

        val entry = repo.catalog.value.resolveOrNull("other-custom")
        assertThat(entry).isNotNull()
        assertThat(entry!!.baseUrl).isEqualTo("https://api.example.com/v1")
        assertThat(entry.modelId).isEqualTo("vendor/model")
    }

    @Test
    fun `no other-custom entry when otherBaseUrl fails validation`() {
        // OtherBaseUrlValidator rejects non-http(s); see HIGH #2 in the Sub 1c review.
        // synth must refuse to materialize so ensureRequiredCredentials reports a clean
        // MissingCredential(OTHER) instead of leaking through to the client builder.
        val store = fakeSettingsStore(otherBaseUrl = "not-a-url", otherModelId = "vendor/model")
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = mockk(relaxed = true),
        )

        assertThat(repo.catalog.value.resolveOrNull("other-custom")).isNull()
    }

    @Test
    fun `synth entry stores normalized URL with trailing slash trimmed`() {
        val store = fakeSettingsStore(
            otherBaseUrl = "https://api.example.com/v1/",
            otherModelId = "vendor/model",
        )
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = mockk(relaxed = true),
        )

        val entry = repo.catalog.value.resolveOrNull("other-custom")
        assertThat(entry).isNotNull()
        assertThat(entry!!.baseUrl).isEqualTo("https://api.example.com/v1")
    }

    // ── Discovery integration ────────────────────────────────────────────

    @Test
    fun `OpenAI refresh persists selectable Responses models after repository restart`() = runTest {
        val context = contextReturningAsset(seedBytes())
        val cache = ModelDiscoveryCache(context = realContextForCache())
        val store = fakeSettingsStore("", "")
        val repo = ModelCatalogRepository(context, store, cache,
            discoverFn = { provider, base, key ->
                assertThat(key).isEqualTo("test-key")
                ModelDiscovery.parse(provider, base, """{"data":[{"id":"gpt-new-test"}]}""")
            },
            clock = { 123L },
        )
        repo.refresh(LLMProvider.OPENAI_API, "test-key", ModelDiscovery.OPENAI_BASE_URL)

        val restarted = ModelCatalogRepository(context, store, cache)
        val entry = restarted.catalog.value.resolve("openai_api:gpt-new-test")
        assertThat(entry.provider).isEqualTo(LLMProvider.OPENAI_API)
        assertThat(entry.api).isEqualTo(ApiType.RESPONSE)
        assertThat(restarted.discoveryState.value.lastFetchedAt[LLMProvider.OPENAI_API]).isEqualTo(123L)
    }

    @Test
    fun `Codex refresh obtains current OAuth headers for every request`() = runTest {
        var token = "first-token"
        val seenTokens = mutableListOf<String>()
        val repo = ModelCatalogRepository(
            contextReturningAsset(seedBytes()), fakeSettingsStore("", ""),
            ModelDiscoveryCache(realContextForCache()),
            codexHeaderSupplier = { CodexHeaders(token, "account", null) },
            discoverCodexFn = { headers ->
                seenTokens.add(headers.accessToken)
                ModelDiscovery.parse(LLMProvider.OPENAI_CODEX, ModelDiscovery.CODEX_BASE_URL,
                    """{"models":[{"slug":"codex-test","visibility":"list"}]}""")
            },
        )
        repo.refreshCodex()
        token = "rotated-token"
        repo.refreshCodex()
        assertThat(seenTokens).containsExactly("first-token", "rotated-token").inOrder()
        assertThat(repo.catalog.value.resolve("openai_codex:codex-test").api).isEqualTo(ApiType.RESPONSE)
    }

    @Test
    fun `empty refresh preserves last usable catalog and reports failure`() = runTest {
        val cache = ModelDiscoveryCache(realContextForCache())
        val provider = LLMProvider.OPENAI_API
        val base = ModelDiscovery.OPENAI_BASE_URL
        cache.write(ModelDiscoveryCache.cacheKey(provider, base), 123L,
            ModelDiscovery.parse(provider, base, """{"data":[{"id":"gpt-cached"}]}"""))
        val repo = ModelCatalogRepository(contextReturningAsset(seedBytes()), fakeSettingsStore("", ""), cache,
            discoverFn = { _, _, _ -> emptyList() })

        repo.refresh(provider, "key", base)
        assertThat(repo.catalog.value.resolveOrNull("openai_api:gpt-cached")).isNotNull()
        assertThat(repo.discoveryState.value.lastFetchedAt[provider]).isEqualTo(123L)
        assertThat(repo.discoveryState.value.lastError[provider]).contains("No compatible models")
    }

    @Test
    fun `concurrent refresh is ignored and cancellation permits retry without an error`() = runTest {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val repo = ModelCatalogRepository(contextReturningAsset(seedBytes()), fakeSettingsStore("", ""),
            ModelDiscoveryCache(realContextForCache()),
            discoverFn = { provider, base, _ ->
                calls++
                if (calls == 1) {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                ModelDiscovery.parse(provider, base, """{"data":[{"id":"gpt-retry"}]}""")
            })
        val first = launch { repo.refresh(LLMProvider.OPENAI_API, "key", ModelDiscovery.OPENAI_BASE_URL) }
        started.await()
        repo.refresh(LLMProvider.OPENAI_API, "key", ModelDiscovery.OPENAI_BASE_URL)
        assertThat(calls).isEqualTo(1)
        first.cancelAndJoin()
        assertThat(repo.discoveryState.value.refreshing).isEmpty()
        assertThat(repo.discoveryState.value.lastError).isEmpty()

        repo.refresh(LLMProvider.OPENAI_API, "key", ModelDiscovery.OPENAI_BASE_URL)
        assertThat(repo.catalog.value.resolveOrNull("openai_api:gpt-retry")).isNotNull()
    }

    @Test
    fun `OpenAI refresh refuses an alternate credential destination`() = runTest {
        val repo = ModelCatalogRepository(contextReturningAsset(seedBytes()), fakeSettingsStore("", ""),
            ModelDiscoveryCache(realContextForCache()),
            discoverFn = { _, _, _ -> error("Must not send credentials") })
        repo.refresh(LLMProvider.OPENAI_API, "key", "https://unrelated.example/v1")
        assertThat(repo.discoveryState.value.lastError[LLMProvider.OPENAI_API]).contains("fixed")
    }

    @Test
    fun `refresh writes cache, emits new catalog containing discovered entries`() = runTest {
        val cache = ModelDiscoveryCache(context = realContextForCache())
        val endpointBase = "https://example.test/v1"
        val store = fakeSettingsStore(otherBaseUrl = endpointBase, otherModelId = "")
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = cache,
            discoverFn = { provider, base, _ ->
                assertThat(provider).isEqualTo(LLMProvider.OTHER)
                assertThat(base).isEqualTo(endpointBase)
                listOf(
                    DiscoveredModel(
                        entry = ModelEntry(
                            name = "other:anthropic/claude-opus-4.7",
                            displayName = "Claude Opus 4.7",
                            provider = LLMProvider.OTHER,
                            api = ApiType.CHAT,
                            modelId = "anthropic/claude-opus-4.7",
                            contextWindow = 200_000,
                            baseUrl = endpointBase,
                        ),
                        created = 1_700_000_000L,
                    )
                )
            },
            clock = { 9_999L },
        )

        repo.refresh(LLMProvider.OTHER, "sk-fake", endpointBase)
        val after = repo.catalog.value
        assertThat(after.resolveOrNull("other:anthropic/claude-opus-4.7")).isNotNull()
        assertThat(repo.discoveryState.value.lastFetchedAt[LLMProvider.OTHER]).isEqualTo(9_999L)
        assertThat(repo.discoveryState.value.refreshing).isEmpty()
    }

    @Test
    fun `refresh failure surfaces error in discoveryState and leaves cache untouched`() = runTest {
        val cache = ModelDiscoveryCache(context = realContextForCache())
        val store = fakeSettingsStore(otherBaseUrl = "https://example.test/v1", otherModelId = "")
        val endpointBase = "https://example.test/v1"
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = cache,
            discoverFn = { _, _, _ -> throw java.io.IOException("HTTP 503") },
        )

        repo.refresh(LLMProvider.OTHER, "sk-fake", endpointBase)

        val state = repo.discoveryState.value
        assertThat(state.lastError[LLMProvider.OTHER]).contains("503")
        assertThat(state.refreshing).isEmpty()
        // Cache is empty — no successful refresh happened.
        assertThat(cache.readAll()).isEmpty()
    }

    @Test
    fun `OTHER refresh rejected when baseUrl invalid`() = runTest {
        val cache = ModelDiscoveryCache(context = realContextForCache())
        val store = fakeSettingsStore(otherBaseUrl = "", otherModelId = "")
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = cache,
            discoverFn = { _, _, _ -> error("should not be called") },
        )

        repo.refresh(LLMProvider.OTHER, "sk-fake", baseUrl = "not-a-url")

        assertThat(repo.discoveryState.value.lastError[LLMProvider.OTHER]).isNotNull()
    }

    @Test
    fun `refresh uses caller-provided baseUrl, NOT persisted settings (race fix)`() = runTest {
        // Codex review CRITICAL #1: persisted A, live B; refresh must hit B.
        val cache = ModelDiscoveryCache(context = realContextForCache())
        val staleUrlA = "https://api-a.example.com/v1"
        val freshUrlB = "https://api-b.example.com/v1"
        val store = mockk<AppSettingsStore>(relaxed = true)
        // Settings store has the OLD URL (debounce hasn't committed B yet).
        every { store.load() } returns defaultSettings(otherBaseUrl = staleUrlA, otherModelId = "")
        var discoverHit: String? = null
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = cache,
            discoverFn = { _, base, _ -> discoverHit = base; emptyList() },
        )

        repo.refresh(LLMProvider.OTHER, "sk-fake", baseUrl = freshUrlB)

        assertThat(discoverHit).isEqualTo(freshUrlB)
    }

    @Test
    fun `stale refresh completion is dropped when effective baseUrl moved on`() = runTest {
        val cache = ModelDiscoveryCache(context = realContextForCache())
        val urlA = "https://api-a.example.com/v1"
        val urlB = "https://api-b.example.com/v1"
        var currentUrl = urlA
        val store = mockk<AppSettingsStore>(relaxed = true)
        every { store.load() } answers { defaultSettings(otherBaseUrl = currentUrl, otherModelId = "") }
        var discoverHit: String? = null
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = cache,
            discoverFn = { _, base, _ ->
                discoverHit = base
                // Simulate the user switching URL while we're "in flight".
                currentUrl = urlB
                listOf(
                    DiscoveredModel(
                        entry = ModelEntry(
                            name = "other:vendor/from-a",
                            displayName = "From A",
                            provider = LLMProvider.OTHER,
                            api = ApiType.CHAT,
                            modelId = "vendor/from-a",
                            contextWindow = 128_000,
                            baseUrl = urlA,
                        ),
                        created = 0L,
                    )
                )
            },
        )

        repo.refresh(LLMProvider.OTHER, "sk-fake", baseUrl = urlA)

        // discover() was called with the captured URL...
        assertThat(discoverHit).isEqualTo(urlA)
        // ...but because the effective URL flipped to B mid-fetch, the result was dropped.
        assertThat(cache.readAll()).isEmpty()
        assertThat(repo.discoveryState.value.lastFetchedAt[LLMProvider.OTHER]).isNull()
        assertThat(repo.discoveryState.value.refreshing).isEmpty()
    }

    @Test
    fun `discovered entries scoped to current effective OTHER baseUrl`() = runTest {
        val cache = ModelDiscoveryCache(context = realContextForCache())
        val urlA = "https://a.example.com/v1"
        val urlB = "https://b.example.com/v1"
        var otherUrl = urlA
        val store = mockk<AppSettingsStore>(relaxed = true)
        every { store.load() } answers { defaultSettings(otherBaseUrl = otherUrl, otherModelId = "vendor/manual") }
        val repo = ModelCatalogRepository(
            context = contextReturningAsset(seedBytes()),
            settingsStore = store,
            discoveryCache = cache,
            discoverFn = { provider, base, _ ->
                listOf(
                    DiscoveredModel(
                        entry = ModelEntry(
                            name = "${provider.name.lowercase()}:vendor/from-$base",
                            displayName = "From $base",
                            provider = provider,
                            api = ApiType.CHAT,
                            modelId = "vendor/from-$base",
                            contextWindow = 128_000,
                            baseUrl = base,
                        ),
                        created = 0L,
                    )
                )
            },
        )

        repo.refresh(LLMProvider.OTHER, "sk-fake", baseUrl = urlA)
        // After refresh against A, the catalog shows the A entry.
        assertThat(repo.catalog.value.names()).contains("other:vendor/from-$urlA")

        // User flips baseUrl to B (without refresh). Catalog must hide A entries.
        otherUrl = urlB
        repo.invalidate()
        val names = repo.catalog.value.names()
        assertThat(names).doesNotContain("other:vendor/from-$urlA")
    }

    private fun realContextForCache(): Context {
        // Use a real filesDir for cache so write/read round-trips work.
        val tmp = java.nio.file.Files.createTempDirectory("repo-test-cache").toFile()
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.filesDir } returns tmp
        return ctx
    }

    // ── Fixtures ──

    private fun seedBytes(): ByteArray = seedAssetFile.readBytes()

    private fun defaultSettings(
        otherBaseUrl: String = "",
        otherModelId: String = "",
    ): AppSettings = AppSettings(
        selectedModel = AppSettingsStore.DEFAULT_MODEL,
        debugMode = AppSettingsStore.DEFAULT_DEBUG_MODE,
        perceptionMode = AppSettingsStore.DEFAULT_PERCEPTION_MODE,
        llmBackend = AppSettingsStore.DEFAULT_LLM_BACKEND,
        localModel = AVAILABLE_LOCAL_MODELS.first(),
        platformMode = AppSettingsStore.DEFAULT_PLATFORM_MODE,
        traceEnabled = AppSettingsStore.DEFAULT_TRACE_ENABLED,
        browserScriptEnabled = AppSettingsStore.DEFAULT_BROWSER_SCRIPT_ENABLED,
        termuxShellEnabled = AppSettingsStore.DEFAULT_TERMUX_SHELL_ENABLED,
        openaiBaseUrl = "",
        otherBaseUrl = otherBaseUrl,
        otherModelId = otherModelId,
        approvalMode = AppSettingsStore.DEFAULT_APPROVAL_MODE,
    )

    private fun fakeSettingsStore(
        otherBaseUrl: String,
        otherModelId: String,
    ): AppSettingsStore {
        val store = mockk<AppSettingsStore>(relaxed = true)
        every { store.load() } returns defaultSettings(otherBaseUrl, otherModelId)
        return store
    }

    private fun contextReturningAsset(bytes: ByteArray): Context {
        val assets = mockk<AssetManager>()
        every { assets.open("llm_models.json") } answers { ByteArrayInputStream(bytes) }
        return contextWith(assets)
    }

    private fun contextReturningSequentialAssets(vararg payloads: ByteArray): Context {
        val assets = mockk<AssetManager>()
        var idx = 0
        every { assets.open("llm_models.json") } answers {
            val payload = payloads[idx.coerceAtMost(payloads.lastIndex)]
            idx++
            ByteArrayInputStream(payload)
        }
        return contextWith(assets)
    }

    private fun contextWith(assets: AssetManager): Context {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.assets } returns assets
        every { context.applicationContext } returns context
        every { context.getSharedPreferences(any(), any()) } returns prefs
        // Real filesDir so ModelDiscoveryCache constructor can build its File path.
        every { context.filesDir } returns
            java.nio.file.Files.createTempDirectory("repo-test-ctx").toFile()
        return context
    }
}
