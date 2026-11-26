package com.example.voiceai.shared

/**
 * 手机 ↔ 手表 之间的 Data Layer 协议常量。
 *
 * 注意：Data Layer 只在「applicationId 相同、签名相同」的手机 App 与手表 App 之间传递，
 * 所以 app 与 wear 两个模块的 applicationId 必须都是 com.example.wristai。
 */
object WristAiProtocol {
    /** 手机端在 res/values/wear.xml 声明的 capability，手表用它找到手机节点。 */
    const val CAPABILITY_PHONE = "wristai_phone"

    /** 手表端声明的 capability。 */
    const val CAPABILITY_WATCH = "wristai_watch"

    /** 手表 → 手机：录音文件走 Channel，路径 = 前缀 + "/" + 录音 id。 */
    const val PATH_AUDIO_PREFIX = "/wristai/audio"

    /** 手机 → 手表：处理进度（payload = UTF-8 文本）。 */
    const val PATH_STATUS = "/wristai/status"

    /** 手机 → 手表：最终摘要（payload = MeetingSummary JSON）。 */
    const val PATH_SUMMARY = "/wristai/summary"

    fun audioPath(recordingId: String) = "$PATH_AUDIO_PREFIX/$recordingId"

    /** 手表 → 手机：遥控手机录音，payload = [CMD_START] / [CMD_STOP] / [CMD_QUERY]。 */
    const val PATH_PHONE_REC_CMD = "/wristai/phone_rec/cmd"
    const val CMD_START = "start"
    const val CMD_STOP = "stop"
    const val CMD_QUERY = "query"

    /** 手机 → 手表：介面语言，payload = "zh" / "en" / "ko"（手表跟着手机的介面语言）。 */
    const val PATH_UI_LANG = "/wristai/ui_lang"

    /** 手机 → 手表：外观主题，payload = "ink"（水墨）/ "dark"。 */
    const val PATH_THEME = "/wristai/theme"

    /** 手表 → 手机：要一笔录音的完整报告，payload = 录音 id。 */
    const val PATH_DETAIL_REQ = "/wristai/detail/req"

    /** 手机 → 手表：完整报告，payload = JSON {id, title, status, markdown, durationSec, createdAt}。 */
    const val PATH_DETAIL = "/wristai/detail/res"

    /** 手表 → 手机：要录音清单（报告书页），payload = 查询 JSON {q, kind, month, offset, limit, key}。路径放在 /wristai/detail 底下，沿用手机的 message filter。 */
    const val PATH_LIST_REQ = "/wristai/detail/list_req"

    /** 手机 → 手表：录音清单（可搜寻 / 依月份 / 分页），payload 格式见手机端 WatchNotifier.sendList。 */
    const val PATH_LIST = "/wristai/detail/list"

    /** 手表 → 手机：录音的 GPS 戳记，payload = JSON {id, lat, lon, accuracy, at}（[GeoFix]）。 */
    const val PATH_GEO = "/wristai/geo"

    /**
     * 手表 → 手机：手表开始录音了，请手机定位（GPS 以联动的手机为主要来源），payload = 录音 id。
     * 手机回 [PATH_GEO_ACK]，payload = "录音id|1"（拿到位置）或 "录音id|0"（拿不到，手表改用自己的 GPS）。
     */
    const val PATH_GEO_REQ = "/wristai/geo/req"
    const val PATH_GEO_ACK = "/wristai/geo/ack"

    /** 手机 → 手表：今天的饮食热量，payload = "已吃大卡/目标大卡/餐数"（文字）。 */
    const val PATH_FOOD_TODAY = "/wristai/food_today"

    /** 手机 → 手表：手机录音状态，payload = 开始时间（毫秒，文字）；"0" = 没在录。 */
    const val PATH_PHONE_REC_STATE = "/wristai/phone_rec/state"

    /**
     * 手表 → 手机：长会议边录边传的分段（AAC m4a）。
     * 路径 = 前缀/录音id/序号/PCM样本数/end|more。样本数让手机能去掉 AAC 编码器在每段开头补的静音。
     */
    const val PATH_SEGMENT_PREFIX = "/wristai/seg"

    fun segmentPath(s: Segment) =
        "$PATH_SEGMENT_PREFIX/${s.recordingId}/${s.seq}/${s.samples}/${if (s.last) "end" else "more"}"

