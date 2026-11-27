package com.example.wristai

import android.content.Context
import com.example.voiceai.shared.tr
import java.util.Calendar

/**
 * 飲食的兩個小幫手：
 * - [FoodRepeat]：常吃的（30 天內記過兩次以上的同一餐）一鍵再記一次：照抄那一餐的內容與營養，餐別換成現在的時段，不用再問 AI。
 * - [FoodGoals]：健康目標（均衡／減脂控卡／增肌高蛋白／控糖穩血糖）：選了就換一組每日目標，AI 的「營養小提醒」也照目標講重點。
 */
object FoodRepeat {
    /** 現在的餐別。 */
    fun mealNow(cal: Calendar = Calendar.getInstance()): String {
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return when {
            m < 4 * 60 -> "宵夜"
            m < 10 * 60 + 30 -> "早餐"
            m < 14 * 60 -> "午餐"
            m < 17 * 60 -> "點心"
            m < 21 * 60 -> "晚餐"
            else -> "宵夜"
        }
    }

    /** 標題去掉餐別：「午餐：牛肉麵＋燙青菜」→「牛肉麵＋燙青菜」。 */
    fun dish(title: String?): String = title.orEmpty().substringAfter("：").substringAfter(":").trim()

    /** 常吃的：30 天內同一道記過 2 次以上，依次數排，每道取最近一筆。 */
    fun favorites(food: List<Meeting>, now: Long = System.currentTimeMillis()): List<Pair<Meeting, Int>> =
        food.filter { it.status == MeetingStatus.DONE && it.nutrition != null && now - it.createdAt < 30L * 86_400_000 && dish(it.title).isNotBlank() }
            .groupBy { dish(it.title) }
            .filter { it.value.size >= 2 }
            .map { (_, list) -> list.maxBy { it.createdAt } to list.size }
            .sortedByDescending { it.second }
            .take(6)

    /** 再記一次：新的一筆（現在、現在的餐別），報告照抄；回傳新編號。 */
    fun repeat(c: Context, src: Meeting): String {
        val now = System.currentTimeMillis()
        val meal = mealNow()
        val id = com.example.voiceai.shared.RecordingIds.newId(c, FoodLog.ID_PREFIX)
        val title = meal + "：" + dish(src.title)
        val n = src.nutrition?.copy(meal = meal)
        MeetingStore.save(c, Meeting(id, MeetingStatus.SUMMARIZING, now, 0.0, null, title, null, source = "repeat",
            lang = src.lang))
        MeetingStore.reportFile(c, src.id).takeIf { it.exists() }?.let { f ->
            MeetingStore.reportFile(c, id).writeText(f.readText().replace(src.nutrition?.meal ?: "\u0000", meal))
        }
        // 改成「做完」：健康同步、小工具、手錶今日熱量都照常更新
        MeetingStore.update(c, id) { it.copy(status = MeetingStatus.DONE, nutrition = n, aiModel = src.aiModel) }
        WatchNotifier.broadcastFoodToday(c)
        return id
    }
}

object FoodGoals {
    const val BALANCED = "balanced"
    const val LOSE = "lose"
    const val MUSCLE = "muscle"
    const val SUGAR = "sugar"
    val ALL = listOf(BALANCED, LOSE, MUSCLE, SUGAR)

    private fun p(c: Context) = c.getSharedPreferences("wristai_settings", Context.MODE_PRIVATE)
    fun mode(c: Context): String = p(c).getString("food_goal_mode", BALANCED) ?: BALANCED

    /** 這次處理的那一餐用的目標（[ProcessMeetingWorker] 設；提示詞用）。 */
    @Volatile var active: String = BALANCED

    fun label(m: String) = when (m) {
        LOSE -> "減脂控卡".tr
        MUSCLE -> "增肌高蛋白".tr
        SUGAR -> "控糖穩血糖".tr
        else -> "均衡".tr
    }

    /** 每個目標的預設：熱量、蛋白質、纖維、碳水上限（0 = 不限）。 */
    fun preset(m: String): IntArray = when (m) {
        LOSE -> intArrayOf(1600, 90, 25, 0)
        MUSCLE -> intArrayOf(2400, 130, 25, 0)
        SUGAR -> intArrayOf(1800, 70, 30, 180)
        else -> intArrayOf(2000, 60, 25, 0)
    }

    /** 選目標：存起來並套用預設的每日目標（之後還可以自己改數字）。 */
    fun setMode(c: Context, m: String) {
        val v = preset(m)
        p(c).edit().putString("food_goal_mode", m).putInt("carb_cap", v[3]).apply()
        Settings.setDailyKcalGoal(c, v[0])
        Settings.setProteinGoal(c, v[1])
        Settings.setFiberGoal(c, v[2])
    }

    /** 控糖：每日碳水上限（克，0 = 不限）。 */
    fun carbCap(c: Context): Int = p(c).getInt("carb_cap", 0)

    /** 提示詞：營養小提醒照目標講重點。 */
    fun adviceRule(m: String = active): String = when (m) {
        LOSE -> "使用者的目標是減脂控卡：營養小提醒以熱量、份量、油脂與含糖飲料為重點，提出下一餐可以怎麼少一點熱量但吃得飽。"
        MUSCLE -> "使用者的目標是增肌高蛋白：營養小提醒以這餐蛋白質夠不夠為重點，建議下一餐補什麼蛋白質來源（豆漿、蛋、雞胸、魚、豆腐等）。"
        SUGAR -> "使用者的目標是控糖穩血糖（適合銀髮族與三高）：營養小提醒特別標出精緻澱粉、含糖飲料與甜點的份量，建議穩定血糖的吃法（先吃菜和蛋白質、換成全穀、無糖）。"
        else -> "營養小提醒給均衡飲食的簡短建議。"
    }
}
