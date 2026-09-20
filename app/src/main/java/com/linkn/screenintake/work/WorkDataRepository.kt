package com.linkn.screenintake.work
import com.linkn.screenintake.store.HubRoot

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.linkn.screenintake.store.FileSnapshotCache
import com.linkn.screenintake.store.StorageLayout
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WorkOverview(val cutoff: String, val metrics: List<Pair<String, String>>)
data class WorkProject(val manager: String, val name: String, val amount: String, val expected: String, val progress: String)
data class WorkCustomer(val owner: String, val name: String, val type: String, val feedback: String, val lastVisit: String)
data class WorkEvent(val date: String, val owner: String, val target: String, val type: String, val detail: String, val match: String)

/** Mini 从正式工作簿生成的只读手机镜像；无镜像时明确显示等待同步，绝不把空值当成零。 */
class WorkDataRepository(private val context: Context) {
    fun overview(folder: String): WorkOverview? = json(folder, "工作概览.json")?.let { source ->
        val metrics = listOf("厂租投放", "厂租剩余储备", "厂配容投放", "厂配容剩余投放", "厂配容储备")
            .mapNotNull { key -> source.opt(key)?.toString()?.takeIf(String::isNotBlank)?.let { key to it } }
        WorkOverview(source.optString("dataCutoff"), metrics)
    }
    fun projects(folder: String) = array(folder, "储备项目.json", "projects").map { item ->
        WorkProject(item.optString("manager", item.optString("客户经理")), item.optString("name", item.optString("项目名称")),
            item.opt("amount")?.toString() ?: item.opt("金额")?.toString().orEmpty(), item.optString("expected", item.optString("预计投放时间")),
            item.optString("progress", item.optString("当前进度")))
    }
    fun capacityCustomers(folder: String) = array(folder, "厂配容名单.json", "customers").map { item ->
        WorkCustomer(item.optString("owner", item.optString("名单归属人姓名")), item.optString("name", item.optString("企业名称")),
            item.optString("type", item.optString("厂配容客户类型")), item.optString("feedback", item.optString("营销反馈")),
            item.optString("lastVisit", item.optString("最近一次走访日期")))
    }
    fun overdueCustomers(folder: String) = array(folder, "逾期客户.json", "customers").map { item ->
        WorkCustomer(item.optString("owner", item.optString("客户经理")), item.optString("name", item.optString("客户名称")),
            item.optString("type", item.optString("逾期类型")), item.optString("feedback", item.optString("reason", item.optString("筛选依据"))),
            item.optString("lastVisit", item.optString("dataDate")))
    }
    fun visits(folder: String) = events(folder, "走访记录.json")
    /** 投放页是本月只读看板；历史投放仍保留在原始业务数据中，不在 App 里做修改。 */
    fun placements(folder: String): List<WorkEvent> {
        val month = SimpleDateFormat("yyyy-MM", Locale.CHINA).format(Date())
        return events(folder, "投放记录.json").filter { it.date.startsWith(month) }
    }

    private fun events(folder: String, name: String) = array(folder, name, "records").map { item ->
        WorkEvent(item.optString("date", item.optString("日期")), item.optString("owner", item.optString("客户经理")),
            item.optString("target", item.optString("对象", item.optString("承租人"))), item.optString("type", item.optString("对象类型", item.optString("业务类型"))),
            item.optString("detail", item.optString("content", item.optString("内容", item.optString("amount", item.optString("金额"))))),
            item.optString("match", item.optString("matchedList", item.optString("匹配名单"))))
    }
    private fun array(folder: String, name: String, key: String): List<JSONObject> {
        val payload = json(folder, name) ?: return emptyList()
        val values = payload.optJSONArray(key) ?: payload.optJSONArray("items") ?: JSONArray()
        return (0 until values.length()).mapNotNull { values.optJSONObject(it) }
    }
    private fun json(folder: String, name: String): JSONObject? = runCatching {
        val root = HubRoot.resolve(context, folder) ?: return@runCatching null
        val work = StorageLayout.domain(root, StorageLayout.WORK, false) ?: return@runCatching null
        val file = work.findFile("业务数据")?.findFile(name) ?: return@runCatching null
        FileSnapshotCache.readFile(context, file)?.let(::JSONObject)
    }.getOrNull()
}
