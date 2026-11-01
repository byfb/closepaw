package ai.closepaw.voice

import ai.closepaw.tool.*
import ai.closepaw.tool.impl.EndGptLiveCallTool
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Test

class EndGptLiveCallToolTest {
    @Test fun `phone tool confirms shutdown only for its active call`() = runTest {
        var active = true
        val tool = EndGptLiveCallTool("current") { active.also { active = false } }
        val context = mockk<ToolExecutionContext>(relaxed = true)
        assertThat(tool.invoke(JSONObject().put("phone_number", "123"))).isInstanceOf(ToolExecutionResult.Failure::class.java)
        assertThat(active).isTrue()
        assertThat(tool.invoke(JSONObject().put("call_id", "old"))).isInstanceOf(ToolExecutionResult.Failure::class.java)
        assertThat(active).isTrue()
        val call = tool.createInvocation(JSONObject().put("call_id", "current"))
        assertThat(call.execute(context)).isInstanceOf(ToolExecutionResult.Success::class.java)
        assertThat(active).isFalse()
        assertThat(call.execute(context)).isInstanceOf(ToolExecutionResult.Failure::class.java)
    }

    @Test fun `a new call rejects the previous call ID`() = runTest {
        val previous = EndGptLiveCallTool("first") { true }
        val current = EndGptLiveCallTool("second") { error("stale invocation") }
        val arguments = JSONObject().put("call_id", "first")
        assertThat(previous.validate(arguments)).isEqualTo(ValidationResult.Valid)
        assertThat(current.invoke(arguments)).isInstanceOf(ToolExecutionResult.Failure::class.java)
    }
}
