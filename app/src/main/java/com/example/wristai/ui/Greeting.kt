package com.example.wristai.ui

import com.example.voiceai.shared.tr
import com.example.voiceai.shared.trf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Calendar

/**
 * 首页的问候：一句大标（依时段）＋一句有温度的小字（依时段、星期、今天记了什么）。
 * 同一天同一时段固定同一句（不会每次打开都换），隔一段时间自动更新。
 */
data class Greeting(val title: String, val line: String)

/** [todayCount] = 今天的记录数（不含饮食）；[ateLunch] / [ateDinner] = 今天有没有记午餐 / 晚餐；[todayKcal] / [goal] = 今日热量与目标。 */
fun greetingFor(
    now: Calendar, todayCount: Int, ateLunch: Boolean, ateDinner: Boolean, todayKcal: Int, goal: Int, usesFood: Boolean,
): Greeting {
    val h = now.get(Calendar.HOUR_OF_DAY)
    val weekend = now.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
    val friday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY
    val seed = now.get(Calendar.DAY_OF_YEAR)
    fun pick(vararg s: String) = s[seed % s.size]

    val title = when (h) {
        in 5..10 -> if (weekend) "週末早安".tr else "早安".tr
        in 11..13 -> "午安".tr
        in 14..16 -> "下午好".tr
        in 17..18 -> "傍晚好".tr
        in 19..22 -> "晚上好".tr
        else -> "夜深了".tr
    }
    val line = when (h) {
        in 5..10 -> when {
            weekend -> pick("難得週末，慢慢來就好。".tr, "睡飽了嗎？今天放鬆一點。".tr)
            friday -> pick("星期五了，再撐一下就週末。".tr, "最後一天，加油，有事交給我記。".tr)
            else -> pick("今天也慢慢來，要記的交給我。".tr, "先喝杯水，開始美好的一天。".tr, "新的一天，有想法隨時說給我聽。".tr)
        }
        in 11..13 -> when {
            usesFood && !ateLunch -> pick("午餐吃了嗎？吃完說一聲，我幫你算熱量。".tr, "記得好好吃午飯，別餓著了。".tr)
            else -> pick("吃飽了，休息一下再出發。".tr, "午後容易睏，起來走一走。".tr)
        }
        in 14..16 -> when {
            todayCount > 0 -> "今天已經記了 {0} 筆，休息一下、喝口水。".trf(todayCount)
            else -> pick("喝口水、伸個懶腰，休息一下。".tr, "下午也辛苦了，慢慢來。".tr)
        }
        in 17..18 -> pick("今天辛苦了，準備收工吧。".tr, "外面天色變了，回家路上小心。".tr)
        in 19..22 -> when {
            usesFood && !ateDinner && h <= 20 -> "晚餐吃了嗎？吃完說一聲就好。".tr
            usesFood && goal > 0 && todayKcal > goal -> "今天吃得比較多，明天清淡一點就好。".tr
            todayCount > 0 -> "今天記了 {0} 筆，辛苦了，好好休息。".trf(todayCount)
            else -> pick("今天辛苦了，好好放鬆。".tr, "忙完了嗎？給自己一點時間。".tr)
        }
        else -> pick("早點休息，事情明天再說。".tr, "還沒睡嗎？別太累了。".tr)
    }
    return Greeting(title, line)
}

/** 目前时间（每分钟更新一次，问候跟着时段换）。 */
@Composable
fun rememberNowMinute(): Calendar {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = System.currentTimeMillis()
        }
    }
    return Calendar.getInstance().apply { timeInMillis = now }
}
