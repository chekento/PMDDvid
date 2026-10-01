package cloud.kosch.pmddvid

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test
    fun flatDepthIsNeutral() {
        val depth = DepthMap.normalize(FloatArray(64) { 7f }, 8, 8)
        assertTrue(depth.values.all { it == .5f })
    }

    @Test
    fun invalidDepthIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DepthMap.normalize(FloatArray(16) { Float.NaN }, 4, 4)
        }
    }

    @Test
    fun styleCatalogMatchesPhotoTool() {
        assertEquals(61, Styles.all.size)
        assertEquals(61, Styles.all.map { it.id }.distinct().size)
        assertEquals("vivid", Styles.get("unknown").id)
    }

    @Test
    fun recipeClampsExtremeValues() {
        val r =
            Recipe(depth = Float.NaN, separation = 5f, sharpness = -1f, style = "invalid")
                .normalized()
        assertEquals(1.25f, r.depth, 0f)
        assertEquals(1f, r.separation, 0f)
        assertEquals(0f, r.sharpness, 0f)
        assertEquals("vivid", r.style)
    }

    @Test
    fun staticDepthRemainsStable() {
        val t = TemporalDepth()
        val g = FloatArray(4096) { (it % 64) / 64f }
        val a = FloatArray(4096) { .45f }
        t.apply(a, g, 64, 64)
        val output = t.apply(FloatArray(4096) { .50f }, g, 64, 64)
        assertTrue(output.all { it < .48f && it > .45f })
    }

    @Test
    fun sceneCutDoesNotDragDepth() {
        val t = TemporalDepth()
        t.apply(FloatArray(4096) { .1f }, FloatArray(4096) { .1f }, 64, 64)
        val output = t.apply(FloatArray(4096) { .9f }, FloatArray(4096) { .9f }, 64, 64)
        assertTrue(output.all { it == .9f })
    }

    @Test
    fun largeDepthJumpRejectsHistory() {
        val t = TemporalDepth()
        val g = FloatArray(4096) { (it % 64) / 64f }
        t.apply(FloatArray(4096) { .1f }, g, 64, 64)
        val output = t.apply(FloatArray(4096) { .9f }, g, 64, 64)
        assertTrue(output.all { it > .85f })
    }
}
