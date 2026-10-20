package com.example.unzipkeylist.archive

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.unzipkeylist.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface ExtractionCallback {
    fun onStatus(text: String)

    /** percent < 0 表示总量未知（UI 显示不确定进度） */
    fun onProgress(percent: Int, entryName: String)
}

data class JobResult(
    val successArchives: Int,
    val failures: List<String>,
    /** 用"手动输入"的密码解压成功的那个密码（用于事后询问是否写入密码本） */
    val manualPasswordUsed: String?,
    /** 勾选「删除原文件」但没能删掉的原压缩包（例如来源不支持删除） */
    val undeletedOriginals: List<String>
)

/**
 * 解压流程编排：
 * 1. 识别格式 → 2. 判断是否加密 → 3. 有手动密码先试手动，再逐条遍历密码本
 * → 4. 用命中的密码解压 → 5. 按需删除原压缩包（真实文件连分卷一起删；
 *      缓存副本来源则删除原文档，删不掉时记录到 [JobResult.undeletedOriginals]）
 */
class ExtractionRunner(
    private val context: Context,
    private val callback: ExtractionCallback
) {

    suspend fun run(
        sources: List<ArchiveSource>,
        destinationOf: (ArchiveSource) -> File,
        manualPassword: String?,
        bookPasswords: List<String>,
        deleteOriginals: Boolean
    ): JobResult = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        val undeletedOriginals = mutableListOf<String>()
        var successes = 0
        var manualPasswordUsed: String? = null

        sources.forEachIndexed { index, source ->
            val engine = engineFor(source.type)
            val label = "(${index + 1}/${sources.size})"
            try {
                callback.onStatus(
                    context.getString(R.string.status_checking, label, source.displayName)
                )
                var password: String? = null

                if (engine.isEncrypted(source.parts)) {
                    val candidates = LinkedHashSet<String>()
                    manualPassword?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
                    candidates.addAll(bookPasswords)
                    val list = candidates.toList()

                    for ((attempt, candidate) in list.withIndex()) {
                        callback.onStatus(
                            context.getString(
                                R.string.status_trying_password,
                                label,
                                attempt + 1,
                                list.size
                            )
                        )
                        if (engine.checkPassword(source.parts, candidate)) {
                            password = candidate
                            break
                        }
                    }
                    if (password == null) {
                        throw ExtractionException(
                            if (list.isEmpty()) context.getString(R.string.error_no_password_available)
                            else context.getString(R.string.error_all_passwords_failed, list.size)
                        )
                    }
                    if (!manualPassword.isNullOrBlank() && password == manualPassword.trim()) {
                        manualPasswordUsed = password
                    }
                }

                val destDir = destinationOf(source)
                callback.onStatus(
                    context.getString(R.string.status_extracting_archive, label, source.displayName)
                )
                engine.extract(source.parts, destDir, password) { done, total, entry ->
                    val percent = if (total > 0) {
                        val inner = (done.toDouble() / total).coerceIn(0.0, 1.0)
                        (((index + inner) / sources.size) * 100).toInt().coerceIn(0, 99)
                    } else {
                        -1
                    }
                    callback.onProgress(percent, entry)
                }

                if (deleteOriginals) {
                    if (source.sourceUri != null) {
                        // 缓存副本来源：删的是原文档，副本由调用方统一清理
                        if (!deleteSafDocument(source.sourceUri)) {
                            undeletedOriginals.add(source.displayName)
                        }
                    } else {
                        source.parts.forEach { runCatching { it.delete() } }
                    }
                }
                successes++
            } catch (t: Throwable) {
                val detail = when (t) {
                    is ExtractionException -> t.message ?: t.javaClass.simpleName
                    is WrongPasswordException -> passwordErrorText(t.reason)
                    else -> t.message ?: t.javaClass.simpleName
                }
                failures.add(context.getString(R.string.failure_entry, source.displayName, detail))
            } finally {
                engine.cleanup()
            }
        }

        callback.onProgress(100, "")
        JobResult(successes, failures, manualPasswordUsed, undeletedOriginals)
    }

    /** 通过 SAF 删除原文档；来源不支持删除或没有写权限时返回 false */
    private fun deleteSafDocument(uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            .getOrDefault(false)

    private fun passwordErrorText(reason: WrongPasswordReason): String = context.getString(
        when (reason) {
            WrongPasswordReason.NEEDS_PASSWORD -> R.string.error_encrypted_needs_password
            WrongPasswordReason.PASSWORD_REJECTED -> R.string.error_password_rejected
        }
    )

    private fun engineFor(type: ArchiveType): ArchiveEngine = when (type) {
        ArchiveType.ZIP -> ZipEngine(
            File(context.cacheDir, "split-merge"),
            context.getString(R.string.status_merging_split)
        )

        ArchiveType.SEVEN_ZIP -> SevenZEngine()
        ArchiveType.RAR -> RarEngine()
    }
}
