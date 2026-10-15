package com.snaptubepro

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.snaptubepro.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // -------------------------------------------------------------------------
    // Permission launcher
    // -------------------------------------------------------------------------

    private val storagePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Se necesita permiso de almacenamiento para descargar.", Toast.LENGTH_LONG).show()
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        requestStoragePermissionsIfNeeded()

        // Ensure yt-dlp binary is present (downloads on first run)
        lifecycleScope.launch {
            YtDlpInstaller.ensureInstalled(this@MainActivity) { status ->
                runOnUiThread { binding.tvStatus.text = status }
            }
        }

        setupClickListeners()
        observeViewModel()
    }

    // -------------------------------------------------------------------------
    // UI Setup
    // -------------------------------------------------------------------------

    private fun setupClickListeners() {
        binding.btnSearch.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            viewModel.search(url)
        }

        binding.btnDownloadMp4.setOnClickListener {
            viewModel.downloadMp4()
        }

        binding.btnDownloadMp3.setOnClickListener {
            viewModel.downloadMp3()
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Idle -> showIdle()
                is UiState.Loading -> showLoading()
                is UiState.Ready -> showReady(state.info)
                is UiState.Downloading -> showDownloading(state.message)
                is UiState.Done -> showDone(state.file, state.mimeType)
                is UiState.Error -> showError(state.message)
            }
        }
    }

    // -------------------------------------------------------------------------
    // State renderers
    // -------------------------------------------------------------------------

    private fun showIdle() {
        binding.layoutProgress.visibility = View.GONE
        binding.cardVideoInfo.visibility = View.GONE
        binding.cardDownload.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.status_idle)
        setButtonsEnabled(true)
    }

    private fun showLoading() {
        binding.layoutProgress.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        binding.tvStatus.text = getString(R.string.status_loading)
        binding.cardVideoInfo.visibility = View.GONE
        binding.cardDownload.visibility = View.GONE
        setButtonsEnabled(false)
    }

    private fun showReady(info: VideoInfo) {
        binding.layoutProgress.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.status_idle)

        // Populate video info card
        binding.tvTitle.text = info.title
        binding.tvDuration.text = getString(R.string.label_duration) + info.formattedDuration()
        Glide.with(this)
            .load(info.thumbnailUrl)
            .centerCrop()
            .into(binding.ivThumbnail)

        binding.cardVideoInfo.visibility = View.VISIBLE
        binding.cardDownload.visibility = View.VISIBLE
        setButtonsEnabled(true)
    }

    private fun showDownloading(message: String) {
        binding.layoutProgress.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = true
        // Show last meaningful progress line (filter yt-dlp noise)
        val display = when {
            message.contains("[download]") -> message.substringAfter("[download]").trim()
            message.contains("[ExtractAudio]") -> "Convirtiendo a MP3…"
            message.contains("[Merger]") -> "Fusionando video y audio…"
            message.isBlank() -> getString(R.string.status_downloading)
            else -> message.take(80)
        }
        binding.tvStatus.text = display
        setButtonsEnabled(false)
    }

    private fun showDone(file: File, mimeType: String) {
        binding.layoutProgress.visibility = View.GONE
        binding.tvStatus.text = getString(R.string.status_complete)
        setButtonsEnabled(true)

        Toast.makeText(this, "✓ ${file.name}", Toast.LENGTH_LONG).show()

        // Offer to open the file
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(Intent.createChooser(intent, getString(R.string.open)))
            }
        } catch (e: Exception) {
            // Not fatal — the file is already saved
        }
    }

    private fun showError(message: String) {
        binding.layoutProgress.visibility = View.GONE
        binding.tvStatus.text = message
        setButtonsEnabled(true)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnSearch.isEnabled = enabled
        binding.btnDownloadMp4.isEnabled = enabled
        binding.btnDownloadMp3.isEnabled = enabled
    }

    private fun requestStoragePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ — READ_MEDIA_VIDEO / READ_MEDIA_AUDIO instead of legacy storage
            val perms = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) perms.add(Manifest.permission.READ_MEDIA_VIDEO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (perms.isNotEmpty()) storagePermLauncher.launch(perms.toTypedArray())
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            val perms = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (perms.isNotEmpty()) storagePermLauncher.launch(perms.toTypedArray())
        }
    }
}
