package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wristai.ui.theme.Brand

/** 一个套件（assets/licenses/libraries.tsv，由 tools/gen_licenses.py 产生）。 */
private data class Lib(val group: String, val artifact: String, val version: String, val license: String)

private fun loadLibs(c: Context): List<Lib> = runCatching {
    c.assets.open("licenses/libraries.tsv").bufferedReader().readLines().mapNotNull { l ->
        l.split('\t').takeIf { it.size >= 4 }?.let { Lib(it[0], it[1], it[2], it[3]) }
    }
}.getOrDefault(emptyList())

private fun asset(c: Context, name: String): String =
    runCatching { c.assets.open("licenses/${name}").bufferedReader().readText() }.getOrDefault("（找不到 {0}）".trf(name))

/** 大D的主機用的模型与执行环境（模型由主机第一次用到时从原始网站下载，不随 App 散布）。 */
private data class HostItem(val name: String, val by: String, val license: String, val url: String)

private val HOST_ITEMS = listOf(
    HostItem("Qwen3-8B（MLX 4bit）".tr, "Alibaba Qwen；mlx-community 轉換".tr, "Apache License 2.0", "https://huggingface.co/mlx-community/Qwen3-8B-4bit"),
    HostItem("FunASR（程式）".tr, "Alibaba DAMO Academy / ModelScope", "MIT License", "https://github.com/modelscope/FunASR"),
    HostItem("FunASR 語音辨識模型".tr, "Alibaba DAMO Academy / ModelScope", "依模型頁的授權".tr, "https://github.com/modelscope/FunASR"),
    HostItem("Breeze-ASR-26（MLX 8bit）".tr, "MediaTek Research；doggy8088 轉換".tr, "依模型頁的授權".tr, "https://huggingface.co/doggy8088/Breeze-ASR-26-MLX-8bit"),
    HostItem("Taiwan-Tongues-ASR-CE（客語，MLX 8bit 本機轉換）".tr, "數位發展部數位產業署；台灣大哥大維護".tr,
        "TRAIL v0.1（只呼叫、不散布轉換後的模型）".tr, "https://huggingface.co/adi-gov-tw/Taiwan-Tongues-ASR-CE-pretrained-v1.0"),
    HostItem("SARC 台語 12B（GGUF）".tr, "Speech AI Research Center", "依模型頁的授權".tr, "https://huggingface.co/Speech-AI-Research-Center/SARC-Taigi-LLM-12b-GGUF"),
    HostItem("MLX", "Apple", "MIT License", "https://github.com/ml-explore/mlx"),
    HostItem("llama.cpp", "ggml-org", "MIT License", "https://github.com/ggml-org/llama.cpp"),
)

/** 云端 AI：使用者用自己的金钥，依各家条款。只放确定的条款网址。 */
private val CLOUD = listOf(
    "Google Gemini API" to "https://ai.google.dev/gemini-api/terms",
    "Anthropic Claude API" to "https://www.anthropic.com/legal/commercial-terms",
    "OpenAI API" to "https://openai.com/policies/",
    "阿里雲百煉（Fun-ASR、通義千問）" to null,
    "科大訊飛（錄音轉寫、星火）" to null,
    "Meta Llama API" to null,
)

