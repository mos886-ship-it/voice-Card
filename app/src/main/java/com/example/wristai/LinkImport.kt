package com.example.wristai

import android.content.Context
import android.net.Uri
import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 從連結匯入：
 * - 直接的音檔 / 影片連結（.mp3、.m4a、.mp4…，例如 Podcast 檔）：手機自己下載。
 * - 其他網頁（YouTube 等）：請大D的主機用 yt-dlp 抓音訊（`/v1/fetch_url`），手機收到再匯入。
 * 抓到的檔案照一般匯入處理（[AudioImporter]），之後的報告流程都一樣。
 */
object LinkImport {
    private const val MAX_BYTES = 300L * 1024 * 1024
    private val MEDIA_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr", "3gp", "mp4", "m4v", "mov", "webm", "mkv")

    fun normalize(raw: String): String? {
        val t = raw.trim().let { s -> Regex("https?://\\S+").find(s)?.value ?: s }   // 分享來的文字常夾標題
        return t.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.trimEnd('.', ',', '）', ')')
    }

    fun isDirectMedia(url: String): Boolean =
        runCatching { URL(url).path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in MEDIA_EXT }.getOrDefault(false)

    private fun nameOf(url: String) = runCatching { Uri.decode(URL(url).path.substringAfterLast('/')).substringBeforeLast('.') }
        .getOrNull()?.takeIf { it.isNotBlank() }

    /** 阻塞執行（背景執行緒）。回傳新記錄 id。 */
    fun import(c: Context, raw: String, onStatus: (String) -> Unit = {}): String {
        val url = normalize(raw) ?: throw IllegalArgumentException("請貼上 http 或 https 開頭的連結".tr)
        val dir = File(c.cacheDir, "link_import").apply { mkdirs() }
        val f = File(dir, "dl_${System.currentTimeMillis()}")
        try {
            val title: String? = if (isDirectMedia(url)) {
                onStatus("下載中…".tr)
                download(url, f)
                nameOf(url)
            } else {
                if (!com.example.wristai.ai.LocalHost.configured(c))
                    throw IllegalStateException("這個連結要請大D的主機抓（設定 → AI 引擎 → 私有主機）；直接的音檔連結（.mp3、.m4a…）手機可以自己下載".tr)
                onStatus("大D的主機抓取中…（影片長的話要幾分鐘）".tr)
                com.example.wristai.ai.LocalHost.fetchUrl(c, url, f)
            }
            onStatus("匯入中…".tr)
            return AudioImporter.import(c, Uri.fromFile(f), title = title?.take(80), fallbackEnd = System.currentTimeMillis())
        } finally { f.delete() }
    }

    private fun download(url: String, out: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 60_000; instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("下載失敗（HTTP {0}）".trf(code))
            if (conn.contentLengthLong > MAX_BYTES) throw IllegalStateException("檔案太大（超過 300 MB）".tr)
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(256 * 1024); var total = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IllegalStateException("檔案太大（超過 300 MB）".tr)
                        o.write(buf, 0, n)
                    }
                }
            }
        } finally { conn.disconnect() }
    }

}
