package com.ericcatch

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ericcatch.databinding.ActivityHistoryBinding
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.historyToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.recyclerHistory.layoutManager = LinearLayoutManager(this)
        loadFiles()
    }

    override fun onResume() {
        super.onResume()
        loadFiles()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun loadFiles() {
        val files = getDownloadedFiles()
        if (files.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.recyclerHistory.visibility = View.GONE
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.recyclerHistory.visibility = View.VISIBLE
            binding.recyclerHistory.adapter = DownloadAdapter(files) { loadFiles() }
        }
    }

    private fun getDownloadedFiles(): List<File> {
        val dirs = mutableListOf<File>()

        // Carpeta pública Downloads/EricCatch
        val pub = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        File(pub, "EricCatch").takeIf { it.exists() }?.let { dirs.add(it) }

        // Carpeta privada como fallback
        getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, "EricCatch") }
            ?.takeIf { it.exists() }
            ?.let { dirs.add(it) }

        return dirs.flatMap { dir ->
            dir.listFiles()
                ?.filter { it.isFile && (it.extension.equals("mp4", true) ||
                        it.extension.equals("mp3", true) ||
                        it.extension.equals("m4a", true) ||
                        it.extension.equals("webm", true)) }
                ?: emptyList()
        }.sortedByDescending { it.lastModified() }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class DownloadAdapter(
        private val files: List<File>,
        private val onDeleted: () -> Unit
    ) : RecyclerView.Adapter<DownloadAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val thumb:   ImageView     = view.findViewById(R.id.ivItemThumb)
            val type:    TextView      = view.findViewById(R.id.tvItemType)
            val title:   TextView      = view.findViewById(R.id.tvItemTitle)
            val size:    TextView      = view.findViewById(R.id.tvItemSize)
            val date:    TextView      = view.findViewById(R.id.tvItemDate)
            val btnPlay: MaterialButton = view.findViewById(R.id.btnItemPlay)
            val btnShare:MaterialButton = view.findViewById(R.id.btnItemShare)
            val btnDel:  MaterialButton = view.findViewById(R.id.btnItemDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_download, parent, false)
        )

        override fun getItemCount() = files.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val file = files[pos]
            val isAudio = file.extension.equals("mp3", true) ||
                          file.extension.equals("m4a", true)

            // Título = nombre sin extensión
            h.title.text = file.nameWithoutExtension

            // Tipo badge
            h.type.text = file.extension.uppercase()

            // Tamaño
            h.size.text = formatSize(file.length())

            // Fecha
            h.date.text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                .format(Date(file.lastModified()))

            // Miniatura: para video intentamos cargar el frame; para audio ícono
            if (isAudio) {
                Glide.with(h.thumb).load(R.drawable.ic_logo).centerCrop().into(h.thumb)
            } else {
                Glide.with(h.thumb).load(file).centerCrop()
                    .placeholder(R.drawable.bg_thumb_placeholder)
                    .error(R.drawable.ic_logo)
                    .into(h.thumb)
            }

            // Animación de entrada escalonada
            h.itemView.alpha = 0f
            h.itemView.translationY = 20f
            h.itemView.animate()
                .alpha(1f).translationY(0f)
                .setDuration(300)
                .setStartDelay((pos * 50).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()

            // Acciones
            h.btnPlay.setOnClickListener  { playFile(file, isAudio) }
            h.btnShare.setOnClickListener { shareFile(file, isAudio) }
            h.btnDel.setOnClickListener   { confirmDelete(file) }
        }

        private fun formatSize(bytes: Long): String {
            val df = DecimalFormat("#.#")
            return when {
                bytes >= 1_073_741_824L -> "${df.format(bytes / 1_073_741_824.0)} GB"
                bytes >= 1_048_576L     -> "${df.format(bytes / 1_048_576.0)} MB"
                bytes >= 1_024L         -> "${df.format(bytes / 1_024.0)} KB"
                else                    -> "$bytes B"
            }
        }

        private fun playFile(file: File, isAudio: Boolean) {
            try {
                val uri = FileProvider.getUriForFile(
                    this@HistoryActivity, "${packageName}.fileprovider", file)
                val mime = if (isAudio) "audio/*" else "video/*"
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, getString(R.string.action_play)
                ))
            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity,
                    "No se encontró reproductor", Toast.LENGTH_SHORT).show()
            }
        }

        private fun shareFile(file: File, isAudio: Boolean) {
            try {
                val uri = FileProvider.getUriForFile(
                    this@HistoryActivity, "${packageName}.fileprovider", file)
                val mime = if (isAudio) "audio/*" else "video/*"
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, getString(R.string.action_share)
                ))
            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity,
                    "Error al compartir", Toast.LENGTH_SHORT).show()
            }
        }

        private fun confirmDelete(file: File) {
            AlertDialog.Builder(this@HistoryActivity)
                .setTitle(getString(R.string.confirm_delete))
                .setMessage(file.name)
                .setPositiveButton(getString(R.string.action_delete)) { _, _ ->
                    file.delete()
                    Toast.makeText(this@HistoryActivity,
                        getString(R.string.deleted), Toast.LENGTH_SHORT).show()
                    onDeleted()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }
}
