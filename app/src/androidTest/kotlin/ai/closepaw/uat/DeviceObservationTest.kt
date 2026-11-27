package ai.closepaw.uat

import ai.closepaw.app.AgentService
import ai.closepaw.perception.PerceptionConfig
import ai.closepaw.platform.AccessibilityPlatform
import ai.closepaw.protocol.SessionConfig
import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.impl.GetCurrentTimeTool
import ai.closepaw.tool.impl.OpenAppTool
import ai.closepaw.tool.impl.SearchAppsTool
import android.app.UiAutomation
import android.content.ComponentName
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in QA device test. Rebinds ClosePaw during instrumentation and restores the prior setting. */
@RunWith(AndroidJUnit4::class)
class DeviceObservationTest {
    @Test fun opensInstalledSettingsAndReadsOneRequestedScreenshot() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("deviceToolUat") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val resolver = instrumentation.targetContext.contentResolver
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val original = Settings.Secure.getString(resolver, key)
        val component = ComponentName(instrumentation.targetContext, AgentService::class.java)
        val others = original.orEmpty().split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != component }
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            // Starting instrumentation force-stops the app, including an already-bound service.
            Settings.Secure.putString(resolver, key, others.joinToString(":"))
            delay(300)
            Settings.Secure.putString(resolver, key, (others + component.flattenToString()).joinToString(":"))
            val service = withTimeout(15_000) {
                while (AgentService.instance == null) delay(100)
                requireNotNull(AgentService.instance)
            }
            verifyObservations(service)
        } finally {
            Settings.Secure.putString(resolver, key, original)
            automation.dropShellPermissionIdentity()
        }
    }

    private suspend fun verifyObservations(service: AgentService) {
        val device = AccessibilityPlatform(service, SessionConfig(perceptionConfig = PerceptionConfig.TreeFirst))
        val context = object : ToolExecutionContext {
            override val platform = device
            override val currentSnapshot = null
            override fun isCancelled() = false
        }
        val pkg = "com.android.settings"
        val matches = SearchAppsTool.createInvocation(JSONObject().put("query", pkg)).execute(context)
        assertTrue((matches as ToolExecutionResult.Success).output.contains(pkg))
        val open = OpenAppTool().createInvocation(JSONObject().put("app_name", pkg)).execute(context)
        assertTrue(open.toString(), open is ToolExecutionResult.Success)
        withTimeout(10_000) { while (device.getCurrentPackageName() != pkg) delay(100) }
        val tree = device.captureScreen()
        assertTrue(tree.elements.isNotEmpty())
        assertNull(tree.image)
        val image = device.captureScreenWithScreenshot().image
        assertTrue(image != null && image.bytes.isNotEmpty())
        assertNull(device.captureScreen().image)
        val strict = AccessibilityPlatform(service, SessionConfig(perceptionConfig = PerceptionConfig.AccessibilityOnly))
        assertNull(strict.captureScreenWithScreenshot().image)
        val clock = GetCurrentTimeTool.createInvocation(JSONObject()).execute(context) as ToolExecutionResult.Success
        assertEquals(ZoneId.systemDefault(), ZonedDateTime.parse(clock.output).zone)
    }
}
