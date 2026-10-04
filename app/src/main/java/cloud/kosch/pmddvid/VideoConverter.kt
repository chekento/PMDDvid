package cloud.kosch.pmddvid

import android.content.Context
import android.net.Uri
import android.opengl.GLES20.*
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.transformer.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(UnstableApi::class)
class PmddVideoEffect(
    private val context: Context,
    private val recipe: Recipe,
    val kind: String,
    private val frames: AtomicInteger,
    private val canceled: AtomicBoolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        check(!useHdr) { "Bitte SDR-Tonemapping für den Export aktivieren." }
        return object : BaseGlShaderProgram(false, 1) {
            private val render = PmddGl(false, true)
            private val engine = DepthEngine(context.applicationContext)
            private var width = 0
            private var height = 0

            override fun configure(inputWidth: Int, inputHeight: Int): Size {
                width = inputWidth
                height = inputHeight
                return Size(if (kind == "stereo") width * 2 else width, height)
            }

            override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
                try {
                    check(!canceled.get()) { "Konversion abgebrochen" }
                    val identity = PmddGl.identity()
                    val sample = render.sample(inputTexId, identity)
                    val map =
                        try {
                            engine.analyze(
                                sample,
                                identity,
                                presentationTimeUs * 1000,
                                recipe.detectObjects,
                                recipe.trailSuppression,
                            )
                        } finally {
                            sample.recycle()
                        }
                    check(!canceled.get()) { "Konversion abgebrochen" }
                    if (kind == "stereo") {
                        glViewport(0, 0, width, height)
                        render.draw(inputTexId, identity, width, height, recipe, map, eye = -1f)
                        glViewport(width, 0, width, height)
                        render.draw(inputTexId, identity, width, height, recipe, map, eye = 1f)
                        glViewport(0, 0, width * 2, height)
                    } else {
                        glViewport(0, 0, width, height)
                        render.draw(
                            inputTexId,
                            identity,
                            width,
                            height,
                            recipe,
                            map,
                            if (kind == "depth") 1 else 0,
                        )
                    }
                    frames.incrementAndGet()
                } catch (e: Exception) {
                    throw VideoFrameProcessingException(e, presentationTimeUs)
                }
            }

            override fun release() {
                try {
                    engine.close()
                    render.release()
                } finally {
                    super.release()
                }
            }
        }
    }
}

/**
 * Transformer keeps source timestamps and audio; the effect outputs one frame per input. No resize,
 * frame-rate override, frame dropping effect, or encoder resolution fallback.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoConverter(private val context: Context) {
    private class Run(val file: File) {
        val thread = HandlerThread("PMDD-export-control").apply { start() }
        val handler = Handler(thread.looper)
        val canceled = AtomicBoolean(false)
        val canceledDelivered = AtomicBoolean(false)
        @Volatile var cancelCallback: () -> Unit = {}
        val frames = AtomicInteger()
        @Volatile var progress: Int? = null
        var transformer: Transformer? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var active: Run? = null
    private var lastFrames = AtomicInteger()
    val frames: AtomicInteger
        get() = lastFrames

    /** Public methods are called on the UI thread. Encoding and cancellation never block it. */
    fun start(
        uri: Uri,
        file: File,
        recipe: Recipe,
        kind: String,
        done: (File) -> Unit,
        error: (String) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(active == null) { "Es läuft bereits eine Konversion." }
        require(kind in setOf("rendered", "depth", "stereo"))
        val run = Run(file)
        val snapshot = recipe.normalized()
        active = run
        lastFrames = run.frames
        fun finish(message: String?) {
            run.transformer = null
            run.handler.removeCallbacksAndMessages(null)
            run.thread.quitSafely()
            if (message != null || run.canceled.get()) file.delete()
            main.post {
                if (run.canceled.get()) {
                    if (run.canceledDelivered.compareAndSet(false, true)) run.cancelCallback()
                } else if (active === run) {
                    active = null
                    if (message == null) done(file) else error(message)
                }
            }
        }
        run.handler.post {
            if (run.canceled.get()) return@post
            try {
                val effect = PmddVideoEffect(context, snapshot, kind, run.frames, run.canceled)
                val settings =
                    VideoEncoderSettings.Builder()
                        .experimentalSetEnableHighQualityTargeting(true)
                        .build()
                val factory =
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(settings)
                        .setEnableFallback(false)
                        .build()
                val listener =
                    object : Transformer.Listener {
                        override fun onCompleted(
                            composition: Composition,
                            exportResult: ExportResult,
                        ) {
                            finish(
                                if (file.length() == 0L || run.frames.get() == 0)
                                    "Keine vollständige Videodatei erzeugt."
                                else null
                            )
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            finish(
                                "Die Datei konnte nicht vollständig verarbeitet werden: ${exportException.message}. Das Original bleibt unverändert."
                            )
                        }
                    }
                val media =
                    EditedMediaItem.Builder(MediaItem.fromUri(uri))
                        .setEffects(Effects(emptyList(), listOf(effect)))
                        .build()
                val composition =
                    Composition.Builder(EditedMediaItemSequence(media))
                        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                        .build()
                run.transformer =
                    Transformer.Builder(context)
                        .setLooper(run.thread.looper)
                        .setEncoderFactory(factory)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(listener)
                        .build()
                run.transformer!!.start(composition, file.absolutePath)
                run.handler.post(
                    object : Runnable {
                        override fun run() {
                            val transformer = run.transformer ?: return
                            if (run.canceled.get()) return
                            val holder = ProgressHolder()
                            run.progress =
                                if (
                                    transformer.getProgress(holder) ==
                                        Transformer.PROGRESS_STATE_AVAILABLE
                                )
                                    holder.progress
                                else null
                            run.handler.postDelayed(this, 500)
                        }
                    }
                )
            } catch (e: Exception) {
                runCatching { run.transformer?.cancel() }
                finish("Konversion fehlgeschlagen: ${e.message}")
            }
        }
    }

    fun progress(): Int? = active?.progress

    fun cancel(onCanceled: () -> Unit = {}) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val run =
            active
                ?: run {
                    onCanceled()
                    return
                }
        active = null
        run.cancelCallback = onCanceled
        run.canceled.set(true)
        run.handler.removeCallbacksAndMessages(null)
        val posted =
            run.handler.post {
                try {
                    run.transformer?.cancel()
                } finally {
                    run.transformer = null
                    run.file.delete()
                    run.thread.quitSafely()
                    main.post {
                        if (run.canceledDelivered.compareAndSet(false, true)) run.cancelCallback()
                    }
                }
            }
        // Completion may have stopped the worker immediately before the user canceled.
        if (!posted) {
            run.file.delete()
            if (run.canceledDelivered.compareAndSet(false, true)) run.cancelCallback()
        }
    }
}
