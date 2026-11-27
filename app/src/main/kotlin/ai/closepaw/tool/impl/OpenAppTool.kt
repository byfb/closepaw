package ai.closepaw.tool.impl

import android.util.Log
import ai.closepaw.memory.MemoryStore
import ai.closepaw.platform.AndroidPlatform
import ai.closepaw.platform.AppInfo
import ai.closepaw.platform.ActionResult
import ai.closepaw.protocol.AppTier
import ai.closepaw.tool.action.buildObservation
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolObservation
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.appendReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Well-known app aliases for name → package resolution.
 */
internal object AppAliases {
    val PACKAGE_MAP = mapOf(
        "google maps" to "com.google.android.apps.maps",
        "maps" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome",
        "google chrome" to "com.android.chrome",
        "browser" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "email" to "com.google.android.gm",
        "youtube" to "com.google.android.youtube",
        "play store" to "com.android.vending",
        "google play" to "com.android.vending",
        "files" to "com.google.android.apps.nbu.files",
        "phone" to "com.android.dialer",
        "dialer" to "com.android.dialer",
        "camera" to "com.android.camera",
        "settings" to "com.android.settings",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "contacts" to "com.android.contacts",
        "calendar" to "com.google.android.calendar",
        "clock" to "com.google.android.deskclock",
        "calculator" to "com.google.android.calculator",
        "photos" to "com.google.android.apps.photos",
        "drive" to "com.google.android.apps.docs",
        "keep" to "com.google.android.keep",
        "google keep" to "com.google.android.keep",
        "simple calendar" to "com.simplemobiletools.calendar.pro",
        "simple calendar pro" to "com.simplemobiletools.calendar.pro",
        "simple draw pro" to "com.simplemobiletools.draw.pro",
        "audio recorder" to "com.dimowner.audiorecorder",
        "pro expense" to "com.arduia.expense",
        "markor" to "net.gsantner.markor",
    )
}

/** Resolve a single installed target and pin it through policy approval and launch. */
class OpenAppTool(private val memory: MemoryStore? = null) : ToolSpec {

    override val name: String = "open_app"

    override val description: String = """
Launch an app by name. Always use this to open apps — do NOT navigate the app drawer or home screen manually.
If the target app is already in the foreground, this returns success without relaunching it.
Use the user's name for the app or its exact installed package. If multiple apps match, ask which to use.
After the user chooses, open the exact package and set remember_as to their original name for the app. Use remember_as for an explicit future default too. Omit it for one-time overrides.
""".trimIndent()

    override val parameterSchema: JSONObject by lazy {
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("agent_thought", JSONObject().apply {
                    put("type", "string")
                    put("description", "Brief reason for this action")
                })
                put("remember_as", JSONObject().put("type", "string")
                    .put("description", "Original app name or purpose to remember for future tasks, after the user selects a default."))
                put("app_name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Name of the app to open (e.g., 'Gmail', 'Settings', 'Chrome'). Case-insensitive.")
                })
            })
            put("required", JSONArray(listOf("app_name")))
            put("additionalProperties", false)
        }
    }

    override fun validate(params: JSONObject): ValidationResult {
        val appName = params.optString("app_name", "").trim()
        if (appName.isEmpty()) {
            return ValidationResult.Invalid("open_app requires app_name")
        }
        if (params.has("remember_as")) {
            val alias = params.opt("remember_as") as? String
            if (alias.isNullOrBlank() || alias.length > 120 || alias.any(Char::isISOControl))
                return ValidationResult.Invalid("remember_as must be a single short app name")
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val appName = params.optString("app_name", "").trim()
        val agentThought = params.optString("agent_thought", "").trim()

        val desc = appendReason("Open app: $appName", agentThought)

        return OpenAppInvocation(params, desc, appName, memory)
    }
}

/**
 * Executable invocation for opening an app.
 *
 * Handles name resolution, foreground check, launch, and post-launch screen capture.
 */
