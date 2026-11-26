package com.example.wristai

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wristai.ui.GlassCard
import com.example.wristai.ui.TopBar
import com.example.wristai.ui.theme.AppTheme
import com.example.wristai.ui.theme.Brand
import com.example.wristai.ui.theme.ThemeMode
import com.example.wristai.ui.theme.WristAI_ProjectTheme

/** Health Connect 授权画面的「隱私權政策」：说明大D写进健康的资料。 */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppTheme.mode = ThemeMode.from(Settings.themeMode(this))
        setContent {
            WristAI_ProjectTheme(AppTheme.mode) {
                Column(Modifier.fillMaxSize().background(Brand.Bg).systemBarsPadding()) {
                    TopBar("大D 與健康資料".tr, onBack = { finish() })
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GlassCard {
                            Text(PRIVACY, style = MaterialTheme.typography.bodyMedium, color = Brand.Text)
                        }
                    }
                }
            }
        }
    }

    companion object {
        val PRIVACY = """
            大D 只「寫入」飲食資料，不讀取任何健康資料。

            寫入的內容：每一筆飲食記錄的時間、餐別、名稱，以及 AI 估算的熱量、蛋白質、碳水化合物、脂肪。

            這些數字是 AI 依你的口述估算，僅供參考，不是醫療或營養建議。

            只有在「設定 → 健康同步」打開時才會寫入；在大D 刪除飲食記錄，健康裡的那一筆也會一起刪除。關掉同步後，已寫入的資料留在健康裡，可在 Health Connect 自行刪除。

            資料只在你的手機上、Health Connect 與你自己連結的健康 App（例如 Samsung Health）之間傳遞，大D 不會上傳到任何伺服器。
        """.trimIndent()
    }
}
