package com.example.unzipkeylist.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import java.io.File

/** 存储权限与 SAF Uri → 真实路径解析 */
object StorageUtils {

    /** 是否已获得"所有文件访问"（Android 11+）或旧版外部存储读写权限 */
    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** 跳转"所有文件访问权限"设置页 */
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    /** SAF 选中的文档 Uri → 真实文件；解析不了返回 null（调用方回退到复制进缓存目录） */
    fun resolveRealFile(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        return when (uri.authority) {
            "com.android.externalstorage.documents" -> documentIdToFile(docId)
            "com.android.providers.downloads.documents" ->
                if (docId.startsWith("raw:")) File(docId.removePrefix("raw:")) else null
            else -> null
        }
    }

    /** SAF 授权的目录 tree Uri → 真实目录 */
    fun resolveRealDirectory(treeUri: Uri): File? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        return documentIdToFile(docId)
    }

    fun displayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    private fun documentIdToFile(docId: String): File? {
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        val volume = parts[0]
        val relative = parts[1]
        val root = when {
            volume.equals("primary", true) || volume.equals("raw", true) ->
                Environment.getExternalStorageDirectory().absolutePath
            else -> if (File("/storage/$volume").exists()) "/storage/$volume" else null
        } ?: return null
        return if (relative.isEmpty()) File(root) else File(root, relative)
    }
}

object FileUtils {

    /**
     * 把压缩包内的条目名映射为安全的目标文件，阻止 ../ 路径穿越。
     * 条目名为空或是目录时返回 destDir 本身。
     */
    fun safeTargetFile(destDir: File, entryName: String): File {
        val segments = entryName.replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        if (segments.isEmpty()) return destDir
        var current = destDir
        for (segment in segments) current = File(current, segment)
        val canonicalDir = destDir.canonicalPath
        return if (current.canonicalPath == canonicalDir || current.canonicalPath.startsWith(canonicalDir + File.separator)) {
            current
        } else {
            File(destDir, segments.last())
        }
    }
}
