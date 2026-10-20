package com.example.unzipkeylist.password

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 密码本：纯文本，一行一个密码。
 * 读取时自动忽略空行与首尾空白，并保持顺序去重。
 */
class PasswordBook(val file: File) {

    fun load(): List<String> {
        if (!file.exists()) return emptyList()
        val result = LinkedHashSet<String>()
        file.forEachLine { line ->
            val password = line.trim()
            if (password.isNotEmpty()) result.add(password)
        }
        return result.toList()
    }

    fun save(passwords: List<String>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            passwords.forEach { writer.appendLine(it) }
        }
    }

    /** 追加一个密码，已存在则不重复写入；返回是否真的新增 */
    fun add(password: String): Boolean {
        val password = password.trim()
        if (password.isEmpty()) return false
        val current = load().toMutableList()
        if (current.contains(password)) return false
        current.add(password)
        save(current)
        return true
    }

    fun importFrom(input: InputStream): List<String> {
        val result = LinkedHashSet<String>()
        input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            val password = line.trim()
            if (password.isNotEmpty()) result.add(password)
        }
        return result.toList()
    }

    fun exportTo(output: OutputStream, passwords: List<String>) {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            passwords.forEach { writer.appendLine(it) }
        }
    }

    fun count(): Int = load().size
}
