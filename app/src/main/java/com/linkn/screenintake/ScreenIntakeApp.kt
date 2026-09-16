package com.linkn.screenintake

import android.app.Application
import com.linkn.screenintake.settings.SecureSettingsStore
import com.linkn.screenintake.store.PriceCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
        PriceCache.load(this)
    }

    companion object {
        lateinit var instance: ScreenIntakeApp
            private set
    }
}
