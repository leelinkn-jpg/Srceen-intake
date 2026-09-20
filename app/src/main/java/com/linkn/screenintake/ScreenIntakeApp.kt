package com.linkn.screenintake

import android.app.Application
import com.linkn.screenintake.settings.SecureSettingsStore
import com.linkn.screenintake.store.PriceCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.linkn.screenintake.report.AiReportNotificationWorker
import com.linkn.screenintake.store.SyncNotificationWorker
import com.linkn.screenintake.store.EveningHealthReminderWorker
import com.linkn.screenintake.store.StorageLayout
import com.linkn.screenintake.store.LocalDataIndexWorker

class ScreenIntakeApp : Application() {
    lateinit var settingsStore: SecureSettingsStore
        private set

    // 给"寿命比某个 Activity/Service 短"的调用方（比如快速文字输入框，提交完立刻关闭页面）
    // 用来发起不想被打断的后台工作——绑定在 Application 上，只要进程还活着就不会被取消。
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsStore = SecureSettingsStore(this)
        StorageLayout.initialize(this)
        PriceCache.load(this)
        AiReportNotificationWorker.schedule(this)
        SyncNotificationWorker.schedule(this)
        EveningHealthReminderWorker.schedule(this)
        // 冷启动先让界面使用已有快照；结构化读取在后台补齐，不阻塞首屏。
        LocalDataIndexWorker.refresh(this)
    }

    companion object {
        lateinit var instance: ScreenIntakeApp
            private set
    }
}
