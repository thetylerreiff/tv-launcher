package co.thetylerreiff.gallery

import android.app.Activity
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.content.pm.ApplicationInfo
import java.util.Calendar
import kotlin.math.max

class MainActivity : Activity() {

    private lateinit var apps: Apps
    private lateinit var scene: Backdrop
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var header: View
    private lateinit var dim: View
    private lateinit var scroller: GridScroll
    private lateinit var grid: LinearLayout
    private val tiles = mutableListOf<View>()
    private val utilityTiles = mutableSetOf<View>()
    private lateinit var sheet: Sheet
    private lateinit var hint: TextView

    private var shownKeys: List<String> = emptyList()
    private var focusedKey: String? = null
    private var previewHour: Float? = null
    private var previewScene: String? = null
    private var returningFromApp = false
    private val ease = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private val handler = Handler(Looper.getMainLooper())
    private val minuteTick = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dp(v: Int) = dp(v.toFloat()).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apps = Apps(this)

        val root = FrameLayout(this).apply { clipChildren = false }
        scene = Backdrop(this).apply { onPalette = ::tintDock }
        root.addView(scene, FrameLayout.LayoutParams(-1, -1))

        clock = text("sans-serif-light", 104f).apply { letterSpacing = -0.03f; includeFontPadding = false; fontFeatureSettings = "pnum" }
        date = text("sans-serif", 20f).apply { alpha = 0.8f; setShadowLayer(dp(8f), 0f, dp(1f), Color.argb(110, 0, 0, 0)) } // holds on pale daytime skies
        header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(clock)
            addView(date, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(5); topMargin = -dp(8) })
        }
        root.addView(header, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = gridLeft(); topMargin = dp(36)
        })

        dim = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        root.addView(dim, FrameLayout.LayoutParams(-1, -1))

        // Apple TV-style: the first row of apps rests at the bottom of the screen; pressing Down
        // slides the whole grid up, dimming the scene and fading the clock.
        val screenH = resources.displayMetrics.heightPixels
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, screenH - dp(REST_GAP) - dp(TILE_H), 0, screenH / 2)
        }
        scroller = GridScroll(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            addView(grid, FrameLayout.LayoutParams(-1, -2))
            setOnScrollChangeListener { _, _, y, _, _ ->
                val f = (y / (screenH * 0.35f)).coerceIn(0f, 1f)
                gridFade = f
                applyFade()
            }
        }
        root.addView(scroller, FrameLayout.LayoutParams(-1, -1))

        hint = text("sans-serif-medium", 18f).apply { alpha = 0f; gravity = Gravity.CENTER }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        sheet = Sheet(root, ::sheetClosed)

        setContentView(root)
        readPreview(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshDock()
        handler.post(minuteTick)
        scene.animating = true
        if (returningFromApp) { returningFromApp = false; resetToTop() }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(minuteTick)
        scene.animating = false
    }

    /** Pressing Home while already home returns focus to the first app. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readPreview(intent)
        if (Intent.ACTION_MAIN == intent.action) resetToTop()
    }

    /** Home always lands on the resting top row with the clock showing; focus stays on the last app if it's up there. */
    private fun resetToTop() {
        if (movingKey != null) endMove()
        if (sheet.isOpen) sheet.close(notify = false)
        grid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        scroller.scrollTo(0, 0)
        val i = tiles.indexOfFirst { it.tag == focusedKey }
        tiles.getOrNull(if (i in 0 until COLS) i else 0)?.requestFocus()
    }

    /** Back returns from the grid to the top row; on the top row it does nothing. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) scene.quiet()
        if (handleMoveKey(event)) return true
        if (sheet.isOpen && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) sheet.close()
            return true
        }
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && scroller.scrollY > 0) tiles.firstOrNull()?.requestFocus()
        return true
    }

    private fun updateTime() {
        val now = Calendar.getInstance()
        previewHour?.let { now.set(Calendar.HOUR_OF_DAY, it.toInt()); now.set(Calendar.MINUTE, ((it % 1f) * 60).toInt()) }
        val pattern = if (DateFormat.is24HourFormat(this)) "H:mm" else "h:mm"
        clock.text = DateFormat.format(pattern, now)
        date.text = DateFormat.format("EEEE, MMMM d", now)
        scene.scene = Themes.current(previewScene ?: apps.scene, apps.rotateHours, now)
    }

    /** Debug builds only: `--es scene DUNES --ef hour 21.5` previews a scene at a given time. */
    private fun readPreview(intent: Intent) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        // Previews never touch the saved background choice; "none" ends the preview.
        intent.getStringExtra("scene")?.let { previewScene = it.takeIf { s -> s != "none" } }
        if (intent.hasExtra("hour")) previewHour = intent.getFloatExtra("hour", 0f).takeIf { it >= 0f }
        scene.hourOverride = previewHour
        updateTime()
        // `--ez uncapped true` lets a metal scene draw as fast as the GPU allows (frame rate goes to logcat).
        if (intent.hasExtra("uncapped")) scene.uncapped(intent.getBooleanExtra("uncapped", false))
        // `--es demo menu|move` opens the first tile's menu or move mode (for screenshots; no key presses needed).
        // `--es demo themes --ei focus 2` opens the theme picker with its third tile focused.
        when (intent.getStringExtra("demo")) {
            "themes", "themes-rotate" -> startActivity(Intent(this, ThemesActivity::class.java).putExtras(intent))
            "menu", "move" -> grid.post {
                val app = apps.dock().firstOrNull() ?: return@post
                tiles.firstOrNull()?.requestFocus()
                if (intent.getStringExtra("demo") == "menu") showOptions(app) else startMove(app)
            }
        }
    }

    private fun refreshDock(force: Boolean = false) {
        val list = apps.dock() + apps.settings()
        val keys = list.map { it.key }
        if (!force && keys == shownKeys) return
        shownKeys = keys
        grid.removeAllViews()
        tiles.clear()
        utilityTiles.clear()
        val rowWidth = if (list.size <= COLS) -2 else dp(COLS * TILE_W + (COLS - 1) * GAP)
        list.chunked(COLS).forEachIndexed { r, rowApps ->
            val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
            rowApps.forEachIndexed { c, app ->
                val index = r * COLS + c
                val t = tile(app, isSettings = index == list.lastIndex, row = r)
                tiles += t
                row.addView(t, LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)).apply {
                    if (c < rowApps.lastIndex) rightMargin = dp(GAP)
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(rowWidth, -2).apply { if (r > 0) topMargin = dp(ROW_GAP) })
        }
        val target = shownKeys.indexOf(focusedKey).takeIf { it >= 0 } ?: 0
        tiles.getOrNull(target)?.requestFocus()
        scene.repaint() // tint the new tiles to the current background
    }

    /** Top row rests at the bottom of the screen; any other focused row is brought to just below center. */
    private fun scrollToRow(r: Int) {
        val rows = grid.childCount
        val block = rows * dp(TILE_H) + (rows - 1) * dp(ROW_GAP)
        val y = when {
            r == 0 -> 0
            block <= scroller.height * 0.75f -> grid.paddingTop - (scroller.height - block) / 2 // whole grid fits: center it
            else -> grid.paddingTop + r * dp(TILE_H + ROW_GAP) - (scroller.height * 0.42f).toInt()
        }
        scroller.smoothScrollTo(0, max(0, y))
    }

    /** Left edge of the 6-column grid; the clock lines up with it. */
    private fun gridLeft() = (resources.displayMetrics.widthPixels - dp(COLS * TILE_W + (COLS - 1) * GAP)) / 2

    private var gridFade = 0f

    /** Scene dims (in its own darkest color) as the grid rises; move mode adds a light dim. */
    private fun applyFade() {
        val f = gridFade
        dim.alpha = max(0.62f * f, if (movingKey != null) 0.22f else 0f)
        scene.bodyFade = f // the sun or moon recedes so it can't ghost between grid tiles
        if (movingKey == null) header.alpha = 1f - f
    }

    private fun tile(app: DockApp, isSettings: Boolean, row: Int): View {
        val radius = dp(12f)
        // Utility tiles (Settings, and known system utilities like TCL's Quick panel) share one style:
        // scene-tinted fill, glyph and label. Everything else shows its own 16:9 TV banner.
        val utility = isSettings || app.key in UTILITY_PKGS
        val banner = app.banner.takeUnless { utility }
        val content: View = if (banner != null) {
            ImageView(this).apply {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
                clipToOutline = true
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(ImageView(this@MainActivity).apply {
                    setImageDrawable(app.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(TextView(this@MainActivity).apply {
                    text = app.label
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f)
                    setTextColor(Color.argb(220, 255, 255, 255))
                    maxLines = 1
                }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            }
        }
        return FrameLayout(this).apply {
            tag = app.key
            isFocusable = true
            // Banner tiles have no fill of their own, so nothing bleeds at the rounded edge of dark banners.
            background = GradientDrawable().apply { cornerRadius = radius; setColor(if (banner != null) Color.TRANSPARENT else Color.rgb(0x26, 0x2A, 0x48)) }
            if (utility) utilityTiles += this
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipChildren = false // lets the focus halo draw just outside the tile
            elevation = dp(4f)
            addView(content, FrameLayout.LayoutParams(-1, -1))

            val ring = Tiles.ring(radius, dp(1f))
            foreground = ring
            setOnFocusChangeListener { v, focused ->
                if (!focused && app.key == heldKey) return@setOnFocusChangeListener // keep the lifted look under its menu
                Tiles.lift(v, ring, focused, dp(1f))
                if (focused) {
                    focusedKey = app.key
                    scrollToRow(row)
                }
            }
            setOnClickListener { launch(app) }
            setOnLongClickListener { if (isSettings) showHomeMenu() else showOptions(app); true }
        }
    }

    /** Settings tile and tile shadows take their color from the scene, so the dock belongs to it. */
    private fun tintDock(palette: IntArray) {
        val shadow = palette[0]
        dim.setBackgroundColor(palette[0])
        val fill = Tiles.utilityFill(palette)
        sheet.tint = Tiles.menuTint(fill)
        for (tile in tiles) {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                tile.outlineSpotShadowColor = Color.BLACK
                tile.outlineAmbientShadowColor = shadow
            }
            if (tile in utilityTiles) (tile.background as? GradientDrawable)?.apply {
                setColor(fill)
                setStroke(1, Color.argb(31, 255, 255, 255))
            }
        }
    }

    private fun launch(app: DockApp) {
        try {
            startActivity(app.intent)
            returningFromApp = true
        } catch (_: ActivityNotFoundException) {
            refreshDock(force = true)
        }
    }

    // ---- menus ----

    /** The tile whose menu is open; it keeps its focused look while the menu has focus. */
    private var heldKey: String? = null

    private fun openSheet(title: String, items: List<Sheet.Item>, message: String? = null) {
        heldKey = focusedKey
        grid.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        sheet.show(title, items, message, anchor = tiles.firstOrNull { it.tag == focusedKey })
    }

    private fun sheetClosed() {
        heldKey = null
        grid.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        tiles.firstOrNull { it.tag == focusedKey }?.requestFocus()
    }

    private fun showOptions(app: DockApp) = openSheet(app.label, listOf(
        Sheet.Item("Move") { startMove(app) },
        Sheet.Item("Hide") { apps.hidden = apps.hidden + app.key; refreshDock(force = true) },
    ))

    private fun showHomeMenu() = openSheet("Home", listOf(
        Sheet.Item("Themes") { openThemes() },
        Sheet.Item("Hidden apps") { showHidden() },
    ))

    private fun openThemes() {
        startActivity(Intent(this, ThemesActivity::class.java).apply { previewHour?.let { putExtra("hour", it) } })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun showHidden() {
        val hidden = apps.hidden.toList()
        if (hidden.isEmpty()) {
            openSheet("Hidden apps", listOf(Sheet.Item("Done") {}), "Hold OK on any app to move or hide it.")
        } else {
            openSheet("Show again", hidden.map { key ->
                Sheet.Item(apps.label(key)) { apps.hidden = apps.hidden - key; refreshDock(force = true) }
            })
        }
    }

    // ---- move mode: the held tile lifts and wiggles; arrows move it anywhere in the grid ----

    private var movingKey: String? = null
    private var wiggle: ObjectAnimator? = null

    private fun startMove(app: DockApp) {
        movingKey = app.key
        hint.text = "Use the arrows to move, then press OK."
        hint.animate().alpha(0.85f).setDuration(200).start()
        header.animate().alpha(0f).setDuration(200).start()
        applyFade()
        liftMovingTile()
    }

    private fun liftMovingTile() {
        val t = tiles.firstOrNull { it.tag == movingKey } ?: return
        t.requestFocus()
        t.animate().cancel()
        t.foreground?.alpha = 0 // moving looks different from focus: no ring, just lift and wiggle
        t.scaleX = 1.16f; t.scaleY = 1.16f
        t.translationY = -dp(8f); t.translationZ = dp(18f)
        t.post { // keep the hint just above the tile being moved
            val at = IntArray(2).also { t.getLocationOnScreen(it) }
            hint.translationY = at[1] - hint.height - dp(26f)
        }
        wiggle?.cancel()
        wiggle = ObjectAnimator.ofFloat(t, View.ROTATION, -1.1f, 1.1f).apply {
            duration = 150
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun endMove() {
        wiggle?.cancel()
        wiggle = null
        val t = tiles.firstOrNull { it.tag == movingKey }
        movingKey = null
        t?.foreground?.alpha = 235
        hint.animate().alpha(0f).setDuration(200).start()
        header.animate().alpha(if (scroller.scrollY > 0) 0f else 1f).setDuration(200).start()
        applyFade()
        t?.animate()?.rotation(0f)?.scaleX(1.10f)?.scaleY(1.10f)?.translationY(-dp(6f))?.translationZ(dp(7f))
            ?.setInterpolator(ease)?.setDuration(300)?.start()
    }

    /** Returns true when the key was handled by move mode. */
    private fun handleMoveKey(event: KeyEvent): Boolean {
        val key = movingKey ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            val by = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                KeyEvent.KEYCODE_DPAD_UP -> -COLS
                KeyEvent.KEYCODE_DPAD_DOWN -> COLS
                else -> 0
            }
            if (by != 0) {
                apps.move(key, by)
                refreshDock(force = true)
                liftMovingTile()
            }
        } else if (event.action == KeyEvent.ACTION_UP && event.keyCode in DROP_KEYS) {
            endMove()
        }
        return true
    }

    private fun text(family: String, sizeDp: Float) = TextView(this).apply {
        typeface = Typeface.create(family, Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp)
        setTextColor(Color.WHITE)
        setShadowLayer(dp(16f), 0f, dp(1f), Color.argb(50, 0, 0, 0))
        layoutParams = ViewGroup.LayoutParams(-2, -2)
    }

    private companion object {
        const val COLS = 6
        const val TILE_W = 112
        const val TILE_H = 63
        const val GAP = 24
        const val ROW_GAP = 32
        const val REST_GAP = 30 // top row's distance from the bottom edge; ≤ ROW_GAP keeps row 2 off-screen
        /** System utilities shown as glyph tiles instead of their banner art. */
        val UTILITY_PKGS = setOf("com.tcl.suspension", "com.tcl.dashboard") // TCL Quick panel entries
        val DROP_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_A)
    }
}

/**
 * We place rows ourselves (see scrollToRow). Stop ScrollView's own auto-scroll, and leave arrow keys to
 * normal focus search: ScrollView's handling skips tiles that are off-screen, like the hidden second row.
 */
internal class GridScroll(context: Context) : ScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0
    override fun executeKeyEvent(event: KeyEvent): Boolean = false
}