internal class OpenAppInvocation(
    override val params: JSONObject,
    private val description: String,
    private val appName: String,
    private val memory: MemoryStore?,
) : ToolInvocation {

    companion object {
        private const val TAG = "OpenAppInvocation"
        private const val UI_SETTLE_DELAY_MS = 800L
        private const val SUGGESTION_LIMIT = 5
    }

    override val toolName: String = "open_app"

    override fun getDescription(): String = description

    private var target: AppInfo? = null

    /** Return an actionable diagnostic for ambiguity or a missing app. */
    suspend fun resolve(platform: AndroidPlatform): String? {
        if (target != null) return null
        val apps = platform.getInstalledApps()
        val matches = searchInstalledApps(appName, apps)
        val byPackage = apps.firstOrNull { it.packageName.equals(appName, ignoreCase = true) }
        val preferred = if (byPackage == null) withContext(Dispatchers.IO) { memory?.preferredAppPackage(appName) } else null
        val exact = matches.filter { it.label.equals(appName, ignoreCase = true) }
        target = byPackage ?: apps.firstOrNull { it.packageName == preferred }
            ?: (exact.ifEmpty { matches }).singleOrNull()
        if (target != null) return null
        if (matches.isNotEmpty()) {
            return "Multiple apps match '$appName': " + (exact.ifEmpty { matches }).take(10).joinToString { it.displayMatch() } +
                ". Ask the user which app, then open its exact package with remember_as set to '$appName'."
        }
        val suggestions = findSimilarApps(appName.lowercase(), apps).joinToString()
        return "App not found: '$appName'. Use search_apps to find an installed package." +
            if (suggestions.isBlank()) "" else " Similar apps: $suggestions."
    }

    val destinationPackage: String? get() = target?.packageName

    override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
        if (context.isCancelled()) return ToolExecutionResult.Cancelled("Cancelled before execution")
        resolve(context.platform)?.let { return ToolExecutionResult.Failure(it) }
        val match = requireNotNull(target)
        val targetPackage = match.packageName
        Log.d(TAG, "Resolved '$appName' -> $targetPackage (${match.label})")

        // --- Destination tier check: deny launch into BLOCKED apps ---
        val classifier = context.appClassifier
        if (classifier != null && classifier.classify(targetPackage) == AppTier.BLOCKED) {
            Log.w(TAG, "Denied open_app to BLOCKED package: $targetPackage")
            return ToolExecutionResult.Failure(
                "Cannot open '${match.label}': app is blocked by security policy."
            )
        }

        // --- Foreground check: skip re-launch if already open ---
        val currentPackage = context.platform.getCurrentPackageName()
        if (currentPackage != null && currentPackage == targetPackage) {
            Log.d(TAG, "'${match.label}' is already in the foreground, skipping launch")
            return ToolExecutionResult.Success(
                output = "'${match.label}' is already in the foreground." + saveChoice(targetPackage)
            )
        }

        // --- Launch ---
        val result = context.platform.launchApp(targetPackage)

        return when (result) {
            is ActionResult.Success -> {
                delay(UI_SETTLE_DELAY_MS)

                val snapshot = try {
                    context.platform.captureScreen()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to capture screen after app launch", e)
                    null
                }

                val observation = snapshot?.let {
                    buildObservation(it, context.platform, context.appClassifier)
                }

                ToolExecutionResult.Success(
                    output = "Launched ${match.label} ($targetPackage)" + saveChoice(targetPackage),
                    observation = observation
                )
            }
            is ActionResult.Failure -> ToolExecutionResult.Failure(
                "Failed to launch '${match.label}': ${result.reason}"
            )
            else -> ToolExecutionResult.Failure("Unexpected result: $result")
        }
    }

    private suspend fun saveChoice(packageName: String): String {
        val alias = params.optString("remember_as").trim().takeIf { it.isNotEmpty() } ?: return ""
        val saved = withContext(Dispatchers.IO) { memory?.rememberApp(alias, packageName) == true }
        return if (saved) " Remembered '$alias' for future tasks." else " Could not save the app preference."
    }

    /**
     * Find similar app names for error suggestions.
     *
     * Uses simple heuristics: prefix match, substring overlap, character similarity.
     */
    private fun findSimilarApps(
        searchTerm: String,
        apps: List<ai.closepaw.platform.AppInfo>,
        limit: Int = SUGGESTION_LIMIT
    ): List<String> {
        val term = searchTerm.lowercase()
        val termChars = term.toSet()

        data class ScoredApp(val label: String, val score: Int)

        val scored = apps.mapNotNull { app ->
            val label = app.label.lowercase()
            val score = when {
                // Prefix match (e.g. "gma" → "gmail")
                label.startsWith(term) || term.startsWith(label) -> 4
                // Substring match
                label.contains(term) || term.contains(label) -> 3
                // Package name contains term
                app.packageName.lowercase().contains(term) -> 2
                // Character overlap > 50%
                termChars.isNotEmpty() &&
                    termChars.intersect(label.toSet()).size > termChars.size / 2 -> 1
                else -> 0
            }
            if (score > 0) ScoredApp(app.label, score) else null
        }

        return scored
            .sortedByDescending { it.score }
            .take(limit)
            .map { it.label }
    }
}
