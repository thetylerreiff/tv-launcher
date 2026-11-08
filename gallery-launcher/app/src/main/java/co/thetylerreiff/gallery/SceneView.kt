package co.thetylerreiff.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Dithered home-screen backdrop.
 *
 * Each [Scene] is a field of tones (0 = darkest, 1 = lightest) computed at low resolution, rebuilt
 * every half hour so the sun or moon can move. Each redraw maps the tones onto the current
 * time-of-day ramp with an 8×8 Bayer ordered dither, then scales up unfiltered so cells stay crisp.
 *
 * Animation is subtle and cheap: only cells that move (stars, water glints, mist, halos) are
 * re-dithered, about 12 times a second, and only while [animating] is on.
 */
class SceneView(context: Context) : View(context) {

    /** [gpu] scenes are drawn on the GPU ([MetalGl], [SurveyGl]); the rest are dithered here. */
    enum class Scene(val title: String, val gpu: Boolean = false) {
        RIDGE("Ridge"), DUNES("Dunes"), SEA("Sea"), PLANET("Planet"), SKY("Sky"),
        SIGNAL("Orbit"), BEACON("Beacon"), ARRAY("Array"), STRIPE("Stripe"),
        NEON("Skyline"), HIGHWAY("Highway"), SIGN("Sign"), WINDOW("Window"),
        SURVEY("Terrain", gpu = true), RADAR("Radar", gpu = true), GLOBE("Globe", gpu = true),
        CHROME("Chrome", gpu = true), GOLD("Gold", gpu = true), ALLOY("Alloy", gpu = true),
    }

    var scene = Scene.RIDGE
        set(value) {
            if (field != value) { field = value; builtFor = "" }
            invalidate()
        }

    /** Run the subtle background motion; turn off while the launcher isn't visible. */
    var animating = false
        set(value) { field = value; if (value) invalidate() else removeCallbacks(nextFrame) }

    /** Called on each redraw with the current ramp (dark → light) so other views can match it. */
    var onPalette: ((IntArray) -> Unit)? = null

    /** Fixed hour (0–24) for previews; null follows the clock. */
    var hourOverride: Float? = null

    /** Dither cell size; thumbnails use a finer cell than the full-screen backdrop. */
    var cellDp = 2f

    /** Darken the top-left corner behind the clock; off for thumbnails. */
    var calmCorner = true

    // ---- time-of-day palettes: six anchor colors dark → light, expanded to RAMP steps ----
    private class Key(val hour: Float, val ramp: IntArray, val stars: Float, val accent: Int = Color.WHITE)

    private fun ramp(vararg hex: String): IntArray {
        val anchors = IntArray(hex.size) { Color.parseColor(hex[it]) }
        return IntArray(RAMP) { i ->
            val p = i * (anchors.size - 1) / (RAMP - 1f)
            val k = p.toInt().coerceAtMost(anchors.size - 2)
            lerp(anchors[k], anchors[k + 1], p - k)
        }
    }
    private val night = ramp("#04060F", "#0A1028", "#141D45", "#22306A", "#4A5A9C", "#D2DAF4")
    private val dawn = ramp("#141A36", "#262F5C", "#4A568E", "#8088BC", "#CDB2CC", "#F2DCE4")
    private val day = ramp("#1C2A4A", "#2C3B66", "#3D62B0", "#4F8EE0", "#8FBCEF", "#E4F0FB")
    private val dusk = ramp("#0B0A1E", "#1B1840", "#3C3A6E", "#62588E", "#B892B8", "#E4C8D8")
    private val keys = listOf(
        Key(0f, night, 1f), Key(5f, night, 1f), Key(7f, dawn, 0f), Key(10f, day, 0f),
        Key(16.5f, day, 0f), Key(19f, dusk, 0f), Key(21f, night, 1f), Key(24f, night, 1f),
    )

    /** Tech scenes keep one look all day, a little lifted in daylight; each has one accent color. */
    private fun cycle(night: IntArray, day: IntArray, accentNight: String, accentDay: String): List<Key> {
        val an = Color.parseColor(accentNight)
        val ad = Color.parseColor(accentDay)
        return listOf(Key(0f, night, 0f, an), Key(6f, night, 0f, an), Key(9f, day, 0f, ad),
            Key(17f, day, 0f, ad), Key(20f, night, 0f, an), Key(24f, night, 0f, an))
    }
    private val signalKeys = cycle(
        ramp("#060706", "#0B0E0A", "#151C10", "#2A3A12", "#6E9A0E", "#D4FF2A"),
        ramp("#0A0C0A", "#121610", "#1E2914", "#3A5016", "#8CC413", "#E2FF66"), "#FF2E88", "#FF4F9A")
    private val neonKeys = cycle(
        ramp("#05030B", "#120A26", "#2B0F45", "#6A1466", "#E2347E", "#FFE14D"),
        ramp("#0D0A16", "#1D1736", "#3A2454", "#783A70", "#E06A8C", "#FFE89A"), "#2EF0FF", "#2EE0F0")
    private fun keysFor(s: Scene) = when (s) {
        Scene.SIGNAL, Scene.BEACON, Scene.ARRAY, Scene.STRIPE -> signalKeys
        Scene.NEON, Scene.HIGHWAY, Scene.SIGN, Scene.WINDOW -> neonKeys
        else -> keys
    }

