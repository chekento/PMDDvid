package cloud.kosch.pmddvid

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.EGL14
import android.opengl.GLES20.*
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    private fun evidence(name: String, file: File) {
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(
                    MediaStore.MediaColumns.MIME_TYPE,
                    if (name.endsWith(".png")) "image/png" else "video/mp4",
                )
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/pmddvid-tests")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri =
            requireNotNull(
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            )
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
    }

    private fun evidence(name: String, bitmap: Bitmap) {
        val f = File(context.cacheDir, name)
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        evidence(name, f)
        f.delete()
    }

    private fun shot(name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/pmddvid-tests")
        device.executeShellCommand("screencap -p /sdcard/Download/pmddvid-tests/$name.png")
    }

    @Test
    fun offlineModelsProduceFiniteDepthAndObjectAnchors() {
        assertEquals("1", device.executeShellCommand("settings get global airplane_mode_on").trim())
        val photo =
            instrumentation.context.assets.open("dogs.jpg").use { BitmapFactory.decodeStream(it) }!!
        DepthEngine(context).use { engine ->
            val result = engine.analyze(photo, PmddGl.identity(), System.nanoTime())
            assertTrue(
                "SSD initializes offline and detects at least two objects",
                result.objectCount >= 2,
            )
            val values = (0 until 65536).map { result.pixels.get(it * 4).toInt() and 255 }
            assertTrue("MiDaS returns a non-flat depth map", values.max() - values.min() > 128)
            val map = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            for (y in 0 until 256) for (x in 0 until 256) {
                val v = values[y * 256 + x]
                map.setPixel(x, y, Color.rgb(v, v, v))
            }
            evidence("model-depth.png", map)
            map.recycle()
        }
        photo.recycle()
    }

    @Test
    fun shaderPreservesOrientationAndDoesNotPaintDepthContours() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE,
                    EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE,
                    8,
                    EGL14.EGL_GREEN_SIZE,
                    8,
                    EGL14.EGL_BLUE_SIZE,
                    8,
                    EGL14.EGL_ALPHA_SIZE,
                    8,
                    EGL14.EGL_NONE,
                ),
                0,
                configs,
                0,
                1,
                count,
                0,
            )
        )
        val glContext =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        val surface =
            EGL14.eglCreatePbufferSurface(
                display,
                configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 256, EGL14.EGL_HEIGHT, 256, EGL14.EGL_NONE),
                0,
            )
        assertTrue(EGL14.eglMakeCurrent(display, surface, surface, glContext))
        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        val bytes = ByteBuffer.allocateDirect(256 * 256 * 4)
        for (y in 0 until 256) for (x in 0 until 256) {
            bytes.put((if (x < 128) 255 else 0).toByte())
            bytes.put((if (y < 128) 255 else 0).toByte())
            bytes.put(0)
            bytes.put(255.toByte())
        }
        bytes.rewind()
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 256, 256, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
        val render = PmddGl(false)
        try {
            val bitmap = render.sample(ids[0], PmddGl.identity())
            assertEquals("Top-left stays top-left", Color.RED, bitmap.getPixel(32, 32))
            assertEquals("Bottom-left stays bottom-left", Color.YELLOW, bitmap.getPixel(32, 224))
            assertEquals("No horizontal mirror", Color.BLACK, bitmap.getPixel(224, 32))
            bitmap.recycle()
            // An abrupt depth boundary on a uniform surface must not create contour lines.
            bytes.clear()
            repeat(65536) {
                bytes.put(128.toByte())
                bytes.put(128.toByte())
                bytes.put(128.toByte())
                bytes.put(255.toByte())
            }
            bytes.rewind()
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, ids[0])
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 256, 256, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
            val depthBytes = ByteBuffer.allocateDirect(65536 * 4)
            for (y in 0 until 256) for (x in 0 until 256) {
                depthBytes.put((if (x < 128) 30 else 225).toByte())
                depthBytes.put(128.toByte())
                depthBytes.put(0)
                depthBytes.put(255.toByte())
            }
            depthBytes.rewind()
            glViewport(0, 0, 256, 256)
            render.draw(
                ids[0],
                PmddGl.identity(),
                256,
                256,
                Recipe(depth = 2.5f, haze = 0f, vignette = 0f),
                DepthFrame(depthBytes, PmddGl.identity(), System.nanoTime(), 0, 0),
            )
            val output = ByteBuffer.allocateDirect(65536 * 4)
            glReadPixels(0, 0, 256, 256, GL_RGBA, GL_UNSIGNED_BYTE, output)
            for (x in 1 until 255) assertTrue(
                "No line at depth discontinuity",
                abs(
                    (output.get((128 * 256 + x) * 4).toInt() and 255) -
                        (output.get((128 * 256 + x - 1) * 4).toInt() and 255)
                ) <= 2,
            )
            // Vivid opens photographed shadows and preserves highlight steps without a clock input.
            bytes.clear()
            for (y in 0 until 256) for (x in 0 until 256) {
                repeat(3) { bytes.put(x.toByte()) }
                bytes.put(255.toByte())
            }
            bytes.rewind()
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, ids[0])
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 256, 256, 0, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
            val vivid = Recipe(vignette = 0f, haze = 0f, bokeh = 0f)
            render.draw(ids[0], PmddGl.identity(), 256, 256, vivid, null)
            output.rewind()
            glReadPixels(0, 0, 256, 256, GL_RGBA, GL_UNSIGNED_BYTE, output)
            fun brightness(x: Int): Int {
                val i = (128 * 256 + x) * 4
                return ((output.get(i).toInt() and 255) +
                    (output.get(i + 1).toInt() and 255) +
                    (output.get(i + 2).toInt() and 255)) / 3
            }
            assertTrue("Vivid opens shadows", brightness(60) > 65)
            assertTrue("Vivid retains bright gradation", brightness(250) > brightness(230) + 4)
            val first = ByteArray(65536 * 4)
            output.rewind()
            output.get(first)
            render.draw(ids[0], PmddGl.identity(), 256, 256, vivid, null)
            output.rewind()
            glReadPixels(0, 0, 256, 256, GL_RGBA, GL_UNSIGNED_BYTE, output)
            val second = ByteArray(first.size)
            output.rewind()
            output.get(second)
            assertArrayEquals("Static input has no animated tone flicker", first, second)
            assertEquals(GL_NO_ERROR, glGetError())
        } finally {
            render.release()
            glDeleteTextures(1, ids, 0)
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, glContext)
            EGL14.eglTerminate(display)
        }
    }

    private data class Track(val pts: List<Long>, val format: MediaFormat)

    private fun track(file: File, type: String): Track {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.path)
            val i =
                (0 until ex.trackCount).first {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(type)
                }
            val f = ex.getTrackFormat(i)
            ex.selectTrack(i)
            val pts = mutableListOf<Long>()
            while (ex.sampleTrackIndex >= 0) {
                pts += ex.sampleTime
                if (!ex.advance()) break
            }
            return Track(pts, f)
        } finally {
            ex.release()
        }
    }

    private fun frame(file: File): Bitmap {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            requireNotNull(r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST))
        } finally {
            r.release()
        }
    }

    private fun convert(
        worker: VideoConverter,
        source: File,
        result: File,
        kind: String = "rendered",
    ) {
        val done = CountDownLatch(1)
        var failure: String? = null
        instrumentation.runOnMainSync {
            worker.start(
                Uri.fromFile(source),
                result,
                Recipe(),
                kind,
                done = { done.countDown() },
                error = {
                    failure = it
                    done.countDown()
                },
            )
        }
        assertTrue("Conversion completes", done.await(180, TimeUnit.SECONDS))
        assertNull(failure)
    }

    @Test
    fun converterPreservesVfrAudioResolutionOrientationAndRecoversAfterCancel() {
        val source = File(context.cacheDir, "rotation-vfr.mp4")
        instrumentation.context.assets.open(source.name).use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        val sourceBytes = source.readBytes()
        val output = File(context.cacheDir, "converted.mp4")
        val worker = VideoConverter(context)
        convert(worker, source, output)
        val inputTrack = track(source, "video/")
        val outputTrack = track(output, "video/")
        assertEquals("Every source frame is retained", inputTrack.pts.size, outputTrack.pts.size)
        for (i in inputTrack.pts.indices) assertTrue(
            "VFR timestamp $i retained",
            abs(inputTrack.pts[i] - outputTrack.pts[i]) <= 2,
        )
        val inputAudio = track(source, "audio/")
        val outputAudio = track(output, "audio/")
        assertTrue(
            "Audio samples are retained",
            inputAudio.pts.isNotEmpty() && outputAudio.pts.isNotEmpty(),
        )
        // AAC priming can have negative PTS, and muxers may trim the initial padding packet.
        assertTrue(
            "Audio duration preserved within one AAC frame",
            abs(inputAudio.pts.last() - outputAudio.pts.last()) < 25000,
        )
        assertTrue(
            "No substantial audio loss",
            abs(inputAudio.pts.size - outputAudio.pts.size) <= 2,
        )

        val before = frame(source)
        val after = frame(output)
        assertEquals("Displayed width preserved after rotation", before.width, after.width)
        assertEquals("Displayed height preserved after rotation", before.height, after.height)
        var difference = 0
        for (y in listOf(.2f, .8f)) for (x in listOf(.2f, .8f)) {
            val a = before.getPixel((before.width * x).toInt(), (before.height * y).toInt())
            val b = after.getPixel((after.width * x).toInt(), (after.height * y).toInt())
            for (channel in listOf<(Int) -> Int>(Color::red, Color::green, Color::blue)) {
                assertEquals(
                    "Corner colors retain orientation and do not mirror",
                    channel(a) > 128,
                    channel(b) > 128,
                )
                difference += abs(channel(a) - channel(b))
            }
        }
        assertTrue("PMDD is encoded into the exported pixels", difference > 0)
        assertArrayEquals("Imported source untouched", sourceBytes, source.readBytes())
        evidence("rotation-vfr-original.mp4", source)
        evidence("rotation-vfr-pmdd.mp4", output)
        evidence("converted-frame.png", after)
        before.recycle()
        after.recycle()
        val interrupted = File(context.cacheDir, "interrupted.mp4")
        val canceled = CountDownLatch(1)
        instrumentation.runOnMainSync {
            worker.start(
                Uri.fromFile(source),
                interrupted,
                Recipe(),
                "rendered",
                done = { fail("Canceled job must not complete") },
                error = { fail(it) },
            )
            worker.cancel { canceled.countDown() }
        }
        assertTrue(
            "Cancellation completes without blocking the UI",
            canceled.await(30, TimeUnit.SECONDS),
        )
        assertFalse("Partial file removed", interrupted.exists())
        val stereo = File(context.cacheDir, "stereo.mp4")
        convert(worker, source, stereo, "stereo")
        val stereoFrame = frame(stereo)
        val standard = frame(output)
        assertEquals("Two full-width views", standard.width * 2, stereoFrame.width)
        assertEquals(standard.height, stereoFrame.height)
        stereoFrame.recycle()
        standard.recycle()
        assertEquals(inputTrack.pts.size, track(stereo, "video/").pts.size)
        evidence("stereo.mp4", stereo)
        source.delete()
        output.delete()
        stereo.delete()
    }

    @Test
    fun liveCameraRecordsProcessedVideoWithAudioPauseAndSafeSave() {
        device.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.CAMERA}")
        device.executeShellCommand(
            "pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}"
        )
        context
            .getSharedPreferences("pmddvid", 0)
            .edit()
            .clear()
            .putString("quality", "HD")
            .commit()
        val store = VideoStore(context)
        val existing = store.all().map { it.name }.toSet()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            val shutter = awaitUi(By.desc("Videoaufnahme starten"), 90_000)
            val until = SystemClock.uptimeMillis() + 120_000
            while (!shutter.isEnabled && SystemClock.uptimeMillis() < until) SystemClock.sleep(250)
            assertTrue("Live PMDD becomes ready", shutter.isEnabled)
            shot("camera-pmdd")
            shutter.click()
            awaitUi(By.textStartsWith("REC ·"), 30_000)
            SystemClock.sleep(1500)
            awaitUi(By.desc("Aufnahme pausieren oder fortsetzen"), 10_000).click()
            SystemClock.sleep(750)
            awaitUi(By.desc("Aufnahme pausieren oder fortsetzen"), 10_000).click()
            SystemClock.sleep(1500)
            shot("recording")
            awaitUi(By.desc("Videoaufnahme stoppen"), 10_000).click()
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (
                store.all().none { it.name !in existing } && SystemClock.uptimeMillis() < deadline
            ) SystemClock.sleep(200)
            val recorded = store.all().single { it.name !in existing }
            assertTrue(recorded.length() > 1000)
            assertTrue(
                "Camera effect produces encoded video frames",
                track(recorded, "video/").pts.size > 5,
            )
            assertTrue("Microphone track present", track(recorded, "audio/").pts.isNotEmpty())
            val b = frame(recorded)
            assertTrue("Portrait video keeps correct orientation", b.height > b.width)
            evidence("camera-recording.mp4", recorded)
            evidence("camera-recording.png", b)
            b.recycle()
            awaitUi(By.desc("Videos öffnen"), 10_000).click()
            awaitUi(By.text(recorded.name), 10_000).click()
            awaitUi(By.text("Datei speichern"), 10_000)
            shot("saved-video")
        }
    }

    private fun awaitUi(selector: BySelector, timeout: Long): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            if (device.hasObject(By.pkg("android").text("Viewing full screen")))
                device.findObject(By.res("android", "ok"))?.click()
            if (device.hasObject(By.pkg("android").text("Quickstep isn't responding"))) {
                shot("launcher-anr")
                device.findObject(By.res("android", "aerr_close"))?.click()
            }
            device.findObject(selector)?.let {
                return it
            }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        shot("missing-ui")
        throw AssertionError("Missing UI $selector; foreground ${device.currentPackageName}")
    }
}
