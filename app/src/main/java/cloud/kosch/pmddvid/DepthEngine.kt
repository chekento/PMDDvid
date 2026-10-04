package cloud.kosch.pmddvid

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

/** Immutable result. RGB = continuous inverse depth, guide luminance, object confidence. */
data class DepthFrame(
    val pixels: ByteBuffer,
    val matrix: FloatArray,
    val createdNs: Long,
    val objectCount: Int,
    val inferenceMs: Long,
)

class TemporalDepth {
    private var previousRaw: FloatArray? = null
    private var gray: FloatArray? = null

    fun reset() {
        previousRaw = null
        gray = null
    }

    /**
     * One-frame, motion/edge-aware temporal stabilization.
     *
     * Important: the blended output is never fed back into history. History always stores the raw
     * depth estimate from the previous analyzed frame, preventing recursive ghost trails.
     */
    fun apply(
        values: FloatArray,
        luma: FloatArray,
        w: Int,
        h: Int,
        trailSuppression: Float = .9f,
    ): FloatArray {
        val old = previousRaw
        val guide = gray
        if (old == null || guide == null || old.size != values.size) {
            previousRaw = values.clone()
            gray = luma.clone()
            return values
        }

        var best = Float.POSITIVE_INFINITY
        var dx = 0
        var dy = 0
        for (oy in -3..3) for (ox in -3..3) {
            var error = 0f
            var count = 0
            for (y in 4 until h - 4 step 8) for (x in 4 until w - 4 step 8) {
                error += abs(luma[y * w + x] - guide[(y + oy) * w + x + ox])
                count++
            }
            error /= count.coerceAtLeast(1)
            if (
                error < best - 1e-6f ||
                    (abs(error - best) <= 1e-6f && abs(ox) + abs(oy) < abs(dx) + abs(dy))
            ) {
                best = error
                dx = ox
                dy = oy
            }
        }

        // A scene change or large camera jump must never drag depth history into the new frame.
        if (best > .105f) {
            previousRaw = values.clone()
            gray = luma.clone()
            return values
        }

        val result = FloatArray(values.size)
        val movement = abs(dx) + abs(dy)
        val suppression = trailSuppression.safe(.9f)
        val staticHistory = .42f * (1f - suppression * .45f)
        val movingHistory = .10f * (1f - suppression * .75f)

        fun edge(field: FloatArray, x: Int, y: Int): Float {
            val xl = max(0, x - 1)
            val xr = min(w - 1, x + 1)
            val yt = max(0, y - 1)
            val yb = min(h - 1, y + 1)
            return abs(field[y * w + xr] - field[y * w + xl]) +
                abs(field[yb * w + x] - field[yt * w + x])
        }

        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val px = x + dx
            val py = y + dy
            if (px !in 0 until w || py !in 0 until h) {
                result[i] = values[i]
                continue
            }
            val j = py * w + px
            val colorError = abs(luma[i] - guide[j])
            val delta = abs(values[i] - old[j])
            val currentEdge = edge(luma, x, y)
            val oldEdge = edge(guide, px, py)

            // Clamp history to the current local depth envelope so old foreground/background
            // values cannot smear across a newly exposed edge.
            var localMin = values[i]
            var localMax = values[i]
            for (oy in -1..1) for (ox in -1..1) {
                val sx = (x + ox).coerceIn(0, w - 1)
                val sy = (y + oy).coerceIn(0, h - 1)
                val v = values[sy * w + sx]
                localMin = min(localMin, v)
                localMax = max(localMax, v)
            }
            val history = old[j].coerceIn(localMin - .018f, localMax + .018f)

            val motionLike = movement > 0 || colorError > .028f
            var blend = if (motionLike) movingHistory else staticHistory
            blend *= 1f - ((colorError - .018f) / .075f).coerceIn(0f, 1f)
            blend *= 1f - ((delta - .035f) / .13f).coerceIn(0f, 1f)

            // Hard edges and disocclusions are "no-trail" zones.
            val edgeStrength = max(currentEdge, oldEdge)
            blend *= 1f - ((edgeStrength - .035f) / .16f).coerceIn(0f, 1f) * suppression
            if (colorError > .09f || delta > .22f || (edgeStrength > .18f && delta > .045f))
                blend = 0f

            result[i] = values[i] * (1f - blend) + history * blend
        }

        // Store only the raw current estimate. Never accumulate a blurred temporal tail.
        previousRaw = values.clone()
        gray = luma.clone()
        return result
    }
}

/** One owner/thread, cached CPU sessions, no GPU inference competing with the encoder. */
class DepthEngine(private val context: Context) : Closeable {
    companion object {
        private val modelLock = Any()
    }

    private val env = OrtEnvironment.getEnvironment()
    private var depthSession: OrtSession? = null
    private var objectSession: OrtSession? = null
    private val temporal = TemporalDepth()
    private var lastTime = 0L
    private var lastObjects = emptyList<SceneObject>()
    private var lastObjectTime = Long.MIN_VALUE
    @Volatile var canceled = false

    private fun model(name: String, size: Long): File =
        synchronized(modelLock) {
            val file = File(context.noBackupFilesDir, name)
            if (!file.exists() || file.length() != size) {
                val tmp = File(file.parent, "$name.tmp")
                context.assets.open(name).use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                check(tmp.length() == size && tmp.renameTo(file)) {
                    "Modell konnte nicht bereitgestellt werden: $name"
                }
            }
            file
        }

