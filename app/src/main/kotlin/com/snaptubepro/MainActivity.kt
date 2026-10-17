package com.ericcatch

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ericcatch.databinding.ActivityMainBinding
import com.ericcatch.databinding.BottomSheetQualityBinding
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    // ── Acción pendiente tras login ────────────────────────────────────────────
    private var pendingDownloadAction: (() -> Unit)? = null

    // ── DownloadService binding ────────────────────────────────────────────────
    private var downloadService: DownloadService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            downloadService = (binder as DownloadService.DownloadBinder).getService()
            serviceBound = true
            // Registrar callback: cuando la Activity vuelve al frente recibe el progreso
            downloadService?.progressCallback = { progress -> handleProgress(progress) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceBound = false
            downloadService = null
        }
    }

    // ── Launchers ──────────────────────────────────────────────────────────────

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            updateSessionBadge()
            Toast.makeText(this, "✓ Sesión activa. Reintentando descarga…", Toast.LENGTH_SHORT).show()
            pendingDownloadAction?.invoke()
            pendingDownloadAction = null
        } else {
            if (pendingDownloadAction != null) {
                AlertDialog.Builder(this)
                    .setTitle("Continuar sin sesión")
                    .setMessage("Sin sesión activa algunas calidades pueden fallar.\n¿Intentar de todos modos?")
                    .setPositiveButton("Intentar igual") { _, _ ->
                        pendingDownloadAction?.invoke()
                        pendingDownloadAction = null
                    }
                    .setNegativeButton("Cancelar") { _, _ -> pendingDownloadAction = null }
                    .show()
            }
            invalidateOptionsMenu()
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { !it })
            Toast.makeText(this, "Algunos permisos denegados.", Toast.LENGTH_LONG).show()
    }

    // ── Menú ───────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_login)?.apply {
            title = if (LoginActivity.isLoggedIn(this@MainActivity)) "✓ Sesión" else "Sesión"
            setIcon(android.R.drawable.ic_menu_myplaces)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_history -> {
                startActivity(Intent(this, HistoryActivity::class.java))
                true
            }
            R.id.action_login -> {
                if (LoginActivity.isLoggedIn(this)) {
                    AlertDialog.Builder(this)
                        .setTitle("Sesión activa")
                        .setMessage("¿Qué deseas hacer?")
                        .setPositiveButton("Cambiar cuenta") { _, _ ->
                            loginLauncher.launch(Intent(this, LoginActivity::class.java))
                        }
                        .setNegativeButton("Cerrar sesión") { _, _ ->
                            LoginActivity.clearSession(this)
                            updateSessionBadge()
                            Toast.makeText(this, "Sesión cerrada.", Toast.LENGTH_SHORT).show()
                            invalidateOptionsMenu()
                        }
                        .setNeutralButton("Cancelar", null)
                        .show()
                } else {
                    loginLauncher.launch(Intent(this, LoginActivity::class.java))
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── onCreate / onDestroy ───────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        requestAllPermissions()

        // Conectar al servicio de descarga
        Intent(this, DownloadService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        lifecycleScope.launch {
            YtDlpInstaller.ensureInstalled(this@MainActivity) { status ->
                runOnUiThread { setStatusText(status) }
            }
        }

        setupSearch()
        setupDownloadButtons()
        observeViewModel()
        updateSessionBadge()
    }

    override fun onResume() {
        super.onResume()
        updateSessionBadge()
        invalidateOptionsMenu()
        // Reconectar callback si el service ya estaba corriendo
        downloadService?.progressCallback = { progress -> handleProgress(progress) }
    }

    override fun onDestroy() {
        if (serviceBound) {
            downloadService?.progressCallback = null
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    // ── Badge sesión ───────────────────────────────────────────────────────────

    private fun updateSessionBadge() {
        val isLoggedIn = LoginActivity.isLoggedIn(this)
        val tint = if (isLoggedIn) getColor(R.color.colorAccent) else getColor(R.color.icon_tint)
        val stroke = if (isLoggedIn) getColor(R.color.border_glow_cyan) else getColor(R.color.border_glass)
        binding.btnLogin.setIconTint(android.content.res.ColorStateList.valueOf(tint))
        binding.btnLogin.strokeColor = android.content.res.ColorStateList.valueOf(stroke)
    }

    // ── Animación de entrada ───────────────────────────────────────────────────

    private fun animateToolbarEntrance() {
        binding.ivLogo.apply {
            alpha = 0f; scaleX = 0.4f; scaleY = 0.4f
            animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(500).setInterpolator(OvershootInterpolator(1.8f)).start()
        }
        binding.layoutAppTitle.apply {
            alpha = 0f; translationX = -20f
            animate().alpha(1f).translationX(0f)
                .setDuration(400).setStartDelay(180).setInterpolator(DecelerateInterpolator()).start()
        }
        binding.layoutSearchField.apply {
            alpha = 0f; translationY = 20f
            animate().alpha(1f).translationY(0f)
                .setDuration(400).setStartDelay(280).setInterpolator(DecelerateInterpolator()).start()
        }
        binding.btnSearch.apply {
            alpha = 0f; scaleX = 0.7f; scaleY = 0.7f
            animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(360).setStartDelay(340).setInterpolator(OvershootInterpolator(1.4f)).start()
        }
    }

    // ── Permisos ───────────────────────────────────────────────────────────────

    private fun requestAllPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS))
                needed += Manifest.permission.POST_NOTIFICATIONS
        } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (!hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE))
                needed += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Permisos necesarios")
                .setMessage("EricCatch necesita permisos de almacenamiento para guardar las descargas.")
                .setPositiveButton("Aceptar") { _, _ -> permLauncher.launch(needed.toTypedArray()) }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ── Búsqueda ───────────────────────────────────────────────────────────────

    private fun setupSearch() {
        binding.etSearch.setOnFocusChangeListener { _, hasFocus ->
            binding.layoutSearchField.setBackgroundResource(
                if (hasFocus) R.drawable.bg_search_active else R.drawable.bg_search_field)
            val scale = if (hasFocus) 1.02f else 1f
            binding.layoutSearchField.animate()
                .scaleX(scale).scaleY(scale)
                .setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        }
        binding.btnSearch.setOnClickListener {
            ObjectAnimator.ofFloat(binding.btnSearch, "rotation", 0f, 360f).apply {
                duration = 500; interpolator = DecelerateInterpolator(); start()
            }
            triggerSearch()
        }
        binding.etSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                triggerSearch(); true
            } else false
        }
    }

    private fun triggerSearch() {
        hideKeyboard()
        viewModel.search(binding.etSearch.text.toString().trim())
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
    }

    // ── Botones de descarga ────────────────────────────────────────────────────

    private fun setupDownloadButtons() {
        binding.btnDownloadMp4.apply {
            setOnClickListener { checkSessionThenDownload { viewModel.downloadMp4() } }
            setupPressAnimation(this)
        }
        binding.btnDownloadMp3.apply {
            setOnClickListener { checkSessionThenDownload { viewModel.downloadMp3() } }
            setupPressAnimation(this)
        }
    }

    private fun checkSessionThenDownload(action: () -> Unit) {
        if (LoginActivity.isLoggedIn(this)) { action(); return }
        AlertDialog.Builder(this)
            .setTitle("🔐 Sesión requerida para HD")
            .setMessage(
                "YouTube ahora requiere autenticación para servir videos en alta calidad.\n\n" +
                "• Descargar en 1080p, 4K sin fallos\n" +
                "• Mejor audio para MP3\n" +
                "• Evitar bloqueos 403\n\n" +
                "Solo necesitas hacerlo una vez."
            )
            .setPositiveButton("Iniciar sesión") { _, _ ->
                pendingDownloadAction = action
                loginLauncher.launch(Intent(this, LoginActivity::class.java))
            }
            .setNegativeButton("Intentar sin sesión") { _, _ ->
                Toast.makeText(this, "⚠ Sin sesión pueden fallar calidades altas", Toast.LENGTH_LONG).show()
                action()
            }
            .setCancelable(true)
            .show()
    }

    private fun setupPressAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate()
                    .scaleX(0.95f).scaleY(0.95f)
                    .setDuration(120).setInterpolator(DecelerateInterpolator()).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f)
                        .setDuration(300).setInterpolator(OvershootInterpolator(2f)).start()
                    if (event.action == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    // ── Observar ViewModel ─────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is UiState.Idle             -> renderIdle()
                is UiState.Loading          -> renderLoading("Buscando…")
                is UiState.SearchResults    -> renderSearchResults(state.results)
                is UiState.Ready            -> renderReady(state.info)
                is UiState.LoadingFormats   -> renderLoading("Obteniendo formatos…")
                is UiState.SelectingQuality -> showQualitySheet(state)
                is UiState.Downloading      -> renderDownloading(state.message)
                is UiState.Done             -> renderDone(state.file, state.mimeType)
                is UiState.Error            -> renderError(state.message)
            }
        }
    }

    // ── Progreso del DownloadService ───────────────────────────────────────────

    private fun handleProgress(progress: DownloadProgress) {
        runOnUiThread {
            when (progress) {
                is DownloadProgress.InProgress -> {
                    showProgressBlock(
                        pct       = progress.percent,
                        speed     = progress.speed,
                        size      = progress.size,
                        indeterminate = false
                    )
                    setStatusText("Descargando ${progress.percent}%")
                }
                is DownloadProgress.Converting -> {
                    showProgressBlock(pct = 100, speed = "", size = "", indeterminate = true)
                    setStatusText("Convirtiendo a MP3…")
                }
                is DownloadProgress.Merging -> {
                    showProgressBlock(pct = 100, speed = "", size = "", indeterminate = true)
                    setStatusText("Fusionando pistas…")
                }
                is DownloadProgress.Done -> {
                    hideProgressBlock()
                    renderDone(progress.file, progress.mimeType)
                }
                is DownloadProgress.Error -> {
                    hideProgressBlock()
                    renderError(progress.message)
                }
                is DownloadProgress.Cancelled -> {
                    hideProgressBlock()
                    renderIdle()
                }
            }
        }
    }

    // ── Renderizadores ─────────────────────────────────────────────────────────

    private fun renderIdle() {
        hideProgressBlock()
        hideView(binding.layoutSearchResults)
        hideView(binding.cardVideoInfo)
        hideView(binding.cardDownload)
        setStatusText(getString(R.string.status_idle))
        setButtons(true)
    }

    private fun renderLoading(msg: String) {
        showProgressBlock(pct = 0, speed = "", size = "", indeterminate = true)
        setStatusText(msg)
        setButtons(false)
    }

    private fun renderSearchResults(results: List<SearchResult>) {
        hideProgressBlock()
        hideView(binding.cardVideoInfo)
        hideView(binding.cardDownload)
        setStatusText("${results.size} resultados")
        setButtons(true)

        binding.layoutSearchResults.removeAllViews()
        val inflater = LayoutInflater.from(this)
        results.forEachIndexed { index, result ->
            val item = inflater.inflate(R.layout.item_search_result,
                binding.layoutSearchResults, false)
            item.findViewById<TextView>(R.id.tvResultTitle).text = result.title
            item.findViewById<TextView>(R.id.tvResultChannel).text = result.channelName
            item.findViewById<TextView>(R.id.tvResultDuration).text = result.duration
            Glide.with(this).load(result.thumbnailUrl).centerCrop()
                .placeholder(android.R.color.darker_gray)
                .into(item.findViewById(R.id.ivThumb))
            item.setOnClickListener {
                hideView(binding.layoutSearchResults)
                viewModel.selectSearchResult(result)
            }
            item.alpha = 0f; item.translationY = 24f
            binding.layoutSearchResults.addView(item)
            item.animate().alpha(1f).translationY(0f)
                .setDuration(320).setStartDelay((index * 60).toLong())
                .setInterpolator(DecelerateInterpolator()).start()
        }
        showView(binding.layoutSearchResults)
    }

    private fun renderReady(info: VideoInfo) {
        hideProgressBlock()
        hideView(binding.layoutSearchResults)
        setStatusText(getString(R.string.status_idle))
        binding.tvTitle.text = info.title
        binding.tvDuration.text = info.formattedDuration()
        Glide.with(this).load(info.thumbnailUrl).centerCrop()
            .placeholder(android.R.color.darker_gray).into(binding.ivThumbnail)
        showViewAnimated(binding.cardVideoInfo, delay = 0)
        showViewAnimated(binding.cardDownload, delay = 120)
        setButtons(true)
    }

    private fun showQualitySheet(state: UiState.SelectingQuality) {
        hideProgressBlock()
        setButtons(true)

        val sheetBinding = BottomSheetQualityBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)

        val isAudio = state.formats.firstOrNull()?.type == FormatType.AUDIO
        sheetBinding.tvSheetTitle.text =
            if (isAudio) "Selecciona calidad de audio" else "Selecciona calidad de video"

        state.formats.forEachIndexed { index, format ->
            val item = layoutInflater.inflate(R.layout.item_quality_option,
                sheetBinding.layoutOptions, false)
            item.findViewById<TextView>(R.id.tvQualityLabel).text = format.label
            item.alpha = 0f; item.translationY = 16f
            item.setOnClickListener {
                dialog.dismiss()
                startDownloadViaService(state.info, format)
            }
            sheetBinding.layoutOptions.addView(item)
            item.animate().alpha(1f).translationY(0f)
                .setDuration(260).setStartDelay((80 + index * 50).toLong())
                .setInterpolator(DecelerateInterpolator()).start()
        }
        dialog.show()
    }

    /**
     * Inicia la descarga a través del ForegroundService.
     * Si el service no está disponible, cae al ViewModel como fallback.
     */
    private fun startDownloadViaService(info: VideoInfo, format: FormatOption) {
        val service = downloadService
        if (service != null) {
            setButtons(false)
            showProgressBlock(pct = 0, speed = "", size = "", indeterminate = true)
            setStatusText("Preparando descarga…")
            service.progressCallback = { progress -> handleProgress(progress) }

            val destDir = viewModel.getDownloadsDir()
            // Arrancar el service como foreground para que sobreviva en background
            val intent = Intent(this, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            service.startDownload(
                url     = info.originalUrl,
                format  = format,
                title   = info.title,
                destDir = destDir
            )
        } else {
            // Fallback al ViewModel si el servicio no está listo
            viewModel.downloadWithFormat(format)
        }
    }

    private fun renderDownloading(message: String) {
        // Parsear progreso del mensaje de yt-dlp para la barra
        val pctMatch = Regex("""(\d+\.?\d*)%""").find(message)
        val pct = pctMatch?.groupValues?.get(1)?.toFloatOrNull()?.toInt() ?: 0
        val speedMatch = Regex("""at\s+([\d.]+\s*\w+/s)""").find(message)
        val speed = speedMatch?.groupValues?.get(1) ?: ""
        val sizeMatch = Regex("""of\s+([\d.]+\s*\w+)""").find(message)
        val size = sizeMatch?.groupValues?.get(1) ?: ""

        val indeterminate = pct == 0 && !message.contains("%")
        showProgressBlock(pct = pct, speed = speed, size = size, indeterminate = indeterminate)

        val display = when {
            message.contains("[download]")     -> {
                if (pct > 0) "Descargando $pct%" else message.substringAfter("[download]").trim()
            }
            message.contains("[ExtractAudio]") -> "Convirtiendo a MP3…"
            message.contains("[Merger]")       -> "Fusionando pistas…"
            message.contains("[ffmpeg]")       -> "Procesando…"
            message.isBlank()                  -> getString(R.string.status_downloading)
            else -> message.take(60)
        }
        setStatusText(display)
        setButtons(false)
    }

    private fun renderDone(file: File, mimeType: String) {
        hideProgressBlock()
        setStatusText(getString(R.string.status_complete))
        pulseView(binding.cardDownload)
        setButtons(true)
        Toast.makeText(this, "✓ Guardado: ${file.name}", Toast.LENGTH_LONG).show()
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(packageManager) != null)
                startActivity(Intent.createChooser(intent, getString(R.string.open)))
        } catch (_: Exception) { }
    }

    private fun renderError(message: String) {
        hideProgressBlock()
        val is403 = message.contains("403") || message.contains("Forbidden", ignoreCase = true)
        if (is403 && !LoginActivity.isLoggedIn(this)) {
            setStatusText("⚠ Error 403 — Se requiere sesión")
            shakeView(binding.layoutSearchField)
            AlertDialog.Builder(this)
                .setTitle("⚠ Bloqueado por YouTube")
                .setMessage("YouTube bloqueó esta descarga. Inicia sesión para resolverlo.")
                .setPositiveButton("Iniciar sesión ahora") { _, _ ->
                    loginLauncher.launch(Intent(this, LoginActivity::class.java))
                }
                .setNegativeButton("Cerrar", null)
                .show()
        } else {
            setStatusText("⚠ $message")
            shakeView(binding.layoutSearchField)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
        setButtons(true)
    }

    // ── Helpers de progreso ────────────────────────────────────────────────────

    private fun showProgressBlock(
        pct: Int, speed: String, size: String, indeterminate: Boolean
    ) {
        binding.layoutProgress.visibility = View.VISIBLE
        binding.progressBar.isIndeterminate = indeterminate
        if (!indeterminate) {
            binding.progressBar.progress = pct
        }
        binding.tvProgressPct.text = if (indeterminate) "…" else "$pct%"
        binding.tvProgressSpeed.text = speed
        binding.tvProgressSize.text  = size
    }

    private fun hideProgressBlock() {
        binding.layoutProgress.visibility = View.GONE
        binding.progressBar.progress = 0
    }

    // ── Helpers de animación ───────────────────────────────────────────────────

    private fun showViewAnimated(view: View, delay: Long = 0) {
        view.alpha = 0f; view.translationY = 40f; view.visibility = View.VISIBLE
        view.animate().alpha(1f).translationY(0f)
            .setDuration(380).setStartDelay(delay)
            .setInterpolator(DecelerateInterpolator(1.5f)).start()
    }

    private fun showView(view: View) {
        if (view.visibility == View.VISIBLE) return
        view.visibility = View.VISIBLE
    }

    private fun hideView(view: View) {
        if (view.visibility != View.VISIBLE) return
        view.animate().alpha(0f).setDuration(180).setInterpolator(DecelerateInterpolator())
            .withEndAction { view.visibility = View.GONE; view.alpha = 1f }.start()
    }

    private fun setStatusText(text: String) {
        if (binding.tvStatus.text == text) return
        binding.tvStatus.animate().alpha(0f).setDuration(120)
            .withEndAction {
                binding.tvStatus.text = text
                binding.tvStatus.animate().alpha(1f).setDuration(160).start()
            }.start()
    }

    private fun pulseView(view: View) {
        AnimatorSet().apply {
            playSequentially(
                ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.03f).apply { duration = 140 },
                ObjectAnimator.ofFloat(view, "scaleX", 1.03f, 1f).apply {
                    duration = 280; interpolator = OvershootInterpolator(3f)
                }
            )
            start()
        }
        AnimatorSet().apply {
            playSequentially(
                ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.03f).apply { duration = 140 },
                ObjectAnimator.ofFloat(view, "scaleY", 1.03f, 1f).apply {
                    duration = 280; interpolator = OvershootInterpolator(3f)
                }
            )
            start()
        }
    }

    private fun shakeView(view: View) {
        ObjectAnimator.ofFloat(view, "translationX",
            0f, -12f, 12f, -10f, 10f, -6f, 6f, -3f, 3f, 0f
        ).apply { duration = 500; interpolator = DecelerateInterpolator(); start() }
    }

    private fun setButtons(enabled: Boolean) {
        binding.btnSearch.isEnabled = enabled
        binding.btnDownloadMp4.isEnabled = enabled
        binding.btnDownloadMp3.isEnabled = enabled
    }
}
