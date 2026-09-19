package com.linkn.screenintake.store

import android.content.Context

/** Only operational metadata, never record contents or model credentials. */
object SystemStatus {
    private val failures = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    fun failureGeneration(stage: String): Long = failures[stage]?.get() ?: 0
    fun success(context: Context, stage: String) {
        context.getSharedPreferences("system_status", Context.MODE_PRIVATE).edit()
            .putLong("$stage.time", System.currentTimeMillis()).remove("$stage.error").apply()
    }
    fun failure(context: Context, stage: String, message: String) {
        failures.getOrPut(stage) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        context.getSharedPreferences("system_status", Context.MODE_PRIVATE).edit()
            .putString("$stage.error", message.take(160)).apply()
    }
}
