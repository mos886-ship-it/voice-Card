package com.example.wristai

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** ML Kit 离线翻译模型：手机上有哪些、下载、删除。英文内建（不能删）。 */
object TranslateModels {
    private val manager by lazy { RemoteModelManager.getInstance() }
    private val _downloaded = MutableStateFlow<Set<String>?>(null)
    /** 已下载的语言代码；null = 还没查。 */
    val downloaded: StateFlow<Set<String>?> = _downloaded.asStateFlow()
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    suspend fun refresh() {
        _downloaded.value = runCatching {
            manager.getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet() + "en"
        }.getOrDefault(setOf("en"))
    }

    suspend fun download(c: Context, code: String): Result<Unit> {
        _busy.value = _busy.value + code
        val r = runCatching {
            val cond = DownloadConditions.Builder().apply { if (Settings.translateModelsWifiOnly(c)) requireWifi() }.build()
            manager.download(TranslateRemoteModel.Builder(code).build(), cond).await(); Unit
        }
        refresh(); _busy.value = _busy.value - code
        return r
    }

    suspend fun delete(code: String): Result<Unit> {
        if (code == "en") return Result.success(Unit)
        _busy.value = _busy.value + code
        val r = runCatching { manager.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await(); Unit }
        refresh(); _busy.value = _busy.value - code
        return r
    }
}
