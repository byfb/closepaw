package ai.closepaw.tool.impl

import com.google.common.truth.Truth.assertThat
import ai.closepaw.memory.MemoryStore
import ai.closepaw.platform.AppInfo
import ai.closepaw.test.FakeAndroidPlatform
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ValidationResult
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpenAppToolTest {
    @get:Rule val folder = TemporaryFolder()

    private fun contextWith(vararg apps: AppInfo) = object : ToolExecutionContext {
        override val platform = FakeAndroidPlatform(installedApps = apps.toList())
        override val currentSnapshot = null
        override fun isCancelled() = false
    }

    @Test fun `a disambiguated app persists across tasks, supports overrides, and is checked after removal`() = runTest {
        val first = AppInfo("org.first.maps", "Maps")
        val second = AppInfo("org.second.maps", "Maps")
        val third = AppInfo("org.third.maps", "Maps")
        val both = contextWith(first, second)
        val dir = folder.newFolder()
        suspend fun open(name: String, remember: String? = null, context: ToolExecutionContext = both): ToolExecutionResult {
            val params = JSONObject().put("app_name", name).put("remember_as", remember)
            return OpenAppTool(MemoryStore(dir)).createInvocation(params).execute(context)
        }
        val ambiguous = open("Maps") as ToolExecutionResult.Failure
        assertThat(ambiguous.error).contains(first.packageName)
        assertThat(ambiguous.error).contains(second.packageName)
        val selection = open(second.packageName, "Maps") as ToolExecutionResult.Success
        assertThat(selection.output).contains("Remembered")
        assertThat((open("maps") as ToolExecutionResult.Success).output).contains(second.packageName)
        open(first.packageName)
        assertThat(MemoryStore(dir).preferredAppPackage("Maps")).isEqualTo(second.packageName)
        open(first.packageName, "Maps")
        assertThat(MemoryStore(dir).preferredAppPackage("Maps")).isEqualTo(first.packageName)
        val afterRemoval = open("Maps", context = contextWith(second, third)) as ToolExecutionResult.Failure
        assertThat(afterRemoval.error).contains(third.packageName)
        assertThat(afterRemoval.error).doesNotContain(first.packageName)
        assertThat((open("Maps", context = contextWith(third)) as ToolExecutionResult.Success).output).contains(third.packageName)
    }

    @Test fun `search discovers launchable apps by label and package and supports browsing`() = runTest {
        val apps = (1..33).map { AppInfo("org.maps.app$it", "地圖 $it") }
        val context = contextWith(*apps.toTypedArray())
        suspend fun search(params: JSONObject) =
            (SearchAppsTool.createInvocation(params).execute(context) as ToolExecutionResult.Success).output
        assertThat(search(JSONObject().put("query", "APP31"))).contains("地圖 31 (org.maps.app31)")
        assertThat(search(JSONObject().put("query", "地圖"))).contains("33 matching installed apps")
        assertThat(search(JSONObject())).contains("Next offset: 30")
        assertThat(search(JSONObject().put("offset", 30)).lines()).hasSize(4)
    }

    @Test fun `device clock reports local date time offset and zone`() = runTest {
        val before = java.time.Instant.now()
        val result = GetCurrentTimeTool.createInvocation(JSONObject()).execute(contextWith()) as ToolExecutionResult.Success
        val time = ZonedDateTime.parse(result.output)
        assertThat(time.zone).isEqualTo(ZoneId.systemDefault())
        assertThat(time.toInstant()).isAtLeast(before)
        assertThat(time.toInstant()).isAtMost(java.time.Instant.now())
    }

    @Test
    fun `open_app requires app_name`() {
        val tool = OpenAppTool()
        val params = JSONObject()

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `open_app with empty app_name is invalid`() {
        val tool = OpenAppTool()
        val params = JSONObject().put("app_name", "")

        val result = tool.validate(params)

        assertThat(result).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `open_app with app_name is valid`() {
        val tool = OpenAppTool()
        val params = JSONObject().put("app_name", "Gmail")

        val result = tool.validate(params)

        assertThat(result).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `open_app with app_name and agent_thought is valid`() {
        val tool = OpenAppTool()
        val params = JSONObject()
            .put("app_name", "Settings")
            .put("agent_thought", "Need to change Wi-Fi settings")

        val result = tool.validate(params)

        assertThat(result).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `tool name is open_app`() {
        val tool = OpenAppTool()

        assertThat(tool.name).isEqualTo("open_app")
    }

    @Test
    fun `schema has required app_name`() {
        val tool = OpenAppTool()
        val schema = tool.parameterSchema

        val required = schema.getJSONArray("required")
        assertThat(required.length()).isEqualTo(1)
        assertThat(required.getString(0)).isEqualTo("app_name")
    }

    @Test
    fun `schema accepts an optional app preference`() {
        val tool = OpenAppTool()
        val properties = tool.parameterSchema.getJSONObject("properties")

        val keys = properties.keys().asSequence().toSet()
        assertThat(keys).containsExactly("app_name", "agent_thought", "remember_as")
    }

    @Test
    fun `alias map contains simple calendar pro`() {
        assertThat(AppAliases.PACKAGE_MAP["simple calendar pro"])
            .isEqualTo("com.simplemobiletools.calendar.pro")
    }

    @Test
    fun `alias map contains simple calendar`() {
        assertThat(AppAliases.PACKAGE_MAP["simple calendar"])
            .isEqualTo("com.simplemobiletools.calendar.pro")
    }

    @Test
    fun `alias map contains audio recorder`() {
        assertThat(AppAliases.PACKAGE_MAP["audio recorder"])
            .isEqualTo("com.dimowner.audiorecorder")
    }

    @Test
    fun `alias map contains pro expense`() {
        assertThat(AppAliases.PACKAGE_MAP["pro expense"])
            .isEqualTo("com.arduia.expense")
    }

    @Test
    fun `alias map contains markor`() {
        assertThat(AppAliases.PACKAGE_MAP["markor"])
            .isEqualTo("net.gsantner.markor")
    }

    @Test
    fun `alias map contains simple draw pro`() {
        assertThat(AppAliases.PACKAGE_MAP["simple draw pro"])
            .isEqualTo("com.simplemobiletools.draw.pro")
    }
}
