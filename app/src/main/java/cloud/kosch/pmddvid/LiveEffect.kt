package cloud.kosch.pmddvid

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.*
import android.opengl.GLES20.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.annotation.RequiresApi
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@RequiresApi(29)
private class ThermalWatcher(
    private val power: PowerManager,
    executor: Executor,
    onStatus: (Int) -> Unit,
) : Closeable {
    private val listener = PowerManager.OnThermalStatusChangedListener(onStatus)

    init {
        onStatus(power.currentThermalStatus)
        power.addThermalStatusListener(executor, listener)
    }

    override fun close() {
        power.removeThermalStatusListener(listener)
    }
}

class LiveEffect(context: Context, private val state: (String, Boolean) -> Unit) :
    SurfaceProcessor, Closeable {
    private val thread = HandlerThread("PMDD-video-GL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }
    val effect =
        object :
            CameraEffect(
                PREVIEW or VIDEO_CAPTURE,
                executor,
                this,
                Consumer { error ->
                    failed = true
                    state(error.message ?: "Kameraeffekt fehlgeschlagen", false)
                },
            ) {}
    private val inference = Executors.newSingleThreadExecutor { r -> Thread(r, "PMDD-local-depth") }
    private val engine = DepthEngine(context.applicationContext)
    private val analyzing = AtomicBoolean(false)
    @Volatile private var lastInferenceMs = 0L
    @Volatile private var thermalStatus = 0
    private val thermalWatcher: Closeable? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThermalWatcher(
                context.applicationContext.getSystemService(PowerManager::class.java),
                executor,
            ) { thermalStatus = it }
        } else null
    @Volatile var recipe = Recipe()
    @Volatile var original = false
    @Volatile var depthOnly = false
    @Volatile private var depth: DepthFrame? = null
    @Volatile private var closing = false
    @Volatile
    var failed = false
        private set

    private var lastAnalysis = 0L
    private var display = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var dummy = EGL14.EGL_NO_SURFACE
    private lateinit var config: EGLConfig
    private var renderer: PmddGl? = null

    private data class Input(
        val texture: Int,
        val stream: SurfaceTexture,
        val surface: Surface,
        var lastTimestamp: Long = Long.MIN_VALUE,
    )

    private val inputs = mutableSetOf<Input>()
    private val outputs = linkedMapOf<SurfaceOutput, EGLSurface>()

    private fun initialize() {
        if (renderer != null) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val attributes =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                0x3142,
                1,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0
        )
        config = configs[0]!!
        eglContext =
            EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        dummy =
            EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
        makeCurrent(dummy)
        renderer = PmddGl(true)
        state("KI-Tiefe wird lokal vorbereitet …", false)
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, eglContext)) {
            "Grafikgerät wurde zurückgesetzt"
        }
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (closing) {
            request.willNotProvideSurface()
            return
        }
        try {
            initialize()
            makeCurrent(dummy)
            val ids = IntArray(1)
            glGenTextures(1, ids, 0)
            glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            val stream = SurfaceTexture(ids[0])
            stream.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            val input = Input(ids[0], stream, Surface(stream))
            inputs += input
            stream.setOnFrameAvailableListener({ draw(input) }, handler)
            request.provideSurface(input.surface, executor) {
                makeCurrent(dummy)
                input.stream.setOnFrameAvailableListener(null)
                input.surface.release()
                input.stream.release()
                glDeleteTextures(1, intArrayOf(input.texture), 0)
                inputs.remove(input)
                shutdownIfReady()
            }
        } catch (error: Exception) {
            request.willNotProvideSurface()
            failed = true
            state(error.message ?: "Kamerafläche nicht verfügbar", false)
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (closing) {
            output.close()
            return
        }
        try {
            initialize()
            val surface =
                output.getSurface(executor) {
                    outputs.remove(output)?.let { EGL14.eglDestroySurface(display, it) }
                    output.close()
                }
            val window =
                EGL14.eglCreateWindowSurface(
                    display,
                    config,
                    surface,
                    intArrayOf(EGL14.EGL_NONE),
                    0,
                )
            check(window != EGL14.EGL_NO_SURFACE)
            outputs[output] = window
        } catch (error: Exception) {
            output.close()
            failed = true
            state(error.message ?: "Videoausgabe nicht verfügbar", false)
        }
    }

    private fun draw(input: Input) {
        if (closing || failed || renderer == null) return
        try {
            makeCurrent(dummy)
            input.stream.updateTexImage()
            // Multiple queued callbacks can refer to the same newest SurfaceTexture image.
            // Rendering it twice wastes GPU time and gives the encoder duplicate timestamps.
            if (input.stream.timestamp <= input.lastTimestamp) return
            input.lastTimestamp = input.stream.timestamp
            val raw = FloatArray(16)
            input.stream.getTransformMatrix(raw)
            val now = System.nanoTime()
            val scene = recipe
            val analysisPlan = PerformanceGovernor.plan(lastInferenceMs, thermalStatus)
            val analysisOutput =
                outputs.keys.firstOrNull { it.targets and CameraEffect.VIDEO_CAPTURE != 0 }
                    ?: outputs.keys.firstOrNull()
            if (
                analysisOutput != null &&
                    !original &&
                    analysisPlan.enabled &&
                    now - lastAnalysis > analysisPlan.intervalNs &&
                    analyzing.compareAndSet(false, true)
            ) {
                lastAnalysis = now
                // Analyze exactly the oriented/cropped view CameraX supplies to the recorder.
                // The saved matrix also maps a differently mirrored preview back to this depth.
                val analysisMatrix = FloatArray(16)
                analysisOutput.updateTransformMatrix(analysisMatrix, raw)
                val bitmap = renderer!!.sample(input.texture, analysisMatrix)
                inference.execute {
                    try {
                        val result =
                            engine.analyze(
                                bitmap,
                                analysisMatrix,
                                now,
                                scene.detectObjects,
                                scene.trailSuppression,
                            )
                        if (!closing && !failed) {
                            lastInferenceMs = result.inferenceMs
                            depth = result
                            state(
                                "LIVE · ${result.objectCount} Objektanker · KI ${result.inferenceMs} ms",
                                true,
                            )
                        }
                    } catch (error: Throwable) {
                        if (!closing) {
                            state(
                                "KI nicht verfügbar: ${error.message}. Originalmodus bleibt nutzbar.",
                                false,
                            )
                            original = true
                        }
                    } finally {
                        bitmap.recycle()
                        analyzing.set(false)
                    }
                }
            }
            for ((output, surface) in outputs.toMap()) {
                makeCurrent(surface)
                val size = output.size
                glViewport(0, 0, size.width, size.height)
                val matrix = FloatArray(16)
                output.updateTransformMatrix(matrix, raw)
                val depthFrame = depth
                val sourceAge =
                    if (depthFrame == null) Float.POSITIVE_INFINITY
                    else ((now - depthFrame.sourceNs).coerceAtLeast(0L) / 1e9f)
                // The uploaded real-world clips showed that geometric reprojection from a depth
                // frame older than the visible RGB frame creates the apparent "trails".
                // Shading may fade more slowly, geometry is disabled aggressively.
                val liveDepthTrust =
                    if (depthFrame == null) 0f
                    else (1f - ((sourceAge - .18f) / .82f)).coerceIn(0f, 1f)
                val liveGeometryTrust =
                    if (depthFrame == null) 0f
                    else (1f - ((sourceAge - .07f) / .11f)).coerceIn(0f, 1f)
                renderer!!.draw(
                    input.texture,
                    matrix,
                    size.width,
                    size.height,
                    scene,
                    depthFrame,
                    if (original) 2 else if (depthOnly) 1 else 0,
                    depthTrust = liveDepthTrust,
                    geometryTrust = liveGeometryTrust,
                )
                EGLExt.eglPresentationTimeANDROID(display, surface, input.stream.timestamp)
                check(EGL14.eglSwapBuffers(display, surface)) { "Videooberfläche wurde beendet" }
            }
        } catch (error: Throwable) {
            failed = true
            state("Rendering angehalten: ${error.message}", false)
        }
    }

    override fun close() {
        if (closing) return
        closing = true
        engine.canceled = true
        thermalWatcher?.close()
        inference.execute { engine.close() }
        inference.shutdown()
        handler.post {
            for ((out, surface) in outputs) {
                EGL14.eglDestroySurface(display, surface)
                out.close()
            }
            outputs.clear()
            shutdownIfReady()
        }
    }

    private fun shutdownIfReady() {
        if (!closing || inputs.isNotEmpty()) return
        if (display != EGL14.EGL_NO_DISPLAY) {
            makeCurrent(dummy)
            renderer?.release()
            renderer = null
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, dummy)
            EGL14.eglDestroyContext(display, eglContext)
            EGL14.eglTerminate(display)
            display = EGL14.EGL_NO_DISPLAY
        }
        thread.quitSafely()
    }
}