    private val bayer = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21,
    ).map { (it + 0.5f) / 64f }.toFloatArray()

    private var bw = 0
    private var bh = 0
    private var builtFor = ""
    private var tones = FloatArray(0)
    private var starAt = FloatArray(0) // per cell: star shows once star visibility exceeds this; 2 = never
    private var pixels = IntArray(0)
    private var paletteMinute = -1
    private val nextFrame = Runnable { invalidate() }
    private var starVis = 0f

    // Animated cells: tone += amp * (0.5 + 0.5 * sin(phase + seconds * speed)). Stars ride along with amp 0.
    private var animCells = IntArray(0)
    private var bodyCells = IntArray(0)
    private var bodyAmt = FloatArray(0)
    private var drawnFade = 0f

    /** 0 = sun/moon fully shown, 1 = removed (used while the app grid is up). */
    var bodyFade = 0f
        set(value) { if (field != value) { field = value; invalidate() } }
    private var animAmp = FloatArray(0)
    private var animPhase = FloatArray(0)
    private var animSpeed = FloatArray(0)
    private var animBayer = FloatArray(0) // each animated cell's dither threshold, precomputed
    private val sinTable = FloatArray(1024) { sin(it * 2 * PI / 1024).toFloat() }
    private fun fastSin(x: Double): Float = sinTable[((x * (1024 / (2 * PI))).toLong() and 1023L).toInt()]
    private var bitmap: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val fadePaint = Paint().apply { isFilterBitmap = false }
    private var previous: Bitmap? = null
    private var fadeFrom = 0L
    private val dst = Rect()
    private val palette = IntArray(RAMP)

    private var levels = IntArray(0) // each cell's ramp step, so unchanged cells are skipped
    private var accent = Color.WHITE

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cell = max(2, (cellDp * resources.displayMetrics.density).roundToInt())
        bw = max(1, w / cell)
        bh = max(1, h / cell)
        bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        previous = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888) // the last frame, for the half-hourly crossfade
        pixels = IntArray(bw * bh)
        levels = IntArray(bw * bh)
        builtFor = ""
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val hour = hourOverride ?: Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60f }
        val key = "$scene/${(hour * 2).toInt()}/$bw"
        if (key != builtFor) {
            // The half-hourly rebuild moves the sun or moon a little; crossfade from the last frame rather than jump.
            if (builtFor.startsWith("$scene/") && builtFor.endsWith("/$bw")) {
                previous?.setPixels(pixels, 0, bw, 0, 0, bw, bh)
                fadeFrom = SystemClock.uptimeMillis()
            }
            buildTones(hour); builtFor = key; paletteMinute = -1
        }
        val top = RAMP - 1

        val minute = (hour * 60).toInt()
        if (minute != paletteMinute) {
            paletteMinute = minute
            val keys = keysFor(scene)
            val i = keys.indexOfLast { it.hour <= hour }.coerceIn(0, keys.size - 2)
            val a = keys[i]
            val b = keys[i + 1]
            val t = ((hour - a.hour) / (b.hour - a.hour)).coerceIn(0f, 1f)
            for (k in palette.indices) palette[k] = lerp(a.ramp[k], b.ramp[k], t)
            accent = lerp(a.accent, b.accent, t)
            starVis = a.stars + (b.stars - a.stars) * t
            onPalette?.invoke(palette)
            for (c in tones.indices) {
                levels[c] = level(c % bw, c / bw, tones[c], starAt[c] < starVis)
                pixels[c] = color(levels[c])
            }
        }

        run { // animated cells are drawn even when still, so thumbnails show a frame of the motion
            val sec = SystemClock.uptimeMillis() / 1000.0 // Double, never wrapped, so no phase jumps
            for (j in animCells.indices) {
                val c = animCells[j]
                val wave = 0.5f + 0.5f * fastSin(animPhase[j] + sec * animSpeed[j])
                val sa = starAt[c]
                val tone = tones[c]
                set(c, when {
                    sa < 2f && sa < starVis * (0.55f + 0.45f * wave) -> top
                    tone >= 1.5f -> if (wave < 0.12f) 2 else ACCENT // accent lights flicker off now and then
                    animAmp[j] < 0f -> { // a failing tube: lit, with short dropouts where two slow waves peak together
                        val dip = fastSin(animPhase[j] + sec * animSpeed[j]) * fastSin(1.3 + sec * animSpeed[j] * 0.37) > 0.9f
                        ((tone + (if (dip) animAmp[j] else 0f)) * top + animBayer[j]).toInt().coerceIn(0, top)
                    }
                    else -> ((tone + animAmp[j] * wave) * top + animBayer[j]).toInt().coerceIn(0, top)
                })
            }
        }
        if (animating) { // one pending frame at most, however many times we were invalidated
            removeCallbacks(nextFrame)
            postDelayed(nextFrame, FRAME_MS)
        }

        if (bodyFade > 0f || drawnFade > 0f) { // re-dither the sun/moon cells with their share faded out
            for (j in bodyCells.indices) {
                val c = bodyCells[j]
                val tone = tones[c] - bodyAmt[j] * bodyFade
                set(c, (tone * top + bayer[((c / bw) and 7) * 8 + ((c % bw) and 7)]).toInt().coerceIn(0, top))
            }
            drawnFade = bodyFade
        }

        bmp.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bmp, null, dst, paint)
        val fade = 1f - (SystemClock.uptimeMillis() - fadeFrom) / FADE_MS
        if (fade > 0f) previous?.let {
            fadePaint.alpha = (255 * fade).toInt()
            canvas.drawBitmap(it, null, dst, fadePaint)
            postInvalidateOnAnimation()
        }
        drawOverlay(canvas)
    }

    private fun color(level: Int) = if (level >= ACCENT) accent else palette[level]

    private fun set(c: Int, level: Int) {
        if (levels[c] == level) return
        levels[c] = level
        pixels[c] = color(level)
    }

    /** Tone → ramp step (Bayer dithered); tones of 2 are the scene's accent color. */
    private fun level(x: Int, y: Int, tone: Float, star: Boolean): Int {
        if (star) return RAMP - 1
        if (tone >= 1.5f) return ACCENT
        return (tone * (RAMP - 1) + bayer[(y and 7) * 8 + (x and 7)]).toInt().coerceIn(0, RAMP - 1)
    }

    // ---- scenes ----

    private var sky = false // set by each scene fn: true when the cell is open sky (eligible for stars)
    private var bodyShare = 0f // set by withBody: how much of this cell's tone is the sun/moon
    private var anAmp = 0f // set by each scene fn: this cell's animation amplitude, phase and speed
    private var anPhase = 0f
    private var anSpeed = 0f
    private var aspect = 16f / 9f
    private var reflectAmp = 0.14f
    private var daylight = 0f // 0 at night, 1 in full day: the clock corner darkens more against a pale sky
    private var cornerU = 1f // the clock corner's shape for this build (see buildTones)
    private var cornerV = 1f
    private var cornerK = 1f
    private var sunV = 0.5f // vertical position of the sun (day) or moon (night), 0 = top

    /** Sun arcs from the horizon at 6:00 to high at noon and back by 18:00; the moon does the same overnight. */
    private fun bodyHeight(hour: Float): Float {
        val day = hour in 6f..18f
        val p = if (day) (hour - 6f) / 12f else ((hour + 6f) % 24f) / 12f
        return 0.58f - (if (day) 0.36f else 0.30f) * sin(PI.toFloat() * p)
    }

    private fun buildTones(hour: Float) {
        val n = bw * bh
        if (tones.size != n) { tones = FloatArray(n); starAt = FloatArray(n) }
        aspect = width.toFloat() / max(1, height)
        sunV = bodyHeight(hour)
        daylight = MetalGl.day(hour)
        cornerU = 1f / (0.42f + 0.20f * daylight)
        cornerV = 1f / (0.50f + 0.20f * daylight)
        cornerK = 1f / (1f - 0.4f * daylight)
        reflectAmp = when { hour < 6f || hour >= 20f -> 0.26f; hour in 9f..16f -> 0.11f; else -> 0.19f }
        prepareFields()
        val rnd = Random(11)
        val cells = IntArray(n)
        val amps = FloatArray(n)
        val phases = FloatArray(n)
        val speeds = FloatArray(n)
        var count = 0
        val bCells = IntArray(n)
        val bAmts = FloatArray(n)
        var bCount = 0
        for (y in 0 until bh) {
            val v = (y + 0.5f) / bh
            for (x in 0 until bw) {
                val u = (x + 0.5f) / bw
                sky = false
                anAmp = 0f
                bodyShare = 0f
                var t = when (scene) {
                    Scene.RIDGE -> ridge(u, v)
                    Scene.DUNES -> dunes(u, v)
                    Scene.SEA -> sea(u, v)
                    Scene.PLANET -> planet(u, v)
                    Scene.SKY -> skyBand(u, v)
                    Scene.SIGNAL -> signal(u, v)
                    Scene.BEACON -> beacon(u, v)
                    Scene.ARRAY -> array(x, y)
                    Scene.STRIPE -> stripe(u, v)
                    Scene.NEON -> neon(u, v, x, y)
                    Scene.HIGHWAY -> highway(u, v, x, y)
                    Scene.SIGN -> sign(u, v, x, y)
                    Scene.WINDOW -> window(u, v, x, y)
                    Scene.SURVEY, Scene.RADAR, Scene.GLOBE, Scene.CHROME, Scene.GOLD, Scene.ALLOY -> 0f // drawn on the GPU, never here
                }
                // Keep the clock corner calm: cap brightness and darken slightly behind the time and date.
                val w = if (calmCorner) smooth((1f - hypot(u * cornerU, v * cornerV)) * cornerK) else 0f
                if (t < 1f) t = min(t, 1f - (0.3f + 0.12f * daylight) * w) - (0.1f + 0.11f * daylight) * w // (sun and moon discs, at 1, stay whole) // wider and deeper in daylight, when the sky is pale
                val c = y * bw + x
                tones[c] = if (t >= 1.5f) 2f else t.coerceIn(0f, 1f)
                starAt[c] = if (sky && w < 0.02f && v < 0.5f && rnd.nextFloat() < 0.003f) 0.1f + rnd.nextFloat() * 0.85f else 2f
                if (starAt[c] < 2f) { // stars twinkle, each at its own pace
                    anAmp = 0f; anPhase = rnd.nextFloat() * 6.28f; anSpeed = 0.8f + rnd.nextFloat() * 1.6f
                }
                if (bodyShare > 0.004f) { bCells[bCount] = c; bAmts[bCount] = bodyShare * (1f - w); bCount++ }
                if (abs(anAmp) > 0.004f || starAt[c] < 2f) { // negative amp marks a dropout cell
                    cells[count] = c; amps[count] = anAmp * (1f - w); phases[count] = anPhase; speeds[count] = anSpeed
                    count++
                }
            }
        }
        bodyCells = bCells.copyOf(bCount)
        bodyAmt = bAmts.copyOf(bCount)
        animCells = cells.copyOf(count)
        animAmp = amps.copyOf(count)
        animPhase = phases.copyOf(count)
        animSpeed = speeds.copyOf(count)
        // Array's dots ripple as whole dots (one level each), not as dithered speckle.
        animBayer = FloatArray(count) { val c = animCells[it]; if (scene == Scene.ARRAY) 0.5f else bayer[((c / bw) and 7) * 8 + ((c % bw) and 7)] }
    }

    private fun animate(amp: Float, phase: Float, speed: Float) {
        anAmp = amp; anPhase = phase; anSpeed = speed
    }

    private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }

    private fun skyBand(u: Float, v: Float): Float {
        sky = true
        val above = (v - 0.62f) / 0.45f
        val below = (v - 0.62f) / 0.22f
        val q = if (v < 0.62f) above else below
        val m = (v - 0.60f) / 0.08f
        val m2 = (v - 0.44f) / 0.035f
        var band2 = 0f
        if (m2 * m2 < 9f) { // the second, thinner band: drifts the other way, more slowly
            band2 = 0.07f * exp(-m2 * m2) * (0.6f + 0.4f * sin(u * 5.1f + 1.3f))
            animate(0.05f * exp(-m2 * m2), u * 9.4f - sin(v * 24f), -0.07f)
        } else if (m * m < 9f) animate(0.06f * exp(-m * m), u * 12.6f + sin(v * 30f), 0.10f)
        val s = 0.22f + 0.62f * exp(-q * q) + band2
        return withBody(s, u, v, 0.70f, 0.05f, fall = 0.26f, glow = 0.09f)
    }

    private fun skyTone(v: Float, top: Float, bottom: Float, horizon: Float): Float {
        sky = true
        return top + (bottom - top) * (v / horizon).coerceIn(0f, 1f)
    }

    /** Sky tone [s] with the sun/moon added; records how much the body contributed (for [bodyFade]). */
    private fun withBody(s: Float, u: Float, v: Float, cx: Float, r: Float, fall: Float = 0.12f, glow: Float = 0.18f): Float {
        val b = body(u, v, cx, r, fall, glow) ?: return s
        val t = if (b == 1f) 1f else s + b * (1.2f - s)
        bodyShare = t - s
        return t
    }

    /** Sun/moon disc with a soft halo, or null when (u, v) is outside the halo's reach. */
    private fun body(u: Float, v: Float, cx: Float, r: Float, fall: Float, glow: Float): Float? {
        val d = hypot((u - cx) * aspect, v - sunV)
        if (d < r) return 1f
        val q = (d - r) / fall
        val halo = glow * exp(-q * q)
        if (halo <= 0.005f) return null
        return halo // still: animating the halo re-dithers it into a crawling ring
    }

    // Ridge outlines: seeded midpoint displacement gives irregular, asymmetric peaks.
    private class Ridge(val heightFrac: Float, val base: Float, val outline: FloatArray)
    private val ridgeLayers = listOf(
        Ridge(0.48f, 0.42f, fractal(3, 0.38f)),
        Ridge(0.37f, 0.27f, fractal(5, 0.45f)),
        Ridge(0.23f, 0.10f, fractal(9, 0.32f)),
    )

    private fun fractal(seed: Int, rough: Float): FloatArray {
        val n = 128
        val r = Random(seed)
        val y = FloatArray(n + 1)
        y[0] = 0.3f + r.nextFloat() * 0.4f
        y[n] = 0.3f + r.nextFloat() * 0.4f
        var step = n
        var amp = rough
        while (step > 1) {
            for (i in step / 2 until n step step) y[i] = (y[i - step / 2] + y[i + step / 2]) / 2 + (r.nextFloat() - 0.5f) * amp
            step /= 2
            amp *= 0.55f
        }
        // Normalize so the highest peak reaches 8% and the lowest saddle 70% down the ridge's box.
        val lo = y.min()
        val span = (y.max() - lo).coerceAtLeast(1e-3f)
        return FloatArray(y.size) { 0.08f + (y[it] - lo) / span * 0.62f }
    }

    private fun ridge(u: Float, v: Float): Float {
        for (layer in ridgeLayers.asReversed()) {
            val pts = layer.outline
            val p = u * (pts.size - 1)
            val k = p.toInt().coerceIn(0, pts.size - 2)
            val outline = (pts[k] + (pts[k + 1] - pts[k]) * (p - k)).coerceIn(0f, 1f)
            val top = 1f - layer.heightFrac + outline * layer.heightFrac
            if (v >= top) return layer.base + 0.05f * (1f - (v - top) / (1f - top))
        }
        val s = skyTone(v, 0.58f, 0.95f, 0.75f)
        val m = (v - 0.70f) / 0.06f
        animate(0.07f * exp(-m * m), u * 15.7f + sin(v * 25f) * 0.8f, 0.12f)
        return withBody(s, u, v, 0.74f, 0.05f)
    }

    private fun duneTop(u: Float, base: Float, amp: Float, freq: Float, phase: Float) =
        base - amp * sin(u * freq * PI.toFloat() + phase) - amp * 0.4f * sin(u * freq * 2.3f + phase * 1.7f)

    private val duneLayers = arrayOf(
        floatArrayOf(0.88f, 0.06f, 2.1f, 4.0f, 0.12f),
        floatArrayOf(0.77f, 0.05f, 3.2f, 1.7f, 0.27f),
        floatArrayOf(0.67f, 0.035f, 5.0f, 0.4f, 0.42f),
    )

    private fun dunes(u: Float, v: Float): Float {
        for (l in duneLayers) {
            val top = duneTop(u, l[0], l[1], l[2], l[3])
            if (v >= top) {
                val e = 0.003f
                val slope = (duneTop(u + e, l[0], l[1], l[2], l[3]) - duneTop(u - e, l[0], l[1], l[2], l[3])) / (2 * e)
                return l[4] + 0.05f * slope.coerceIn(-1f, 1f) + 0.10f * (1f - (v - top) / 0.008f).coerceAtLeast(0f) // a lit rim along the crest
            }
        }
        val s = skyTone(v, 0.56f, 0.92f, 0.7f)
        val m = (v - 0.64f) / 0.035f
        if (m * m < 9f) animate(0.08f * exp(-m * m), u * 11.3f + sin(v * 40f) * 0.7f, 0.09f) // haze drifting along the far crest
        return withBody(s, u, v, 0.70f, 0.06f)
    }

    private fun sea(u: Float, v: Float): Float {
        val horizon = 0.64f
        val r = 0.06f
        if (v < horizon) {
            val s = skyTone(v, 0.54f, 0.92f, horizon)
            return withBody(s, u, v, 0.5f, r)
        }
        val depth = (v - horizon) / (1f - horizon)
        val g = (u - 0.5f) * aspect / 0.085f
        val fade = (1f - depth / 0.75f).coerceIn(0f, 1f)
        val column = reflectAmp * exp(-g * g) * fade * fade
        animate(column * 1.1f, v * 300f + sin(u * 60f) * 1.5f, -1.6f)
        return 0.40f - 0.30f * depth + column * 0.65f
    }

    private fun planet(u: Float, v: Float): Float {
        val dx = (u - 0.62f) * aspect
        val dy = v - 1.85f
        val d = hypot(dx, dy)
        val radius = 1.30f
        if (d < radius) {
            val nx = dx / radius
            val ny = dy / radius
            val nz = sqrt(max(0f, 1f - nx * nx - ny * ny))
            val light = max(0f, nx * -0.45f + ny * -0.75f + nz * 0.48f)
            return 0.04f + 0.40f * light // enough of a terminator to give the planet volume
        }
        val skyT = skyTone(v, 0.40f, 0.66f, 0.8f)
        val rim = d - radius
        if (rim >= 0.045f) return skyT
        val low = smooth((0.86f - v) / 0.10f) // the limb dims as it nears the app row
        animate(0.05f * low, atan2(dx, -dy) * 30f, 0.6f)
        return max(skyT, skyT + (0.88f - rim / 0.045f * 0.45f - skyT) * low)
    }

    // ---- tech scenes ----

    /** Signal: a halftone planet inside a thin tilted ring, one accent marker; acid on black. */
    private fun signal(u: Float, v: Float): Float {
        val x = (u - 0.69f) * aspect
        val y = v - 0.46f
        val r = 0.30f
        val d = hypot(x, y)
        val rx = x * RING_COS - y * RING_SIN
        val ry = x * RING_SIN + y * RING_COS
        val e = hypot(rx / 0.42f, ry / 0.11f)
        // Distance to each ring in screen units (|e - k| over the gradient of e), so the line keeps one width all the way round.
        val ring = abs(e - 1.06f) < 0.3f && run { // only near the rings: distance over the gradient of e
            val grad = max(hypot(rx / (0.42f * 0.42f), ry / (0.11f * 0.11f)) / max(e, 1e-3f), 1e-3f)
            abs(e - 1f) / grad < max(0.0045f, 0.8f / bh) || abs(e - 1.12f) / grad < max(0.0022f, 0.6f / bh)
        }
        if (ring && (ry > 0f || d > r)) { animate(0.12f, atan2(ry, rx) * 5f, 1.1f); return 0.9f }
        if (d < r) {
            val nx = x / r
            val ny = y / r
            val nz = sqrt(max(0f, 1f - nx * nx - ny * ny))
            val light = max(0f, -0.55f * nx - 0.60f * ny + 0.58f * nz)
            animate(0.08f, v * 120f, 1.6f) // fine scan bands drift down the sphere
            return 0.08f + 0.82f * light * light
        }
        if (abs(u - 0.885f) * aspect < 0.012f && abs(v - 0.205f) < 0.012f) return 2f
        val q = (d - r) / 0.10f
        return 0.04f + 0.12f * exp(-q * q) + 0.10f * smooth((v - 0.55f) / 0.45f)
    }

    // Per-size cache for Neon: the tower under each column.
    private var towerAt: Array<IntArray> = emptyArray()
    private var towersFor = ""

    private fun prepareFields() {
        val size = "${bw}x$bh"
        if ((scene == Scene.NEON || scene == Scene.HIGHWAY) && towersFor != size) {
            towerAt = Array(skyline.size) { l -> IntArray(bw) { x -> val u = (x + 0.5f) / bw; skyline[l].indexOfFirst { u >= it.u0 && u < it.u1 } } }
            towersFor = size
        }
    }

    /** Beacon: a thin mast whose light sends rings outward across the dark. */
    private fun beacon(u: Float, v: Float): Float {
        val x = (u - BEACON_U) * aspect
        val y = v - BEACON_V
        val d = hypot(x, y)
        if (d < 0.012f) return 2f
        if (abs(x) < 0.0035f && v > BEACON_V && v < 0.78f) return 0.72f
        val k = d / 0.065f
        if (k >= 1f && k < 9f && abs(k - floor(k) - 0.5f) * 0.065f < 0.0035f) {
            val fade = smooth((0.82f - v) / 0.08f) // rings thin out before the app row
            animate(0.18f * fade, -d * 32f, 2.2f) // crests travel outward (about a third of a cycle per ring, so they don't pulse in unison)
            return 0.03f + (0.09f + 0.62f * (1f - 0.7f * k / 9f)) * fade
        }
        val q = d / 0.12f
        return 0.03f + 0.20f * exp(-q * q)
    }

    /** Array: a dot-matrix panel; dot sizes trace two interfering waves, and brightness ripples through them. */
    private fun array(x: Int, y: Int): Float {
        val pitch = max(4, bh / 22)
        val gx = x / pitch
        val gy = y / pitch
        val cu = (gx + 0.5f) * pitch / bw
        val cv = (gy + 0.5f) * pitch / bh
        val mask = smooth((cu - 0.36f) / 0.22f) * smooth((0.80f - cv) / 0.06f) * smooth((0.98f - cu) / 0.03f) * smooth((cv - 0.07f) / 0.05f) // inside the brackets
        if (mask <= 0.03f) return 0.03f
        val d1 = hypot((cu - 0.62f) * aspect, cv - 0.30f)
        val d2 = hypot((cu - 0.86f) * aspect, cv - 0.62f)
        val f = 0.5f + 0.5f * cos(d1 * 24f) * (0.65f + 0.35f * cos(d2 * 13f))
        val off = hypot(x + 0.5f - (gx + 0.5f) * pitch, y + 0.5f - (gy + 0.5f) * pitch)
        val r = f * mask * pitch * 0.44f
        if (r > 0.7f && off < r) {
            if (hash(gx, gy, 3) < 0.008f) return 2f // a few dots in the accent colour
            animate(0.12f, d1 * 24f, 0.6f)
            return (0.30f + 0.55f * f).let { floor(it * (RAMP - 1) + 0.5f) / (RAMP - 1) } // on a ramp step, so each dot is one flat level
        }
        return if (off < 0.7f) 0.12f * mask else 0.03f // pinprick grid where dots are small
    }

    /** Stripe: a poster band of marching stripes cut cleanly at one end, crossing a large thin ring. */
    private fun stripe(u: Float, v: Float): Float {
        val x = (u - 0.70f) * aspect
        val y = v - 0.50f
        val along = x * STRIPE_COS + y * STRIPE_SIN
        val across = -x * STRIPE_SIN + y * STRIPE_COS
        if (abs(across) < 0.082f && along > -0.33f) {
            animate(0.85f, along * 75f, -0.5f) // stripes march slowly along the band
            return 0.03f
        }
        if (abs(across - 0.115f) < 0.006f && along in 0.05f..0.24f) return 2f
        val d = hypot((u - 0.68f) * aspect, v - 0.46f)
        if (abs(d - 0.33f) < 0.004f) return 0.7f
        return 0.03f + 0.10f * smooth((v - 0.4f) / 0.6f)
    }

    private class Tower(val u0: Float, val u1: Float, val top: Float, val spire: Float, val sign: Boolean, val seed: Int)

    private fun towers(seed: Int, lo: Float, hi: Float): List<Tower> {
        val r = Random(seed)
        val list = ArrayList<Tower>()
        var u = -0.02f
        var i = 0
        while (u < 1.02f) {
            val w = 0.022f + r.nextFloat() * 0.05f
            val top = lo + r.nextFloat() * (hi - lo)
            val spire = if (r.nextFloat() < 0.25f) 0.03f + r.nextFloat() * 0.06f else 0f
            list += Tower(u, u + w, top, spire, r.nextFloat() < 0.3f, seed * 100 + i)
            u += w + if (r.nextFloat() < 0.3f) 0.004f else 0f
            i++
        }
        return list
    }

    private val skyline = listOf(towers(23, 0.30f, 0.62f), towers(22, 0.45f, 0.74f), towers(21, 0.60f, 0.86f)) // far → near
    private val layerTone = floatArrayOf(0.40f, 0.19f, 0.03f)

    /** Highway: an elevated road across the haze; brake lights stream one way, headlights the other. */
    private fun highway(u: Float, v: Float, x: Int, y: Int): Float {
        val deck = 0.60f - 0.06f * sin(u * 2.0f + 0.3f)
        if (v >= deck) {
            val dv = v - deck
            if (dv < 0.006f) return 2f // a cyan strip along the deck's edge
            if (dv in 0.008f..0.016f) { animate(0.75f, -u * aspect * 90f, 6.5f); return 0.10f } // brake lights, streaming right
            if (dv in 0.019f..0.027f) { animate(0.60f, u * aspect * 70f, 5.0f); return 0.36f } // headlights, streaming left
            if (dv < 0.05f) return 0.03f
            val pillar = (u * aspect / 0.21f) % 1f < 0.10f
            // Beneath the overpass: city glow along the street, a few lamps, and their reflections on the wet road.
            val col = floor(u * aspect / 0.009f).toInt()
            val lamp = hash(col, 0, 31) < 0.07f && !pillar
            if (v < STREET) {
                if (pillar) return 0.015f // pillars, in silhouette against the glow
                if (lamp && v > STREET - 0.006f - 0.008f * hash(col, 2, 31)) return 0.88f * (0.6f + 0.4f * hash(col, 3, 31)) // lamps of varied height and brightness
                if (v > deck + 0.07f && v < STREET - 0.03f) { // far windows glimpsed under the deck
                    val wx = u * aspect / 0.0105f
                    val wy = v / 0.017f
                    if (wx - floor(wx) in 0.3f..0.75f && wy - floor(wy) in 0.35f..0.75f && hash(floor(wx).toInt(), floor(wy).toInt(), 37) < 0.09f) return 0.30f
                }
                val g = (v - STREET + 0.015f) / 0.05f
                return 0.03f + 0.05f * smooth((v - deck - 0.05f) / 0.08f) + 0.36f * exp(-g * g)
            }
            val below = v - STREET
            if (pillar) return 0.015f + 0.02f * exp(-below / 0.03f) // their reflections, darker still
            var t = 0.03f + 0.30f * exp(-(below / 0.035f) * (below / 0.035f)) // the glow, mirrored: continuous at the waterline, then falling away
            if (lamp && abs(u * aspect - (col + 0.5f) * 0.009f) < 0.0045f) {
                val k = exp(-below / (0.03f + 0.04f * hash(col, 2, 31)))
                animate(0.16f * k, below * 170f + col * 1.3f, 1.7f) // the reflection shimmers
                t += 0.42f * k
            }
            return t
        }
        val t = skyline[0].getOrNull(towerAt[0][x])
        if (t != null && v >= t.top) {
            if (hash(floor((u - t.u0) * aspect / 0.0105f).toInt(), floor((v - t.top) / 0.017f).toInt(), t.seed) < 0.10f) return 0.52f
            return layerTone[0] - 0.06f
        }
        sky = true
        if (v < 0.8f && hash(x + y / 4, 0, 5) < 0.05f) animate(0.14f, v * 40f + hash(x + y / 4, 1, 5) * 6.28f, -12f) // rain: thin slanted streaks falling
        val g = (v - 0.55f) / 0.22f
        return 0.06f + 0.62f * exp(-g * g)
    }

    /** Sign: a tall neon sign of invented glyphs on a wet wall, mirrored in a rippling puddle below. */
    private fun sign(u: Float, v: Float, x: Int, y: Int): Float {
        if (v > SIGN_WATER) {
            val ripple = 0.004f * sin(v * 90f + u * 6f)
            if (hash(x, y, 7) < 0.04f) animate(0.18f, hash(x, y, 8) * 6.28f, 2.2f) // rain landing on the water
            val r = signTone(u + ripple, 2 * SIGN_WATER - v)
            return 0.03f + 0.45f * (if (r >= 1.5f) 0.7f else r) * smooth((0.86f - v) / 0.10f)
        }
        val t = signTone(u, v)
        if (t >= 1.5f) return t
        if (t < 0.5f && hash(x + y / 4, 0, 5) < 0.05f) animate(0.12f, v * 40f + hash(x + y / 4, 1, 5) * 6.28f, -12f) // rain falling past the wall, not over the sign
        // One glyph flickers like a failing tube.
        val gx = (u - SIGN_U0) / (SIGN_U1 - SIGN_U0) * 2f
        val gy = (v - SIGN_V0) / (SIGN_V1 - SIGN_V0) * 5f
        if (t > 0.6f && floor(gx).toInt() == 1 && floor(gy).toInt() == 3) { animate(-0.55f, 0f, 1.9f); return t } // negative amp: a brief dropout now and then
        return t
    }

    /** Wall, glyph strokes, the sign's cyan frame and its glow, without animation (shared with the reflection). */
    private fun signTone(u: Float, v: Float): Float {
        val px = u * aspect
        val x0 = SIGN_U0 * aspect
        val x1 = SIGN_U1 * aspect
        val outside = hypot(max(max(x0 - px, px - x1), 0f), max(max(SIGN_V0 - v, v - SIGN_V1), 0f))
        val pad = 0.014f
        val edge = max(max(x0 - pad - px, px - x1 - pad), max(SIGN_V0 - pad - v, v - SIGN_V1 - pad))
        if (abs(edge) < max(0.0025f, 0.6f / bh)) return 2f
        val seam = if ((v * 12.5f) % 1f < 0.04f) 0.03f else 0f
        val wall = 0.04f + seam + 0.30f * exp(-(outside / 0.10f) * (outside / 0.10f))
        if (outside > 0f) return wall
        // Inside: 2 × 5 glyph cells, each a few strokes chosen by hash.
        val cw = (x1 - x0) / 2f
        val ch = (SIGN_V1 - SIGN_V0) / 5f
        val ci = min(1, ((px - x0) / cw).toInt())
        val cj = min(4, ((v - SIGN_V0) / ch).toInt())
        val lx = (px - x0 - ci * cw) / cw
        val ly = (v - SIGN_V0 - cj * ch) / ch
        var d = 9f
        for (s in 0 until 6) {
            if (strokes[(cj * 2 + ci) * 6 + s]) {
                val dd = when (s) {
                    0 -> abs(ly - 0.18f) * ch + max(0f, abs(lx - 0.5f) - 0.32f) * cw // top bar
                    1 -> abs(ly - 0.5f) * ch + max(0f, abs(lx - 0.5f) - 0.32f) * cw  // middle bar
                    2 -> abs(ly - 0.82f) * ch + max(0f, abs(lx - 0.5f) - 0.32f) * cw // bottom bar
                    3 -> abs(lx - 0.18f) * cw + max(0f, abs(ly - 0.5f) - 0.32f) * ch // left post
                    4 -> abs(lx - 0.82f) * cw + max(0f, abs(ly - 0.5f) - 0.32f) * ch // right post
                    else -> abs(lx - 0.5f) * cw + max(0f, abs(ly - 0.5f) - 0.32f) * ch // centre post
                }
                d = min(d, dd)
            }
        }
        if (d < 0.0055f) return 0.80f
        return 0.07f + 0.32f * exp(-(d / 0.018f) * (d / 0.018f))
    }

    // Window's lights (u, v, radius, brightness) and Sign's strokes per glyph, worked out once rather than per cell.
    private val lights = FloatArray(LIGHTS * 4) { j ->
        val i = j / 4
        when (j % 4) {
            0 -> 0.22f + 0.80f * hash(i, 1, 61)
            1 -> 0.04f + 0.84f * hash(i, 2, 61)
            2 -> 0.035f + 0.075f * hash(i, 3, 61)
            else -> hash(i, 4, 61)
        }
    }
    private val strokes = BooleanArray(2 * 5 * 6) { j -> hash((j / 6) % 2, (j / 6) / 2, 40 + j % 6) < 0.5f }

    /** Window: city lights out of focus behind rain-streaked glass. */
    private fun window(u: Float, v: Float, x: Int, y: Int): Float {
        val px = u * aspect
        var t = 0.05f + 0.10f * smooth((v - 0.3f) / 0.7f)
        for (i in 0 until LIGHTS) {
            val r = lights[i * 4 + 2]
            val dx = px - lights[i * 4] * aspect
            if (dx > r || dx < -r) continue
            val dy = v - lights[i * 4 + 1]
            if (dy > r || dy < -r) continue
            val d = hypot(dx, dy)
            if (d < r) {
                val k = lights[i * 4 + 3]
                if (k < 0.10f && lights[i * 4 + 1] < 0.5f && d < r * 0.18f) return 2f // a cyan light: a small solid point inside its bokeh
                // Out-of-focus lights: soft edge, a brighter rim, and they add where they overlap.
                val soft = 1f - smooth((d - r * 0.80f) / (r * 0.20f))
                val rim = 0.08f * smooth((d - r * 0.70f) / (r * 0.15f)) * soft
                t += (0.14f + 0.26f * k) * soft + rim
                animate(0.03f, i * 1.1f, 0.12f) // each light breathes slowly
            }
        }
        t = min(t, 1f)
        // Rain on the glass: beads, and short trails running down.
        val col = floor(px / 0.012f).toInt()
        if (hash(col, 0, 71) < 0.35f && abs(px - (col + 0.5f) * 0.012f) < 0.0022f) {
            val beadY = hash(col, 1, 71)
            if (abs(v - beadY) < 0.006f) return min(1f, t + 0.25f)
            if (v > beadY && v < beadY + 0.12f * hash(col, 2, 71)) { animate(0.20f, -v * 60f, 2.4f); return t * 0.8f }
        }
        return t
    }

    /** Neon: a night skyline in haze, lit windows, flickering signs, fine rain against the glow. */
    private fun neon(u: Float, v: Float, x: Int, y: Int): Float {
        for (layer in 2 downTo 0) {
            val t = skyline[layer].getOrNull(towerAt[layer][x]) ?: continue
            val mid = (t.u0 + t.u1) / 2
            if (t.spire > 0f && v < t.top && v >= t.top - t.spire && abs(u - mid) * aspect < 0.0022f) {
                if (layer > 0 && v < t.top - t.spire + 0.006f) { animate(0.01f, t.seed.toFloat(), 1.3f); return 2f }
                return layerTone[layer]
            }
            if (v < t.top) continue
            if (layer > 0) {
                val side = (u - t.u0) * aspect
                if (t.sign && layer == 2 && side in 0.004f..0.010f && v in t.top + 0.03f..min(t.top + 0.16f, 0.78f)) {
                    animate(0.01f, t.seed * 1.7f, 0.9f)
                    return 2f
                }
                val wx = side / 0.0105f
                val wy = (v - t.top) / 0.017f
                val fx = wx - floor(wx)
                val fy = wy - floor(wy)
                if (wy > 0.6f && fx in 0.3f..0.75f && fy in 0.35f..0.75f &&
                    hash(floor(wx).toInt(), floor(wy).toInt(), t.seed) < (if (layer == 2) 0.07f else 0.16f) * smooth((0.84f - v) / 0.12f)) {
                    return if (layer == 2) 0.97f else 0.60f
                }
            }
            return layerTone[layer]
        }
        sky = true
        if (v < 0.8f && hash(x + y / 4, 0, 5) < 0.05f) animate(0.14f, v * 40f + hash(x + y / 4, 1, 5) * 6.28f, -12f) // rain: thin slanted streaks falling
        val g = (v - 0.60f) / 0.26f
        return 0.06f + 0.74f * exp(-g * g)
    }

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Crisp line work over the dithered field: registration marks and a ruler, or a survey grid. */
    private fun drawOverlay(canvas: Canvas) {
        val k = width / 960f
        val w = width.toFloat()
        val h = height.toFloat()
        when (scene) {
            Scene.SIGNAL -> {
                linePaint.color = palette[RAMP - 1]; linePaint.alpha = 170; linePaint.strokeWidth = 1.2f * k
                val ax = 0.885f * w
                val ay = 0.205f * h
                canvas.drawCircle(ax, ay, 16 * k, linePaint)
                canvas.drawLine(ax - 26 * k, ay, ax - 20 * k, ay, linePaint)
                canvas.drawLine(ax, ay - 26 * k, ax, ay - 20 * k, linePaint)
                canvas.drawLine(ax, ay + 20 * k, ax, ay + 26 * k, linePaint)
                canvas.drawLine(ax + 20 * k, ay, w - 28 * k, ay, linePaint)
                val rx = w - 28 * k
                canvas.drawLine(rx, 0.10f * h, rx, 0.70f * h, linePaint)
                var y = 0.10f * h
                var i = 0
                while (y <= 0.70f * h) { canvas.drawLine(rx - (if (i % 5 == 0) 9 else 4) * k, y, rx, y, linePaint); y += 10 * k; i++ }
            }
            Scene.BEACON -> { // a measuring scale up the mast
                linePaint.color = palette[RAMP - 1]; linePaint.alpha = 130; linePaint.strokeWidth = 1.1f * k
                val mx = BEACON_U * w + 8 * k
                var y = BEACON_V * h + 22 * k
                var i = 0
                while (y < 0.80f * h) { canvas.drawLine(mx, y, mx + (if (i % 5 == 0) 10 else 4) * k, y, linePaint); y += 9 * k; i++ }
            }
            Scene.ARRAY -> { // corner brackets around the panel
                linePaint.color = palette[RAMP - 1]; linePaint.alpha = 150; linePaint.strokeWidth = 1.2f * k
                val l = 0.40f * w; val r = w - 24 * k; val t = 24 * k; val b = 0.80f * h; val s = 16 * k
                for ((cx, cy, dx, dy) in listOf(floatArrayOf(l, t, 1f, 1f), floatArrayOf(r, t, -1f, 1f), floatArrayOf(l, b, 1f, -1f), floatArrayOf(r, b, -1f, -1f))) {
                    canvas.drawLine(cx, cy, cx + dx * s, cy, linePaint)
                    canvas.drawLine(cx, cy, cx, cy + dy * s, linePaint)
                }
            }
            Scene.STRIPE -> { // a registration mark where the band begins
                linePaint.color = palette[RAMP - 1]; linePaint.alpha = 160; linePaint.strokeWidth = 1.2f * k
                val bx = (0.70f + (-0.36f - 0.02f) * STRIPE_COS / aspect) * w
                val by = (0.50f + (-0.36f - 0.02f) * STRIPE_SIN) * h
                canvas.drawCircle(bx, by, 12 * k, linePaint)
                canvas.drawLine(bx - 20 * k, by, bx + 20 * k, by, linePaint)
                canvas.drawLine(bx, by - 20 * k, bx, by + 20 * k, linePaint)
            }
            else -> {}
        }
    }

    private fun lerp(x: Int, y: Int, t: Float): Int = Color.rgb(
        (Color.red(x) + (Color.red(y) - Color.red(x)) * t).toInt(),
        (Color.green(x) + (Color.green(y) - Color.green(x)) * t).toInt(),
        (Color.blue(x) + (Color.blue(y) - Color.blue(x)) * t).toInt(),
    )

    private companion object {
        const val ACCENT = 11 // level index for the accent color, one past the ramp
        const val RAMP = 11
        const val FRAME_MS = 80L
        const val FADE_MS = 1500f
        const val LIGHTS = 24
        const val SIGN_U0 = 0.64f
        const val SIGN_U1 = 0.80f
        const val SIGN_V0 = 0.06f
        const val SIGN_V1 = 0.54f
        const val SIGN_WATER = 0.63f
        const val STREET = 0.75f // Highway's street level, under the overpass
        const val BEACON_U = 0.70f
        const val BEACON_V = 0.30f
        val STRIPE_COS = cos(-0.52f)
        val STRIPE_SIN = kotlin.math.sin(-0.52f)
        val RING_COS = cos(-0.22f)
        val RING_SIN = kotlin.math.sin(-0.22f)
    }
}
