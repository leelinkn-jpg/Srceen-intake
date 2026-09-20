package com.linkn.screenintake.store

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 同步根解析：正式用户走 SAF content tree URI。
 * 模拟器测试：不要依赖 /sdcard（MANAGE_EXTERNAL_STORAGE 常 appops=allow 但进程仍 Permission denied）。
 * 旁路：可读路径直接绑 file://；否则从 /data/local/tmp 拷进 filesDir/秒记中枢 再绑。
 */
object HubRoot {
    const val ACTION_BIND_TEST_HUB = "com.linkn.screenintake.ACTION_BIND_TEST_HUB"
    const val EXTRA_PATH = "path"
    /** 可选：显式源目录，拷贝进内部 filesDir/秒记中枢 后再绑定 */
    const val EXTRA_COPY_FROM = "copy_from"
    const val DEFAULT_TEST_PATH = "/storage/emulated/0/秒记中枢"
    const val TMP_HUB_PATH = "/data/local/tmp/秒记中枢"
    const val BIND_PATH_FILE = "/data/local/tmp/miaoj_bind_path.txt"

    private val hubMarkers = listOf("财务", "健康", "工作", "成长", "系统", "综合报告")

    fun resolve(context: Context, folderUri: String): DocumentFile? {
        if (folderUri.isBlank()) return null
        val uri = Uri.parse(folderUri)
        return when (uri.scheme) {
            "file" -> {
                val path = uri.path ?: return null
                val dir = File(path)
                if (!dir.isDirectory) null else DocumentFile.fromFile(dir)
            }
            "content" -> DocumentFile.fromTreeUri(context, uri)
            null, "" -> {
                val dir = File(folderUri)
                if (dir.isDirectory) DocumentFile.fromFile(dir) else DocumentFile.fromTreeUri(context, uri)
            }
            else -> DocumentFile.fromTreeUri(context, uri)
        }
    }

    fun looksLikeHub(root: DocumentFile): Boolean {
        val names = root.listFiles()?.mapNotNull { it.name }?.toSet().orEmpty()
        return hubMarkers.count { it in names } >= 2
    }

    fun looksLikeHubDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val names = dir.list()?.toSet().orEmpty()
        return hubMarkers.count { it in names } >= 2
    }

    fun internalHubDir(context: Context): File = File(context.filesDir, "秒记中枢")

    /**
     * 测试绑定：优先用进程真正能 isDirectory 的路径；必要时从 copyFrom/tmp 拷进内部目录。
     * 不依赖 Environment.isExternalStorageManager()。
     */
    fun bindForTest(context: Context, requestedPath: String?, copyFrom: String?): Result<BindResult> {
        val pathFromFile = runCatching {
            File(BIND_PATH_FILE).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        val tried = mutableListOf<String>()

        fun tryBindExisting(path: String): BindResult? {
            tried += "check:$path"
            val dir = File(path)
            if (!dir.isDirectory) {
                tried += "not_dir:$path"
                return null
            }
            if (!looksLikeHubDir(dir)) {
                tried += "not_hub:$path names=${dir.list()?.take(8)}"
                return null
            }
            // 不用 canonicalFile：部分设备会把路径解析到进程读不了的挂载点
            val abs = dir.absoluteFile
            val uri = Uri.fromFile(abs).toString()
            return BindResult(uri, abs.absolutePath, "existing", tried.toList())
        }

        // 1) 显式 copy_from → 内部目录
        val copySources = listOfNotNull(
            copyFrom?.takeIf { it.isNotBlank() },
            requestedPath?.takeIf { it.isNotBlank() && it != internalHubDir(context).absolutePath },
            pathFromFile,
            TMP_HUB_PATH,
        ).distinct()

        for (srcPath in copySources) {
            val src = File(srcPath)
            tried += "copy_src:$srcPath"
            if (!src.isDirectory) {
                tried += "copy_src_missing:$srcPath"
                continue
            }
            if (!looksLikeHubDir(src)) {
                tried += "copy_src_not_hub:$srcPath"
                continue
            }
            val dest = internalHubDir(context)
            val copied = runCatching {
                if (dest.exists()) dest.deleteRecursively()
                copyRecursive(src, dest)
                dest
            }
            if (copied.isFailure) {
                tried += "copy_fail:${copied.exceptionOrNull()?.message}"
                continue
            }
            tryBindExisting(dest.absolutePath)?.let {
                return Result.success(it.copy(mode = "copied_from:$srcPath", tried = tried.toList()))
            }
        }

        // 2) 直接绑已有可读目录（内部 / tmp / 请求路径 / 默认 sdcard）
        val direct = listOfNotNull(
            requestedPath?.takeIf { it.isNotBlank() },
            pathFromFile,
            internalHubDir(context).absolutePath,
            TMP_HUB_PATH,
            DEFAULT_TEST_PATH,
            "/sdcard/秒记中枢",
        ).distinct()

        for (path in direct) {
            tryBindExisting(path)?.let { return Result.success(it) }
        }

        return Result.failure(
            IllegalStateException(
                "无法绑定测试中枢。请 adb push 到 $TMP_HUB_PATH 后重发 BIND。tried=$tried"
            )
        )
    }

    /** @deprecated 保留给旧调用；转 bindForTest */
    fun bindAbsolutePath(context: Context, absolutePath: String): Result<String> =
        bindForTest(context, absolutePath, null).map { it.uri }

    private fun copyRecursive(src: File, dest: File) {
        if (src.isDirectory) {
            if (!dest.exists() && !dest.mkdirs()) error("无法创建 ${dest.path}")
            val children = src.listFiles() ?: return
            for (child in children) {
                copyRecursive(child, File(dest, child.name))
            }
        } else {
            dest.parentFile?.mkdirs()
            FileInputStream(src).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        }
    }

    data class BindResult(
        val uri: String,
        val absolutePath: String,
        val mode: String,
        val tried: List<String>,
    )
}
