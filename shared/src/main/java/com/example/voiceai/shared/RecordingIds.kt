package com.example.voiceai.shared

import android.content.Context
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 录音编号：`[前缀]yyyyMMdd_HHmmss_<装置代码>`，例 `20261124_225933_k7q2`、`food_20261124_120501_k7q2`。
 *
 * - 时间 = 开始录音的时间（精确到秒，看得懂、可以排序）。
 * - 装置代码 = 这台装置（手机或手表）安装 App 时随机产生的 4 码小写英数字，存在本机，之后都一样。
 *   两台装置共用同一个 Google 云端硬碟备份时，就算同一秒开始录音编号也不会一样（以前没有代码，会互相盖掉）。
 * - 旧的编号没有装置代码（`20261124_225933`），照样能用；新旧格式不会撞。
 * - 前缀：`food_` 饮食、`imp_` 汇入、`call_` 通话；手表、手机录音没有前缀。
 * iOS 也用同一种格式（见 交接/IOS_TO_ANDROID.md / DRIVE_BACKUP.md）。
 */
object RecordingIds {
    private const val PREFS = "dylan_ids"
    private const val KEY = "device_code"
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"   // 去掉容易看错的 l、o、0、1
    private val TIME = Regex("""(\d{8}_\d{6})""")

    /** 这台装置的代码（第一次叫的时候产生）。 */
    @Synchronized
    fun deviceCode(c: Context): String {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY, null)?.takeIf { isCode(it) }?.let { return it }
        val r = SecureRandom()
        val code = (1..4).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
        p.edit().putString(KEY, code).apply()
        return code
    }

    fun isCode(s: String) = s.length == 4 && s.all { it in ALPHABET }

    /** 新的录音编号。 */
    fun newId(c: Context, prefix: String = "", at: Date = Date()): String = format(prefix, at, deviceCode(c))

    fun format(prefix: String, at: Date, code: String): String =
        prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(at) + "_" + code

    /** 编号里的时间（毫秒）；看不懂 → null。新旧格式、有没有前缀都可以。 */
    fun timeOf(id: String): Long? = TIME.find(id)?.value?.let {
        runCatching { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(it)?.time }.getOrNull()
    }

    /** 编号里的装置代码；旧格式没有 → null。 */
    fun deviceOf(id: String): String? =
        id.substringAfterLast('_', "").takeIf { isCode(it) && TIME.containsMatchIn(id.substringBeforeLast('_')) }
}
