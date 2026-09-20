package com.linkn.screenintake.store

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 手机同步目录的稳定结构。App 只接触这五个目录，不读取 Mini 专属的部门资料、模型规则和日志。
 * 读取时兼容旧版根目录平铺文件；第一次写入时会把对应旧文件/目录复制到新位置。
 */
object StorageLayout {
    const val FINANCE = "财务"
    const val HEALTH = "健康"
    const val WORK = "工作"
    const val GROWTH = "成长"
    const val SYSTEM = "系统"

    private val financeFiles = setOf("账本.csv", "卡片.csv", "持仓.csv", "交易记录.csv", "转账记录.csv")
    private val healthFiles = setOf("健康.csv", "运动.csv", "身体数据.csv", "数字健康.csv", "体重.csv", "饮食记录.md", "营养分析.json", "酒精记录.json")
    private val workFiles = setOf("待办.md", "灵感.md")
    // 成长项目本身也是给 Mini 分析的原始数据，不应只留在 App 私有目录。
    private val growthFiles = setOf("项目.json")
    /**
     * 不缓存、更不持久化 DocumentFile 子路径。Syncthing 可以在 App 外部替换文件；
     * Android SAF 的子 URI 因而不是稳定身份。真正的加速由 FileSnapshotCache（文本）和
     * UiDataCache（解析结果）承担；这里每次仅做 1～2 次直接 findFile，成本可忽略。
     */
    fun initialize(context: android.content.Context) = Unit

    fun domainForFile(name: String): String = when (name) {
        in financeFiles -> FINANCE
        in healthFiles -> HEALTH
        in workFiles -> WORK
        in growthFiles -> GROWTH
        else -> SYSTEM
    }

    fun domain(root: DocumentFile, name: String, create: Boolean): DocumentFile? {
        val found = root.findFile(name)?.takeIf { it.isDirectory }
            ?: if (create) root.createDirectory(name) else null
        return found
    }

    fun readFile(root: DocumentFile, name: String): DocumentFile? {
        val nested = domain(root, domainForFile(name), false)?.findFile(name)
        return nested ?: root.findFile(name)?.takeIf { it.isFile }
    }

    fun writableFile(context: Context, root: DocumentFile, name: String, mime: String): DocumentFile {
        // 同步工具可能在后台以“替换文件”的方式落盘；此时缓存的子文件 URI 已经失效，
        // 继续拿它写会造成所有编辑操作一起失败。写入永远从当前树根重新定位一次，
        // 读取仍可使用缓存以保证列表性能。
        val dir = freshDomain(root, domainForFile(name), true) ?: error("无法创建${domainForFile(name)}目录")
        // readFile 可能缓存的是旧版根目录平铺文件；正式写入永远回到新中文目录，
        // 不能因为一次兼容读取又把后续写入锁回旧路径。
        dir.findFile(name)?.takeIf { it.isFile }?.let { return it }
        // Raw DocumentFile.createFile appends the MIME extension, so
        // createFile("text/csv", "卡片.csv") becomes 卡片.csv.csv and later
        // reads of 卡片.csv look empty. file:// test/sync roots must use the exact name.
        val target = if (root.uri.scheme == "file") {
            val directory = File(dir.uri.path ?: error("无法解析${domainForFile(name)}目录"))
            val exact = File(directory, name)
            if (!exact.exists() && !exact.createNewFile()) error("创建 $name 失败")
            DocumentFile.fromFile(exact)
        } else {
            dir.createFile(mime, name) ?: error("创建 $name 失败")
        }
        root.findFile(name)?.takeIf { it.isFile }?.let { copyFile(context, it, target) }
        return target
    }

    fun readDirectory(root: DocumentFile, domain: String, name: String): DocumentFile? {
        return this.domain(root, domain, false)?.findFile(name)?.takeIf { it.isDirectory }
            ?: root.findFile(name)?.takeIf { it.isDirectory }
    }

    fun writableDirectory(context: Context, root: DocumentFile, domain: String, name: String): DocumentFile {
        val domainDir = freshDomain(root, domain, true) ?: error("无法创建${domain}目录")
        domainDir.findFile(name)?.takeIf { it.isDirectory }?.let { return it }
        val target = domainDir.createDirectory(name) ?: error("无法创建${name}目录")
        root.findFile(name)?.takeIf { it.isDirectory }?.let { copyDirectory(context, it, target) }
        return target
    }

    private fun freshDomain(root: DocumentFile, name: String, create: Boolean): DocumentFile? =
        root.findFile(name)?.takeIf { it.isDirectory } ?: if (create) root.createDirectory(name) else null

    /** 保留 API 兼容；目录句柄已不再缓存，外部同步后只需要清文本内存缓存。 */
    fun invalidateExternalHandles(rootUri: String) {
        if (rootUri.isNotBlank()) LedgerReader.invalidateCaches()
    }

    private fun copyDirectory(context: Context, source: DocumentFile, target: DocumentFile) {
        source.listFiles().forEach { item ->
            val name = item.name ?: return@forEach
            if (item.isDirectory) {
                val child = target.findFile(name) ?: target.createDirectory(name) ?: return@forEach
                copyDirectory(context, item, child)
            } else if (item.isFile) {
                val child = target.findFile(name) ?: target.createFile(item.type ?: "application/octet-stream", name)
                    ?: return@forEach
                copyFile(context, item, child)
            }
        }
    }

    private fun copyFile(context: Context, source: DocumentFile, target: DocumentFile) {
        HubIO.openInput(context, source)?.use { input ->
            HubIO.openOutput(context, target, "wt")?.use { output -> input.copyTo(output) }
                ?: error("无法写入${target.name}")
        } ?: error("无法读取${source.name}")
    }
}
