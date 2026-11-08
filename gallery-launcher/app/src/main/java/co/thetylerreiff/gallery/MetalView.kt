package co.thetylerreiff.gallery

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import java.util.Calendar
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Draws a GPU scene ([MetalGl] or [SurveyGl]) full screen at about 30 frames a second while [animating]. */
class MetalView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {

    @Volatile var kind = MetalGl.CHROME
        set(value) { field = value; requestRender() }
    @Volatile var hourOverride: Float? = null
    /** The picker: keep Terrain's lines clear of its wider header. */
    @Volatile var wideCorner = false

    /** Debug builds: log the achieved frame rate every few seconds (uncapped shows what the GPU can do). */
    var perfLog = false
    var uncapped = false
        set(value) { field = value; renderMode = if (value) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY }

    private var program: MetalGl.Program? = null
    private var survey: SurveyGl.Program? = null
    private var w = 0
    private var h = 0
    private var start = SystemClock.uptimeMillis()
    private var frames = 0
    private var since = 0L

    var animating = false
        set(value) {
            if (field == value) return
            field = value
            if (value) { start = SystemClock.uptimeMillis() - (MetalGl.POSE * 1000).toLong(); onResume(); post(tick) } else { removeCallbacks(tick); onPause() } // restart the clock each showing, at the pose the picker thumbnails use
        }
    private val tick = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() >= quietUntil) requestRender()
            if (animating) postDelayed(this, FRAME_MS)
        }
    }

    /** Holds the motion still for [ms] so the interface's own animations get the whole GPU. */
    fun quiet(ms: Long) { quietUntil = SystemClock.uptimeMillis() + ms }
    private var quietUntil = 0L

    init {
        setEGLContextClientVersion(3)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        onPause() // starts paused; [animating] resumes it
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) { program = MetalGl.build(); survey = SurveyGl.build() }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { w = width; h = height }

    override fun onDrawFrame(gl: GL10?) {
        val hour = hourOverride ?: Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60f }
        val t = ((SystemClock.uptimeMillis() - start) % 65_536_000L) / 1000f // wraps every ~18h (time restarts each showing); float time keeps ~8ms precision
        if (kind >= SurveyGl.TERRAIN) SurveyGl.draw(survey ?: return, w, h, t, kind, MetalGl.day(hour), wideCorner)
        else MetalGl.draw(program ?: return, w, h, t, kind, MetalGl.day(hour))
        if (perfLog) {
            frames++
            val now = SystemClock.uptimeMillis()
            if (since == 0L) since = now
            if (now - since >= 5000) { Log.d("GalleryPerf", "metal kind=$kind ${w}x$h ${frames * 1000f / (now - since)} fps"); frames = 0; since = now }
        }
    }

    private companion object { const val FRAME_MS = 33L }
}
