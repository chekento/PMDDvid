package cloud.kosch.pmddvid

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.*
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.*
import org.json.JSONObject

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : ComponentActivity() {
    private val bg = 0xff0b1018.toInt()
    private val panel = 0xff17202e.toInt()
    private val accent = 0xff86f1d1.toInt()
    private val muted = 0xff9aacbf.toInt()
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private lateinit var recordButton: Button
    private lateinit var compare: Button
    private lateinit var pauseButton: Button
    private lateinit var clock: TextView
    private lateinit var store: VideoStore
    private val prefs by lazy { getSharedPreferences("pmddvid", MODE_PRIVATE) }
    private var recipe = Recipe()
    private var original = false
    private var microphone = true
    private var grid = false
    private var front = false
    private var qualityName = "FHD"
    private var torch = false
    private var foreground = false
    private var page = "camera"
    private var epoch = 0
    private var modelReady = false
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: PreviewView? = null
    private var effect: LiveEffect? = null
    private var video: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordingFile: File? = null
    private var paused = false
    private var stopping = false
    private var popup: PopupWindow? = null
    private var player: ExoPlayer? = null
    private var converter: VideoConverter? = null
    private var converting = false
    private var conversionInput: Uri? = null
    private var conversionStatus: TextView? = null
    private var conversionProgress: ProgressBar? = null
    private var pendingSave: File? = null
    private val ui = Handler(Looper.getMainLooper())
    private val controls = mutableListOf<View>()
    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) preview?.let { bindCamera(it) }
            else message("Ohne Kamerazugriff kannst du weiterhin vorhandene Videos konvertieren.")
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted)
                message(
                    "Die Aufnahme startet ohne Ton. Du kannst das Mikrofon später in den Android-Einstellungen freigeben."
                )
            startRecording(granted)
        }
    private val pickVideo =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                showConverter(uri)
            }
        }
    private val saveDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
            val file = pendingSave
            pendingSave = null
            if (uri != null && file != null)
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(uri)?.use { output ->
                                file.inputStream().use { it.copyTo(output) }
                            } ?: error("Zieldatei nicht beschreibbar")
                        }
                        message("Video gespeichert.")
                    } catch (e: Exception) {
                        message(e.message ?: "Speichern fehlgeschlagen")
                    }
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = VideoStore(this)
        recipe =
            runCatching { Recipe.from(JSONObject(prefs.getString("recipe", "{}")!!)) }
                .getOrDefault(Recipe())
        microphone = prefs.getBoolean("microphone", true)
        qualityName = prefs.getString("quality", "FHD") ?: "FHD"
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            content.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        WindowCompat.getInsetsController(window, root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        popup?.isShowing == true -> popup?.dismiss()
                        recording != null -> askStop()
                        converting -> askCancelConversion()
                        page != "camera" -> showCamera()
                        else -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            },
        )
        showCamera()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (page == "camera") preview?.post { preview?.let { bindCamera(it) } }
    }

    override fun onStop() {
        foreground = false
        player?.pause()
        if (recording != null) stopRecording() else releaseCamera()
        super.onStop()
    }

    override fun onDestroy() {
        converter?.cancel()
        ui.removeCallbacksAndMessages(null)
        releaseCamera()
        player?.release()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (page == "camera" && recording == null) preview?.post { preview?.let { bindCamera(it) } }
    }

    private fun clearPage() {
        popup?.dismiss()
        releaseCamera()
        player?.release()
        player = null
        content.removeAllViews()
        controls.clear()
        preview = null
    }

    private fun releaseCamera() {
        epoch++
        provider?.unbindAll()
        effect?.close()
        effect = null
        video = null
        camera = null
        modelReady = false
    }

    private fun showCamera() {
        if (converting) return
        clearPage()
        page = "camera"
        val p =
            PreviewView(this).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                contentDescription = "Live-Videovorschau"
            }
        preview = p
        content.addView(p, FrameLayout.LayoutParams(-1, -1))
        val guide =
            object : View(this) {
                val paint = Paint().apply { color = 0x55ffffff }

                override fun onDraw(c: Canvas) {
                    if (grid)
                        for (i in 1..2) {
                            c.drawLine(width * i / 3f, 0f, width * i / 3f, height.toFloat(), paint)
                            c.drawLine(0f, height * i / 3f, width.toFloat(), height * i / 3f, paint)
                        }
                }
            }
        content.addView(guide, FrameLayout.LayoutParams(-1, -1))
        val top =
            row().apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(25))
                background = fade(true)
            }
        val brand = column()
        brand.addView(label("PMDDvid", 23f, Color.WHITE, true))
        status = label("VIDEO · Lokal auf deinem Gerät", 11f, accent)
        brand.addView(status)
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(icon("photos", "Videos öffnen") { showLibrary() })
        top.addView(
            icon("looks", "Looks und PMDD") { v ->
                showMenu(
                    v,
                    "Bildgestaltung",
                    listOf(
                        Item("looks", "61 Video-Looks", "Farben, Comic, Retro, Atelier") {
                            chooseStyle()
                        },
                        Item("depth", "PMDD-Tiefe", "Tiefe, Trennung und feine Details") {
                            depthSettings()
                        },
                        Item(
                            "compare",
                            "PMDD Vivid · Standard",
                            "Tiefe + Kantenschutz + starke Schlierenunterdrückung",
                        ) {
                            recipe = Recipe()
                            applyRecipe()
                        },
                        Item(
                            "depth",
                            "Deep PMDD",
                            "Maximale Z-Tiefe · 64 Layer · stärkere Parallaxe",
                        ) {
                            recipe =
                                Recipe(
                                    depth = 6f,
                                    layers = 64f,
                                    separation = 1.35f,
                                    relief = .52f,
                                    parallax = .92f,
                                    edgeProtection = .95f,
                                    trailSuppression = .95f,
                                )
                            applyRecipe()
                        },
                        Item(
                            "compare",
                            "Clean Depth",
                            "Minimales Nachziehen · ruhige, klare Kanten",
                        ) {
                            recipe =
                                Recipe(
                                    depth = 5f,
                                    layers = 48f,
                                    separation = 1.05f,
                                    relief = .38f,
                                    parallax = .52f,
                                    edgeProtection = 1f,
                                    trailSuppression = 1f,
                                )
                            applyRecipe()
                        },
                    ),
                )
            }
        )
        top.addView(
            icon("settings", "Kamera und Werkzeuge") { v ->
                showMenu(
                    v,
                    "Kamera & Werkzeuge",
                    listOf(
                        Item("settings", "Auflösung: $qualityName", "Unterstützte Aufnahmegrößen") {
                            chooseQuality()
                        },
                        Item(
                            "camera",
                            "Mikrofon: ${if(microphone)"an" else "aus"}",
                            "Ton für die nächste Aufnahme",
                        ) {
                            microphone = !microphone
                            prefs.edit().putBoolean("microphone", microphone).apply()
                            setStatus(if (microphone) "Mikrofon aktiviert" else "Aufnahme ohne Ton")
                        },
                        Item("flash", "Dauerlicht", "Kameraleuchte an / aus") { toggleTorch() },
                        Item("grid", "Drittelraster", "Bildaufbau unterstützen") {
                            grid = !grid
                            guide.invalidate()
                        },
                        Item("tools", "Video-Konverter", "Vorhandenes AVI, MPEG, MP4 oder WebM") {
                            pickVideo.launch(arrayOf("video/*", "application/octet-stream"))
                        },
                        Item("info", "Hilfe & Formate", "PMDD, Datenschutz und Speicher") {
                            about()
                        },
                    ),
                )
            }
        )
        content.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        val bottom =
            column().apply {
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(26), dp(20), dp(16))
                background = fade(false)
            }
        clock = label("00:00", 16f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
        bottom.addView(clock)
        compare =
            button(if (original) "ORIGINAL" else "PMDD AKTIV") {
                    original = !original
                    effect?.original = original
                    compare.text = if (original) "ORIGINAL" else "PMDD AKTIV"
                    updateRecordState()
                }
                .apply {
                    contentDescription = "Original oder PMDD umschalten"
                    setTextColor(accent)
                }
        controls += compare
        bottom.addView(compare)
        val captureRow = row().apply { gravity = Gravity.CENTER_VERTICAL }
        pauseButton =
            button("Ⅱ") { togglePause() }
                .apply {
                    contentDescription = "Aufnahme pausieren oder fortsetzen"
                    visibility = View.INVISIBLE
                }
        captureRow.addView(pauseButton, LinearLayout.LayoutParams(dp(56), dp(56)))
        recordButton =
            button("●") { if (recording != null) stopRecording() else requestRecording() }
                .apply {
                    textSize = 42f
                    setTextColor(0xffff656b.toInt())
                    contentDescription = "Videoaufnahme starten"
                    background = shape(0xee17202e.toInt(), 40f, Color.WHITE)
                }
        captureRow.addView(
            FrameLayout(this).apply {
                addView(recordButton, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))
            },
            LinearLayout.LayoutParams(0, dp(90), 1f),
        )
        captureRow.addView(
            icon("switch", "Front- oder Rückkamera") {
                front = !front
                p.let { bindCamera(it) }
            },
            LinearLayout.LayoutParams(dp(56), dp(56)),
        )
        bottom.addView(captureRow)
        bottom.addView(
            label("LIVE VIDEO · MP4", 10f, accent, true).apply { gravity = Gravity.CENTER }
        )
        content.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val scale =
            ScaleGestureDetector(
                this,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val z = camera?.cameraInfo?.zoomState?.value ?: return false
                        camera
                            ?.cameraControl
                            ?.setZoomRatio(
                                (z.zoomRatio * detector.scaleFactor).coerceIn(
                                    z.minZoomRatio,
                                    min(z.maxZoomRatio, 10f),
                                )
                            )
                        return true
                    }
                },
            )
        val tap =
            GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: android.view.MotionEvent) = true

                    override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                        camera
                            ?.cameraControl
                            ?.startFocusAndMetering(
                                FocusMeteringAction.Builder(
                                        p.meteringPointFactory.createPoint(e.x, e.y)
                                    )
                                    .build()
                            )
                        p.performClick()
                        return true
                    }
                },
            )
        p.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            if (!scale.isInProgress) tap.onTouchEvent(event)
            true
        }
        p.post {
            if (hasPermission(Manifest.permission.CAMERA)) bindCamera(p)
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    @SuppressLint("MissingPermission")
    private fun bindCamera(p: PreviewView) {
        if (
            !foreground ||
                recording != null ||
                page != "camera" ||
                p !== preview ||
                !hasPermission(Manifest.permission.CAMERA)
        )
            return
        releaseCamera()
        val generation = epoch
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                if (epoch != generation || page != "camera" || !foreground || preview !== p)
                    return@addListener
                try {
                    provider = future.get()
                    val selector =
                        if (front) CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA
                    check(provider!!.hasCamera(selector)) { "Diese Kamera ist nicht verfügbar" }
                    val info =
                        provider!!.availableCameraInfos.first {
                            selector.filter(listOf(it)).isNotEmpty()
                        }
                    val supported = QualitySelector.getSupportedQualities(info)
                    val requested =
                        when (qualityName) {
                            "UHD" -> Quality.UHD
                            "HD" -> Quality.HD
                            else -> Quality.FHD
                        }
                    val chosen =
                        if (requested in supported) requested
                        else
                            supported.firstOrNull { it == Quality.FHD }
                                ?: supported.firstOrNull()
                                ?: error("Keine Videoauflösung verfügbar")
                    if (chosen != requested) {
                        qualityName =
                            if (chosen == Quality.UHD) "UHD"
                            else if (chosen == Quality.FHD) "FHD" else "HD"
                        message("Die Kamera verwendet die unterstützte Auflösung $qualityName.")
                    }
                    val rotation = p.display?.rotation ?: Surface.ROTATION_0
                    val usePreview =
                        Preview.Builder().setTargetRotation(rotation).build().also {
                            it.setSurfaceProvider(p.surfaceProvider)
                        }
                    val bitrate =
                        when (chosen) {
                            Quality.UHD -> 60_000_000
                            Quality.FHD -> 20_000_000
                            else -> 10_000_000
                        }
                    val recorder =
                        Recorder.Builder()
                            .setQualitySelector(QualitySelector.from(chosen))
                            .setTargetVideoEncodingBitRate(bitrate)
                            .build()
                    val capture =
                        VideoCapture.Builder(recorder)
                            .setTargetRotation(rotation)
                            .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
                            .build()
                    val live =
                        LiveEffect(applicationContext) { message, ready ->
                                runOnUiThread {
                                    if (epoch == generation) {
                                        modelReady = ready
                                        setStatus(message)
                                        if (
                                            !ready &&
                                                recording != null &&
                                                (!original || effect?.failed == true)
                                        )
                                            stopRecording()
                                        updateRecordState()
                                    }
                                }
                            }
                            .also {
                                it.recipe = recipe.copy()
                                it.original = original
                            }
                    effect = live
                    val group =
                        UseCaseGroup.Builder()
                            .addUseCase(usePreview)
                            .addUseCase(capture)
                            .addEffect(live.effect)
                            .build()
                    camera = provider!!.bindToLifecycle(this, selector, group)
                    video = capture
                    updateRecordState()
                } catch (e: Exception) {
                    setStatus("Kamerastart fehlgeschlagen: ${e.message}")
                    message(
                        "Kamera konnte nicht gestartet werden. Öffne die Kamera erneut oder wähle eine andere Auflösung. ${e.message}"
                    )
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun updateRecordState() {
        if (!::recordButton.isInitialized || page != "camera") return
        recordButton.isEnabled =
            !stopping &&
                (recording != null ||
                    video != null && effect?.failed != true && (original || modelReady))
        controls.forEach { it.isEnabled = recording == null }
        if (recording == null) pauseButton.visibility = View.INVISIBLE
    }

    private fun requestRecording() {
        if (video == null || (!original && !modelReady)) {
            message(
                "Die lokale Tiefenberechnung wird noch vorbereitet. Im Originalmodus kannst du sofort aufnehmen."
            )
            return
        }
        if (store.folder.usableSpace < 150L * 1024 * 1024) {
            message("Zu wenig freier Speicher für eine sichere Aufnahme.")
            return
        }
        if (microphone && !hasPermission(Manifest.permission.RECORD_AUDIO))
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        else startRecording(microphone)
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(audio: Boolean) {
        val capture = video ?: return
        if (recording != null || !foreground) return
        val file = store.newFile(if (original) "Original" else "PMDD")
        recordingFile = file
        paused = false
        stopping = false
        try {
            var pending =
                capture.output.prepareRecording(this, FileOutputOptions.Builder(file).build())
            if (audio && hasPermission(Manifest.permission.RECORD_AUDIO))
                pending = pending.withAudioEnabled()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            recording =
                pending.start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            recordButton.text = "■"
                            recordButton.contentDescription = "Videoaufnahme stoppen"
                            pauseButton.visibility = View.VISIBLE
                            updateRecordState()
                        }
                        is VideoRecordEvent.Status -> {
                            val seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000
                            clock.text =
                                "${if(paused)"PAUSE · " else "REC · "}%02d:%02d"
                                    .format(seconds / 60, seconds % 60) +
                                    (if (audio) "" else " · ohne Ton")
                        }
                        is VideoRecordEvent.Finalize -> {
                            recording?.close()
                            recording = null
                            stopping = false
                            recordingFile = null
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            recordButton.text = "●"
                            recordButton.contentDescription = "Videoaufnahme starten"
                            clock.text = "00:00"
                            pauseButton.visibility = View.INVISIBLE
                            finalizeRecording(file, event.error)
                            if (!foreground) releaseCamera()
                            updateRecordState()
                        }
                    }
                }
            updateRecordState()
        } catch (e: Exception) {
            recording = null
            file.delete()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            message("Aufnahme fehlgeschlagen: ${e.message}")
            updateRecordState()
        }
    }

    private fun finalizeRecording(file: File, errorCode: Int) {
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val recoverable =
                            errorCode in
                                setOf(
                                    VideoRecordEvent.Finalize.ERROR_NONE,
                                    VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
                                    VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
                                    VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
                                    VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
                                )
                        if (!recoverable || !store.hasVideo(file)) {
                            file.delete()
                            error(
                                "Aufnahme nicht lesbar (Code $errorCode). Die unvollständige Datei wurde entfernt."
                            )
                        }
                        store.commit(file)
                    }
                }
            result
                .onSuccess {
                    setStatus("Gespeichert · ${it.name}")
                    Toast.makeText(
                            this@MainActivity,
                            "Video in der Sammlung gespeichert",
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                    if (errorCode != VideoRecordEvent.Finalize.ERROR_NONE && foreground)
                        message(
                            "Aufnahme wurde unterbrochen (Code $errorCode). Der lesbare Teil wurde gespeichert."
                        )
                }
                .onFailure {
                    setStatus("Speichern fehlgeschlagen")
                    message(it.message ?: "Dateiabschluss fehlgeschlagen")
                }
        }
    }

    private fun stopRecording() {
        if (stopping) return
        stopping = true
        recording?.stop()
        recordButton.isEnabled = false
        setStatus("Aufnahme wird sicher abgeschlossen …")
    }

    private fun togglePause() {
        val r = recording ?: return
        if (stopping) return
        paused = !paused
        if (paused) r.pause() else r.resume()
        pauseButton.text = if (paused) "▶" else "Ⅱ"
    }

    private fun askStop() {
        AlertDialog.Builder(this)
            .setTitle("Aufnahme beenden?")
            .setMessage("Das Video wird zuerst gespeichert.")
            .setPositiveButton("Stoppen") { _, _ -> stopRecording() }
            .setNegativeButton("Weiter aufnehmen", null)
            .show()
    }

    private fun toggleTorch() {
        val c = camera ?: return
        if (!c.cameraInfo.hasFlashUnit()) {
            message("Diese Kamera hat keine Leuchte.")
            return
        }
        torch = !torch
        c.cameraControl.enableTorch(torch)
    }

    private fun chooseQuality() {
        AlertDialog.Builder(this)
            .setTitle("Videoauflösung")
            .setItems(arrayOf("HD · 720p", "FHD · 1080p", "UHD · 2160p")) { _, i ->
                qualityName = arrayOf("HD", "FHD", "UHD")[i]
                prefs.edit().putString("quality", qualityName).apply()
                preview?.let { bindCamera(it) }
            }
            .setNegativeButton("Zurück", null)
            .show()
    }

    private fun applyRecipe() {
        recipe = recipe.normalized()
        prefs.edit().putString("recipe", recipe.json().toString()).apply()
        effect?.recipe = recipe.copy()
        if (page == "camera") setStatus("${Styles.get(recipe.style).name} · ${qualityName}")
    }

    private fun chooseStyle() {
        val groups = Styles.all.map { it.group }.distinct()
        AlertDialog.Builder(this)
            .setTitle("Video-Looks")
            .setItems(groups.toTypedArray()) { _, i ->
                val choices = Styles.all.filter { it.group == groups[i] }
                AlertDialog.Builder(this)
                    .setTitle(groups[i])
                    .setSingleChoiceItems(
                        choices.map { it.name }.toTypedArray(),
                        choices.indexOfFirst { it.id == recipe.style },
                    ) { dialog, index ->
                        recipe.style = choices[index].id
                        recipe.styleMix = 1f
                        applyRecipe()
                        dialog.dismiss()
                    }
                    .setNegativeButton("Zurück", null)
                    .show()
            }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun depthSettings() {
        val box = column().apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        fun slider(title: String, initial: Float, max: Float = 1f, change: (Float) -> Unit) {
            val caption = label("$title · ${(initial*100).roundToInt()} %", 14f)
            box.addView(caption)
            box.addView(
                SeekBar(this).apply {
                    this.max = 100
                    progress = (initial / max * 100).roundToInt()
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onStartTrackingTouch(s: SeekBar) {}

                            override fun onStopTrackingTouch(s: SeekBar) {}

                            override fun onProgressChanged(s: SeekBar, value: Int, user: Boolean) {
                                if (user) {
                                    val v = value / 100f * max
                                    caption.text = "$title · ${(v*100).roundToInt()} %"
                                    change(v)
                                    applyRecipe()
                                }
                            }
                        }
                    )
                }
            )
        }
        slider("3D-Z-Tiefe (±Z)", recipe.depth, 6f) { recipe.depth = it }
        val layerCaption = label("Tiefenlayer · ${recipe.layers.roundToInt()} / 64", 14f)
        box.addView(layerCaption)
        box.addView(
            SeekBar(this).apply {
                max = 62
                progress = recipe.layers.roundToInt().coerceIn(2, 64) - 2
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onStartTrackingTouch(s: SeekBar) {}
                        override fun onStopTrackingTouch(s: SeekBar) {}
                        override fun onProgressChanged(s: SeekBar, value: Int, user: Boolean) {
                            if (user) {
                                recipe.layers = (value + 2).toFloat()
                                layerCaption.text = "Tiefenlayer · ${value + 2} / 64"
                                applyRecipe()
                            }
                        }
                    }
                )
            }
        )
        slider("Fokusebene (Z = 0)", recipe.focus) { recipe.focus = it }
        slider("Ebenentrennung", recipe.separation, 1.5f) { recipe.separation = it }
        slider("Single-View-Parallaxe", recipe.parallax) { recipe.parallax = it }
        slider("Kantenschutz", recipe.edgeProtection) { recipe.edgeProtection = it }
        slider("Schlierenunterdrückung", recipe.trailSuppression) { recipe.trailSuppression = it }
        slider("Detailzeichnung", recipe.sharpness) { recipe.sharpness = it }
        slider("Lichtrelief", recipe.relief) { recipe.relief = it }
        slider("Ferne / Atmosphäre", recipe.haze) { recipe.haze = it }
        slider("Tiefenunschärfe", recipe.bokeh) { recipe.bokeh = it }
        slider("Stilmischung", recipe.styleMix) { recipe.styleMix = it }
        box.addView(
            Switch(this).apply {
                text = "Objektanker erkennen"
                isChecked = recipe.detectObjects
                setOnCheckedChangeListener { _, v ->
                    recipe.detectObjects = v
                    applyRecipe()
                }
            }
        )
        box.addView(
            Switch(this).apply {
                text = "Tiefenrichtung umkehren"
                isChecked = recipe.invertDepth
                setOnCheckedChangeListener { _, v ->
                    recipe.invertDepth = v
                    applyRecipe()
                }
            }
        )
        box.addView(
            label(
                "Signierter PMDD-Z-Raum: Vordergrund liegt auf negativem Z, die Fokusebene auf Z = 0 und der Hintergrund auf positivem Z. Bis zu 64 weiche Layer und ±6 Z. Die Single-View-Parallaxe nutzt nur den aktuellen Frame; Kantenschutz und Schlierenunterdrückung verhindern Nachziehen an Bewegung und Freistellkanten.",
                12f,
                muted,
            )
        )
        AlertDialog.Builder(this)
            .setTitle("PMDD · Tiefengestaltung")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Fertig", null)
            .setNeutralButton("Vivid-Vorgabe") { _, _ ->
                recipe = Recipe()
                applyRecipe()
            }
            .show()
    }

    private fun showLibrary() {
        if (recording != null) return
        clearPage()
        page = "library"
        val box =
            pageLayout(
                "Deine Videos",
                "Aufnahmen und konvertierte Ergebnisse bleiben lokal in der App.",
            )
        box.addView(
            button("Video importieren / konvertieren") {
                pickVideo.launch(arrayOf("video/*", "application/octet-stream"))
            }
        )
        val files = store.all()
        if (files.isEmpty())
            box.addView(label("Noch keine Videos. Starte deine erste Aufnahme.", 15f, muted))
        files.forEach { file ->
            val entry =
                button(file.name) { showVideo(file) }
                    .apply {
                        isAllCaps = false
                        textSize = 13f
                    }
            box.addView(entry)
            val info = label("Informationen werden gelesen …", 12f, muted)
            box.addView(info)
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) { store.description(file) }
                info.text = text
            }
        }
    }

    private fun showVideo(file: File) {
        clearPage()
        page = "player"
        val box = pageLayout(file.name, "Originaldateien beim Import werden nicht überschrieben.")
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        box.addView(
            PlayerView(this).apply {
                player = exo
                useController = true
            },
            LinearLayout.LayoutParams(-1, dp(340)),
        )
        exo.setMediaItem(MediaItem.fromUri(store.uri(file)))
        exo.prepare()
        box.addView(
            button("Teilen") {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, store.uri(file))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        "PMDDvid teilen",
                    )
                )
            }
        )
        box.addView(
            button("Datei speichern") {
                pendingSave = file
                saveDocument.launch(file.name)
            }
        )
        if (Build.VERSION.SDK_INT >= 29)
            box.addView(
                button("In Galerie speichern") {
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.saveToGallery(file) }
                            message("In Movies/PMDDvid gespeichert.")
                        } catch (e: Exception) {
                            message(e.message ?: "Galerieexport fehlgeschlagen")
                        }
                    }
                }
            )
        box.addView(button("Mit Konverter bearbeiten") { showConverter(store.uri(file)) })
        box.addView(button("Zur Sammlung") { showLibrary() })
        box.addView(
            button("Video aus der App löschen") {
                AlertDialog.Builder(this)
                    .setTitle("Dieses Video löschen?")
                    .setMessage(
                        "${file.name}\nBereits in die Galerie oder als Datei gespeicherte Kopien bleiben erhalten."
                    )
                    .setNegativeButton("Behalten", null)
                    .setPositiveButton("Löschen") { _, _ ->
                        player?.release()
                        player = null
                        if (file.delete()) showLibrary()
                        else message("Video konnte nicht gelöscht werden.")
                    }
                    .show()
            }
        )
    }

    private fun showConverter(uri: Uri) {
        clearPage()
        page = "converter"
        conversionInput = uri
        val box =
            pageLayout(
                "Video-Konverter",
                "Untertool · lokale Berechnung für bestehende Videos. Anzeigegröße, Quellzeitstempel und Ton werden erhalten; das Bild wird neu encodiert. HDR wird in SDR umgewandelt.",
            )
        box.addView(button("Look auswählen") { chooseStyle() }.also { controls += it })
        box.addView(button("PMDD einstellen") { depthSettings() }.also { controls += it })
        box.addView(
            label(
                "Ausgabe: MPEG-4 / H.264 (MP4). AVI, MPEG, MOV und WebM lassen sich je nach Gerät und enthaltenem Codec lesen. Ein nicht unterstütztes Format wird gemeldet.",
                13f,
                muted,
            )
        )
        for ((title, kind) in
            listOf(
                "PMDD-Tiefenvideo" to "rendered",
                "Tiefenkarte als Video" to "depth",
                "Stereo · Side-by-Side" to "stereo",
            )) {
            box.addView(button(title) { startConversion(uri, kind) }.also { controls += it })
        }
        box.addView(
            label(
                "Stereo enthält zwei geschätzte Ansichten nebeneinander und benötigt einen SBS-/VR-Player. Es ist doppelt so breit; eine nicht unterstützte Encodergröße wird nicht verkleinert.",
                12f,
                muted,
            )
        )
        conversionProgress =
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                visibility = View.GONE
            }
        box.addView(conversionProgress)
        conversionStatus = label("Bereit · Jeder Quellframe wird einzeln berechnet.", 14f, accent)
        box.addView(conversionStatus)
        box.addView(button("Konversion abbrechen") { askCancelConversion() })
    }

    private fun startConversion(uri: Uri, kind: String) {
        if (converting) return
        val file =
            store.newFile(
                when (kind) {
                    "depth" -> "Tiefe"
                    "stereo" -> "Stereo"
                    else -> "PMDD-Export"
                }
            )
        val worker = VideoConverter(this)
        converter = worker
        converting = true
        controls.forEach { it.isEnabled = false }
        conversionProgress?.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            worker.start(
                uri,
                file,
                recipe,
                kind,
                done = { result ->
                    finishConversion()
                    try {
                        val saved = store.commit(result)
                        showVideo(saved)
                        message("Konversion fertig · ${worker.frames.get()} Frames")
                    } catch (e: Exception) {
                        message(e.message ?: "Dateiabschluss fehlgeschlagen")
                    }
                },
                error = { message ->
                    finishConversion()
                    conversionStatus?.text = message
                    this.message(message)
                },
            )
            val update =
                object : Runnable {
                    override fun run() {
                        if (!converting || converter !== worker) return
                        val progress = worker.progress()
                        conversionProgress?.isIndeterminate = progress == null
                        if (progress != null) conversionProgress?.progress = progress
                        conversionStatus?.text =
                            "${worker.frames.get()} Frames · ${progress?.let{"$it %"}?:"wird vorbereitet"}"
                        ui.postDelayed(this, 500)
                    }
                }
            ui.post(update)
        } catch (e: Exception) {
            finishConversion()
            message("Konversion konnte nicht starten: ${e.message}")
        }
    }

    private fun finishConversion() {
        converting = false
        converter = null
        controls.forEach { it.isEnabled = true }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        conversionProgress?.visibility = View.GONE
    }

    private fun askCancelConversion() {
        if (!converting) return
        AlertDialog.Builder(this)
            .setTitle("Konversion abbrechen?")
            .setMessage("Die unvollständige Ausgabe wird entfernt. Dein Original bleibt erhalten.")
            .setPositiveButton("Abbrechen") { _, _ ->
                conversionStatus?.text = "Abbruch wird abgeschlossen …"
                converter?.cancel {
                    finishConversion()
                    conversionStatus?.text = "Abgebrochen · du kannst neu starten."
                }
            }
            .setNegativeButton("Fortsetzen", null)
            .show()
    }

    private fun about() {
        message(
            "PMDDvid 0.1.6\nVon Kolja Werner Schumann (KoSch) · kosch.cloud\n\nOffline-Videorekorder im Stil von PMDDcam 0.4.0. Vorschau und Aufnahme verwenden denselben PMDD-Shader. Lokale MiDaS-Tiefe und SSD-Objektanker; keine Cloud, keine App-Internetberechtigung.\n\nLive-KI aktualisiert die Tiefe so schnell wie das Gerät sie berechnet; die Kamera und der Encoder laufen unabhängig weiter. Die Tiefen-Historie wird bewegungs- und kantenabhängig begrenzt und nicht rekursiv verschmiert. Die Konversion analysiert jeden Frame.\n\nPMDD gestaltet wahrgenommene Tiefe. Ein normales MP4 reagiert nach dem Export nicht auf Kopfbewegung und enthält keine vollständige 3D-Szene.\n\nAufnahmen liegen zunächst im App-Speicher. Mit „Datei speichern“, „In Galerie speichern“ oder „Teilen“ sichern. Deinstallation löscht den App-Speicher. Beim Verlassen der App wird eine Aufnahme beendet.\n\nAusgabe: MP4, SDR/8 Bit. Die unterstützten Eingabecodecs hängen vom Gerät ab."
        )
    }

    private fun pageLayout(title: String, subtitle: String): LinearLayout {
        val scroll = ScrollView(this)
        val box = column().apply { setPadding(dp(18), dp(14), dp(18), dp(22)) }
        scroll.addView(box)
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        box.addView(button("‹ Kamera") { if (converting) askCancelConversion() else showCamera() })
        box.addView(label(title, 25f, Color.WHITE, true))
        box.addView(label(subtitle, 14f, muted).apply { setPadding(0, dp(10), 0, dp(16)) })
        return box
    }

    private data class Item(
        val icon: String,
        val title: String,
        val subtitle: String,
        val action: () -> Unit,
    )

    private fun showMenu(anchor: View, title: String, items: List<Item>) {
        if (recording != null) return
        popup?.dismiss()
        val box =
            column().apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = shape(panel, 20f)
            }
        box.addView(label(title, 17f, accent, true))
        for (item in items) {
            val line =
                row().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(12), 0, dp(12))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        popup?.dismiss()
                        item.action()
                    }
                }
            line.addView(
                ImageView(this).apply { setImageDrawable(UiIcon(item.icon, accent)) },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(12) },
            )
            line.addView(
                column().apply {
                    addView(label(item.title, 14f, Color.WHITE, true))
                    addView(label(item.subtitle, 11f, muted))
                }
            )
            box.addView(line)
        }
        popup =
            PopupWindow(
                    ScrollView(this).apply { addView(box) },
                    min(resources.displayMetrics.widthPixels - dp(24), dp(332)),
                    min(dp(90 + items.size * 67), resources.displayMetrics.heightPixels - dp(120)),
                    true,
                )
                .apply {
                    elevation = dp(20).toFloat()
                    setBackgroundDrawable(shape(panel, 20f))
                    isOutsideTouchable = true
                    showAsDropDown(anchor, -dp(260), dp(6), Gravity.END)
                }
    }

    private fun icon(name: String, title: String, action: (View) -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageDrawable(UiIcon(name))
            contentDescription = title
            background = shape(0x80212c3a.toInt(), 25f)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(5) }
            setOnClickListener { action(this) }
            controls += this
        }

    private fun button(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            background = shape(panel, 14f)
            minHeight = dp(48)
            setPadding(dp(15), dp(9), dp(15), dp(9))
            layoutParams =
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(5)
                }
            setOnClickListener { action() }
        }

    private fun label(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun shape(color: Int, radius: Float, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius.toInt()).toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(2), stroke)
        }

    private fun fade(top: Boolean) =
        GradientDrawable(
            if (top) GradientDrawable.Orientation.TOP_BOTTOM
            else GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xee0b1018.toInt(), 0x000b1018),
        )

    private fun setStatus(text: String) {
        if (::status.isInitialized) status.text = text
    }

    private fun hasPermission(name: String) =
        ContextCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun message(text: String) {
        if (!isFinishing && !isDestroyed)
            AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show()
    }
}
