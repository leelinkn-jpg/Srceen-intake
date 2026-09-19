package com.linkn.screenintake.capture

import android.content.Context
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.classify.ClassifyResult
import com.linkn.screenintake.store.RecordStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 「确认」或者「编辑提交」之后真正落盘的那一小段逻辑，通知栏上的确认/编辑按钮
 * （[CaptureConfirmReceiver]）和 App 内「待确认」模块的确认/编辑按钮共用同一份，
 * 保证不管从哪边操作，行为都完全一致——不会出现"App 里点确认"和"通知上点确认"
 * 结果不一样的情况。
 */
object CaptureConfirmActions {
    private val confirmationMutex = Mutex()

    /**
     * @param editedText null 表示直接按原样确认；非 null 表示编辑框/输入框里提交了新内容。
     * @param dueAt 待办专用：App 内编辑弹窗时间选择器选出来的具体时间（ISO
     * "yyyy-MM-ddTHH:mm:ss"）。通知栏行内回复的快速编辑没有时间选择器，
     * 传的永远是 null。
     *
     * 三种分支：「忽略」类型、以及「待办但一直没有具体时间、这次编辑也没在 App 里用
     * 时间选择器补上（也就是只在通知栏打字回复）」，都按手动输入重新完整分类一遍——
     * 待办这条是因为：所有待办事项都必须要有具体提醒时间，通知栏打字回复没法直接给
     * 出结构化时间，与其把「还是没有时间」的待办直接落盘，不如让它按全新内容重新走
     * 一遍分类，模型这次如果从新文字里认出了时间就直接记上，认不出的话（见
     * [CapturePipeline]）会再退回「待确认」等你用 App 里的时间选择器补上，不会死循环
     * 丢内容。其余情况（money 类编辑、或者待办已经带着 dueAt 参数）套回
     * [ClassifyResult.withEdit] 直接改字段即可。不管走哪条分支，最后都会把这条草稿从
     * 「待确认」删掉。
     */
    suspend fun confirmOrEdit(
        context: Context,
        draftId: String,
        original: ClassifyResult,
        editedText: String?,
        dueAt: String? = null
    ) = confirmationMutex.withLock {
        val settings = ScreenIntakeApp.instance.settingsStore
        val store = RecordStore(context)
        // 通知与 App 共用同一把锁；连点、或两处同时确认不应重复记账。
        if (store.readPendingDrafts(settings.folderUri).none { it.draftId == draftId }) return@withLock
        val needsReclassify = editedText != null &&
            (original.isIgnore || (original.isTodo && original.dueAt.isNullOrBlank() && dueAt == null))
        if (needsReclassify) {
            CapturePipeline(context).classifyAndRoute(editedText, skipConfirmation = true)
        } else {
            val finalResult = if (editedText != null) original.withEdit(editedText, dueAt) else original
            require(!finalResult.isTodo || !finalResult.dueAt.isNullOrBlank()) { "请先补上具体提醒时间" }
            store.route(settings.folderUri, finalResult)
        }
        store.deletePendingDraft(settings.folderUri, draftId)
    }
}
