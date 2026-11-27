package com.example.wristai

import android.content.Context
import android.telephony.TelephonyManager
import com.example.voiceai.shared.tr

/**
 * 这一餐在哪个国家：饮食用当地的营养资料库（[FoodDb]）与当地常见份量（[portionRule]）。
 *
 * 顺序：设定指定的国家 → 这一餐的 GPS（Geocoder 反查的国家；没网路时用座标粗略判断）
 * → 手机电信网路的国家 → SIM 卡国家 → 手机地区设定。
 * 设定 `food_country`：auto（预设）/ TW / US / JP / KR。
 */
object FoodRegion {
    const val AUTO = "auto"
    private const val PREFS = "wristai_settings"
    private const val KEY = "food_country"

    /** 这次处理的那一餐在哪个国家（[ProcessMeetingWorker] 设；提示词的份量规则用）。 */
    @Volatile var active: String = "TW"

    /** 有内建营养资料库的国家（设定页的选项，依这个顺序）。 */
    val SUPPORTED = listOf("TW", "US", "JP", "KR")

    fun setting(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO
    fun set(c: Context, v: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).apply()

    /** 这一餐的国家（ISO 大写，例如 TW）；[meetingId] = null → 现在所在的国家。 */
    fun of(c: Context, meetingId: String? = null): String {
        setting(c).takeIf { it != AUTO }?.let { return it }
        meetingId?.let { MeetingStore.geo(c, it) }?.let { g -> (g.country ?: countryAt(g.lat, g.lon))?.let { return it } }
        return current(c)
    }

    /** 没有这一餐的位置时：电信网路 → SIM → 手机地区。 */
    fun current(c: Context): String {
        val tm = c.getSystemService(TelephonyManager::class.java)
        listOf(runCatching { tm?.networkCountryIso }.getOrNull(), runCatching { tm?.simCountryIso }.getOrNull())
            .firstOrNull { !it.isNullOrBlank() }?.let { return it.uppercase() }
        return java.util.Locale.getDefault().country.ifBlank { "TW" }.uppercase()
    }

    /**
     * 座标粗略判断国家（只认有资料库的几个；没网路反查不到时用）。框框是大概的范围，边界附近可能判错。
     */
    fun countryAt(lat: Double, lon: Double): String? = when {
        lat in 21.7..26.5 && lon in 118.0..122.2 -> "TW"           // 台湾、澎湖、金门、马祖
        lat in 33.0..38.7 && lon in 124.5..130.95 -> "KR"          // 南韩（含济州）
        lat in 24.0..45.6 && lon in 122.9..146.0 -> "JP"           // 日本
        lat in 24.4..49.5 && lon in -125.0..-66.9 -> "US"          // 美国本土
        lat in 51.0..71.5 && (lon in -180.0..-129.9 || lon in 172.0..180.0) -> "US"   // 阿拉斯加
        lat in 18.8..22.4 && lon in -160.5..-154.7 -> "US"         // 夏威夷
        else -> null
    }

    /** 国家名称（设定页、报告用）。 */
    fun name(cc: String): String = when (cc) {
        "TW" -> "台灣".tr
        "US" -> "美國".tr
        "JP" -> "日本".tr
        "KR" -> "韓國".tr
        else -> java.util.Locale("", cc).getDisplayCountry(java.util.Locale.getDefault()).ifBlank { cc }
    }

    /** 提示词：依当地常见的份量与做法估算。 */
    fun portionRule(cc: String): String = when (cc) {
        "TW" -> "依台灣常見的份量與做法估算（便當、小吃、麵店、手搖飲、便利商店等）。"
        "US" -> "這一餐在美國：依美國常見的份量與做法估算（餐廳份量通常較大；漢堡、三明治、沙拉、披薩、咖啡店飲料的 tall/grande/venti、汽水杯型等），單位換算成克（1 cup 約 240 ml、1 oz 約 28 g）。"
        "JP" -> "這一餐在日本：依日本常見的份量與做法估算（定食、丼飯、拉麵、便利商店便當與飯糰、居酒屋小菜等），食物名稱可以用日文原名並附中文。"
        "KR" -> "這一餐在韓國：依韓國常見的份量與做法估算（白飯一碗約 210 克、湯鍋、拌飯、韓式烤肉、小菜（반찬）、便利商店餐點等），食物名稱可以用韓文原名並附中文。"
        else -> "這一餐在「${name(cc)}」：依當地常見的份量與做法估算。"
    }
}
