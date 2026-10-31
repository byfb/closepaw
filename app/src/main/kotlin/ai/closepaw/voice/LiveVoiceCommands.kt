package ai.closepaw.voice

import java.util.Locale
import kotlinx.serialization.json.JsonObject

/** Only explicit completed user utterances can end voice; partial speech and assistant text cannot. */
internal object LiveVoiceCommands {
    private val hangups = setOf(
        "hangup", "endvoice", "endthevoicecall", "endthisvoicecall", "endthecall", "stopgptlive",
        "canyouhangupnow", "hangupnow", "goodbye", "bye", "掛斷", "挂断", "掛斷電話", "挂断电话", "結束語音", "结束语音",
        "結束通話", "结束通话", "掛斷gptlive", "挂断gptlive", "停止語音", "停止语音",
        "可以掛斷了", "可以挂断了", "幫我掛斷", "帮我挂断", "唔該收線", "唔该收线",
        "收線", "收线", "收線啦", "收线啦", "再見", "再见",
    )

    fun isHangup(event: JsonObject): Boolean {
        if (event.string("type") != "turn.done") return false
        val turn = event.obj("turn") ?: return false
        if (turn.string("role") != "user") return false
        val text = turn.string("transcript") ?: return false
        val normalized = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
            .removePrefix("please").removePrefix("請").removePrefix("请")
            .removeSuffix("please").removeSuffix("thanks").removeSuffix("謝謝").removeSuffix("谢谢")
        return normalized in hangups
    }
}
