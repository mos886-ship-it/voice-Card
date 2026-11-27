package com.example.wristai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import com.example.voiceai.shared.tr

/**
 * 即时翻译能用哪些语言——直接问手机：
 * - 翻成：ML Kit 装置端翻译支援的全部语言（约 59 种，[TranslateLanguage.getAllLanguages]）。
 * - 说的：手机语音辨识支援的语言（Android 13+ 问辨识服务：离线已装 / 可下载离线 / 线上），
 *   而且 ML Kit 翻得了的。问不到（旧手机）就用常见语言的预设地区。
 * 语言代码：说的用辨识服务的 BCP-47（例如 zh-TW、en-GB），翻译用 ML Kit 代码（zh、en）。
 */
object TranslateLangs {
    /** 一个辨识语言（含地区口音）的状态。 */
    enum class Status { OFFLINE, DOWNLOADABLE, ONLINE }

    data class Support(
        val installed: Set<String> = emptySet(),
        val downloadable: Set<String> = emptySet(),
        val online: Set<String> = emptySet(),
        /** true = 真的问到了；false = 问不到，用预设清单 */
        val queried: Boolean = false,
    ) {
        fun status(tag: String): Status? {
            val t = norm(tag)
            return when {
                installed.any { norm(it) == t } -> Status.OFFLINE
                downloadable.any { norm(it) == t } -> Status.DOWNLOADABLE
                online.any { norm(it) == t } -> Status.ONLINE
                else -> null
            }
        }
        val all: Set<String> get() = installed + downloadable + online
    }

    /** 问不到辨识服务时的预设（Google 线上辨识大多支援）。 */
    val DEFAULT_TAGS = listOf(
        "zh-TW", "zh-CN", "yue-Hant-HK", "en-US", "en-GB", "ja-JP", "ko-KR", "vi-VN", "th-TH", "id-ID", "ms-MY",
        "fil-PH", "hi-IN", "bn-IN", "ta-IN", "te-IN", "ur-PK", "ar-SA", "he-IL", "fa-IR", "tr-TR", "ru-RU", "uk-UA",
        "pl-PL", "cs-CZ", "sk-SK", "hu-HU", "ro-RO", "bg-BG", "el-GR", "de-DE", "fr-FR", "es-ES", "es-MX", "it-IT",
        "pt-BR", "pt-PT", "nl-NL", "sv-SE", "da-DK", "nb-NO", "fi-FI", "hr-HR", "sl-SI", "lt-LT", "lv-LV", "et-EE",
        "ca-ES", "gl-ES", "af-ZA", "sw-KE", "is-IS", "ga-IE", "cy-GB", "ka-GE", "mr-IN", "gu-IN", "kn-IN",
    )

    private val _support = MutableStateFlow(Support(online = DEFAULT_TAGS.toSet()))
    val support: StateFlow<Support> = _support.asStateFlow()

    /** ML Kit 能翻的全部语言（代码）。 */
    val targets: List<String> by lazy { TranslateLanguage.getAllLanguages().sorted() }

    /** 辨识语言代码 → ML Kit 代码（粤语、普通话都当中文翻；希伯来、印尼旧代码；菲律宾语）。 */
    fun mlkitOf(tag: String): String? {
        val lang = Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase()
        val m = when (lang) {
            "cmn", "yue", "zh" -> "zh"
            "iw" -> "he"; "in" -> "id"; "ji" -> "yi"
            "fil" -> "tl"; "nb", "nn" -> "no"
            else -> lang
        }
        return m.takeIf { it in targets }
    }

    fun norm(tag: String) = tag.replace('_', '-').lowercase()

