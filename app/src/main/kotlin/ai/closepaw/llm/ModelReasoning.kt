package ai.closepaw.llm

/** Model-advertised efforts take precedence; null means metadata is unavailable. */
object ModelReasoning {
    private val effortId = Regex("[a-z][a-z0-9_-]{0,31}")

    fun isValid(value: String): Boolean = effortId.matches(value)

    fun options(entry: ModelEntry): List<String> {
        entry.supportedReasoningEfforts?.let { efforts ->
            return efforts.filter(::isValid).distinct()
        }
        if (entry.provider != LLMProvider.OPENAI_API && entry.provider != LLMProvider.OPENAI_CODEX) {
            return emptyList()
        }
        // /v1/models omits capability metadata. These bundled families have
        // documented effort support; newer Codex catalogs override this fallback.
        val id = entry.modelId
        if (Regex("gpt-5\\.[245](?:-\\d{4}-\\d{2}-\\d{2})?").matches(id)) {
            return listOf("none", "low", "medium", "high", "xhigh")
        }
        if (id.contains("-pro") || id.startsWith("o1-mini") || id.startsWith("o1-preview")) return emptyList()
        return if (id.startsWith("gpt-5") || id.startsWith("gpt-6") ||
            Regex("o[134](?:-.*)?").matches(id)) {
            listOf("low", "medium", "high")
        } else emptyList()
    }

    /** Default and unsupported saved choices omit the parameter entirely. */
    fun effectiveEffort(entry: ModelEntry, selected: String?): String? =
        selected?.takeIf { it in options(entry) }
}
