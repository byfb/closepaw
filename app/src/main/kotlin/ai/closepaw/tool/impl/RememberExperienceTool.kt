package ai.closepaw.tool.impl

import ai.closepaw.memory.MemoryScope
import ai.closepaw.memory.MemorySchema
import ai.closepaw.memory.MemorySection
import ai.closepaw.memory.MemoryStore
import ai.closepaw.memory.MemoryOperation
import ai.closepaw.memory.SaveResult
import ai.closepaw.protocol.AppTier
import ai.closepaw.tool.AppClassifier
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.appendReason
import ai.closepaw.tool.textToolSuccess
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class RememberExperienceTool(
    private val store: MemoryStore,
    private val appClassifier: AppClassifier
) : ToolSpec {
    companion object {
        private val SCOPE_VALUES = JSONArray(MemoryScope.entries.map { it.wireValue })
        private val SECTION_VALUES = JSONArray(MemorySection.entries.map { it.wireValue })
    }

    override val name: String = "remember_experience"

    override val description: String =
        """
        Add, correct or remove a durable learning in shared long-term memory for phone tasks and live voice.

        Use `scope=user` for cross-app user facts/preferences, `scope=device` for device facts/pitfalls/verification, and `scope=app` for app-specific overrides/preferences/operational notes.
        App operational notes should be plain-language notes, not `[pitfall]` or `[verification]` prefixes.
        Don't save task-specific steps or info already covered by App Skills. Keep to 1-2 generalized sentences.
        Read recalled memory first: skip known facts; replace outdated or contradicted entries instead of appending both versions.
        operation defaults to append. For replace/delete, copy the complete old entry into existing_content exactly
        (the bullet/timestamp may be omitted). It must match one entry in the chosen section; no partial phrases.
        replace uses content as the corrected fact. delete removes only that entry; omit content.
        On a conflict, read the current memory before trying again. Do not append a correction after a failed replacement.
        """.trimIndent()

    override val parameterSchema: JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("agent_thought", JSONObject().apply {
                    put("type", "string")
                    put("description", "Brief reason for saving this experience")
                })
                put("scope", JSONObject().apply {
                    put("type", "string")
                    put("enum", SCOPE_VALUES)
                    put("description", "Memory scope: user, device, or app")
                })
                put("section", JSONObject().apply {
                    put("type", "string")
                    put("enum", SECTION_VALUES)
                    put("description", "Target section within that scope")
                })
                put("content", JSONObject().apply {
                    put("type", "string")
                    put("description", "The learning to save (1-2 generalized sentences)")
                })
                put("operation", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(MemoryOperation.entries.map { it.wireValue }))
                    put("description", "append (default), replace an outdated entry, or delete an entry to forget")
                })
                put("existing_content", JSONObject().apply {
                    put("type", "string")
                    put("description", "Exact complete existing entry, required for replace/delete")
                })
                put("package_name", JSONObject().apply {
                    put("type", "string")
                    put("description", "Target app package name (required when scope = app)")
                })
            })
            put("required", JSONArray(listOf("scope", "section")))
            put("additionalProperties", false)
        }

    override fun validate(params: JSONObject): ValidationResult {
        val operation = MemoryOperation.fromWireValue(params.optString("operation", "append"))
            ?: return ValidationResult.Invalid("operation must be append, replace, or delete")
        val scope = MemoryScope.fromWireValue(params.optString("scope"))
        if (scope == null) {
            return ValidationResult.Invalid("scope must be: user, device, or app")
        }
        val section = MemorySection.fromWireValue(params.optString("section"))
        if (section == null) {
            return ValidationResult.Invalid(
                "section must be one of: ${MemorySection.entries.joinToString { it.wireValue }}"
            )
        }
        if (!MemorySchema.isSectionAllowed(scope, section)) {
            val allowed = MemorySchema.sectionsFor(scope).joinToString { it.wireValue }
            return ValidationResult.Invalid("section must be one of: $allowed for scope=${scope.wireValue}")
        }
        val content = params.optString("content", "").trim()
        if (operation != MemoryOperation.DELETE && content.isEmpty()) return ValidationResult.Invalid("content must not be empty")
        if (operation == MemoryOperation.DELETE && content.isNotEmpty()) return ValidationResult.Invalid("delete must omit content")
        if (content.length > store.maxContentLength) {
            return ValidationResult.Invalid("content too long (max ${store.maxContentLength} chars)")
        }
        val existing = params.optString("existing_content").trim()
        if (operation != MemoryOperation.APPEND && (existing.isEmpty() || existing.contains('\n') || existing.length > store.maxFileBytes))
            return ValidationResult.Invalid("existing_content must be one exact complete entry for replace/delete")
        if (operation == MemoryOperation.APPEND && existing.isNotEmpty())
            return ValidationResult.Invalid("existing_content is only allowed for replace/delete")
        val pkg = params.optString("package_name", "").trim()
        if (scope == MemoryScope.APP) {
            if (pkg.isEmpty()) return ValidationResult.Invalid("package_name required when scope = app")
            if (!pkg.matches(Regex("^[a-zA-Z0-9_.]+$"))) {
                return ValidationResult.Invalid("package_name contains invalid characters")
            }
        } else if (pkg.isNotEmpty()) {
            return ValidationResult.Invalid("package_name is only allowed when scope = app")
        }
        return ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val agentThought = params.optString("agent_thought", "").trim()
        return object : ToolInvocation {
            override val toolName = name
            override val params = params
            override fun getDescription() = appendReason("Update memory (${params.optString("scope")}/${params.optString("section")})", agentThought)
            override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
                if (context.isCancelled()) return ToolExecutionResult.Cancelled()
                return invoke(params, context.platform.getCurrentPackageName())
            }
        }
    }

    /** Same validation, policy and persistence path for Core and the live call controller. */
    suspend fun invoke(params: JSONObject, currentPackage: String?): ToolExecutionResult = withContext(Dispatchers.IO) {
        val validation = validate(params)
        if (validation is ValidationResult.Invalid) return@withContext ToolExecutionResult.Failure(validation.errors.joinToString())
        val scope = requireNotNull(MemoryScope.fromWireValue(params.getString("scope")))
        val section = requireNotNull(MemorySection.fromWireValue(params.getString("section")))
        val operation = requireNotNull(MemoryOperation.fromWireValue(params.optString("operation", "append")))
        val packageName = params.optString("package_name").trim().ifEmpty { null }
        // Layer 4: Memory Gate — block writes when foreground app is BLOCKED
        val currentPkg = currentPackage
        if (appClassifier.classify(currentPkg) == AppTier.BLOCKED) {
            return@withContext ToolExecutionResult.Failure(
                "Cannot save memory: current app ($currentPkg) is blocked by security policy"
            )
        }

        // Layer 4b: Block app-scoped writes targeting a BLOCKED package
        if (scope == MemoryScope.APP && packageName != null &&
            appClassifier.classify(packageName) == AppTier.BLOCKED
        ) {
            return@withContext ToolExecutionResult.Failure(
                "Cannot save memory: target app is restricted by security policy"
            )
        }

        coroutineContext.ensureActive()
        when (val result = store.update(scope, section, operation, params.optString("content"),
            params.optString("existing_content").ifBlank { null }, packageName)) {
            SaveResult.Success -> textToolSuccess("Long-term memory updated (${scope.wireValue}/${section.wireValue}, ${operation.wireValue}).")
            SaveResult.Conflict -> ToolExecutionResult.Failure("Memory conflict: old entry is missing, changed, or not unique. Read current memory before retrying.")
            SaveResult.TooLarge -> ToolExecutionResult.Failure("Memory file is full; remove obsolete entries before adding more.")
            SaveResult.InvalidScope -> ToolExecutionResult.Failure("Invalid memory target.")
            is SaveResult.IoError -> ToolExecutionResult.Failure("Failed to update memory: ${result.message}")
        }
    }
}
