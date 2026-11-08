package co.thetylerreiff.gallery

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import co.thetylerreiff.gallery.SceneView.Scene
import java.util.Calendar
import kotlin.math.max

/**
 * Theme picker. Laid out like the home screen, but each tile is a background: focusing one previews it
 * full screen, OK keeps it. Each [ThemePack] is a row, led by a Rotate tile that cycles the pack.
 */
class ThemesActivity : Activity() {

    private lateinit var apps: Apps
    private lateinit var scene: Backdrop
    private lateinit var fade: ImageView
    private lateinit var title: TextView
    private lateinit var desc: TextView
    private lateinit var scroller: GridScroll
    private lateinit var grid: LinearLayout
    private lateinit var sheet: Sheet
    private val tiles = mutableListOf<View>()
    private val glyphTiles = mutableListOf<View>()
    private val labels = mutableListOf<View>()
    private val packViews = mutableListOf<View>()
    private var previewHour: Float? = null
    private var heldKey: String? = null
    private var snapshot: Bitmap? = null

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dp(v: Int) = dp(v.toFloat()).toInt()

    /** Saved choice with legacy "auto" spelled out as the first pack, so it matches a tile key. */
    private val active get() = Themes.rotatingPack(apps.scene)?.let { "auto:${it.id}" } ?: apps.scene

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = Apps(this)
        val debug = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (intent.hasExtra("hour")) previewHour = intent.getFloatExtra("hour", 0f).takeIf { it >= 0f }

