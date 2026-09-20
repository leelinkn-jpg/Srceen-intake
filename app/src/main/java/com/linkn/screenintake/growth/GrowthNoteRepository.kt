package com.linkn.screenintake.growth
import com.linkn.screenintake.store.HubRoot

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.linkn.screenintake.store.FileSnapshotCache

data class GrowthNote(val uri: String, val name: String, val path: String, val preview: String, val updatedAt: Long)
data class GrowthNoteCollection(val name: String, val path: String)
data class GrowthNoteListing(val collections: List<GrowthNoteCollection>, val notes: List<GrowthNote>)

/** 成长笔记只读展示：只读取中枢正式目录“成长/笔记”。 */
class GrowthNoteRepository(private val context: Context) {
    fun list(folderUri: String, relativePath: String = ""): GrowthNoteListing {
        val root = HubRoot.resolve(context, folderUri)
            ?: error("笔记目录授权已失效，请在设置中检查文件夹")
        val noteRoot = root.findFile("成长")?.takeIf { it.isDirectory }?.findFile("笔记")
            ?.takeIf { it.isDirectory }
            ?: error("暂时无法读取成长/笔记，请检查同步目录")
        val directory = relativePath.split('/').filter(String::isNotBlank).fold(noteRoot) { parent, part ->
            parent.findFile(part)?.takeIf { it.isDirectory }
                ?: error("合集目录暂时不可读，请返回上级刷新")
        }
        check(directory.canRead()) { "笔记读取权限已失效" }
        val entries = directory.listFiles().toList()
        return GrowthNoteListing(
            collections = entries.asSequence().filter(DocumentFile::isDirectory)
                .map { GrowthNoteCollection(it.name.orEmpty(), join(relativePath, it.name.orEmpty())) }
                .filter { it.name.isNotBlank() && !it.name.startsWith(".") }.sortedBy { it.name }.toList(),
            notes = entries.asSequence().filter { it.isFile && it.name?.endsWith(".md", true) == true }
                .map { file ->
                    val name = file.name.orEmpty()
                    GrowthNote(file.uri.toString(), name.removeSuffix(".md"), join(relativePath, name), "", file.lastModified())
                }.sortedByDescending { it.updatedAt }.toList()
        )
    }

    fun content(folderUri: String, note: GrowthNote): String {
        val root = HubRoot.resolve(context, folderUri) ?: error("笔记目录不可访问")
        val file = note.path.split('/').filter(String::isNotBlank)
            .fold(root.findFile("成长")?.findFile("笔记") ?: error("笔记目录不可访问")) { parent, part ->
                parent.findFile(part) ?: error("笔记文件暂时不可读，请刷新后重试")
            }
        return FileSnapshotCache.readFile(context, file) ?: error("笔记内容读取失败")
    }

    private fun join(parent: String, child: String) = if (parent.isBlank()) child else "$parent/$child"
}
