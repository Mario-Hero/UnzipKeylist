package com.example.unzipkeylist

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.unzipkeylist.archive.ArchiveLocator
import com.example.unzipkeylist.archive.ArchiveSource
import com.example.unzipkeylist.archive.ExtractionCallback
import com.example.unzipkeylist.archive.ExtractionRunner
import com.example.unzipkeylist.databinding.ActivityMainBinding
import com.example.unzipkeylist.password.PasswordBook
import com.example.unzipkeylist.util.StorageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var book: PasswordBook
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val sources = mutableListOf<ArchiveSource>()
    private var customDestDir: File? = null

    private val pickArchives =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            resetProgressAndEnableStart()
            addArchives(uris)
        }

    private val pickArchiveFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val dir = StorageUtils.resolveRealDirectory(uri)
            if (dir == null) {
                toast(getString(R.string.toast_resolve_dir_failed))
                return@registerForActivityResult
            }
            resetProgressAndEnableStart()
            val includeSubfolders = binding.cbIncludeSubfolders.isChecked
            scope.launch {
                val found = withContext(Dispatchers.IO) {
                    runCatching { collectArchives(dir, includeSubfolders) }.getOrDefault(emptyList())
                }
                if (appendSources(found) == 0) {
                    toast(getString(R.string.toast_no_archives_in_folder))
                }
                updateSelectedFilesText()
            }
        }

    private val pickDestDir =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            val dir = StorageUtils.resolveRealDirectory(uri)
            if (dir == null) {
                toast(getString(R.string.toast_resolve_dir_failed))
                binding.rbCurrentDir.isChecked = true
                binding.btnPickDir.isEnabled = false
            } else {
                customDestDir = dir
                binding.tvDestDir.text = dir.absolutePath
            }
        }

    private val importBook = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            val imported = contentResolver.openInputStream(uri)?.use { book.importFrom(it) } ?: emptyList()
            val merged = LinkedHashSet(book.load()).apply { addAll(imported) }.toList()
            book.save(merged)
            refreshBookStatus()
            toast(getString(R.string.toast_password_imported, merged.size))
        } catch (e: IOException) {
            toast(getString(R.string.toast_import_failed, e.message.orEmpty()))
        }
    }

    private val exportBook =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { book.exportTo(it, book.load()) }
                toast(getString(R.string.toast_exported))
            } catch (e: IOException) {
                toast(getString(R.string.toast_export_failed, e.message.orEmpty()))
            }
        }

    private val requestAllFilesAccess =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshPermissionState() }

    private val requestLegacyStorage =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshPermissionState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35+ 强制 edge-to-edge：状态栏区域由 AppBarLayout 的紫色背景填充
        // （window.statusBarColor 自 API 35 起已废弃且被系统忽略，不再使用）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        book = PasswordBook(File(filesDir, "password_book.txt"))

        binding.btnGrantPermission.setOnClickListener { requestStorageAccess() }
        binding.btnPickArchive.setOnClickListener { pickArchives.launch(arrayOf("*/*")) }
        binding.btnPickFolder.setOnClickListener {
            if (ensureStorageAccess()) pickArchiveFolder.launch(null)
        }
        binding.btnEditBook.setOnClickListener {
            startActivity(Intent(this, PasswordBookActivity::class.java))
        }
        binding.btnImportBook.setOnClickListener { importBook.launch(arrayOf("text/plain", "*/*")) }
        binding.btnExportBook.setOnClickListener { exportBook.launch("password_book.txt") }

        binding.rgOutput.setOnCheckedChangeListener { _, checkedId ->
            val custom = checkedId == R.id.rbCustomDir
            binding.btnPickDir.visibility = if (custom) View.VISIBLE else View.GONE
            binding.tvDestDir.visibility = if (custom) View.VISIBLE else View.GONE
            if (!custom) {
                customDestDir = null
                binding.tvDestDir.text = getString(R.string.dest_dir_not_selected)
            }
        }
        binding.btnPickDir.setOnClickListener { pickDestDir.launch(null) }
        binding.btnStart.setOnClickListener { startExtraction() }

        updateSelectedFilesText()
        refreshPermissionState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        refreshBookStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ---------- 权限 ----------

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { requestAllFilesAccess.launch(StorageUtils.allFilesAccessIntent(this)) }
                .onFailure {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
        } else {
            requestLegacyStorage.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    private fun refreshPermissionState() {
        binding.permissionBanner.visibility =
            if (StorageUtils.hasStorageAccess(this)) View.GONE else View.VISIBLE
    }

    private fun ensureStorageAccess(): Boolean {
        if (StorageUtils.hasStorageAccess(this)) return true
        toast(getString(R.string.toast_need_storage_permission))
        requestStorageAccess()
        return false
    }

    // ---------- 选文件 ----------

    private fun addArchives(uris: List<Uri>) {
        val found = mutableListOf<ArchiveSource>()
        val failed = mutableListOf<String>()

        for (uri in uris) {
            val file = StorageUtils.resolveRealFile(uri) ?: copyToCache(uri)
            if (file == null) {
                failed.add(StorageUtils.displayName(this, uri) ?: uri.toString())
                continue
            }
            val source = ArchiveLocator.locate(file)
            if (source == null) {
                failed.add(getString(R.string.skip_unsupported_format, file.name))
                continue
            }
            found.add(source)
        }

        appendSources(found)
        updateSelectedFilesText()

        if (failed.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.dialog_skipped_title)
                .setMessage(failed.joinToString("\n"))
                .setPositiveButton(R.string.action_ok, null)
                .show()
        }
    }

    /** 扫描文件夹里的压缩包（可选递归子文件夹） */
    private fun collectArchives(dir: File, includeSubfolders: Boolean): List<ArchiveSource> {
        val files = if (includeSubfolders) {
            dir.walkTopDown().filter { it.isFile }
        } else {
            dir.listFiles()?.asSequence()?.filter { it.isFile } ?: emptySequence<File>()
        }
        return files.mapNotNull { ArchiveLocator.locate(it) }.toList()
    }

    /** 把压缩包追加到待解压列表，按首个分卷去重；返回真正新增的个数 */
    private fun appendSources(newSources: List<ArchiveSource>): Int {
        val seen = sources.mapTo(HashSet()) { it.firstPart.absolutePath }
        var added = 0
        for (source in newSources) {
            if (seen.add(source.firstPart.absolutePath)) {
                sources.add(source)
                added++
            }
        }
        return added
    }

    /** SAF 拿不到真实路径时，复制一份到应用缓存目录再解压 */
    private fun copyToCache(uri: Uri): File? {
        val name = StorageUtils.displayName(this, uri) ?: "archive_${System.currentTimeMillis()}"
        val target = File(File(cacheDir, "saf-picked").apply { mkdirs() }, name)
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                FileOutputStream(target).use { output -> source.copyTo(output) }
            }
            toast(getString(R.string.toast_copied_to_cache, name))
            target
        } catch (e: IOException) {
            null
        }
    }

    private fun updateSelectedFilesText() {
        binding.tvSelectedFiles.text = when {
            sources.isEmpty() -> getString(R.string.state_no_files_selected)
            sources.size == 1 -> getString(R.string.state_selected_one, sources.first().displayName)
            else -> getString(
                R.string.state_selected_many,
                sources.size,
                sources.take(3).joinToString(", ") { it.displayName } +
                    if (sources.size > 3) "…" else ""
            )
        }
    }

    private fun refreshBookStatus() {
        binding.tvBookStatus.text = getString(
            R.string.password_book_summary, book.count(), book.file.absolutePath
        )
    }

    /** 选了新文件/文件夹时调用：把进度 UI 重置到初始态，允许再次点解压 */
    private fun resetProgressAndEnableStart() {
        binding.btnStart.isEnabled = true
        binding.btnStart.text = getString(R.string.action_start)
        binding.progressBar.isIndeterminate = false
        binding.progressBar.progress = 0
        binding.tvProgress.text = getString(R.string.progress_idle)
    }

    // ---------- 解压 ----------

    private fun startExtraction() {
        if (sources.isEmpty()) {
            toast(getString(R.string.toast_select_first))
            return
        }
        if (!ensureStorageAccess()) return

        val useCurrentDir = binding.rbCurrentDir.isChecked
        val destDir = customDestDir
        if (!useCurrentDir && destDir == null) {
            toast(getString(R.string.toast_select_output_dir))
            return
        }

        val manualPassword = binding.etManualPassword.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val bookPasswords = book.load()
        val deleteOriginals = binding.rbDeleteYes.isChecked
        val jobSources = sources.toList()

        binding.btnStart.isEnabled = false
        binding.progressBar.isIndeterminate = true
        binding.tvProgress.text = getString(R.string.status_preparing)

        scope.launch {
            val runner = ExtractionRunner(this@MainActivity, object : ExtractionCallback {
                override fun onStatus(text: String) = runOnUiThread {
                    binding.tvProgress.text = text
                }

                override fun onProgress(percent: Int, entryName: String) = runOnUiThread {
                    if (percent < 0) {
                        binding.progressBar.isIndeterminate = true
                        if (entryName.isNotEmpty()) {
                            binding.tvProgress.text =
                                getString(R.string.status_extracting_entry, entryName)
                        }
                    } else {
                        binding.progressBar.isIndeterminate = false
                        binding.progressBar.progress = percent
                        if (entryName.isNotEmpty()) {
                            binding.tvProgress.text =
                                getString(R.string.status_progress_entry, percent, entryName)
                        }
                    }
                }
            })

            val result = runner.run(
                sources = jobSources,
                destinationOf = { source ->
                    if (useCurrentDir) source.pickedFile.parentFile
                        ?: Environment.getExternalStorageDirectory()
                    else destDir!!
                },
                manualPassword = manualPassword,
                bookPasswords = bookPasswords,
                deleteOriginals = deleteOriginals
            )

            val allSuccess = result.failures.isEmpty()
            binding.btnStart.isEnabled = !allSuccess
            binding.progressBar.isIndeterminate = false
            binding.progressBar.progress = if (allSuccess) 100 else binding.progressBar.progress
            binding.tvProgress.text = if (allSuccess) {
                binding.btnStart.text = getString(R.string.action_done)
                getString(R.string.status_done_success, result.successArchives)
            } else {
                binding.btnStart.text = getString(R.string.action_start)
                getString(
                    R.string.status_done_partial, result.successArchives, result.failures.size
                )
            }

            if (result.failures.isNotEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.dialog_failed_title)
                    .setMessage(result.failures.joinToString("\n\n"))
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
            }

            result.manualPasswordUsed?.let { askToSavePassword(it, bookPasswords) }

            if (deleteOriginals) {
                sources.clear()
                updateSelectedFilesText()
            }
            refreshBookStatus()
        }
    }

    private fun askToSavePassword(password: String, existing: List<String>) {
        if (existing.contains(password)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_save_password_title)
            .setMessage(getString(R.string.dialog_save_password_message, password))
            .setPositiveButton(R.string.action_add) { _, _ ->
                book.add(password)
                refreshBookStatus()
                toast(getString(R.string.toast_password_added))
            }
            .setNegativeButton(R.string.action_no_thanks, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
