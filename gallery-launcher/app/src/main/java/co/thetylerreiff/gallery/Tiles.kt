package co.thetylerreiff.gallery

import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.min

/** Tile look shared by the home grid and the theme picker. */
object Tiles {
    val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

    /** Focus halo: one white line just outside the tile, so it reads on light and dark art alike. */
    fun ring(radius: Float, dp: Float): LayerDrawable {
        val out = -(5 * dp).toInt()
        return LayerDrawable(arrayOf(
            GradientDrawable().apply { cornerRadius = radius + 5 * dp; setColor(Color.TRANSPARENT); setStroke((3 * dp).toInt(), Color.WHITE) },
        )).apply { setLayerInset(0, out, out, out, out); alpha = 0 }
    }

    /** Focused tiles lift toward the viewer and show the halo. */
    fun lift(v: View, ring: Drawable, focused: Boolean, dp: Float) {
        ObjectAnimator.ofInt(ring, "alpha", ring.alpha, if (focused) 235 else 0).setDuration(220).start()
        v.animate().scaleX(if (focused) 1.12f else 1f).scaleY(if (focused) 1.12f else 1f)
            .translationY(if (focused) -6 * dp else 0f)
            .translationZ(if (focused) 14 * dp else 0f)
            .setInterpolator(ease).setDuration(380).start()
    }

    /** Glyph-tile fill from the scene palette, lifted toward white until it's at least as bright as #262A48,
     *  so it reads on dark scenes without picking up a blue cast on neutral or gold ones. */
    fun utilityFill(palette: IntArray): Int {
        val p = palette[2]
        val luma = (0.2126f * Color.red(p) + 0.7152f * Color.green(p) + 0.0722f * Color.blue(p)) / 255f
        val floor = (0.2126f * 0x26 + 0.7152f * 0x2A + 0.0722f * 0x48) / 255f
        if (luma >= floor) return p
        val t = (floor - luma) / (1f - luma)
        fun lift(c: Int) = (c + (255 - c) * t).toInt()
        return Color.rgb(lift(Color.red(p)), lift(Color.green(p)), lift(Color.blue(p)))
    }

    /** Menu card sits a touch lighter than the tiles so it separates from dark scenes. */
    fun menuTint(fill: Int) = Color.rgb(min(255, Color.red(fill) + 14), min(255, Color.green(fill) + 14), min(255, Color.blue(fill) + 16))
}
