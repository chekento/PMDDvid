package cloud.kosch.pmddvid

import kotlin.math.*
import org.json.JSONObject

/** All edits are a recipe. Neither this nor the renderer ever writes to the original. */
data class Recipe(
    var depth: Float = 1.25f,
    var separation: Float = .8f,
    var focus: Float = .6f,
    var relief: Float = .35f,
    var haze: Float = .06f,
    var bokeh: Float = .12f,
    var sharpness: Float = .25f,
    var occlusion: Float = .12f,
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
            depth = depth.safe(1.25f, 0f, 2.5f),
            separation = separation.safe(.8f),
            focus = focus.safe(.6f),
            relief = relief.safe(.35f),
            haze = haze.safe(.06f),
            bokeh = bokeh.safe(.12f),
            sharpness = sharpness.safe(.25f),
            occlusion = occlusion.safe(.12f),
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
