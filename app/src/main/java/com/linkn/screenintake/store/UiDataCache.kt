package com.linkn.screenintake.store

import com.linkn.screenintake.growth.GrowthActivity
import com.linkn.screenintake.growth.GrowthEffort
import com.linkn.screenintake.meeting.MeetingRecord
import com.linkn.screenintake.report.AiReport
import com.linkn.screenintake.work.WorkChange

/**
 * 页面级内存快照。切换 Tab、返回桌面再进入或 Activity 重建时，先显示上一次已经解析好的
 * 数据，再由后台核对同步文件。它不替代文件，进程被系统彻底杀掉后自然清空。
 */
object UiDataCache {
    @Volatile var ledger: List<LedgerRow> = emptyList()
    @Volatile var holdings: List<Holding> = emptyList()
    @Volatile var cards: List<CardAccount> = emptyList()
    @Volatile var transfers: List<TransferRow> = emptyList()
    @Volatile var todos: List<TodoItem> = emptyList()
    @Volatile var notes: List<NoteItem> = emptyList()
    @Volatile var weights: List<WeightRow> = emptyList()
    @Volatile var healthMetrics: List<HealthMetricRow> = emptyList()
    @Volatile var digitalHealth: List<DigitalHealthRow> = emptyList()
    @Volatile var exercises: List<ExerciseRow> = emptyList()
    @Volatile var mealNotes: List<MealNote> = emptyList()
    val photos: MutableMap<String, List<PhotoItem>> = java.util.concurrent.ConcurrentHashMap()
    @Volatile var meetings: List<MeetingRecord> = emptyList()
    @Volatile var growthActivities: List<GrowthActivity> = emptyList()
    @Volatile var growthEfforts: List<GrowthEffort> = emptyList()
    @Volatile var reports: List<AiReport> = emptyList()
    @Volatile var pendingDrafts: List<PendingDraft> = emptyList()
    @Volatile var pendingNotes: List<UnconfirmedNote> = emptyList()
    @Volatile var workChanges: List<WorkChange> = emptyList()
}