    private fun session(name: String, size: Long): OrtSession {
        return OrtSession.SessionOptions().use { opt ->
            opt.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 3))
            opt.setInterOpNumThreads(1)
            opt.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            env.createSession(model(name, size).path, opt)
        }
    }

    fun analyze(
        bitmap: Bitmap,
        matrix: FloatArray,
        timeNs: Long,
        detectObjects: Boolean = true,
        trailSuppression: Float = .9f,
    ): DepthFrame {
        check(!canceled) { "Berechnung abgebrochen" }
        val begin = System.nanoTime()
        val session =
            depthSession ?: session("midas-small.onnx", 66_764_249L).also { depthSession = it }
        val small = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        val colors = IntArray(65536)
        small.getPixels(colors, 0, 256, 0, 0, 256, 256)
        if (small !== bitmap) small.recycle()
        val rgb = FloatArray(65536 * 3)
        val gray = FloatArray(65536)
        colors.forEachIndexed { i, c ->
            val r = ((c shr 16) and 255) / 255f
            val g = ((c shr 8) and 255) / 255f
            val b = (c and 255) / 255f
            rgb[i] = r
            rgb[65536 + i] = g
            rgb[131072 + i] = b
            gray[i] = r * .2126f + g * .7152f + b * .0722f
        }
        var map =
            OnnxTensor.createTensor(env, FloatBuffer.wrap(rgb), longArrayOf(1, 3, 256, 256)).use {
                tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                    val buffer = (out[0] as OnnxTensor).floatBuffer
                    val raw = FloatArray(buffer.remaining())
                    buffer.get(raw)
                    check(raw.size == 65536)
                    DepthMap.normalize(raw, 256, 256)
                }
            }
        check(!canceled) { "Berechnung abgebrochen" }
        if (
            detectObjects &&
                (lastObjectTime == Long.MIN_VALUE || timeNs - lastObjectTime > 2_000_000_000L)
        ) {
            lastObjects = detect(bitmap, map)
            lastObjectTime = timeNs
        } else if (!detectObjects) lastObjects = emptyList()
        if (lastTime == 0L || timeNs <= lastTime || timeNs - lastTime > 2_000_000_000L)
            temporal.reset()
        lastTime = timeNs
        val confidence = FloatArray(65536)
        for (o in lastObjects) {
            val left = (o.left * 256).toInt().coerceIn(0, 255)
            val right = (o.right * 256).toInt().coerceIn(0, 255)
            val top = (o.top * 256).toInt().coerceIn(0, 255)
            val bottom = (o.bottom * 256).toInt().coerceIn(0, 255)
            for (y in top..bottom) for (x in left..right) {
                val i = y * 256 + x
                val weight = (1 - abs(map.values[i] - o.depth) / .16f).coerceIn(0f, 1f) * .12f
                map.values[i] = map.values[i] * (1 - weight) + o.depth * weight
                confidence[i] = max(confidence[i], weight / .12f)
            }
        }
        map =
            DepthMap(
                256,
                256,
                temporal.apply(map.values, gray, 256, 256, trailSuppression),
            )
        val pixels = ByteBuffer.allocateDirect(65536 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until 65536) {
            pixels.put((map.values[i] * 255).roundToInt().toByte())
            pixels.put((gray[i] * 255).roundToInt().toByte())
            pixels.put((confidence[i] * 255).roundToInt().toByte())
            pixels.put(255.toByte())
        }
        pixels.rewind()
        return DepthFrame(
            pixels,
            matrix.clone(),
            System.nanoTime(),
            lastObjects.size,
            (System.nanoTime() - begin) / 1_000_000,
        )
    }

    private fun detect(bitmap: Bitmap, depth: DepthMap): List<SceneObject> {
        val session =
            objectSession ?: session("ssd-mobilenet.onnx", 29_461_455L).also { objectSession = it }
        val small = Bitmap.createScaledBitmap(bitmap, 300, 300, true)
        val colors = IntArray(90000)
        small.getPixels(colors, 0, 300, 0, 0, 300, 300)
        if (small !== bitmap) small.recycle()
        val bytes = ByteBuffer.allocateDirect(270000).order(ByteOrder.nativeOrder())
        colors.forEach { c ->
            bytes.put(((c shr 16) and 255).toByte())
            bytes.put(((c shr 8) and 255).toByte())
            bytes.put((c and 255).toByte())
        }
        bytes.rewind()
        return OnnxTensor.createTensor(env, bytes, longArrayOf(1, 300, 300, 3), OnnxJavaType.UINT8)
            .use { tensor ->
                session.run(mapOf(session.inputNames.single() to tensor)).use { out ->
                    fun floats(name: String): FloatArray {
                        val buffer = (out.get(name).get() as OnnxTensor).floatBuffer
                        return FloatArray(buffer.remaining()).also { buffer.get(it) }
                    }
                    val boxes = floats("detection_boxes")
                    val scores = floats("detection_scores")
                    val count =
                        floats("num_detections")[0]
                            .toInt()
                            .coerceIn(0, min(scores.size, boxes.size / 4))
                    (0 until count)
                        .filter { scores[it] > .45f }
                        .take(20)
                        .map { i ->
                            val top = boxes[i * 4].safe(0f)
                            val left = boxes[i * 4 + 1].safe(0f)
                            val bottom = boxes[i * 4 + 2].safe(1f)
                            val right = boxes[i * 4 + 3].safe(1f)
                            SceneObject(
                                i,
                                "Objekt ${i+1}",
                                left,
                                top,
                                right,
                                bottom,
                                depth = depth.sample((left + right) / 2, (top + bottom) / 2),
                            )
                        }
                }
            }
    }

    override fun close() {
        canceled = true
        depthSession?.close()
        objectSession?.close()
        depthSession = null
        objectSession = null
        temporal.reset()
    }
}
