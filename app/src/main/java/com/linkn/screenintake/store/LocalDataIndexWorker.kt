package com.linkn.screenintake.store

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.linkn.screenintake.ScreenIntakeApp
import com.linkn.screenintake.growth.GrowthEffortRepository
import com.linkn.screenintake.meeting.MeetingRepository
import com.linkn.screenintake.report.AiReportRepository

/**
 * 同步目录的本地读模型预热。
 *
 * 同步文件始终是唯一正式来源；此任务只把已经同步完成的文件读入 App 私有 SQLite 快照和
 * [UiDataCache]。它串行读取，避免启动时同时争抢 SAF/网盘 Provider 的 I/O，且绝不写回
 * 用户目录。读取失败时保留上一次内存结果，下一次自动重试。
 */
class LocalDataIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val folder = ScreenIntakeApp.instance.settingsStore.folderUri
        if (folder.isBlank() || !LedgerReader.folderAccessible(applicationContext, folder)) {
            SystemStatus.failure(applicationContext, "读取", "保存目录尚未选择或暂时不可访问，保留上次内容")
            return@runCatching Result.success()
        }
        val generationAtStart = DataChangeSignal.currentGeneration()
        val failuresAtStart = SystemStatus.failureGeneration("读取")
        val ledger = LedgerReader.readLedger(applicationContext, folder)
        val cards = LedgerReader.readCards(applicationContext, folder)
        val holdings = LedgerReader.readHoldings(applicationContext, folder)
        val transfers = LedgerReader.readTransfers(applicationContext, folder)
        val todos = LedgerReader.readTodos(applicationContext, folder)
        val notes = LedgerReader.readNotes(applicationContext, folder)
        val weights = LedgerReader.readWeights(applicationContext, folder)
        val metrics = LedgerReader.readHealthMetrics(applicationContext, folder)
        val digital = LedgerReader.readDigitalHealth(applicationContext, folder)
        val exercises = LedgerReader.readExercises(applicationContext, folder)
        val meals = LedgerReader.readMealNotes(applicationContext, folder)
        val meetings = MeetingRepository(applicationContext).list(folder)
        val growth = GrowthEffortRepository(applicationContext)
        val activities = growth.activities(folder)
        val efforts = growth.efforts(folder)
        val reports = AiReportRepository(applicationContext).list(folder)
        val recordStore = RecordStore(applicationContext)
        val drafts = recordStore.readPendingDrafts(folder)
        val pendingNotes = recordStore.readUnconfirmedNotes(folder)

        // 用户在读取期间保存过新记录时，页面自己的刷新结果才是最新的；本次预热只留下
        // SQLite 文件快照，不发布这份可能已过期的内存读模型。
        if (generationAtStart != DataChangeSignal.currentGeneration()) return@runCatching Result.retry()

        UiDataCache.ledger = ledger
        UiDataCache.cards = cards
        UiDataCache.holdings = holdings
        UiDataCache.transfers = transfers
        UiDataCache.todos = todos
        TodoReminderWorker.reconcile(applicationContext, todos)
        UiDataCache.notes = notes
        UiDataCache.weights = weights
        UiDataCache.healthMetrics = metrics
        UiDataCache.digitalHealth = digital
        UiDataCache.exercises = exercises
        UiDataCache.mealNotes = meals
        UiDataCache.meetings = meetings
        UiDataCache.growthActivities = activities
        UiDataCache.growthEfforts = efforts
        UiDataCache.reports = reports
        UiDataCache.pendingDrafts = drafts
        UiDataCache.pendingNotes = pendingNotes
        UiSnapshot.save(applicationContext, folder)
        if (failuresAtStart == SystemStatus.failureGeneration("读取")) SystemStatus.success(applicationContext, "读取")
        Result.success()
    }.getOrElse {
        if (it is kotlinx.coroutines.CancellationException) throw it
        SystemStatus.failure(applicationContext, "读取", "读取失败，保留上次内容；请检查目录权限后重试")
        Result.retry()
    }

    companion object {
        private const val WORK_NAME = "refresh-local-data-index"

        /** 应用启动、重新选择目录或 Syncthing 检测到变化后调用；同一时刻只保留一项刷新。 */
        fun refresh(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LocalDataIndexWorker>().build()
            )
        }
    }
}
