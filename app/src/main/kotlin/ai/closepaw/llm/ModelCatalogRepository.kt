package ai.closepaw.llm

import android.content.Context
import android.util.Log
import ai.closepaw.app.AppSettingsStore
import ai.closepaw.app.AuthStoreHolder
import ai.closepaw.auth.CodexHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.IOException

/**
 * Process-wide observable model catalog.
 *
 * Single source of truth for the merged set of [ModelEntry]s the rest of the
 * stack (selector, factory, session bootstrap, validation) consumes. The
 * catalog merges:
 *  - the JSON seed in `assets/llm_models.json`;
 *  - a synthesized `other-custom` [ModelEntry] when both `otherBaseUrl` and
 *    `otherModelId` are non-blank in [AppSettingsStore];
 *  - discovered entries from [ModelDiscoveryCache], scoped by current
 *    effective baseUrl per provider.
 *
 * Created once per process via [ModelCatalogRepositoryHolder] so the UI layer
 * (Compose recomposition via [StateFlow]) and the session layer
 * (`SessionLlmBootstrapper` reading off-main via `.value`) observe identical
 * state.
 */
class ModelCatalogRepository(
    private val context: Context,
    private val settingsStore: AppSettingsStore,
    private val discoveryCache: ModelDiscoveryCache,
    private val discoverFn: suspend (LLMProvider, String, String) -> List<DiscoveredModel> =
        ModelDiscovery::discover,
    private val clock: () -> Long = System::currentTimeMillis,
    private val codexHeaderSupplier: suspend () -> CodexHeaders = {
        AuthStoreHolder.get(context).codexHeaders(LLMProvider.OPENAI_CODEX)
    },
    private val discoverCodexFn: suspend (CodexHeaders) -> List<DiscoveredModel> =
        ModelDiscovery::discoverCodex,
) {
    private val refreshLocks = LLMProvider.entries.associateWith { Mutex() }
    private val _catalog: MutableStateFlow<ModelCatalog> = MutableStateFlow(load())
    val catalog: StateFlow<ModelCatalog> = _catalog.asStateFlow()

    private val _discoveryState: MutableStateFlow<DiscoveryState> =
        MutableStateFlow(initialDiscoveryState())
    /**
     * Per-provider discovery status — `lastFetchedAt` lets the settings UI
     * render a "Last refreshed: X ago" stamp without re-reading the cache.
     */
    val discoveryState: StateFlow<DiscoveryState> = _discoveryState.asStateFlow()

    /**
     * Recompute the merged catalog from seed + settings + discovery cache and
     * publish via [catalog]. Safe to call from any thread.
     */
    fun invalidate() {
        _catalog.value = load()
        val freshTimestamps = readLastFetchedAt()
        _discoveryState.update { it.copy(lastFetchedAt = freshTimestamps) }
    }

    /**
     * Fetch fresh entries from `{baseUrl}/models` for [provider] using [key],
     * persist them to the cache, and republish [catalog]. Errors are
     * surfaced via [discoveryState]; the existing cache is preserved on
     * failure.
     *
     * The caller MUST pass a [baseUrl] that has already been validated and
     * normalized — passing the live UI value (rather than reading from
     * [AppSettingsStore]) closes the persistence-debounce race where the
     * user edits OTHER's URL from A→B and taps refresh before the 300ms
     * debounce commits, which would otherwise send the current key to A.
     *
     * Stale completion safety: discovery and cache state mutations check
     * the live effective baseUrl at completion time and ignore the result
     * when the user has moved on (e.g. a slow refresh against URL A
     * finishes after the user switched to URL B).
     *
     * Built-in API providers use their fixed discovery URL. OTHER uses the
     * validated live URL. ChatGPT sign-in uses [refreshCodex] instead.
     */
    suspend fun refresh(provider: LLMProvider, key: String, baseUrl: String) {
        require(provider.mode == AuthMode.ApiKey) {
            "API-key refresh requires an API-key provider (got $provider)"
        }
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        // Defence in depth: even if the UI gates on validation, we re-check
        // here so a misuse of the API can't write a malformed URL into the
        // cache. For OpenAI API, ensure the caller didn't ask us to fetch
        // from a non-default URL.
        when (provider) {
            LLMProvider.OPENAI_API -> {
                if (baseUrl != effectiveBaseUrlFor(provider)) {
                    updateDiscoveryState {
                        it.withError(provider, "${provider.displayLabel} base URL is fixed.")
                    }
                    return
                }
            }
            LLMProvider.OTHER -> {
                if (OtherBaseUrlValidator.validate(baseUrl).getOrNull() != baseUrl) {
                    updateDiscoveryState {
                        it.withError(provider, "Base URL is not valid.")
                    }
                    return
                }
            }
            else -> {}
        }

        refreshCatalog(provider, baseUrl) { discoverFn(provider, baseUrl, key) }
    }

    suspend fun refreshCodex() {
        refreshCatalog(LLMProvider.OPENAI_CODEX, ModelDiscovery.CODEX_BASE_URL) {
            discoverCodexFn(codexHeaderSupplier())
        }
    }

    private suspend fun refreshCatalog(
        provider: LLMProvider,
        baseUrl: String,
        discover: suspend () -> List<DiscoveredModel>,
    ) {
        val lock = refreshLocks.getValue(provider)
        if (!lock.tryLock()) return
        updateDiscoveryState {
            it.withRefreshing(provider, true).copy(lastError = it.lastError - provider)
        }
        try {
            withContext(Dispatchers.IO) {
                val discovered = discover()
                currentCoroutineContext().ensureActive()
                if (effectiveBaseUrlFor(provider) != baseUrl) return@withContext
                if (discovered.isEmpty()) {
                    throw IOException("No compatible models returned. Previous list kept.")
                }
                val now = clock()
                discoveryCache.write(ModelDiscoveryCache.cacheKey(provider, baseUrl), now, discovered)
                _catalog.value = load()
                updateDiscoveryState { it.withSuccess(provider, now) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "refresh($provider) failed", e)
            if (effectiveBaseUrlFor(provider) == baseUrl) {
                updateDiscoveryState { it.withError(provider, e.message ?: "Refresh failed") }
            }
        } finally {
            updateDiscoveryState { it.withRefreshing(provider, false) }
            lock.unlock()
        }
    }

    private inline fun updateDiscoveryState(transform: (DiscoveryState) -> DiscoveryState) {
        _discoveryState.update(transform)
    }

    /**
     * Resolve the effective base URL for a discovery-capable provider, or
     * null if the configuration is incomplete (e.g. OTHER's `otherBaseUrl`
     * is blank or invalid).
     */
    fun effectiveBaseUrlFor(provider: LLMProvider): String? = when (provider) {
        LLMProvider.OPENAI_API -> ModelDiscovery.OPENAI_BASE_URL
        LLMProvider.OPENAI_CODEX -> ModelDiscovery.CODEX_BASE_URL
        LLMProvider.OTHER -> OtherBaseUrlValidator.validate(settingsStore.load().otherBaseUrl).getOrNull()
        else -> null
    }

    private fun load(): ModelCatalog {
        val seed = ModelCatalog.fromJson(readSeedJsonOrFallback())
        val extras = buildList {
            synthOtherEntry()?.let { add(it) }
            addAll(discoveredEntriesScopedByCurrentBaseUrl())
        }
        val refreshedSeeds = seed.all().mapNotNull { entry ->
            extras.firstOrNull { it.provider == entry.provider && it.modelId == entry.modelId }
                ?.supportedReasoningEfforts?.let { entry.copy(supportedReasoningEfforts = it) }
        }
        return seed.withExtraEntries(extras + refreshedSeeds)
    }

    /**
     * Return discovered entries whose cache-key baseUrl matches the
     * currently effective baseUrl for that provider. Prevents the credential
     * leak failure mode where the user changes `otherBaseUrl` from A to B and
     * an old-A cached entry remains selectable — the agent would then send
     * the current key to the stale URL.
     */
    private fun discoveredEntriesScopedByCurrentBaseUrl(): List<ModelEntry> {
        val all = discoveryCache.readAll()
        return all.entries.flatMap { (key, bucket) ->
            val sep = key.indexOf(':')
            if (sep <= 0) return@flatMap emptyList()
            val provider = runCatching { LLMProvider.valueOf(key.substring(0, sep)) }
                .getOrNull() ?: return@flatMap emptyList()
            val expectedBase = effectiveBaseUrlFor(provider) ?: return@flatMap emptyList()
            val expectedKey = ModelDiscoveryCache.cacheKey(provider, expectedBase)
            if (expectedKey != key) emptyList() else bucket.toDiscovered(provider).map { it.entry }
        }
    }

    private fun synthOtherEntry(): ModelEntry? {
        val settings = settingsStore.load()
        // Validate the model id with the same rules discovery uses — a stray
        // whitespace or leading "/" / ":" would create a catalog entry that
        // cannot be safely round-tripped through the namespacing scheme.
        val modelId = ModelIdValidator.validate(settings.otherModelId).getOrNull()
            ?: return null
        // Reject (don't synth) if the persisted base URL fails validation. Without this,
        // ensureRequiredCredentials sees a synth row, calls into the factory, and the
        // OpenAI SDK constructor surfaces a non-credential error with no clean OTHER
        // deep-link. Forcing the synth to be absent for invalid URLs makes the existing
        // "catalog row absent" check in the bootstrapper return MissingCredential(OTHER)
        // cleanly. Store the NORMALIZED URL (trailing slash trimmed) so downstream
        // consumers don't have to re-normalize.
        val normalizedBaseUrl = OtherBaseUrlValidator.validate(settings.otherBaseUrl).getOrNull()
            ?: return null
        return ModelEntry(
            name = OTHER_CUSTOM_NAME,
            displayName = modelId,
            provider = LLMProvider.OTHER,
            api = ApiType.CHAT,
            modelId = modelId,
            contextWindow = 128_000,
            baseUrl = normalizedBaseUrl,
            apiKeyEnv = null,
            supportsVision = false,
        )
    }

    private fun readSeedJsonOrFallback(): String {
        val raw = try {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Failed to read $ASSET_NAME from assets; using fallback", e)
            return FALLBACK_CATALOG_JSON
        }
        // Probe-parse with the same Json reader the catalog uses so corrupt seed JSON falls back
        // here instead of throwing out of load(). Keeps a single catalog parse call site in main —
        // fallback handling lives at the string layer.
        return try {
            seedProbeJson.decodeFromString<Map<String, JsonElement>>(raw)
            raw
        } catch (e: SerializationException) {
            Log.w(TAG, "Failed to parse $ASSET_NAME; using fallback", e)
            FALLBACK_CATALOG_JSON
        }
    }

    private fun initialDiscoveryState(): DiscoveryState =
        DiscoveryState(lastFetchedAt = readLastFetchedAt())

    private fun readLastFetchedAt(): Map<LLMProvider, Long> =
        LLMProvider.entries.mapNotNull { provider ->
            readLastFetchedAtFor(provider)?.let { provider to it }
        }.toMap()

    private fun readLastFetchedAtFor(provider: LLMProvider): Long? {
        val base = effectiveBaseUrlFor(provider) ?: return null
        return discoveryCache.read(ModelDiscoveryCache.cacheKey(provider, base))?.fetchedAt
    }

    /**
     * UI-facing discovery state. `refreshing` is the set of providers with an
     * in-flight refresh; `lastFetchedAt` is the timestamp from the most
     * recent successful refresh; `lastError` is the most recent error
     * message per provider (cleared on successful refresh).
     */
    data class DiscoveryState(
        val refreshing: Set<LLMProvider> = emptySet(),
        val lastFetchedAt: Map<LLMProvider, Long> = emptyMap(),
        val lastError: Map<LLMProvider, String> = emptyMap(),
    ) {
        fun withRefreshing(provider: LLMProvider, value: Boolean): DiscoveryState =
            copy(refreshing = if (value) refreshing + provider else refreshing - provider)

        fun withSuccess(provider: LLMProvider, at: Long): DiscoveryState = copy(
            lastFetchedAt = lastFetchedAt + (provider to at),
            lastError = lastError - provider,
        )

        fun withError(provider: LLMProvider, message: String): DiscoveryState = copy(
            lastError = lastError + (provider to message),
        )
    }

    companion object {
        private const val TAG = "ModelCatalogRepo"
        private const val ASSET_NAME = "llm_models.json"
        private val seedProbeJson = Json { ignoreUnknownKeys = true }

        /** Stable catalog key for the synthesized OTHER entry. */
        const val OTHER_CUSTOM_NAME = "other-custom"

        private const val FALLBACK_CATALOG_JSON =
            """
            {
              "gpt-5.5": {
                "display_name": "GPT-5.5",
                "provider": "OPENAI_API",
                "api": "response",
                "model_id": "gpt-5.5"
              }
            }
            """
    }
}

/**
 * Application-scoped [ModelCatalogRepository] singleton — mirrors
 * `AuthStoreHolder`. One instance per process keeps UI and session reads
 * coherent across configuration change, service rebind, and process reattach.
 */
object ModelCatalogRepositoryHolder {
    @Volatile private var instance: ModelCatalogRepository? = null

    fun get(context: Context): ModelCatalogRepository {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    /** Replace the cached instance with a test fixture. Pair with [resetForTest]. */
    fun setForTest(repo: ModelCatalogRepository) {
        synchronized(this) { instance = repo }
    }

    /** Clear the cached instance — call from `@After` so [StateFlow] state does not leak across cases. */
    fun resetForTest() {
        synchronized(this) { instance = null }
    }

    private fun build(appContext: Context): ModelCatalogRepository = ModelCatalogRepository(
        context = appContext,
        settingsStore = AppSettingsStore(appContext),
        discoveryCache = ModelDiscoveryCache(appContext),
    )
}
