package ai.closepaw.perception

/**
 * Controls which perception modalities the agent captures each turn.
 *
 * Sealed class ensures exhaustive when-handling and prevents invalid states.
 * Replaces the boolean enableScreenshotInput in SessionConfig.
 */
sealed class PerceptionConfig {

    /** Accessibility tree by default, with one screenshot when the agent requests it. */
    data object TreeFirst : PerceptionConfig()

    /** Accessibility tree only, including when tracing. */
    data object AccessibilityOnly : PerceptionConfig()

    /** Screenshot only. For apps with poor a11y support. */
    data class ScreenshotOnly(
        val maxDimension: Int = DEFAULT_MAX_DIMENSION,
        val jpegQuality: Int = DEFAULT_JPEG_QUALITY
    ) : PerceptionConfig()

    /** Both modalities. Richest perception, highest token cost. */
    data class Hybrid(
        val maxDimension: Int = DEFAULT_MAX_DIMENSION,
        val jpegQuality: Int = DEFAULT_JPEG_QUALITY
    ) : PerceptionConfig()

    /** Whether this config exposes accessibility data to the LLM prompt */
    val capturesAccessibility: Boolean get() = this !is ScreenshotOnly

    /** Whether this config captures screenshots */
    val capturesScreenshot: Boolean get() = this is ScreenshotOnly || this is Hybrid

    fun includesScreenshot(requested: Boolean): Boolean = capturesScreenshot || (this == TreeFirst && requested)

    companion object {
        const val DEFAULT_MAX_DIMENSION = 1024
        const val DEFAULT_JPEG_QUALITY = 70
        val DEFAULT: PerceptionConfig = TreeFirst

        fun fromMode(mode: String): PerceptionConfig = when (mode) {
            "tree_first" -> TreeFirst
            "screenshot_only" -> ScreenshotOnly()
            "hybrid" -> Hybrid()
            else -> AccessibilityOnly
        }
    }
}

/** Max dimension for screenshot scaling. Uses defaults when config does not capture screenshots. */
val PerceptionConfig.screenshotMaxDimension: Int
    get() = when (this) {
        is PerceptionConfig.ScreenshotOnly -> maxDimension
        is PerceptionConfig.Hybrid -> maxDimension
        PerceptionConfig.AccessibilityOnly, PerceptionConfig.TreeFirst -> PerceptionConfig.DEFAULT_MAX_DIMENSION
    }

/** JPEG quality for screenshot compression. Uses defaults when config does not capture screenshots. */
val PerceptionConfig.screenshotJpegQuality: Int
    get() = when (this) {
        is PerceptionConfig.ScreenshotOnly -> jpegQuality
        is PerceptionConfig.Hybrid -> jpegQuality
        PerceptionConfig.AccessibilityOnly, PerceptionConfig.TreeFirst -> PerceptionConfig.DEFAULT_JPEG_QUALITY
    }
