package co.thetylerreiff.gallery

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import co.thetylerreiff.gallery.SceneView.Scene

/**
 * The full-screen background: dithered scenes draw in a [SceneView]; GPU scenes (metal, survey) draw in a
 * [MetalView], created the first time one is shown. Same controls either way.
 */
class Backdrop(context: Context) : FrameLayout(context) {

    private val dither = SceneView(context)
    private var metal: MetalView? = null

    init { addView(dither, LayoutParams(-1, -1)) }

    /** Called with the scene's colour ramp (dark → light) so tiles and menus can match it. */
    var onPalette: ((IntArray) -> Unit)? = null
    private var lastPalette: IntArray? = null

    init { dither.onPalette = { lastPalette = it; onPalette?.invoke(it) } }

    /** Sends the current ramp again, e.g. after new tiles were built (GPU scenes only send it on change). */
    fun repaint() { lastPalette?.let { onPalette?.invoke(it) } }

    var hourOverride: Float? = null
        set(value) { field = value; dither.hourOverride = value; metal?.hourOverride = value }

    /** Set by the picker, whose header is wider than the clock. */
    var wideCorner = false
        set(value) { field = value; metal?.wideCorner = value }

    var animating = false
        set(value) { field = value; dither.animating = value && !scene.gpu; metal?.animating = value && scene.gpu }

    var bodyFade: Float
        get() = dither.bodyFade
        set(value) { dither.bodyFade = value }

    var scene: Scene = Scene.RIDGE
        set(value) {
            if (field == value) return
            field = value
            if (value.gpu) {
                val m = metal ?: MetalView(context).also { m ->
                    metal = m
                    m.hourOverride = hourOverride
                    m.wideCorner = wideCorner
                    m.perfLog = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                    addView(m, 0, LayoutParams(-1, -1))
                }
                m.kind = kindOf(value)
                m.visibility = View.VISIBLE
                dither.visibility = View.GONE
                dither.animating = false
                m.animating = animating
                lastPalette = paletteOf(value)
                onPalette?.invoke(lastPalette!!)
            } else {
                dither.scene = value
                dither.visibility = View.VISIBLE
                dither.animating = animating
                metal?.let { it.animating = false; it.visibility = View.GONE }
            }
        }

    /** Pause metal motion briefly while the user navigates (the GPU is shared with the interface). */
    fun quiet(ms: Long = 700) { metal?.quiet(ms) }

    /** Debug: let the GPU draw as fast as it can, to measure headroom. */
    fun uncapped(on: Boolean) { metal?.uncapped = on }

    /** Copies what's showing into [into] for a crossfade; metal scenes live on a GPU surface, so they can't. */
    fun snapshot(into: Bitmap): Boolean {
        if (scene.gpu || dither.width == 0) return false
        dither.draw(Canvas(into))
        return true
    }

    companion object {
        fun kindOf(s: Scene) = when (s) {
            Scene.SURVEY -> SurveyGl.TERRAIN
            Scene.RADAR -> SurveyGl.RADAR
            Scene.GLOBE -> SurveyGl.GLOBE
            Scene.GOLD -> MetalGl.GOLD
            Scene.ALLOY -> MetalGl.ALLOY
            else -> MetalGl.CHROME
        }

        /** Ramps for tinting tiles and menus to match a GPU scene. */
        fun paletteOf(s: Scene): IntArray = ramp(
            if (s == Scene.GOLD) arrayOf("#0E0C09", "#17130D", "#2A2216", "#4D3D22", "#B08A3E", "#F5D98A")
            else if (s == Scene.SURVEY || s == Scene.RADAR || s == Scene.GLOBE) arrayOf("#06080A", "#0B1015", "#121A22", "#22303C", "#6E8396", "#E3ECF3")
            else arrayOf("#121212", "#1A1A1B", "#2A2B2D", "#4A4C50", "#9A9DA3", "#F2F3F5"),
        )

        private fun ramp(hex: Array<String>): IntArray {
            val a = hex.map { Color.parseColor(it) }
            return IntArray(11) { i ->
                val p = i * (a.size - 1) / 10f
                val k = p.toInt().coerceAtMost(a.size - 2)
                val t = p - k
                Color.rgb(
                    (Color.red(a[k]) + (Color.red(a[k + 1]) - Color.red(a[k])) * t).toInt(),
                    (Color.green(a[k]) + (Color.green(a[k + 1]) - Color.green(a[k])) * t).toInt(),
                    (Color.blue(a[k]) + (Color.blue(a[k + 1]) - Color.blue(a[k])) * t).toInt(),
                )
            }
        }
    }
}
