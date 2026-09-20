package com.linkn.screenintake.store

import android.content.Context
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A pending operation is retained only on failure, never as a historical backup. */
internal object VerifiedFileWrite {
    private data class Operation(val journal: AtomicFile, val payload: JSONObject)
    private val active = ThreadLocal<Operation?>()

    private fun persist(operation: Operation) {
        val stream = operation.journal.startWrite()
        try { stream.write(operation.payload.toString().toByteArray(Charsets.UTF_8)); operation.journal.finishWrite(stream) }
        catch (e: Exception) { operation.journal.failWrite(stream); throw e }
    }

    fun <T> transaction(context: Context, block: () -> T): T {
        if (active.get() != null) return block()
        val directory = File(context.filesDir, "pending-writes").apply { mkdirs() }
        val operation = Operation(AtomicFile(File(directory, "${UUID.randomUUID()}.json")),
            JSONObject().put("createdAt", System.currentTimeMillis()).put("writes", org.json.JSONArray()))
        active.set(operation)
        try {
            val result = block()
            operation.journal.delete()
            return result
        } finally { active.remove() }
    }

    fun replace(context: Context, file: DocumentFile, expected: String?, content: String) {
        if (active.get() == null) return transaction(context) { replace(context, file, expected, content) }
        fun read() = HubIO.openInput(context, file)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("无法读取 ${file.name}，已停止保存")
        val before = read()
        check(expected == null || before == expected) { "文件已被其他操作更新，请刷新后重试；原记录未覆盖" }
        val operation = active.get()!!
        operation.payload.getJSONArray("writes").put(JSONObject().put("uri", file.uri.toString()).put("name", file.name)
            .put("before", before).put("after", content))
        persist(operation)
        check(read() == before) { "同步文件刚刚发生变化，修改已保留，尚未覆盖原文件" }
        HubIO.openOutput(context, file, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            ?: error("无法写入 ${file.name}，修改已保留")
        check(read() == content) { "保存校验未通过，修改已保留，请到系统状态检查" }
        runCatching { FileSnapshotCache.get(context).invalidate(file.uri.toString()) }
            .onFailure { SystemStatus.failure(context, "读取", "数据已保存，缓存更新失败，请重新检查") }
        LedgerReader.invalidateCaches()
        LedgerReader.didWrite(file.uri.toString(), content)
    }

    /** Resolve only files still equal to one of our known states. Never overwrite a third-party edit. */
    fun reconcile(context: Context): String {
        var resolved = 0
        var conflicts = 0
        File(context.filesDir, "pending-writes").listFiles().orEmpty().filter { it.extension == "json" }.forEach { file ->
            runCatching {
                val journal = AtomicFile(file)
                val entries = JSONObject(journal.openRead().bufferedReader().use { it.readText() }).getJSONArray("writes")
                val changes = linkedMapOf<String, Pair<String, String>>()
                for (i in 0 until entries.length()) {
                    val item = entries.getJSONObject(i)
                    val uri = item.getString("uri")
                    changes[uri] = (changes[uri]?.first ?: item.getString("before")) to item.getString("after")
                }
                fun read(uri: String): String {
                    val parsed = android.net.Uri.parse(uri)
                    val stream = if (parsed.scheme == "file") {
                        java.io.FileInputStream(java.io.File(parsed.path!!))
                    } else context.contentResolver.openInputStream(parsed)
                    return stream?.bufferedReader()?.use { it.readText() } ?: error("读取失败")
                }
                val current = changes.keys.associateWith(::read)
                check(changes.all { (uri, versions) -> current[uri] == versions.first || current[uri] == versions.second })
                val complete = changes.all { (uri, versions) -> current[uri] == versions.second }
                if (!complete) changes.entries.reversed().forEach { (uri, versions) ->
                    check(read(uri) == current[uri])
                    if (current[uri] != versions.first) {
                        val parsed = android.net.Uri.parse(uri)
                        val out = if (parsed.scheme == "file") {
                            java.io.FileOutputStream(java.io.File(parsed.path!!))
                        } else context.contentResolver.openOutputStream(parsed, "wt")
                        out?.use { it.write(versions.first.toByteArray(Charsets.UTF_8)) } ?: error("恢复失败")
                        check(read(uri) == versions.first)
                    }
                }
                changes.keys.forEach { FileSnapshotCache.get(context).invalidate(it) }
                journal.delete()
                resolved++
            }.onFailure { conflicts++ }
        }
        if (resolved > 0) DataChangeSignal.bump()
        return "已核对 $resolved 项；${if (conflicts == 0) "没有未解决冲突" else "$conflicts 项与现有文件不一致，已保留等待人工核对"}"
    }
}
