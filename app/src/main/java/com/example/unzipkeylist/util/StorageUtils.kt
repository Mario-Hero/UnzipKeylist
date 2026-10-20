package com.example.unzipkeylist.util

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import java.io.File

/** 存储权限与 SAF Uri → 真实路径解析 */
object StorageUtils {

    /** 老版本「下载」provider 的文档 id 对应 public_downloads 表 */
    private val DOWNLOADS_PUBLIC_URI = Uri.parse("content://downloads/public_downloads")

    private const val COL_ID = "_id"
    private const val COL_DATA = "_data"

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
    fun resolveRealFile(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null

        when (uri.authority) {
            "com.android.externalstorage.documents" -> return documentIdToFile(docId)

            "com.android.providers.downloads.documents" -> {
                if (docId.startsWith("raw:")) return File(docId.removePrefix("raw:"))
                // 新版系统「下载」用 MediaStore 记录：msf:<id> 或纯数字 id
                val id = docId.removePrefix("msf:").toLongOrNull()
                if (id != null) {
                    dataColumnFile(context, ContentUris.withAppendedId(DOWNLOADS_PUBLIC_URI, id))
                        ?.let { return it }
                    mediaStoreFile(context, id)?.let { return it }
                }
            }

            "com.android.providers.media.documents" -> {
                // image:<id> / video:<id> / audio:<id> / msf:<id>
                docId.substringAfter(':').toLongOrNull()?.let { id ->
                    mediaStoreFile(context, id)?.let { return it }
                }
            }
        }

        // 兜底：少数厂商文件管理器直接暴露 _data 列
        return dataColumnFile(context, uri)
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
        // 「下载」根目录的 tree docId 就是 downloads（没有卷名与冒号）
        if (docId.equals("downloads", ignoreCase = true)) {
            return File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS)
        }
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

    /** 用 MediaStore 反查文件：卷为 external 时同时覆盖内置存储与 SD 卡 */
    private fun mediaStoreFile(context: Context, id: Long): File? {
        val volume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.VOLUME_EXTERNAL
        } else {
            "external"
        }
        return dataColumnFile(
            context,
            MediaStore.Files.getContentUri(volume),
            "$COL_ID = ?",
            arrayOf(id.toString())
        )
    }

    /** 查询 _data 列拿到真实路径（少数 provider 的文档 Uri 本身就是可查的） */
    private fun dataColumnFile(
        context: Context,
        uri: Uri,
        selection: String? = null,
        args: Array<String>? = null
    ): File? = runCatching {
        context.contentResolver.query(uri, arrayOf(COL_DATA), selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                File(cursor.getString(0)).takeIf { it.exists() }
            } else {
                null
            }
        }
    }.getOrNull()
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