    fun parseSegmentPath(path: String): Segment? {
        val p = path.removePrefix("$PATH_SEGMENT_PREFIX/").takeIf { it != path }?.split('/') ?: return null
        if (p.size != 4 || p[0].isBlank()) return null
        val seq = p[1].toIntOrNull() ?: return null
        val samples = p[2].toLongOrNull() ?: return null
        if (p[3] != "end" && p[3] != "more") return null
        return Segment(p[0], seq, samples, p[3] == "end")
    }

    /** 分段文件在手表上的文件名（同样带齐资料，重开机后也能重送）。 */
    fun segmentFileName(s: Segment) = "${s.recordingId}__${s.seq}__${s.samples}__${if (s.last) "end" else "more"}.m4a"

    fun parseSegmentFileName(name: String): Segment? {
        val p = name.removeSuffix(".m4a").takeIf { it != name }?.split("__") ?: return null
        if (p.size != 4) return null
        return parseSegmentPath("$PATH_SEGMENT_PREFIX/${p.joinToString("/")}")
    }

    /**
     * 辨识语言（国语 / 台语 / 客语＋腔调），手机与手表共用同一个设定：
     * - 手表 → 手机 [PATH_LANG_SET]：手表上改了（路径在 /wristai/phone_rec 底下，沿用手机的 message filter）。
     * - 手机 → 手表 [PATH_LANG]：手机上改了、或手表打开 App（[CMD_QUERY]）时推目前的设定。
     * payload = "zh" / "nan" / "hak|sixian"（见 [SpeechLangs]）。
     */
    const val PATH_LANG_SET = "/wristai/phone_rec/lang"
    const val PATH_LANG = "/wristai/lang"

    fun recordingIdFromPath(path: String): String? =
        path.removePrefix("$PATH_AUDIO_PREFIX/").takeIf { it.isNotBlank() && it != path }
}

/** 录音格式：16 kHz、单声道、16-bit PCM。语音识别的标准格式，文件也不会太大（约 1.9 MB/分钟）。 */
object AudioSpec {
    /** 分段长度：5 分钟一段，录音中就陆续传到手机，手表只需暂存当前这段。 */
    const val SEGMENT_SECONDS = 300
    /** 分段压缩码率：48 kbps AAC，约 22 MB/小时（WAV 的 1/5），6 小时会议约 130 MB。 */
    const val SEGMENT_BIT_RATE = 48_000
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
}

/** 一段录音。[samples] = 这段的 PCM 样本数；[last] = 录音的最后一段。 */
data class Segment(val recordingId: String, val seq: Int, val samples: Long, val last: Boolean)

/**
 * 辨识语言的代号与名称（手表用；手机的 `SpeechLang` 是同一套代号）。
 * 预设国语；客语才有腔调，预设四县。
 */
object SpeechLangs {
    val LANGS = listOf("zh" to "國語", "nan" to "國台語", "hak" to "客語",
        "en" to "英語", "ja" to "日語", "ko" to "韓語", "yue" to "粵語", "vi" to "越南語", "th" to "泰語", "id" to "印尼語")
    val DIALECTS = listOf("sixian" to "四縣", "hailu" to "海陸", "dapu" to "大埔", "raoping" to "饒平", "zhaoan" to "詔安", "nansixian" to "南四縣")

    fun lang(code: String?): String = LANGS.firstOrNull { it.first == code }?.first ?: "zh"
    fun dialect(code: String?): String = DIALECTS.firstOrNull { it.first == code }?.first ?: "sixian"

    /** "zh" / "nan" / "hak|hailu" */
    fun encode(lang: String?, dialect: String?): String = lang(lang).let { if (it == "hak") "hak|" + dialect(dialect) else it }

    /** 反过来：回传 (语言, 腔调)；腔调只有客语才有。 */
    fun decode(payload: String?): Pair<String, String?> {
        val p = payload.orEmpty().trim().split('|')
        val l = lang(p.getOrNull(0))
        return l to (if (l == "hak") dialect(p.getOrNull(1)) else null)
    }

    /** 「國語」「國台語」「客語・海陸」 */
    fun display(lang: String?, dialect: String?): String {
        val l = lang(lang)
        val name = LANGS.first { it.first == l }.second
        return if (l == "hak") name + "・" + DIALECTS.first { it.first == dialect(dialect) }.second else name
    }
}
