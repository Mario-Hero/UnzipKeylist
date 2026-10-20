package com.example.unzipkeylist.archive

import com.example.unzipkeylist.util.FileUtils
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.model.FileHeader
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * ZIP 引擎（zip4j）：支持普通 zip、ZIP64、AES / 传统 PKWARE 加密。
 * zip4j 不能直接读分卷包，因此分卷先按顺序合并成临时文件再解压。
 */
class ZipEngine(private val workDir: File, private val mergeLabel: String) : ArchiveEngine {

    private var mergedFile: File? = null

    override fun checkPassword(parts: List<File>, password: String): Boolean {
        val zip = open(parts, password, null)
        return try {
            val smallest = zip.fileHeaders
                .filter { !it.isDirectory }
                .minByOrNull { it.uncompressedSize }
                ?: return true
            zip.getInputStream(smallest).use { probeRead(it) }
            true
        } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD) false else throw e
        } catch (e: IOException) {
            false
        } finally {
            runCatching { zip.close() }
        }
    }

    override fun extract(
        parts: List<File>,
        destDir: File,
        password: String?,
        listener: ProgressListener
    ) {
        val zip = open(parts, password, listener)
        try {
            val root = destDir.canonicalFile
            root.mkdirs()
            val headers = zip.fileHeaders
            val total = headers.sumOf { if (it.isDirectory) 0L else it.uncompressedSize }
            var done = 0L
            for (header in headers) {
                val target = FileUtils.safeTargetFile(root, header.fileName)
                if (header.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(header).use { input ->
                    BufferedOutputStream(FileOutputStream(target)).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            listener.onProgress(done, total, header.fileName)
                        }
                    }
                }
            }
            listener.onProgress(done, done, "")
        } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD) {
                throw WrongPasswordException(WrongPasswordReason.PASSWORD_REJECTED)
            }
            throw e
        } finally {
            runCatching { zip.close() }
        }
    }

    override fun cleanup() {
        mergedFile?.delete()
        mergedFile = null
    }

    private fun open(parts: List<File>, password: String?, listener: ProgressListener?): ZipFile {
        val zip = ZipFile(archiveFile(parts, listener))
        if (!password.isNullOrEmpty()) zip.setPassword(password.toCharArray())
        return zip
    }

    /** 分卷时合并为一个临时 zip（结果会缓存，避免重复合并） */
    private fun archiveFile(parts: List<File>, listener: ProgressListener?): File {
        if (parts.size == 1) return parts.first()
        mergedFile?.let { return it }

        workDir.mkdirs()
        val merged = File(workDir, parts.first().name + ".merged.zip")
        val total = parts.sumOf { it.length() }
        var done = 0L
        FileOutputStream(merged).use { output ->
            val buffer = ByteArray(BUFFER_SIZE)
            for (part in parts) {
                part.inputStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        listener?.onProgress(done, total, "$mergeLabel ${part.name}")
                    }
                }
            }
        }
        mergedFile = merged
        return merged
    }
}
