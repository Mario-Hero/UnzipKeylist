package com.example.unzipkeylist.archive

import java.io.File

enum class ArchiveType { ZIP, SEVEN_ZIP, RAR }

/**
 * 一个待解压的压缩包。
 * [parts] 按分卷顺序排列（非分卷时只有一个元素）；解压时按格式取用。
 */
data class ArchiveSource(
    val type: ArchiveType,
    val parts: List<File>,
    val pickedFile: File
) {
    val firstPart: File get() = parts.first()
    val isSplit: Boolean get() = parts.size > 1
    val displayName: String get() = pickedFile.name
}

/**
 * 根据文件名识别格式，并自动把同目录下的分卷兄弟文件按顺序凑齐：
 *  - `xxx.part1.rar` / `xxx.part2.rar` ...      → RAR 分卷
 *  - `xxx.rar` + `xxx.r00` `xxx.r01` ...        → RAR 老式分卷
 *  - `xxx.7z.001` / `.002` ...                  → 7z 分卷
 *  - `xxx.z01` `xxx.z02` ... + `xxx.zip`        → ZIP 分卷
 *  - `xxx.zip.001` / `.002` ...                 → ZIP 分卷（数字后缀写法）
 */
object ArchiveLocator {

    private val partRar = Regex("""(?i)^(.+)\.part(\d+)\.rar$""")
    private val sevenZipSplit = Regex("""(?i)^(.+)\.7z\.(\d+)$""")
    private val zipSplitZ = Regex("""(?i)^(.+)\.z(\d{2})$""")
    private val zipSplitNumeric = Regex("""(?i)^(.+)\.zip\.(\d+)$""")

    fun locate(file: File): ArchiveSource? {
        val dir = file.parentFile ?: return null
        val siblings = dir.listFiles() ?: emptyArray()
        val name = file.name

        // 1) xxx.partN.rar
        partRar.matchEntire(name)?.let { match ->
            val parts = orderedParts(siblings, partRar, match.groupValues[1])
            if (parts.isNotEmpty()) return ArchiveSource(ArchiveType.RAR, parts, file)
        }

        // 2) 普通 .rar（可能带 .r00/.r01 分卷）
        if (name.lowercase().endsWith(".rar")) {
            val base = name.dropLast(4)
            val volumes = siblings
                .filter { Regex("""(?i)^${Regex.escape(base)}\.r\d{2}$""").matches(it.name) }
                .sortedBy { it.name.lowercase() }
            return ArchiveSource(ArchiveType.RAR, listOf(file) + volumes, file)
        }

        // 3) xxx.7z.001
        sevenZipSplit.matchEntire(name)?.let { match ->
            val parts = orderedParts(siblings, sevenZipSplit, match.groupValues[1])
            if (parts.isNotEmpty()) return ArchiveSource(ArchiveType.SEVEN_ZIP, parts, file)
        }

        // 4) 普通 .7z
        if (name.lowercase().endsWith(".7z")) {
            return ArchiveSource(ArchiveType.SEVEN_ZIP, listOf(file), file)
        }

        // 5) xxx.z01 分卷 zip
        zipSplitZ.matchEntire(name)?.let { match ->
            val base = match.groupValues[1]
            val lastPart = siblings.firstOrNull { it.name.equals("$base.zip", true) }
            if (lastPart != null) {
                return ArchiveSource(ArchiveType.ZIP, orderedParts(siblings, zipSplitZ, base) + lastPart, file)
            }
        }

        // 6) 普通 .zip（可能带 .z01 分卷兄弟）
        if (name.lowercase().endsWith(".zip")) {
            val base = name.dropLast(4)
            val zParts = orderedParts(siblings, zipSplitZ, base)
            return if (zParts.isNotEmpty()) {
                ArchiveSource(ArchiveType.ZIP, zParts + file, file)
            } else {
                ArchiveSource(ArchiveType.ZIP, listOf(file), file)
            }
        }

        // 7) xxx.zip.001 分卷
        zipSplitNumeric.matchEntire(name)?.let { match ->
            val parts = orderedParts(siblings, zipSplitNumeric, match.groupValues[1])
            if (parts.isNotEmpty()) return ArchiveSource(ArchiveType.ZIP, parts, file)
        }

        return null
    }

    private fun orderedParts(siblings: Array<File>, regex: Regex, base: String): List<File> =
        siblings.mapNotNull { sibling ->
            val match = regex.matchEntire(sibling.name) ?: return@mapNotNull null
            if (!match.groupValues[1].equals(base, ignoreCase = true)) return@mapNotNull null
            match.groupValues[2].toIntOrNull()?.let { it to sibling }
        }.sortedBy { it.first }.map { it.second }
}
