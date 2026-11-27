package ai.closepaw.tool.impl

import ai.closepaw.tool.ToolExecutionContext
import ai.closepaw.tool.ToolExecutionResult
import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolName
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.textToolSuccess
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

internal abstract class DeviceObservationTool(
    private val id: ToolName,
    final override val description: String,
) : ToolSpec {
    override val name = id.raw
    override val parameterSchema = JSONObject("""{"type":"object","properties":{},"additionalProperties":false}""")
    override fun validate(params: JSONObject) = if (params.length() == 0) ValidationResult.Valid
        else ValidationResult.Invalid("$name takes no arguments")

    protected abstract suspend fun read(context: ToolExecutionContext): ToolExecutionResult

    override fun createInvocation(params: JSONObject) = object : ToolInvocation {
        override val params = params
        override val toolName = name
        override fun getDescription() = id.displayName
        override suspend fun execute(context: ToolExecutionContext) = read(context)
    }
}

internal object RequestScreenshotTool : DeviceObservationTool(
    ToolName.RequestScreenshot,
    "Read one screenshot with the next screen observation when text and layout are insufficient, such as an unlabeled icon or canvas. Call this alone, then inspect the image.",
) {
    override suspend fun read(context: ToolExecutionContext) =
        textToolSuccess("One screenshot requested for the next observation.")
}

internal object GetCurrentTimeTool : DeviceObservationTool(
    ToolName.GetCurrentTime,
    "Read the current date, time, UTC offset and timezone from the device clock.",
) {
    override suspend fun read(context: ToolExecutionContext) =
        textToolSuccess(DateTimeFormatter.ISO_ZONED_DATE_TIME.format(ZonedDateTime.now()))
}
