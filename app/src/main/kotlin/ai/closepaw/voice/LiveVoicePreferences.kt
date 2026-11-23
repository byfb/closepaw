package ai.closepaw.voice

import ai.closepaw.R

/** Voice preferences are snapshotted when a call starts, independently of the worker. */
data class LiveVoicePreferences(
    val voice: LiveVoice = LiveVoice.COVE,
    val language: LiveLanguage = LiveLanguage.AUTO,
    val tone: LiveTone = LiveTone.NATURAL,
    val customInstructions: String = "",
    val onScreenQuestions: Boolean = true,
) {
    fun instructions(): String = ai.closepaw.protocol.CustomInstructions.appendTo(
        "${language.instruction}\n${tone.instruction}", customInstructions,
    )

    internal fun idleReminderInstructions(reminder: String, hasUserSpeech: Boolean, appLanguage: String): String {
        val replyLanguage = when {
            language != LiveLanguage.AUTO -> language.instruction
            hasUserSpeech -> "Use the language of the user's most recent utterance."
            appLanguage == "zh" -> LiveLanguage.MANDARIN.instruction
            else -> LiveLanguage.ENGLISH.instruction
        }
        return "$replyLanguage Say this idle reminder once: $reminder " +
            "Then wait for the user. The phone will disconnect after 20 more seconds of inactivity. " +
            "Do not delegate a task or claim the call has already ended."
    }
}

enum class LiveVoice(val id: String, val label: String) {
    // Codex v3 uses the ChatGPT voice catalog, not the public Realtime v2 catalog.
    COVE("cove", "Cove"),
    JUNIPER("juniper", "Juniper"),
    MAPLE("maple", "Maple"),
    SPRUCE("spruce", "Spruce"),
    EMBER("ember", "Ember"),
    VALE("vale", "Vale"),
    BREEZE("breeze", "Breeze"),
    ARBOR("arbor", "Arbor"),
    SOL("sol", "Sol");
}

enum class LiveLanguage(@androidx.annotation.StringRes val label: Int, val instruction: String, val sample: String) {
    AUTO(R.string.ui_follow_conversation, "Reply in the user's language.", "Hello, I'm ready to help you with your phone."),
    CANTONESE(R.string.live_cantonese, "Speak Hong Kong Cantonese, using natural colloquial Cantonese. Use Traditional Chinese for text.", "你好，我準備好幫你處理手機上嘅工作。"),
    MANDARIN(R.string.live_mandarin, "Speak Mandarin Chinese. Use Traditional Chinese for text.", "你好，我準備好幫你處理手機上的工作。"),
    ENGLISH(R.string.live_english, "Speak English.", "Hello, I'm ready to help you with your phone.");
}

enum class LiveTone(@androidx.annotation.StringRes val label: Int, val instruction: String) {
    NATURAL(R.string.ui_natural, "Keep your delivery natural, concise, and conversational."),
    CALM(R.string.ui_calm, "Use a calm, steady voice with a relaxed pace. Keep replies concise."),
    LIVELY(R.string.ui_lively, "Use a warm, lively voice with an upbeat pace. Keep replies concise.");
}