    /** 说的语言选项：依 ML Kit 语言分组，每组是这个语言的各个地区口音（辨识服务的代码）。 */
    fun sources(s: Support = support.value): Map<String, List<String>> =
        s.all.mapNotNull { t -> mlkitOf(t)?.let { it to t } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, tags) -> tags.distinctBy { norm(it) }.sortedBy { regionName(it) } }
            .toSortedMap()

    /**
     * 这个语言预设用哪个地区：最近用过的（[preferred]）→ 手机的地区 → 台湾（中文）→ 常见的（美国英语、日本日语…）→ 离线有的 → 第一个。
     */
    fun defaultTag(mlkit: String, s: Support = support.value, preferred: List<String> = emptyList()): String {
        val tags = sources(s)[mlkit].orEmpty()
        fun has(t: String) = tags.firstOrNull { norm(it) == norm(t) }
        val region = Locale.getDefault().country
        return preferred.firstNotNullOfOrNull { if (mlkitOf(it) == mlkit) has(it) else null }
            ?: tags.firstOrNull { Locale.forLanguageTag(it).country.equals(region, true) }
            ?: (if (mlkit == "zh") tags.firstOrNull { it.contains("Hant-TW", true) } ?: tags.firstOrNull { it.contains("TW", true) } else null)
            ?: DEFAULT_TAGS.firstNotNullOfOrNull { if (mlkitOf(it) == mlkit) has(it) else null }
            ?: tags.firstOrNull { s.status(it) == Status.OFFLINE }
            ?: tags.firstOrNull()
            ?: DEFAULT_TAGS.firstOrNull { mlkitOf(it) == mlkit }
            ?: mlkit
    }

    /** 语言名称：介面语言的写法＋该语言自己的写法（日文 · 日本語）。中文翻成的结果是繁体，写「繁體中文」。 */
    fun name(code: String, target: Boolean = false): String {
        if (target && code == "zh") return "繁體中文".tr
        val l = Locale.forLanguageTag(mlkitOf(code) ?: code)
        val ui = l.getDisplayLanguage(com.example.voiceai.shared.I18n.locale).ifEmpty { code }
        val self = l.getDisplayLanguage(l).replaceFirstChar { it.uppercase() }
        return if (self.isEmpty() || self.equals(ui, true)) ui else "$ui · $self"
    }

    /** 只要介面语言的写法（短，给按钮）。 */
    fun shortName(code: String, target: Boolean = false): String {
        if (target && code == "zh") return "繁體中文".tr
        val l = Locale.forLanguageTag(mlkitOf(code) ?: code)
        return l.getDisplayLanguage(com.example.voiceai.shared.I18n.locale).ifEmpty { code }
    }

    /** 地区口音：「台灣」「美國」「香港 · 粵語」。 */
    fun regionName(tag: String): String {
        val l = Locale.forLanguageTag(tag.replace('_', '-'))
        val ui = com.example.voiceai.shared.I18n.locale
        val region = l.getDisplayCountry(ui)
        val extra = when (l.language) { "yue" -> l.getDisplayLanguage(ui); "cmn" -> l.getDisplayScript(ui); else -> "" }
        return listOf(region, extra).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { tag }
    }

    /**
     * 问手机的辨识服务（离线 + Google 线上）支援哪些语言；要在主执行绪建辨识器。
     * 第一次进翻译页、设定页时呼叫；问到就记着。
     */
    suspend fun refresh(c: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val app = c.applicationContext
        val out = withContext(Dispatchers.Main) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            var installed = emptySet<String>(); var downloadable = emptySet<String>(); var online = emptySet<String>()
            var ok = false
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) {
                ask(SpeechRecognizer.createOnDeviceSpeechRecognizer(app), intent)?.let {
                    ok = true
                    installed = it.installedOnDeviceLanguages.toSet()
                    downloadable = (it.supportedOnDeviceLanguages + it.pendingOnDeviceLanguages).toSet() - installed
                }
            }
            for (svc in services(app)) {
                ask(SpeechRecognizer.createSpeechRecognizer(app, svc), intent)?.let {
                    ok = true
                    online = online + it.onlineLanguages + it.supportedOnDeviceLanguages + it.installedOnDeviceLanguages
                }
            }
            if (!ok) null else Support(installed, downloadable, online.ifEmpty { DEFAULT_TAGS.toSet() }, queried = true)
        }
        if (out != null) {
            _support.value = out
            // 刚下载好离线语音：即时翻译不要再记着「这个语言没有离线」
            withContext(Dispatchers.Main) { LiveCaptions.offlineInstalled(out) }
        }
    }

    /** 下载离线语音跟页面无关（离开页面还会继续查）。 */
    private val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    /** 正在下载的离线语音：辨识代码 → 进度 0～100（-1 = 下载中、手机没给进度）。 */
    private val _downloading = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloading: StateFlow<Map<String, Int>> = _downloading.asStateFlow()

    /**
     * 下载离线语音（手机系统会问要不要下载）。Android 14+ 有进度、下载好马上更新标签；
     * Android 13 没有进度，每 5 秒查一次，最多 3 分钟。
     */
    fun downloadOffline(c: Context, tag: String) {
        if (Build.VERSION.SDK_INT < 33 || tag in _downloading.value) return
        val app = c.applicationContext
        val r = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(app) }.getOrNull() ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        fun set(p: Int?) { _downloading.value = if (p == null) _downloading.value - tag else _downloading.value + (tag to p) }
        set(-1)
        if (Build.VERSION.SDK_INT >= 34) {
            val ok = runCatching {
                r.triggerModelDownload(intent, app.mainExecutor, object : android.speech.ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) = set(completedPercent)
                    override fun onSuccess() { set(null); r.destroy(); bg.launch { refresh(app) } }
                    override fun onScheduled() { set(null); r.destroy(); bg.launch { refresh(app) } }
                    override fun onError(error: Int) { set(null); r.destroy(); bg.launch { refresh(app) } }
                })
            }.isSuccess
            if (ok) return
        }
        runCatching { r.triggerModelDownload(intent) }.onFailure { set(null); r.destroy(); return }
        bg.launch {
            for (i in 0 until 36) {
                kotlinx.coroutines.delay(5_000)
                refresh(app)
                if (_support.value.status(tag) == Status.OFFLINE) break
            }
            set(null); runCatching { r.destroy() }
        }
    }

    /** 手机上的辨识服务（Google 的排前面）。 */
    fun services(c: Context): List<ComponentName> =
        c.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .sortedBy { if (it.packageName.contains("google")) 0 else 1 }

    private suspend fun ask(r: SpeechRecognizer, intent: Intent): RecognitionSupport? {
        if (Build.VERSION.SDK_INT < 33) return null
        val done = CompletableDeferred<RecognitionSupport?>()
        try {
            r.checkRecognitionSupport(intent, { it.run() }, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) { done.complete(s) }
                override fun onError(error: Int) { done.complete(null) }
            })
            return withTimeoutOrNull(4_000) { done.await() }
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { r.destroy() }
        }
    }

}
