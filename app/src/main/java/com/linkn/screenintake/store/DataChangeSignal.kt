package com.linkn.screenintake.store

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * App 内部的「数据变了」信号——[RecordStore] 每次真正把 账本.csv/待办.md/灵感.md/待确认
 * 草稿写完之后调用 [bump]，各个 Tab 在组合里读一下 [tick].value 并配合
 * `LaunchedEffect(tick) { reload() }` 订阅，一变就重新读文件，不用等下一次真正切 Tab 或者
 * App 回到前台。
 *
 * 为什么光靠 resumeTick（App 回到前台）不够：组合键静默截屏、长按拍照/打字这几条捕获
 * 路径，从设计上就是让底层 App 全程留在前台、不经历 onPause/onResume（长按悬浮窗这个方案
 * 当初就是为了取代会把 App 切到后台的旧设计），resumeTick 因此根本不会变。如果这时候人
 * 正好停在财务/待办/灵感/待确认某个 Tab 上——这其实是最常见的场景，比如就开着账本页面
 * 顺手截一笔账——单靠 resumeTick 完全捕捉不到这次写入，"文件里有、App 里没有"这个问题
 * 就是这么来的。这个信号在真正写完文件那一刻直接通知所有正在看的 Tab，不依赖 Activity
 * 生命周期，是比 resumeTick 更准的信号；resumeTick 仍然保留，作为"App 被切到后台过、
 * 回来时文件可能被外部改过"（比如 Syncthing 从 Mac 同步下来新内容）这种情况的兜底。
 */
object DataChangeSignal {
    val tick: MutableState<Int> = mutableStateOf(0)

    fun bump() {
        tick.value = tick.value + 1
    }
}
