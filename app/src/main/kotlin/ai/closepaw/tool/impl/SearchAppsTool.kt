package ai.closepaw.tool.impl

import ai.closepaw.platform.AppInfo
import ai.closepaw.tool.*
import org.json.JSONObject

internal fun searchInstalledApps(query: String, apps: List<AppInfo>): List<AppInfo> {
    val term = query.trim()
    val alias = AppAliases.PACKAGE_MAP[term.lowercase()]
    return apps.filter {
        it.label.contains(term, ignoreCase = true) || it.packageName.contains(term, ignoreCase = true) ||
            it.packageName == alias
    }.distinctBy { it.packageName }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
}

internal fun AppInfo.displayMatch() = "$label ($packageName)"

internal object SearchAppsTool : ToolSpec {
    override val name = ToolName.SearchApps.raw
    override val description = "Search installed launchable apps by label or package. Omit query to browse. Use the returned package name with open_app."
    override val parameterSchema = JSONObject("""{
        "type":"object","properties":{"query":{"type":"string"},"offset":{"type":"integer","minimum":0}},
        "additionalProperties":false
    }""")

    override fun validate(params: JSONObject) = when {
        params.has("query") && params.opt("query") !is String -> ValidationResult.Invalid("query must be text")
        params.has("offset") && (params.opt("offset") !is Int || params.getInt("offset") < 0) ->
            ValidationResult.Invalid("offset must be a nonnegative integer")
        else -> ValidationResult.Valid
    }

    override fun createInvocation(params: JSONObject) = object : ToolInvocation {
        override val params = params
        override val toolName = name
        override fun getDescription() = "Search installed apps"
        override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
            val apps = searchInstalledApps(params.optString("query"), context.platform.getInstalledApps())
            val offset = params.optInt("offset").coerceIn(0, apps.size)
            val page = apps.drop(offset).take(30)
            return textToolSuccess(buildString {
                appendLine("${apps.size} matching installed apps")
                page.forEach { appendLine(it.displayMatch()) }
                if (offset + page.size < apps.size) append("Next offset: ${offset + page.size}")
            }.trimEnd())
        }
    }
}
