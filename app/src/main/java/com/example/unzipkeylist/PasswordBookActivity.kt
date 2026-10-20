package com.example.unzipkeylist

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.unzipkeylist.databinding.ActivityPasswordBookBinding
import com.example.unzipkeylist.password.PasswordBook
import java.io.File
import java.io.IOException

class PasswordBookActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPasswordBookBinding
    private lateinit var book: PasswordBook

    private val importBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val imported = contentResolver.openInputStream(uri)?.use { book.importFrom(it) } ?: emptyList()
            val merged = LinkedHashSet(editorPasswords()).apply { addAll(imported) }.toList()
            setEditorContent(merged)
            toast(getString(R.string.toast_imported_remember_save, imported.size))
        } catch (e: IOException) {
            toast(getString(R.string.toast_import_failed, e.message.orEmpty()))
        }
    }

    private val exportBook =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { book.exportTo(it, editorPasswords()) }
                toast(getString(R.string.toast_exported))
            } catch (e: IOException) {
                toast(getString(R.string.toast_export_failed, e.message.orEmpty()))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 状态栏区域由 AppBarLayout 的紫色背景填充，不使用已废弃的 window.statusBarColor
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityPasswordBookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        book = PasswordBook(File(filesDir, "password_book.txt"))

        binding.btnSave.setOnClickListener { save() }
        binding.btnImport.setOnClickListener { importBook.launch(arrayOf("text/plain", "*/*")) }
        binding.btnExport.setOnClickListener { exportBook.launch("password_book.txt") }

        setEditorContent(book.load())
    }

    private fun save() {
        val passwords = editorPasswords()
        book.save(passwords)
        setEditorContent(passwords)
        toast(getString(R.string.toast_saved, passwords.size))
        finish()
    }

    private fun editorPasswords(): List<String> =
        LinkedHashSet(
            binding.etBookContent.text?.toString().orEmpty()
                .split('\n', '\r')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        ).toList()

    private fun setEditorContent(passwords: List<String>) {
        binding.etBookContent.setText(passwords.joinToString("\n"))
        binding.tvBookCount.text = getString(R.string.password_count, passwords.size)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
