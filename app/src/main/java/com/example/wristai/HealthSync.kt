package com.example.wristai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * 饮食同步到「健康」（Android 用 Health Connect）。
 *
 * - Google：Health Connect 就是 Google 的健康资料中心（Fit / Google 健康类 App 都从这里读）。
 * - Samsung Health：没有开放给一般 App 直接写入；它会跟 Health Connect 双向同步
 *   （Samsung Health → 設定 → Health Connect），所以写一次 Health Connect 两边都有。
 * - Apple 健康：只在 iPhone 版（HealthKit）。
 *
 * 每笔饮食记录写成一笔 NutritionRecord（热量、蛋白质、碳水、脂肪、餐别、名称），
 * clientRecordId = 记录 id，所以重复同步只会更新、不会重复；删除记录也会从健康里删掉。
 * 预设关闭，使用者在设定里打开并自己按允许。
 */
object HealthSync {
    private const val TAG = "HealthSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 写入饮食需要的权限（只写不读）。 */
    val PERMISSIONS = setOf(HealthPermission.getWritePermission(NutritionRecord::class))

    /** 系统要的授权画面（设定页用 rememberLauncherForActivityResult 开）。 */
    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    fun enabled(context: Context) = Settings.healthSync(context)

    /** Health Connect 能不能用：available / update（要装或更新）/ none（这台不支援）。 */
    fun status(context: Context): String = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> "available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update"
        else -> "none"
    }

    suspend fun hasPermission(context: Context): Boolean = runCatching {
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
    }.getOrDefault(false)

    /** 开 Health Connect（Android 14 起在系统设定里；更旧的版本是另一个 App，没装就去 Play 商店）。 */
    fun openHealthConnect(context: Context) {
        val intents = listOf(
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")),
        )
        for (i in intents) {
            if (runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }

    /** 开 Samsung Health（使用者在里面打开 Health Connect 同步）；没装就回 false。 */
    fun openSamsungHealth(context: Context): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage("com.sec.android.app.shealth") ?: return false
        return runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    fun hasSamsungHealth(context: Context) =
        context.packageManager.getLaunchIntentForPackage("com.sec.android.app.shealth") != null

    /** 一笔饮食记录做完 → 背景写进健康（没开、没权限就什么都不做）。 */
    fun push(context: Context, meeting: Meeting) {
        if (!FoodLog.isFood(meeting.id) || meeting.nutrition == null || !enabled(context)) return
        val app = context.applicationContext
        scope.launch { runCatching { write(app, listOf(meeting)) }.onFailure { Log.w(TAG, "push ${meeting.id} failed", it) } }
    }

    /** 删掉饮食记录 → 也从健康删掉。 */
    fun remove(context: Context, id: String) {
        if (!FoodLog.isFood(id) || !enabled(context)) return
        val app = context.applicationContext
        scope.launch {
            runCatching {
                if (status(app) != "available" || !hasPermission(app)) return@runCatching
                HealthConnectClient.getOrCreate(app).deleteRecords(NutritionRecord::class, emptyList(), listOf(id))
            }.onFailure { Log.w(TAG, "remove $id failed", it) }
        }
    }

    /** 把所有已完成的饮食记录写进健康（打开开关时、或按「同步過去的記錄」）；回传写了几笔。 */
    suspend fun syncAll(context: Context): Int {
        val list = MeetingStore.meetings.value.filter {
            FoodLog.isFood(it.id) && it.status == MeetingStatus.DONE && it.nutrition != null && it.nutrition.kcal > 0
        }
        return write(context, list)
    }

    private suspend fun write(context: Context, list: List<Meeting>): Int {
        if (list.isEmpty() || status(context) != "available" || !hasPermission(context)) return 0
        val records = list.mapNotNull { toRecord(it) }
        if (records.isEmpty()) return 0
        // clientRecordId 一样就是更新（upsert），不会重复
        HealthConnectClient.getOrCreate(context).insertRecords(records)
        Settings.setHealthSyncLast(context, System.currentTimeMillis(), records.size)
        Log.d(TAG, "wrote ${records.size} nutrition record(s)")
        return records.size
    }

    private fun toRecord(m: Meeting): NutritionRecord? {
        val n = m.nutrition ?: return null
        if (n.kcal <= 0) return null   // 「非飲食內容」不写
        val start = Instant.ofEpochMilli(m.createdAt)
        val offset = ZoneId.systemDefault().rules.getOffset(start)
        return NutritionRecord(
            startTime = start, startZoneOffset = offset,
            endTime = start.plusSeconds(60), endZoneOffset = offset,
            metadata = Metadata.manualEntry(clientRecordId = m.id, clientRecordVersion = System.currentTimeMillis()),  // 版本要比旧的大才会更新
            energy = Energy.kilocalories(n.kcal.toDouble()),
            protein = Mass.grams(n.protein.toDouble()),
            totalCarbohydrate = Mass.grams(n.carbs.toDouble()),
            totalFat = Mass.grams(n.fat.toDouble()),
            name = m.title?.substringAfter('：')?.take(60),
            mealType = mealType(n.meal),
        )
    }

    private fun mealType(meal: String) = when {
        "早" in meal -> MealType.MEAL_TYPE_BREAKFAST
        "午" in meal -> MealType.MEAL_TYPE_LUNCH
        "晚" in meal -> MealType.MEAL_TYPE_DINNER
        "點心" in meal || "宵夜" in meal || "飲料" in meal || "点心" in meal -> MealType.MEAL_TYPE_SNACK
        else -> MealType.MEAL_TYPE_UNKNOWN
    }
}
