package com.linkn.screenintake.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.documentfile.provider.DocumentFile

/**
 * 仅用于加速的本地文件快照。
 *
 * 原始 CSV/Markdown 仍在用户选定的同步目录中；本库从不向同步目录写内容。冷启动时如果
 * 文件的 URI、修改时间和大小都不变，直接使用本机快照，避免反复通过 SAF 读取整份文件。
 * 快照损坏、清空或卸载 App 都只会导致下一次重新读取原文件。
 */
class FileSnapshotCache private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "file_snapshots.db", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE snapshots (
              uri TEXT PRIMARY KEY,
              modified INTEGER NOT NULL,
              length INTEGER NOT NULL,
              content TEXT NOT NULL,
              accessed INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun invalidate(uri: String) { writableDatabase.delete("snapshots", "uri=?", arrayOf(uri)) }

    @Synchronized
    fun read(uri: String, modified: Long, length: Long): String? = runCatching {
        readableDatabase.rawQuery(
            "SELECT content FROM snapshots WHERE uri=? AND modified=? AND length=?",
            arrayOf(uri, modified.toString(), length.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            writableDatabase.execSQL("UPDATE snapshots SET accessed=? WHERE uri=?",
                arrayOf(System.currentTimeMillis(), uri))
            cursor.getString(0)
        }
    }.getOrNull()

    @Synchronized
    fun write(uri: String, modified: Long, length: Long, content: String) = runCatching {
        // 这是加速层而非备份层；超长录音全文仍可读取原文件，但不应把 SQLite 撑大。
        if (content.length > MAX_SNAPSHOT_CHARS) return@runCatching
        val values = ContentValues().apply {
            put("uri", uri); put("modified", modified); put("length", length)
            put("content", content); put("accessed", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("snapshots", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        // 只保留最近 80 份文本快照，防止会议全文等缓存无限增长。
        writableDatabase.execSQL("""
            DELETE FROM snapshots WHERE uri IN (
              SELECT uri FROM snapshots ORDER BY accessed DESC LIMIT -1 OFFSET 80
            )
        """.trimIndent())
    }

    companion object {
        private const val MAX_SNAPSHOT_CHARS = 256_000
        @Volatile private var instance: FileSnapshotCache? = null
        fun get(context: Context): FileSnapshotCache = instance ?: synchronized(this) {
            instance ?: FileSnapshotCache(context).also { instance = it }
        }

        /** 供报告等非 LedgerReader 的同步文本读取复用。 */
        fun readFile(context: Context, file: DocumentFile): String? {
            val uri = file.uri.toString()
            val modified = file.lastModified()
            val length = file.length()
            get(context).read(uri, modified, length)?.let { return it }
            val text = HubIO.readText(context, file) ?: return null
            get(context).write(uri, modified, length, text)
            return text
        }
    }
}
