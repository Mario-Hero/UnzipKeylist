package com.example.unzipkeylist.archive

import android.content.Context
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
    val manualPasswordUsed: String?
)

/**
 * 解压流程编排：
 * 1. 识别格式 → 2. 判断是否加密 → 3. 有手动密码先试手动，再逐条遍历密码本
 * → 4. 用命中的密码解压 → 5. 按需删除原压缩包（含全部分卷）
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
                    source.parts.forEach { runCatching { it.delete() } }
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
        JobResult(successes, failures, manualPasswordUsed)
    }

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