/**
 * 设定 →「关于与授权」：本 App 用到的开放原始码套件与授权全文、Google 服务、云端 AI 服务、
 * 大D的主機用的模型、商标与 AI 内容说明。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val libs = remember { loadLibs(context) }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }   // 标题 to 资产档名
    var expanded by remember { mutableStateOf<String?>(null) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
    fun open(url: String) = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    viewing?.let { (title, file) ->
        BackHandler { viewing = null }
        LicenseText(title, asset(context, file)) { viewing = null }
        return
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        TopBar("關於與授權".tr, onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlassCard {
                Text("大D · Dylan {0}".trf(version), style = MaterialTheme.typography.titleMedium, color = Brand.Text)
                Text("報告由 AI 產生，可能有錯誤或遺漏，重要內容請以錄音為準。飲食熱量為估算，僅供參考。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            }

            // ---------- 开放原始码套件 ----------
            SectionLabel("開放原始碼套件".tr)
            GlassCard {
                Text("這個 App 使用 {0} 個套件，依各自的授權使用。".trf(libs.size), style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                Spacer(Modifier.height(8.dp))
                for ((license, list) in libs.groupBy { it.license }.toList().sortedByDescending { it.second.size }) {
                    val open = expanded == license
                    Row(Modifier.fillMaxWidth().clickable { expanded = if (open) null else license }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(license, style = MaterialTheme.typography.bodyMedium, color = Brand.Text, modifier = Modifier.weight(1f))
                        Text("{0} 個 {1}".trf(list.size, if (open) "▴" else "▾"), style = MaterialTheme.typography.labelMedium, color = Brand.TextFaint)
                    }
                    if (open) {
                        for (l in list) Text("${l.group}:${l.artifact} ${l.version}", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                            color = Brand.TextMuted, modifier = Modifier.padding(start = 8.dp, bottom = 2.dp))
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                LinkRow("Apache License 2.0 全文".tr) { viewing = "Apache License 2.0" to "apache-2.0.txt" }
                LinkRow("MIT License 全文".tr) { viewing = "MIT License" to "mit.txt" }
                LinkRow("各套件的 NOTICE".tr) { viewing = "NOTICE" to "notices.txt" }
            }

            // ---------- Google 服务 ----------
            SectionLabel("Google 服務".tr)
            GlassCard {
                Text("手錶連線（Google Play 服務）與 QR 碼掃描（ML Kit）依 Google 的條款提供。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                LinkRow("ML Kit 服務條款".tr) { open("https://developers.google.com/ml-kit/terms") }
                LinkRow("Android 軟體開發套件授權".tr) { open("https://developer.android.com/studio/terms") }
            }

            // ---------- 内建资料：食藥署营养成分（政府资料开放授权条款要标示来源） ----------
            SectionLabel("內建資料".tr)
            GlassCard {
                Text("飲食熱量計算使用衛生福利部食品藥物管理署「食品營養成分資料集」（2026/10/05 版，只取熱量、蛋白質、脂肪、碳水、膳食纖維、糖、鈉，每 100 克），依政府資料開放授權條款－第1版使用。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                LinkRow("食品營養成分資料集（政府資料開放平臺）".tr) { open("https://data.gov.tw/dataset/8543") }
                LinkRow("政府資料開放授權條款－第1版".tr) { open("https://data.gov.tw/license") }
            }

            // ---------- 云端 AI ----------
            SectionLabel("雲端 AI 服務".tr)
            GlassCard {
                Text("使用你自己填的金鑰，錄音與文字依各服務的條款處理。".tr, style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                for ((name, url) in CLOUD) {
                    if (url != null) LinkRow("{0} 條款".trf(name)) { open(url) }
                    else Text(name, style = MaterialTheme.typography.bodyMedium, color = Brand.TextMuted, modifier = Modifier.padding(vertical = 8.dp))
                }
            }

            // ---------- 大D的主機 ----------
            SectionLabel("大D的主機使用的模型".tr)
            GlassCard {
                Text("模型由你的主機第一次用到時從原始網站下載，不隨 App 散布；使用前請確認各模型的授權（例如是否可商用）。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
                for (h in HOST_ITEMS) {
                    Column(Modifier.fillMaxWidth().clickable { open(h.url) }.padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(h.name, style = MaterialTheme.typography.bodyMedium, color = Brand.Text, modifier = Modifier.weight(1f))
                            Text("›", color = Brand.Cyan)
                        }
                        Text("${h.by} · ${h.license}", style = MaterialTheme.typography.labelSmall, color = Brand.TextFaint)
                    }
                }
            }

            // ---------- 商标 ----------
            SectionLabel("商標".tr)
            GlassCard {
                Text("Google、Gemini、Wear OS、Samsung、Galaxy、Alibaba、Qwen、OpenAI、Anthropic、Claude、Meta、Llama 等為各公司的商標。".tr +
                    "大D 只用這些名稱說明相容或使用的服務，與這些公司沒有隸屬或合作關係。".tr,
                    style = MaterialTheme.typography.bodySmall, color = Brand.TextMuted)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Brand.Cyan, modifier = Modifier.weight(1f))
        Text("›", color = Brand.Cyan)
    }
}

@Composable
private fun LicenseText(title: String, text: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, onBack)
        // 授权全文自己有换行（约 80 字一行）：不自动折行，左右滑看，排版才不会乱
        Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Brand.TextMuted, softWrap = false,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp))
    }
}
