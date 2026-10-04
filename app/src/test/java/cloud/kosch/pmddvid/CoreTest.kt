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
            Recipe(depth = Float.NaN, layers = 99f, separation = 5f, sharpness = -1f, style = "invalid")
                .normalized()
        assertEquals(6f, r.depth, 0f)
        assertEquals(64f, r.layers, 0f)
        assertEquals(1.5f, r.separation, 0f)
        assertEquals(0f, r.sharpness, 0f)
        assertEquals("vivid", r.style)
    }

    @Test
    fun signedDepthUsesNegativeAndPositiveZ() {
        assertEquals(-6f, DepthSpace.signedZ(1f, .5f, 6f), 0f)
        assertEquals(0f, DepthSpace.signedZ(.5f, .5f, 6f), 0f)
        assertEquals(6f, DepthSpace.signedZ(0f, .5f, 6f), 0f)
    }

    @Test
    fun maximumDepthDefaultsUseAllLayers() {
        val r = Recipe().normalized()
        assertEquals(6f, r.depth, 0f)
        assertEquals(48f, r.layers, 0f)
        assertEquals(1.15f, r.separation, 0f)
        assertEquals(.72f, r.parallax, 0f)
        assertEquals(.92f, r.edgeProtection, 0f)
        assertEquals(.90f, r.trailSuppression, 0f)
    }

    @Test
    fun staticDepthRemainsStable() {
        val t = TemporalDepth()
        val g = FloatArray(4096) { (it % 64) / 64f }
        val a = FloatArray(4096) { .45f }
        t.apply(a, g, 64, 64)
        val output = t.apply(FloatArray(4096) { .50f }, g, 64, 64)
        assertTrue(output.all { it < .50f && it > .47f })
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

    @Test
    fun movingEdgeDoesNotAccumulateTemporalTrail() {
        val t = TemporalDepth()
        val w = 64
        val h = 64
        val firstLuma = FloatArray(w * h) { i -> if (i % w < 28) .1f else .9f }
        val firstDepth = FloatArray(w * h) { i -> if (i % w < 28) .85f else .15f }
        t.apply(firstDepth, firstLuma, w, h, 1f)
        val secondLuma = FloatArray(w * h) { i -> if (i % w < 36) .1f else .9f }
        val secondDepth = FloatArray(w * h) { i -> if (i % w < 36) .85f else .15f }
        val output = t.apply(secondDepth, secondLuma, w, h, 1f)
        for (y in 0 until h) {
            assertTrue(output[y * w + 34] > .75f)
            assertTrue(output[y * w + 38] < .25f)
        }
    }

    @Test
    fun adaptiveAnalysisBacksOffForSlowInference() {
        val fast = PerformanceGovernor.plan(100, 0)
        val slow = PerformanceGovernor.plan(600, 0)
        assertEquals(350_000_000L, fast.intervalNs)
        assertEquals(900_000_000L, slow.intervalNs)
        assertTrue(fast.enabled)
        assertTrue(slow.enabled)
    }

    @Test
    fun thermalPressureProtectsTheVideoPath() {
        assertEquals(550_000_000L, PerformanceGovernor.plan(100, 1).intervalNs)
        assertEquals(850_000_000L, PerformanceGovernor.plan(100, 2).intervalNs)
        assertTrue("Bootstrap analysis remains available when already hot", PerformanceGovernor.plan(0, 3).enabled)
        assertFalse(PerformanceGovernor.plan(100, 3).enabled)
        assertFalse(PerformanceGovernor.plan(100, 6).enabled)
    }
}
