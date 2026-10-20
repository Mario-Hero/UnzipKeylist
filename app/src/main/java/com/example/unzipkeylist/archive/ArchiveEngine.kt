package com.example.unzipkeylist.archive

import java.io.File

/** 进度回调：done/total 为字节数；total <= 0 表示总量未知，UI 应显示不确定进度 */
fun interface ProgressListener {
    fun onProgress(done: Long, total: Long, currentEntry: String)
}

enum class WrongPasswordReason { NEEDS_PASSWORD, PASSWORD_REJECTED }

/** 密码相关失败；文案由上层（ExtractionRunner）按语言资源生成 */
class WrongPasswordException(val reason: WrongPasswordReason) : Exception(reason.name)

/** 流程层面的失败，message 已经是本地化后的文案 */
class ExtractionException(message: String) : Exception(message)

interface ArchiveEngine {

    /** 是否需要密码：用空密码试读一小段数据即可判定 */
    fun isEncrypted(parts: List<File>): Boolean = !checkPassword(parts, "")

    /** 仅校验密码是否正确，不写盘（空字符串表示"不提供密码"） */
    fun checkPassword(parts: List<File>, password: String): Boolean

    /** 解压到 destDir；password 为空表示不加密 */
    fun extract(parts: List<File>, destDir: File, password: String?, listener: ProgressListener)

    /** 清理解压过程产生的临时文件 */
    fun cleanup() {}
}

internal const val BUFFER_SIZE = 64 * 1024
internal const val PROBE_SIZE = 64 * 1024

/** 读取一小段数据以触发解密校验（加密包的密码校验都在流的开头） */
internal fun probeRead(input: java.io.InputStream) {
    input.read(ByteArray(PROBE_SIZE))
}
