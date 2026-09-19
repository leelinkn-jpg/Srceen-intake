package com.linkn.screenintake.meeting

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import com.linkn.screenintake.store.StorageLayout
import com.linkn.screenintake.store.FileSnapshotCache
import java.io.File
import java.util.UUID

/** 本机文件是录音/断点恢复的暂存；完成后导出到用户同步目录，不使用私有数据库。 */
class MeetingRepository(private val context: Context) {
    private val localRoot = File(context.filesDir, "meeting-recordings").apply { mkdirs() }
    private fun dir(id: String): File {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "无效记录编号" }
        return File(localRoot, id).apply { mkdirs() }
    }
    fun audio(id: String) = File(dir(id), "录音.wav")
    fun transcript(id: String) = File(dir(id), "转录.md")
    fun create(folderUri: String, testOnly: Boolean): MeetingRecord = synchronized(fileLock) {
        val now = System.currentTimeMillis()
        MeetingRecord(
            UUID.randomUUID().toString(), now, folderUri,
            testOnly = testOnly, name = MeetingRecord.defaultName(now, testOnly)
        ).also(::save)
    }

    fun save(record: MeetingRecord) = synchronized(fileLock) {
        atomicWrite(File(dir(record.id), "记录.json"), record.toJson())
    }
    fun read(id: String): MeetingRecord = synchronized(fileLock) {
        MeetingRecord.fromJson(AtomicFile(File(dir(id), "记录.json")).openRead().bufferedReader().use { it.readText() })
    }
    fun saveTranscript(record: MeetingRecord) = atomicWrite(transcript(record.id), record.markdown())

    fun list(folderUri: String): List<MeetingRecord> {
        val local = localRoot.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull {
            runCatching { read(it.name) }.getOrNull()
        }
        val merged = local.associateBy { it.id }.toMutableMap()
        // Mini 会在同一目录中把 pending_local 更新为 ready。即使手机已有本地记录，
        // 也必须接收包含更多转录内容或更完整状态的同步副本。
        runCatching {
            val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            val work = root?.let { StorageLayout.domain(it, StorageLayout.WORK, false) }
            val directories = listOfNotNull(
                work?.findFile("会议记录"), work?.findFile("晨会记录"),
                root?.findFile("会议记录"), root?.findFile("晨会记录")
            )
            directories.flatMap { it.listFiles().filter { child -> child.isDirectory } }
                .distinctBy { it.name }.forEach { d ->
                runCatching {
                    val file = d.findFile("记录.json") ?: return@runCatching null
                    FileSnapshotCache.readFile(context, file)?.let { input ->
                        MeetingRecord.fromJson(input).copy(folderUri = folderUri, synced = true)
                    }
                }.getOrNull()?.let { remote ->
                    val current = merged[remote.id]
                    if (current == null || shouldUseRemote(current, remote)) {
                        // 用户在手机上改的会议名属于用户字段；Mini 的转录结果不能把它覆盖掉。
                        val accepted = remote.copy(name = current?.name?.takeIf { it.isNotBlank() } ?: remote.name)
                        save(accepted)
                        if (accepted.parts.isNotEmpty()) saveTranscript(accepted)
                        merged[accepted.id] = accepted
                    }
                }
            }
        }
        return merged.values.sortedByDescending { it.createdAt }
    }

    /** 只在服务未运行时恢复：进程退出前已经写下的 PCM 可以补 WAV 头，不自动恢复麦克风。 */
    fun recover() = synchronized(fileLock) {
        // 页面加载与点击录音可能同时发生，不能修复正在写入的 WAV 文件。
        if (MeetingRecorderService.state.value.busy) return@synchronized
        localRoot.listFiles().orEmpty().filter { it.isDirectory }.forEach { d ->
            val record = runCatching { read(d.name) }.getOrNull() ?: return@forEach
            if (record.status == "recording" || record.status == "transcribing") {
                val duration = WavAudio.repair(audio(record.id))
                save(record.copy(status = if (record.status == "recording") "interrupted" else "failed",
                    durationMs = maxOf(duration, record.durationMs), error = "上次任务被中断，已保留录音和转录进度", synced = false))
            }
        }
    }

    fun ensureAudio(record: MeetingRecord): File {
        val target = audio(record.id)
        if (target.exists() && target.length() > WavAudio.HEADER_SIZE) return target
        val remote = archiveDir(record, false)?.findFile("录音.wav") ?: error("找不到录音文件")
        val temp = File(target.parentFile, "录音.download")
        context.contentResolver.openInputStream(remote.uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取同步目录中的录音")
        check(temp.length() > WavAudio.HEADER_SIZE) { "录音文件不完整" }
        check(temp.renameTo(target)) { "保存下载录音失败" }
        return target
    }

    fun transcriptText(record: MeetingRecord): String {
        val remote = runCatching { archiveDir(record, false)?.findFile("转录.md") }.getOrNull()
        if (record.synced && remote != null) {
            FileSnapshotCache.readFile(context, remote)?.let { return it }
        }
        val file = transcript(record.id)
        return if (file.exists()) file.readText() else record.markdown()
    }

    fun export(record: MeetingRecord): MeetingRecord {
        val destination = archiveDir(record, true) ?: error("无法打开会议记录目录")
        val audio = ensureAudio(record)
        val remoteAudio = destination.findFile("录音.wav")
        if (remoteAudio == null || remoteAudio.length() != audio.length()) {
            write(destination, "录音.wav", "audio/wav") { out -> audio.inputStream().use { it.copyTo(out) } }
        }
        // 手机只发布原始录音和待处理元数据；转录文本由 Mini 生成并回传。
        if (record.parts.isNotEmpty() || record.status == "ready") {
            saveTranscript(record)
            write(destination, "转录.md", "text/markdown") { it.write(record.markdown().toByteArray(Charsets.UTF_8)) }
        }
        val saved = record.copy(synced = true)
        write(destination, "记录.json", "application/json") { it.write(saved.toJson().toByteArray(Charsets.UTF_8)) }
        save(saved)
        return saved
    }

    fun rename(record: MeetingRecord, name: String): MeetingRecord = synchronized(fileLock) {
        val clean = name.trim()
        require(clean.length in 1..32 && clean.none { it == '\n' || it == '\r' }) { "会议名称为 1–32 个字，不能换行" }
        val updated = record.copy(name = clean)
        save(updated)
        if (updated.synced) export(updated) else updated
    }

    /** 用户从会议记录页明确删除时调用：同步目录和本机暂存一并删除。 */
    fun delete(record: MeetingRecord) = synchronized(fileLock) {
        // 先处理同步副本。删除失败就保留本机副本，避免用户以为已经彻底删除。
        archiveDir(record, false)?.let { remote ->
            check(remote.delete()) { "无法删除同步文件夹中的会议记录" }
        }
        val local = File(localRoot, record.id)
        if (local.exists()) check(local.deleteRecursively()) { "无法删除本机会议记录" }
    }

    private fun archiveDir(record: MeetingRecord, create: Boolean): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(record.folderUri)) ?: error("请先选择保存文件夹")
        val work = StorageLayout.domain(root, StorageLayout.WORK, false)
        val current = work?.findFile("会议记录")?.let { findRecordDirectory(it, record.id) }
        val oldNamed = work?.findFile("晨会记录")?.let { findRecordDirectory(it, record.id) }
        val legacy = root.findFile("会议记录")?.let { findRecordDirectory(it, record.id) }
            ?: root.findFile("晨会记录")?.let { findRecordDirectory(it, record.id) }
        (current ?: oldNamed)?.let { existing ->
            if (create && existing.name != folderName(record)) existing.renameTo(folderName(record))
            return existing
        }
        if (!create) return legacy
        val meetings = StorageLayout.writableDirectory(context, root, StorageLayout.WORK, "会议记录")
        findRecordDirectory(meetings, record.id)?.let { existing ->
            val desired = folderName(record)
            if (existing.name != desired) existing.renameTo(desired)
            return existing
        }
        return meetings.createDirectory(folderName(record))
    }

    private fun findRecordDirectory(parent: DocumentFile, id: String): DocumentFile? {
        parent.findFile(id)?.takeIf { it.isDirectory }?.let { return it }
        return parent.listFiles().firstOrNull { directory ->
            if (!directory.isDirectory) return@firstOrNull false
            val metadata = directory.findFile("记录.json") ?: return@firstOrNull false
            runCatching {
                context.contentResolver.openInputStream(metadata.uri)?.bufferedReader()?.use {
                    MeetingRecord.fromJson(it.readText()).id == id
                } == true
            }.getOrDefault(false)
        }
    }

    private fun folderName(record: MeetingRecord): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.CHINA)
            .format(java.util.Date(record.createdAt))
        val title = record.name.ifBlank { MeetingRecord.defaultName(record.createdAt, record.testOnly) }
            .replace(Regex("[\\\\/:*?\"<>|]"), "")
            .trim().ifBlank { "会议记录" }.take(30)
        return "${stamp}_${title}"
    }
    private fun write(dir: DocumentFile, name: String, mime: String, writer: (java.io.OutputStream) -> Unit) {
        val f = dir.findFile(name) ?: dir.createFile(mime, name) ?: error("无法创建 $name")
        context.contentResolver.openOutputStream(f.uri, "wt")?.use(writer) ?: error("无法写入 $name")
    }
    private fun atomicWrite(file: File, content: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(content.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output)
        } catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    companion object {
        private val fileLock = Any()

        internal fun shouldUseRemote(local: MeetingRecord, remote: MeetingRecord): Boolean {
            val rank = mapOf("recording" to 0, "recorded" to 1, "pending_local" to 2,
                "interrupted" to 2, "failed" to 2, "transcribing" to 3, "ready" to 4)
            val remoteRank = rank[remote.status] ?: 0
            val localRank = rank[local.status] ?: 0
            if (remoteRank > localRank) return true
            // Mini 可能精修文字但段数不变；不能只拿段数判断是否更新。
            return remoteRank == localRank && remote.parts.isNotEmpty() && remote.parts != local.parts
        }
    }
}