        val root = FrameLayout(this).apply { clipChildren = false }
        scene = Backdrop(this).apply { hourOverride = previewHour; wideCorner = true; onPalette = ::tint }
        root.addView(scene, FrameLayout.LayoutParams(-1, -1))
        fade = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f }
        root.addView(fade, FrameLayout.LayoutParams(-1, -1))

        // Header sits where the clock does on the home screen.
        title = text("sans-serif-light", 72f).apply { letterSpacing = -0.02f; includeFontPadding = false }
        desc = text("sans-serif", 20f).apply { alpha = 0.8f }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("sans-serif-medium", 18f).apply { text = "Themes"; alpha = 0.6f }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(3) })
            addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            addView(desc, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(3); topMargin = dp(6) })
        }
        root.addView(header, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = gridLeft(); topMargin = dp(48)
        })

        // Packs stack like home rows: the first rests at the bottom edge, later ones sit below it.
        val screenH = resources.displayMetrics.heightPixels
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, screenH - dp(REST_GAP + TILE_H + LABEL_H + LABEL_GAP), 0, screenH / 2)
        }
        scroller = GridScroll(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            addView(grid, FrameLayout.LayoutParams(-1, -2))
            // Packs that slide up toward the header fade out instead of running under it.
            setOnScrollChangeListener { _, _, y, _, _ ->
                for (v in packViews) v.alpha = ((v.top - y - header.bottom + dp(8)) / dp(24f)).coerceIn(0f, 1f)
            }
        }
        root.addView(scroller, FrameLayout.LayoutParams(-1, -1))
        sheet = Sheet(root, ::sheetClosed)
        setContentView(root)

        buildPacks()
        // Only now: a GPU scene reports its colours at once, and tinting needs the menu and tiles to exist.
        scene.scene = Themes.current(apps.scene, apps.rotateHours, now())
        scene.repaint()
        val focus = if (debug && intent.hasExtra("focus")) intent.getIntExtra("focus", 0) else tiles.indexOfFirst { it.tag == active }
        // After layout, so a tile in a lower pack can scroll into view.
        scroller.post { tiles.getOrElse(focus) { tiles.first() }.requestFocus() }
        if (debug && intent.getStringExtra("demo") == "themes-rotate") grid.post { showRotate(Themes.all) }
    }

    override fun onResume() { super.onResume(); scene.animating = true }
    override fun onPause() { super.onPause(); scene.animating = false }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) scene.quiet()
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) if (sheet.isOpen) sheet.close() else close()
        return true
    }

    private fun close() {
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun now() = Calendar.getInstance().apply {
        previewHour?.let { set(Calendar.HOUR_OF_DAY, it.toInt()); set(Calendar.MINUTE, ((it % 1f) * 60).toInt()) }
    }

    private val gpuThumbs by lazy {
        val gpuScenes = Themes.packs.flatMap { it.scenes }.filter { it.gpu }.distinct()
        val shots = MetalGl.snapshots(gpuScenes.map(Backdrop::kindOf), dp(TILE_W), dp(TILE_H), previewHour ?: now().let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60f })
        gpuScenes.zip(shots).toMap()
    }

    private fun buildPacks() {
        val rowWidth = dp(COLS * TILE_W + (COLS - 1) * GAP)
        (listOf(Themes.all) + Themes.packs).forEachIndexed { p, pack ->
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
            grid.addView(column, LinearLayout.LayoutParams(rowWidth, -2).apply { if (p > 0) topMargin = dp(PACK_GAP) })
            packViews += column
            column.addView(text("sans-serif-medium", 16f).apply {
                text = pack.title; alpha = 0.9f; labels += this
                setShadowLayer(dp(6f), 0f, dp(1f), Color.argb(170, 0, 0, 0)) // a tighter, firmer shadow: these sit over pale daytime skies too
            },
                LinearLayout.LayoutParams(rowWidth, dp(LABEL_H)))
            // Rotate only means something once a pack has more than one background.
            val items = (if (pack.rotation.size > 1) listOf("auto:${pack.id}") else emptyList()) + pack.scenes.map { it.name }
            items.chunked(COLS).forEachIndexed { r, chunk ->
                val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
                chunk.forEachIndexed { c, key ->
                    val t = tile(pack, key, p)
                    tiles += t
                    row.addView(t, LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)).apply { if (c < chunk.lastIndex) rightMargin = dp(GAP) })
                }
                column.addView(row, LinearLayout.LayoutParams(rowWidth, -2).apply { topMargin = dp(if (r == 0) LABEL_GAP else ROW_GAP) })
            }
        }
    }

    private fun tile(pack: ThemePack, key: String, packIndex: Int): View {
        val radius = dp(12f)
        val sceneOf = Scene.entries.firstOrNull { it.name == key }
        val rounded = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        val content: View = if (sceneOf != null && sceneOf.gpu) {
            // A still from the GPU renderer, made once when the picker opens.
            ImageView(this).apply {
                setImageBitmap(gpuThumbs[sceneOf])
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = rounded; clipToOutline = true
            }
        } else if (sceneOf != null) {
            // A live miniature of the scene at the current time, dithered on a finer grid. Scenes built around one
            // small object are drawn larger than the tile and framed on that object, so the thumbnail isn't mostly black.
            val mini = SceneView(this).apply { scene = sceneOf; cellDp = 1f; calmCorner = false; hourOverride = previewHour }
            val (z, fx, fy) = thumbFraming(sceneOf)
            FrameLayout(this).apply {
                outlineProvider = rounded; clipToOutline = true
                val w = dp(TILE_W * z)
                val h = dp(TILE_H * z)
                addView(mini, FrameLayout.LayoutParams(w.toInt(), h.toInt()))
                mini.translationX = (dp(TILE_W / 2f) - fx * w).coerceIn(dp(TILE_W.toFloat()) - w, 0f)
                mini.translationY = (dp(TILE_H / 2f) - fy * h).coerceIn(dp(TILE_H.toFloat()) - h, 0f)
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ImageView(this@ThemesActivity).apply { setImageResource(if (pack.shuffle) R.drawable.ic_shuffle else R.drawable.ic_rotate) }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(TextView(this@ThemesActivity).apply {
                    text = if (pack.shuffle) "Shuffle" else "Rotate"
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
                    setTextColor(Color.argb(220, 255, 255, 255))
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }
        }
        return FrameLayout(this).apply {
            tag = key
            isFocusable = true
            background = GradientDrawable().apply { cornerRadius = radius; setColor(if (sceneOf != null) Color.TRANSPARENT else Color.rgb(0x26, 0x2A, 0x48)) }
            if (sceneOf == null) glyphTiles += this
            outlineProvider = rounded
            clipChildren = false
            elevation = dp(4f)
            addView(content, FrameLayout.LayoutParams(-1, -1))
            // A faint hairline, so dark scene thumbnails keep their edge against a dark background.
            if (sceneOf != null) addView(View(this@ThemesActivity).apply {
                background = GradientDrawable().apply { cornerRadius = radius; setColor(Color.TRANSPARENT); setStroke(dp(1), Color.argb(31, 255, 255, 255)) }
            }, FrameLayout.LayoutParams(-1, -1))
            if (key == active) addView(checkBadge(), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(6); rightMargin = dp(6)
            })
            val ring = Tiles.ring(radius, dp(1f))
            foreground = ring
            setOnFocusChangeListener { v, focused ->
                if (!focused && key == heldKey) return@setOnFocusChangeListener
                Tiles.lift(v, ring, focused, dp(1f))
                if (focused) { show(pack, key); scrollToPack(packIndex) }
            }
            setOnClickListener {
                if (sceneOf == null) showRotate(pack) else { apps.scene = key; close() }
            }
        }
    }

    /** Thumbnail zoom and the subject's position (fraction of width, of height from the top) for dithered scenes. */
    private fun thumbFraming(s: Scene): Triple<Float, Float, Float> = when (s) {
        Scene.SIGNAL -> Triple(1.35f, 0.68f, 0.46f)
        Scene.BEACON -> Triple(1.5f, 0.70f, 0.42f)
        Scene.ARRAY -> Triple(1.25f, 0.67f, 0.42f)
        Scene.STRIPE -> Triple(1.5f, 0.66f, 0.50f)
        Scene.SIGN -> Triple(1.4f, 0.72f, 0.37f) // keeps a margin above the sign's frame
        else -> Triple(1f, 0.5f, 0.5f)
    }

    /** Marks the background in use: a small white disc with a check, inside the tile's corner. */
    private fun checkBadge() = TextView(this).apply {
        text = "✓"
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
        setTextColor(Color.rgb(0x10, 0x12, 0x1C))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(240, 255, 255, 255)) }
        elevation = dp(2f)
    }

    /** Updates the header and previews the focused background full screen, crossfading from the last one. */
    private fun show(pack: ThemePack, key: String) {
        val sc = Scene.entries.firstOrNull { it.name == key }
        val target = sc ?: Themes.rotating(pack, apps.rotateHours, now())
        if (sc != null) {
            title.text = sc.title
            desc.text = Themes.description(sc)
        } else if (pack.shuffle) {
            title.text = "Shuffle"
            desc.text = "All ${pack.rotation.size} backgrounds in random order, ${Themes.intervalLabel(apps.rotateHours).lowercase()}"
        } else {
            val names = pack.rotation.map { it.title }
            title.text = "Rotate"
            desc.text = "${names.dropLast(1).joinToString(", ")} and ${names.last()}, ${Themes.intervalLabel(apps.rotateHours).lowercase()}"
        }
        if (target == scene.scene) return
        val snap = if (scene.width > 0) snapshot ?: Bitmap.createBitmap(scene.width, scene.height, Bitmap.Config.ARGB_8888).also { snapshot = it } else null
        if (snap != null && scene.snapshot(snap)) { // GPU scenes can't be captured, so they switch without the fade
            fade.setImageBitmap(snap)
            fade.animate().cancel()
            fade.alpha = 1f
            fade.animate().alpha(0f).setInterpolator(Tiles.ease).setDuration(420).start()
        }
        scene.scene = target
    }


    private fun showRotate(pack: ThemePack) {
        val key = "auto:${pack.id}"
        heldKey = key
        grid.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        (labels + desc).forEach { it.animate().alpha(0f).setDuration(160).start() } // the menu covers these
        sheet.show(if (pack.shuffle) "Shuffle everything" else "Rotate ${pack.title}", Themes.intervals.map { h ->
            Sheet.Item(Themes.intervalLabel(h), checked = active == key && apps.rotateHours == h) {
                apps.rotateHours = h
                apps.scene = key
                close()
            }
        }, anchor = tiles.firstOrNull { it.tag == key })
    }

    private fun sheetClosed() {
        val key = heldKey
        heldKey = null
        grid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        desc.animate().alpha(0.8f).setDuration(160).start()
        labels.forEach { it.animate().alpha(0.9f).setDuration(160).start() }
        tiles.firstOrNull { it.tag == key }?.requestFocus()
    }

    private fun scrollToPack(p: Int) {
        val packH = dp(LABEL_H + LABEL_GAP + TILE_H + PACK_GAP)
        // The focused pack takes the resting spot at the bottom; packs above it slide up, clear of the header.
        scroller.smoothScrollTo(0, max(0, p * packH))
    }

    private fun tint(palette: IntArray) {
        val fill = Tiles.utilityFill(palette)
        sheet.tint = Tiles.menuTint(fill)
        for (t in tiles) {
            if (android.os.Build.VERSION.SDK_INT >= 28) { t.outlineSpotShadowColor = Color.BLACK; t.outlineAmbientShadowColor = palette[0] }
            if (t in glyphTiles) (t.background as? GradientDrawable)?.apply { setColor(fill); setStroke(1, Color.argb(31, 255, 255, 255)) }
        }
    }

    private fun gridLeft() = (resources.displayMetrics.widthPixels - dp(COLS * TILE_W + (COLS - 1) * GAP)) / 2

    private fun text(family: String, sizeDp: Float) = TextView(this).apply {
        typeface = Typeface.create(family, Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp)
        setTextColor(Color.WHITE)
        setShadowLayer(dp(10f), 0f, dp(1f), Color.argb(120, 0, 0, 0)) // enough to hold small labels on pale daytime skies
    }

    private companion object {
        const val COLS = 6
        const val TILE_W = 112
        const val TILE_H = 63
        const val GAP = 24
        const val ROW_GAP = 32
        const val REST_GAP = 30
        const val LABEL_H = 22
        const val LABEL_GAP = 26 // clears the focused tile's lift and halo
        const val PACK_GAP = 40
    }
}
