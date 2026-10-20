package com.example.unzipkeylist.archive

import com.example.unzipkeylist.util.FileUtils
import com.github.junrar.Archive
import com.github.junrar.exception.RarException
import com.github.junrar.rarfile.FileHeader
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * RAR 引擎（junrar 7.6+）：支持 RAR4 / RAR5、`part1.rar` 与 `.r00` 两种分卷、
 * AES 加密、以及**加密文件头（EncryptFileNames）**。
 *
 * 关键：Archive 构造函数必须在构造阶段就拿到密码，否则加密文件头的包会在构造时直接失败，
 * 后续再调 setPassword() 已无济于事。
 */
class RarEngine : ArchiveEngine {

    override fun checkPassword(parts: List<File>, password: String): Boolean {
        val archive = try {
            open(parts, password)
        } catch (e: RarException) {
            if (isPasswordIssue(e)) return false
            throw e
        }
        return try {
            val smallest = archive.fileHeaders
                .filter { !it.isDirectory }
                .minByOrNull { it.fullUnpackSize }
                ?: return true
            archive.getInputStream(smallest).use { probeRead(it) }
            true
        } catch (e: Exception) {
            false
        } finally {
            runCatching { archive.close() }
        }
    }

    override fun extract(
        parts: List<File>,
        destDir: File,
        password: String?,
        listener: ProgressListener
    ) {
        val archive = try {
            open(parts, password.orEmpty())
        } catch (e: RarException) {
            if (isPasswordIssue(e)) throw WrongPasswordException(WrongPasswordReason.PASSWORD_REJECTED)
            throw e
        }
        try {
            val total = archive.fileHeaders
                .filter { !it.isDirectory }
                .sumOf { if (it.fullUnpackSize < 0) 0L else it.fullUnpackSize }
            val root = destDir.canonicalFile
            root.mkdirs()
            var done = 0L
            while (true) {
                val header: FileHeader = archive.nextFileHeader() ?: break
                val target = FileUtils.safeTargetFile(root, header.fileName)
                if (header.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                BufferedOutputStream(FileOutputStream(target)).use { output ->
                    archive.extractFile(header, output)
                }
                done += if (header.fullUnpackSize < 0) 0L else header.fullUnpackSize
                listener.onProgress(done, total, header.fileName)
            }
            listener.onProgress(done, done, "")
        } catch (e: RarException) {
            if (isPasswordIssue(e)) throw WrongPasswordException(WrongPasswordReason.PASSWORD_REJECTED)
            throw e
        } finally {
            runCatching { archive.close() }
        }
    }

    /**
     * 构造 Archive 时直接传入密码。
     * 对加密文件头的包，password 必须在构造阶段就可用，否则解析头部直接失败。
     */
    private fun open(parts: List<File>, password: String): Archive {
        return Archive(parts.first(), password)
    }

    /** 区分"密码相关"与"包本身损坏/其他异常" */
    private fun isPasswordIssue(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            cause.message?.let { msg ->
                if (msg.contains("password", ignoreCase = true)) return true
                if (msg.contains("encrypt", ignoreCase = true)) return true
                if (msg.contains("crc", ignoreCase = true) &&
                    cause.cause != null && isPasswordIssue(cause.cause!!)) return true
            }
            cause = cause.cause
        }
        return false
    }
}
