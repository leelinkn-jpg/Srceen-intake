package com.linkn.screenintake.capture

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * 悬浮相机小工具专用：CameraX 的 bindToLifecycle 需要一个 LifecycleOwner，但悬浮窗不是
 * Activity，没有现成的生命周期可用，所以手写一个最简单的、自己手动推进状态的
 * LifecycleOwner——小工具出现时推到 RESUMED，收起来时推到 DESTROYED（CameraX 会在
 * DESTROYED 那一刻自动解绑摄像头，不用自己再手动 unbind 一次）。
 */
class OverlayLifecycleOwner : LifecycleOwner {

    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry

    fun start() {
        registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.STARTED
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
