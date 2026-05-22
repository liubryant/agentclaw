package ai.cjym.agentclaw.ui.shell.chat

import ai.cjym.agentclaw.R
import ai.cjym.agentclaw.databinding.DialogExportedFilesBinding
import ai.cjym.agentclaw.databinding.ItemExportedFileBinding
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportedFilesDialogFragment : DialogFragment() {

    private var _binding: DialogExportedFilesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).also { dialog ->
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogExportedFilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.closeButton.setOnClickListener { dismiss() }

        val files = queryExportedFiles(requireContext())

        if (files.isEmpty()) {
            binding.fileList.visibility = View.GONE
            binding.emptyState.visibility = View.VISIBLE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.fileList.visibility = View.VISIBLE
            binding.fileList.layoutManager = LinearLayoutManager(requireContext())
            binding.fileList.adapter = ExportedFilesAdapter(files,
                onPreview = { item -> openFile(item) },
                onShare = { item -> shareFile(item) }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                (resources.displayMetrics.widthPixels * 0.92).toInt(),
                1200
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun openFile(item: ExportedFileItem) {
        val mime = item.mimeType ?: getMimeType(item.name) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.contentUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "没有可以打开此文件的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(item: ExportedFileItem) {
        val mime = item.mimeType ?: getMimeType(item.name) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, item.contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "分享 ${item.name}"))
    }

    // ───────────────────────── query ─────────────────────────

    private fun queryExportedFiles(context: Context): List<ExportedFileItem> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryViaMediaStore(context)
        } else {
            queryViaFileSystem()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun queryViaMediaStore(context: Context): List<ExportedFileItem> {
        val result = mutableListOf<ExportedFileItem>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
            MediaStore.Downloads.MIME_TYPE,
            MediaStore.Downloads.RELATIVE_PATH
        )
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("Download/AgentClaw/%")
        val sortOrder = "${MediaStore.Downloads.DATE_MODIFIED} DESC"

        val cursor: Cursor? = context.contentResolver.query(
            collection, projection, selection, selectionArgs, sortOrder
        )
        cursor?.use {
            val idCol = it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = it.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val sizeCol = it.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
            val dateCol = it.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
            val mimeCol = it.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
            while (it.moveToNext()) {
                val id = it.getLong(idCol)
                val uri = Uri.withAppendedPath(collection, id.toString())
                result.add(
                    ExportedFileItem(
                        id = id,
                        name = it.getString(nameCol) ?: "未知文件",
                        size = it.getLong(sizeCol),
                        dateModified = it.getLong(dateCol) * 1000L,
                        mimeType = it.getString(mimeCol),
                        contentUri = uri
                    )
                )
            }
        }
        return result
    }

    @Suppress("DEPRECATION")
    private fun queryViaFileSystem(): List<ExportedFileItem> {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AgentClaw"
        )
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.provider",
                    file
                )
                ExportedFileItem(
                    id = file.hashCode().toLong(),
                    name = file.name,
                    size = file.length(),
                    dateModified = file.lastModified(),
                    mimeType = getMimeType(file.name),
                    contentUri = uri
                )
            } ?: emptyList()
    }

    private fun getMimeType(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }

    companion object {
        const val TAG = "ExportedFilesDialog"
        fun newInstance() = ExportedFilesDialogFragment()
    }
}

// ─────────────────────────── data model ───────────────────────────

data class ExportedFileItem(
    val id: Long,
    val name: String,
    val size: Long,
    val dateModified: Long,
    val mimeType: String?,
    val contentUri: Uri
)

// ─────────────────────────── adapter ───────────────────────────

class ExportedFilesAdapter(
    private val items: List<ExportedFileItem>,
    private val onPreview: (ExportedFileItem) -> Unit,
    private val onShare: (ExportedFileItem) -> Unit
) : RecyclerView.Adapter<ExportedFilesAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemExportedFileBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemExportedFileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        with(holder.binding) {
            val ext = item.name.substringAfterLast('.', "").uppercase(Locale.getDefault())
            fileTypeLabel.text = ext.take(4).ifEmpty { "FILE" }
            fileName.text = item.name
            fileMeta.text = "${formatSize(item.size)}  ·  ${formatDate(item.dateModified)}"
            previewButton.setOnClickListener { onPreview(item) }
            shareButton.setOnClickListener { onShare(item) }
            root.setOnClickListener { onPreview(item) }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val df = DecimalFormat("#,##0.#")
        return "${df.format(bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
    }

    private fun formatDate(timeMs: Long): String {
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timeMs))
    }
}
