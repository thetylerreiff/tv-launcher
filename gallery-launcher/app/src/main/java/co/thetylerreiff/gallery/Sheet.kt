package co.thetylerreiff.gallery

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The launcher's own menu: a scene-tinted card over a dimmed screen. Replaces Android's
 * stock dialogs so menus match the rest of the home screen.
 */
class Sheet(private val host: FrameLayout, private val onClose: () -> Unit) {

    class Item(val label: String, val checked: Boolean = false, val action: () -> Unit)

    /** Card fill; updated from the scene palette so the menu belongs to the current background. */
    var tint: Int = Color.rgb(0x26, 0x2A, 0x48)

    val isOpen get() = overlay != null
    private var overlay: View? = null
    private val ctx = host.context
    private val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private fun dp(v: Float) = v * ctx.resources.displayMetrics.density
    private fun dp(v: Int) = dp(v.toFloat()).toInt()

    /** Shows the menu just above [anchor] (centered under it horizontally), or mid-screen when there is none. */
    fun show(title: String, items: List<Item>, message: String? = null, anchor: View? = null) {
        close(notify = false)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(18), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22f)
                setColor(Color.argb(244, Color.red(tint), Color.green(tint), Color.blue(tint)))
                setStroke(dp(1), Color.argb(40, 255, 255, 255))
            }
            elevation = dp(24f)
            addView(label(title, 15f, "sans-serif-medium", 0.6f), LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = dp(18); rightMargin = dp(18); bottomMargin = dp(if (message == null) 8 else 4)
            })
            if (message != null) addView(label(message, 17f, "sans-serif", 0.85f), LinearLayout.LayoutParams(-1, -2).apply {
                leftMargin = dp(18); rightMargin = dp(18); bottomMargin = dp(12)
            })
            items.forEach { addView(row(it), LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(2) }) }
        }
        val scrim = FrameLayout(ctx).apply {
            // Anchored menus leave the screen undimmed so the held tile stays bright; centered ones dim lightly.
            setBackgroundColor(if (anchor == null) Color.argb(60, 0, 0, 0) else Color.TRANSPARENT)
            isClickable = true
            alpha = 0f
            addView(card, placeCard(card, anchor))
        }
        host.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        overlay = scrim
        scrim.animate().alpha(1f).setDuration(180).start()
        card.pivotY = card.measuredHeight.toFloat()
        card.pivotX = 0f
        card.scaleX = 0.96f; card.scaleY = 0.96f
        card.animate().scaleX(1f).scaleY(1f).setInterpolator(ease).setDuration(260).start()
        (card.getChildAt(card.childCount - items.size + items.indexOfFirst { it.checked }.coerceAtLeast(0)))?.requestFocus()
    }

    private fun placeCard(card: View, anchor: View?): FrameLayout.LayoutParams {
        val w = dp(CARD_W)
        if (anchor == null) return FrameLayout.LayoutParams(w, -2, Gravity.CENTER)
        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val hostAt = IntArray(2).also { host.getLocationOnScreen(it) }
        val ax = at[0] - hostAt[0]
        val ay = at[1] - hostAt[1]
        val margin = dp(48)
        return FrameLayout.LayoutParams(w, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = ax.coerceIn(margin, host.width - w - margin) // left edges line up with the held tile
            topMargin = (ay - card.measuredHeight - dp(12)).coerceAtLeast(margin)
        }
    }

    fun close(notify: Boolean = true) {
        val o = overlay ?: return
        overlay = null
        o.animate().alpha(0f).setDuration(140).withEndAction { host.removeView(o) }.start()
        if (notify) onClose()
    }

    private fun row(item: Item) = TextView(ctx).apply {
        text = if (item.checked) "${item.label}   ✓" else item.label
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), 0, dp(18), 0)
        isFocusable = true
        val bg = GradientDrawable().apply { cornerRadius = dp(13f); setColor(Color.TRANSPARENT) }
        background = bg
        setTextColor(Color.WHITE)
        setOnFocusChangeListener { _, focused ->
            bg.setColor(if (focused) Color.argb(235, 255, 255, 255) else Color.TRANSPARENT)
            setTextColor(if (focused) Color.rgb(0x10, 0x12, 0x1C) else Color.WHITE)
        }
        setOnClickListener { close(); item.action() }
    }

    private fun label(s: String, size: Float, family: String, a: Float) = TextView(ctx).apply {
        text = s
        typeface = Typeface.create(family, Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        setTextColor(Color.WHITE)
        alpha = a
    }

    private companion object { const val CARD_W = 250 }
}
