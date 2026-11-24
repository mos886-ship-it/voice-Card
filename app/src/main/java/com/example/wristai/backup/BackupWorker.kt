package com.example.wristai.backup

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 背景备份 / 取回（WorkManager：App 关掉也会做完，没网路会等）。
 * - 自动备份：报告做完（[MeetingStore.update] 状态变成完成）时排一次，可设「只用 Wi-Fi」。
 * - 设定页的「立即備份」「從雲端取回」也走这里，进度给 [DriveBackup.status]。
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        private const val TAG = "BackupWorker"
        private const val KEY_MODE = "mode"
        const val MODE_BACKUP = "backup"
        const val MODE_RESTORE = "restore"
        private const val WORK = "drive-backup"

        /** 立即备份 / 取回（使用者按的：只要有网路）。 */
        fun run(c: Context, mode: String) = enqueue(c, mode, wifiOnly = false, policy = ExistingWorkPolicy.REPLACE)

        /** 报告做完时：有开自动备份、已连结才排；同时排很多笔只做一次（APPEND 会在前一次后面再跑一次，把新的带上）。 */
        fun scheduleAuto(c: Context) {
            if (!DriveBackup.linked(c) || !DriveBackup.auto(c)) return
            enqueue(c, MODE_BACKUP, DriveBackup.wifiOnly(c), ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        private fun enqueue(c: Context, mode: String, wifiOnly: Boolean, policy: ExistingWorkPolicy) {
            val req = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(workDataOf(KEY_MODE to mode))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork(WORK, policy, req)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val c = applicationContext
        val restore = inputData.getString(KEY_MODE) == MODE_RESTORE
        val verb = if (restore) "取回" else "備份"
        DriveBackup.report(DriveBackup.Status(running = true, text = "$verb：連線到 Google 雲端硬碟…"))
        try {
            val token = DriveBackup.token(c)
            val n = if (restore) {
                DriveBackup.restore(c, token) { i, total ->
                    DriveBackup.report(DriveBackup.Status(true, "取回中 ${i + 1}/$total", done = i, total = total))
                }
            } else {
                DriveBackup.backup(c, token) { i, total ->
                    DriveBackup.report(DriveBackup.Status(true, "備份中 ${minOf(i + 1, total)}/$total", done = i, total = total))
                }
            }
            DriveBackup.report(DriveBackup.Status(text = when {
                restore && n == 0 -> "雲端上沒有手機缺少的錄音"
                restore -> "已取回 $n 筆"
                n == 0 -> "都已備份，沒有新的"
                else -> "已備份 $n 筆"
            }))
            Result.success()
        } catch (e: DriveBackup.NeedsConsent) {
            DriveBackup.report(DriveBackup.Status(text = "Google 雲端硬碟要重新授權：請到設定按「連結 Google 雲端硬碟」", error = true))
            Result.failure()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$verb failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            val net = e is java.io.IOException && e !is DriveBackup.BackupException
            DriveBackup.report(DriveBackup.Status(text = "$verb 失敗：" + if (net) "連不到 Google（網路？）" else msg, error = true))
            // 网路问题：WorkManager 等一下再试；其他（授权、空间不够）重试也没用
            if (net && runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
