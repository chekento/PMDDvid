package cloud.kosch.pmddvid

import kotlin.math.*
import org.json.JSONObject

/** All edits are a recipe. Neither this nor the renderer ever writes to the original. */
data class Recipe(
    var depth: Float = 6f,
    var layers: Float = 48f,
    var separation: Float = 1.15f,
    var focus: Float = .5f,
    var relief: Float = .45f,
    var haze: Float = .07f,
    var bokeh: Float = .10f,
    var sharpness: Float = .22f,
    var occlusion: Float = .35f,
    var parallax: Float = .72f,
    var edgeProtection: Float = .92f,
    var trailSuppression: Float = .90f,
    var vignette: Float = .06f,
    var exposure: Float = 0f,
    var contrast: Float = .05f,
    var saturation: Float = 1.03f,
    var style: String = "vivid",
    var styleMix: Float = 1f,
    var detectObjects: Boolean = true,
    var invertDepth: Boolean = false,
) {
    fun normalized(): Recipe =
        copy(
            depth = depth.safe(6f, 0f, 6f),
            layers = layers.safe(48f, 2f, 64f),
            separation = separation.safe(1.15f, 0f, 1.5f),
            focus = focus.safe(.5f),
            relief = relief.safe(.45f),
            haze = haze.safe(.07f),
            bokeh = bokeh.safe(.10f),
            sharpness = sharpness.safe(.22f),
            occlusion = occlusion.safe(.35f),
            parallax = parallax.safe(.72f),
            edgeProtection = edgeProtection.safe(.92f),
            trailSuppression = trailSuppression.safe(.90f),
            vignette = vignette.safe(.06f),
            exposure = exposure.safe(0f, -1f, 1f),
            contrast = contrast.safe(.05f, -.5f, .8f),
            saturation = saturation.safe(1.03f, 0f, 2f),
            style = Styles.get(style).id,
            styleMix = styleMix.safe(1f),
        )

    fun json(): JSONObject =
        JSONObject().apply {
            put("version", 1)
            Recipe::class
                .java
                .declaredFields
                .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .forEach {
                    it.isAccessible = true
                    put(it.name, it.get(this@Recipe))
                }
        }

    companion object {
        fun from(j: JSONObject): Recipe {
            val r = Recipe()
            Recipe::class
                .java
                .declaredFields
                .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .forEach {
                    it.isAccessible = true
                    if (j.has(it.name))
                        runCatching {
                            when (it.type) {
                                java.lang.Float.TYPE ->
                                    it.setFloat(r, j.getDouble(it.name).toFloat())
                                java.lang.Integer.TYPE -> it.setInt(r, j.getInt(it.name))
                                java.lang.Boolean.TYPE -> it.setBoolean(r, j.getBoolean(it.name))
                                String::class.java -> it.set(r, j.getString(it.name))
                            }
                        }
                }
            return r.normalized()
        }
    }
}

fun Float.safe(fallback: Float, min: Float = 0f, max: Float = 1f) =
    if (isFinite()) coerceIn(min, max) else fallback

/** Signed PMDD depth space: foreground is negative Z, focus plane is 0, background is positive Z. */
object DepthSpace {
    fun signedZ(depth: Float, focus: Float, gain: Float): Float {
        val d = depth.safe(.5f)
        val f = focus.safe(.5f)
        val g = gain.safe(0f, 0f, 6f)
        return (f - d) * 2f * g
    }
}

data class SceneObject(
    val id: Int,
    val name: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val depth: Float,
)

class DepthMap(val width: Int, val height: Int, val values: FloatArray) {
    init {
        require(width > 0 && height > 0 && values.size == width * height)
    }

    fun sample(u: Float, v: Float): Float {
        val x = u.coerceIn(0f, 1f) * (width - 1)
        val y = v.coerceIn(0f, 1f) * (height - 1)
        val ix = x.toInt()
        val iy = y.toInt()
        val ax = x - ix
        val ay = y - iy
        val jx = min(ix + 1, width - 1)
        val jy = min(iy + 1, height - 1)
        return (values[iy * width + ix] * (1 - ax) + values[iy * width + jx] * ax) * (1 - ay) +
            (values[jy * width + ix] * (1 - ax) + values[jy * width + jx] * ax) * ay
    }

    fun copy() = DepthMap(width, height, values.clone())

    companion object {
        fun normalize(raw: FloatArray, w: Int, h: Int): DepthMap {
            val sorted = raw.filter { it.isFinite() }.sorted()
            require(sorted.isNotEmpty()) { "Das Tiefenmodell lieferte keine gültigen Werte." }
            val lo = sorted[(sorted.size * .02).toInt()]
            val hi = sorted[(sorted.size * .98).toInt().coerceAtMost(sorted.lastIndex)]
            val span = hi - lo
            return DepthMap(
                w,
                h,
                FloatArray(raw.size) {
                    if (span < 1e-6f) .5f else ((raw[it] - lo) / span).safe(.5f)
                },
            )
        }
    }
}
