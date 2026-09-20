package com.linkn.screenintake.store

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** SAF content:// 走 ContentResolver；file:// / DocumentFile.fromFile 走 java.io.File。 */
object HubIO {
    fun openInput(context: Context, file: DocumentFile): InputStream? {
        val uri = file.uri
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val f = File(path)
            if (!f.isFile) return null
            return FileInputStream(f)
        }
        return context.contentResolver.openInputStream(uri)
    }

    fun openOutput(context: Context, file: DocumentFile, mode: String = "wt"): OutputStream? {
        val uri = file.uri
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val f = File(path)
            f.parentFile?.mkdirs()
            return FileOutputStream(f, mode.contains("wa") || mode == "wa")
        }
        return context.contentResolver.openOutputStream(uri, mode)
    }

    fun readText(context: Context, file: DocumentFile): String? =
        openInput(context, file)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
}
