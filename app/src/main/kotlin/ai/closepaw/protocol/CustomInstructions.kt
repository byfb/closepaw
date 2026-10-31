package ai.closepaw.protocol

/** User preferences supplement the role prompt; runtime tool permissions still apply. */
object CustomInstructions {
    const val MAX_LENGTH = 12_000

    fun appendTo(base: String, custom: String): String {
        val instructions = custom.trim().take(MAX_LENGTH)
        return if (instructions.isEmpty()) base else "$base\n\nUser custom instructions:\n$instructions"
    }
}
