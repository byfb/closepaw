package ai.closepaw.voice

/** Voice preferences are snapshotted when a call starts, independently of the worker. */
data class LiveVoicePreferences(
    val voice: LiveVoice = LiveVoice.MARIN,
    val language: LiveLanguage = LiveLanguage.AUTO,
    val tone: LiveTone = LiveTone.NATURAL,
) {
    fun instructions(): String = "${language.instruction}\n${tone.instruction}"
}

enum class LiveVoice(val id: String, val label: String) {
    MARIN("marin", "Marin"), CEDAR("cedar", "Cedar");
}

enum class LiveLanguage(val label: String, val instruction: String, val sample: String) {
    AUTO("Follow conversation", "Reply in the user's language.", "Hello, I'm ready to help you with your phone."),
    CANTONESE("香港粵語", "Speak Hong Kong Cantonese, using natural colloquial Cantonese. Use Traditional Chinese for text.", "你好，我準備好幫你處理手機上嘅工作。"),
    MANDARIN("普通話", "Speak Mandarin Chinese. Use Traditional Chinese for text.", "你好，我準備好幫你處理手機上的工作。"),
    ENGLISH("English", "Speak English.", "Hello, I'm ready to help you with your phone.");
}

enum class LiveTone(val label: String, val instruction: String) {
    NATURAL("Natural", "Keep your delivery natural, concise, and conversational."),
    CALM("Calm", "Use a calm, steady voice with a relaxed pace. Keep replies concise."),
    LIVELY("Lively", "Use a warm, lively voice with an upbeat pace. Keep replies concise.");
}
