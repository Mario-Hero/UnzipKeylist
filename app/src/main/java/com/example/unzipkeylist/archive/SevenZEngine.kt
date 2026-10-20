package com.example.unzipkeylist.archive

import com.example.unzipkeylist.util.FileUtils
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.SeekableByteChannel

/**
 * 7z 引擎（Apache Commons Compress）：支持 LZMA/LZMA2/BZip2 等编码、AES-256 加密、
 * 以及 `xxx.7z.001` 形式的多卷包（通过拼接只读通道实现）。
 */
class SevenZEngine : ArchiveEngine {

    override fun checkPassword(parts: List<File>, password: String): Boolean {
        return try {
            open(parts, password).use { file ->
                while (true) {
                    val entry = file.nextEntry ?: break
                    if (entry.isDirectory) continue
                    file.read(ByteArray(PROBE_SIZE), 0, PROBE_SIZE)
                    break
                }
            }
            true
        } catch (e: IOException) {
            false
        }
    }

    override fun extract(
        parts: List<File>,
        destDir: File,
        password: String?,
        listener: ProgressListener
    ) {
        val total = totalSize(parts, password)
        open(parts, password).use { file ->
            val root = destDir.canonicalFile
            root.mkdirs()
            var done = 0L
            while (true) {
                val entry = file.nextEntry ?: break
                val target = FileUtils.safeTargetFile(root, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile?.mkdirs()
                BufferedOutputStream(FileOutputStream(target)).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = file.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        listener.onProgress(done, total, entry.name)
                    }
                }
            }
            listener.onProgress(done, done, "")
        }
    }

    private fun open(parts: List<File>, password: String?): SevenZFile {
        val channel: SeekableByteChannel =
            MultiReadOnlySeekableByteChannel.forFiles(*parts.toTypedArray())
        val builder = SevenZFile.builder().setSeekableByteChannel(channel)
        if (!password.isNullOrEmpty()) builder.setPassword(password)
        return builder.get()
    }

    /** 预扫一遍求总解压字节数；solid 包中可能取不到单条大小，此时返回 0（UI 显示不确定进度） */
    private fun totalSize(parts: List<File>, password: String?): Long {
        return try {
            open(parts, password).use { file ->
                var total = 0L
                while (true) {
                    val entry: SevenZArchiveEntry = file.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (entry.size < 0) return 0L
                    total += entry.size
                }
                total
            }
        } catch (e: IOException) {
            0L
        }
    }
}
