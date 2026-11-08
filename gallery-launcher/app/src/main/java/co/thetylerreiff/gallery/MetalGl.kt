package co.thetylerreiff.gallery

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Full-resolution metal scenes, drawn on the GPU. Each pixel works out the surface normal of a 2.5D
 * shape, reflects the view off it into a procedural studio (dark room, tall softboxes, pale floor),
 * and tints the result by the metal's reflectance, with Fresnel brightening toward the edges.
 */
object MetalGl {

    class Program(val id: Int, val aPos: Int, val uRes: Int, val uTime: Int, val uKind: Int, val uDay: Int)

    /** Where scene time starts each showing, and the moment thumbnails capture: Alloy's drops are gathered
     *  right of the clock and the Globe's satellite is in view. */
    const val POSE = 70f

    /** Scene kinds understood by the shader. */
    const val CHROME = 0
    const val GOLD = 1
    const val ALLOY = 2

    internal val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

    fun build(): Program {
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, shader(GLES20.GL_VERTEX_SHADER, VERT))
        GLES20.glAttachShader(id, shader(GLES20.GL_FRAGMENT_SHADER, FRAG))
        GLES20.glLinkProgram(id)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "metal program: " + GLES20.glGetProgramInfoLog(id) }
        return Program(id, GLES20.glGetAttribLocation(id, "aPos"), GLES20.glGetUniformLocation(id, "uRes"),
            GLES20.glGetUniformLocation(id, "uTime"), GLES20.glGetUniformLocation(id, "uKind"), GLES20.glGetUniformLocation(id, "uDay"))
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "metal shader: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    fun draw(p: Program, w: Int, h: Int, time: Float, kind: Int, day: Float) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(p.id)
        GLES20.glUniform2f(p.uRes, w.toFloat(), h.toFloat())
        GLES20.glUniform1f(p.uTime, time)
        GLES20.glUniform1f(p.uKind, kind.toFloat())
        GLES20.glUniform1f(p.uDay, day)
        GLES20.glEnableVertexAttribArray(p.aPos)
        GLES20.glVertexAttribPointer(p.aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** 0 at night, 1 in daylight, easing through dawn and dusk. */
    fun day(hour: Float): Float {
        fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
        return smooth(6f, 9f, hour) * (1f - smooth(17f, 20f, hour))
    }

    /** How far a thumbnail zooms in, and where on screen its subject sits (x across, y up from the bottom). */
    private fun framing(kind: Int): FloatArray = when (kind) {
        CHROME -> floatArrayOf(1.6f, 0.68f, 0.60f)
        GOLD -> floatArrayOf(1.5f, 0.70f, 0.55f)
        ALLOY -> floatArrayOf(1.4f, 0.72f, 0.535f)
        SurveyGl.RADAR -> floatArrayOf(1.25f, 0.68f, 0.585f)
        SurveyGl.GLOBE -> floatArrayOf(1.4f, 0.68f, 0.57f)
        else -> floatArrayOf(1f, 0.5f, 0.5f)
    }

    /** Renders still frames off screen (for picker thumbnails), one per kind (metal or survey), zoomed in on the subject. */
    fun snapshots(kinds: List<Int>, w: Int, h: Int, hour: Float): List<Bitmap> {
        val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(dpy, IntArray(1), 0, IntArray(1), 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(dpy, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE,
        ), 0, configs, 0, 1, IntArray(1), 0)
        val ctx = EGL14.eglCreateContext(dpy, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(dpy, configs[0], intArrayOf(EGL14.EGL_WIDTH, w * 2, EGL14.EGL_HEIGHT, h * 2, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
        try {
            val program by lazy { build() }
            val survey by lazy { SurveyGl.build() }
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            val flip = Matrix().apply { preScale(1f, -1f) } // GL rows run bottom-up
            return kinds.map { kind ->
                // Draw the whole scene larger than the tile, then read back the tile-sized window around its subject.
                val (z, fx, fy) = framing(kind).let { Triple(it[0], it[1], it[2]) }
                val zw = (w * z).toInt()
                val zh = (h * z).toInt()
                if (kind >= SurveyGl.TERRAIN) SurveyGl.draw(survey, zw, zh, POSE, kind, day(hour)) else draw(program, zw, zh, POSE, kind, day(hour))
                val x0 = (fx * zw - w / 2f).toInt().coerceIn(0, zw - w)
                val y0 = (fy * zh - h / 2f).toInt().coerceIn(0, zh - h)
                buf.rewind()
                GLES20.glReadPixels(x0, y0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf) }
                Bitmap.createBitmap(raw, 0, 0, w, h, flip, false)
            }
        } finally {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
        }
    }

    private const val VERT = """
attribute vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    private const val FRAG = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 uRes;
uniform float uTime;
uniform float uKind;
uniform float uDay;

const float PI = 3.14159265;
const vec3 CHROME = vec3(0.62, 0.63, 0.66);
const vec3 GOLD = vec3(1.00, 0.74, 0.32);
const vec3 ACCENT = vec3(0.50, 0.66, 1.00);

float PX;  // one pixel, in screen-height units
float DP;  // one density-independent pixel (1/540 of the height)

float hash(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); } // no sin: cheap per pixel

// The studio as seen along direction r (y up, +z toward the viewer): a dark room lit by a large key
// softbox, a tall fill strip, an overhead panel and a rim light behind, over a seamless pale floor.
// It turns slowly, so reflections drift across the metal.
float box(float az, float el, float a0, float a1, float e0, float e1, float soft) {
    vec4 e = clamp((vec4(az, a1, el, e1) - vec4(a0, az, e0, el)) / (2.0 * soft) + 0.5, 0.0, 1.0); // linear soft edges
    return e.x * e.y * e.z * e.w;
}
float studio(vec3 r) {
    float az = atan(r.x, r.z) + 0.05 * uTime;
    az = mod(az + PI, 2.0 * PI) - PI;
    float el = r.y * (1.0 + 0.22 * r.y * r.y); // close enough to asin for placing lights, and much cheaper
    float room = mix(0.04, 0.08, uDay) + 0.10 * smoothstep(-0.1, 1.3, el);
    float light = 2.6 * box(az, el, -1.05, -0.45, 0.10, 0.70, 0.03)      // key softbox
                + 1.7 * box(az, el, 0.55, 0.68, -0.05, 0.95, 0.015)      // tall fill strip
                + 1.1 * box(az, el, -0.30, 0.30, 1.05, 1.60, 0.05)       // overhead panel
                + 2.2 * box(abs(az), el, 2.55, 2.85, 0.00, 0.90, 0.02)   // rim lights behind
                + 0.9 * box(az, el, 1.35, 1.95, 0.20, 0.45, 0.02);       // a low window
    float floorL = mix(0.85, 0.30, smoothstep(-0.10, -1.3, el));          // seamless floor, brightest at its far edge
    float cyc = smoothstep(-0.02, -0.16, el);                              // where the floor curves up into the wall
    return mix(room + light, floorL * mix(0.8, 1.0, uDay), cyc);
}

// Reflected colour for surface normal n and metal reflectance f0 (Schlick Fresnel toward the rim).
vec3 metal(vec3 n, vec3 f0) {
    vec3 r = vec3(2.0 * n.z * n.x, 2.0 * n.z * n.y, 2.0 * n.z * n.z - 1.0);
    float m = 1.0 - n.z;
    float m2 = m * m;
    vec3 f = f0 + (1.0 - f0) * (m2 * m2 * m);
    return studio(r) * f;
}

// A liquid blob: wobbling outline, dome-shaped surface with a slow ripple. Returns normal and coverage.
vec4 blob(vec2 p, vec2 c, float R, float seed) {
    vec2 d = p - c;
    float r = length(d);
    float a = atan(d.y, d.x);
    float t = uTime;
    float Ra = R * (1.0 + 0.07 * sin(3.0 * a + 0.6 + seed + 0.5 * sin(t * 0.23 + seed))
                        + 0.05 * sin(5.0 * a - 1.1 + 0.17 * t + 2.0 * seed)
                        + 0.03 * sin(2.0 * a + 2.0 + 0.11 * t));
    float cover = 1.0 - smoothstep(Ra - 1.5 * PX, Ra, r);
    // A pillow profile (flatter in the middle, rounding hard at the rim) reads more like poured metal.
    float s = min(r / Ra, 0.999);
    float s2 = s * s;
    float k = max(1.0 - s2 * s2, 1e-4);
    float slope = s2 * s * inversesqrt(k * sqrt(k)); // superellipse (p = 4) profile: s^3 / (1 - s^4)^(3/4)
    vec3 n = normalize(vec3(d / max(r, 1e-4) * slope, 1.0));
    // Slow, broad ripples across the surface.
    vec2 q = d / R;
    n.xy += 0.22 * n.z * vec2(sin(q.y * 4.1 + 0.18 * t + seed) + 0.5 * sin(q.x * 7.3 - 0.13 * t),
                              sin(q.x * 3.7 - 0.16 * t - seed) + 0.5 * sin(q.y * 6.9 + 0.11 * t));
    return vec4(normalize(n), cover);
}

// Four drops, two chrome and two gold, drifting and merging (metaballs). Returns normal and coverage;
// goldShare says how much of this point belongs to the gold drops.
vec4 alloy(vec2 p, vec2 c, out float goldShare) {
    float f = 0.0;
    vec2 g = vec2(0.0);
    float gold = 0.0;
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        // A slow drift; uneven phases so no two drops mirror each other (merged, they read as a face).
        vec2 ci = c + vec2(0.22 * sin(uTime * (0.035 + 0.01 * fi) + fi * 2.9), 0.15 * cos(uTime * (0.03 + 0.0075 * fi) + fi * 0.8));
        float ri = 0.065 + 0.018 * fi;
        vec2 d = p - ci;
        float q = dot(d, d) + 1e-5;
        float w = ri * ri / q;
        f += w;
        g += -2.0 * ri * ri * d / (q * q);
        gold += w * (mod(fi, 2.0) > 0.5 ? 1.0 : 0.0); // drops 1 and 3: the largest is gold
    }
    goldShare = gold / f;
    float edge = (f - 1.0) / max(length(g) * PX, 1e-5);
    float cover = smoothstep(0.0, 1.5, edge);
    // Surface slope. Where drops fuse the field is barely over the threshold; flooring it keeps the neck's
    // reflection smooth instead of breaking into shards.
    vec2 gz = 0.12 * g / (2.0 * sqrt(max(f - 1.0, 0.15)));
    return vec4(normalize(vec3(-gz, 1.0)), cover);
}

// A slowly turning ring of ticks around c, and a hairline down to a small node (like a diagram).
vec4 ticks(vec2 p, vec2 c, float R) {
    vec2 d = p - c;
    float r = length(d);
    float rr = R * 1.32;
    float stepA = PI / 60.0;
    float k = (atan(d.y, d.x) + uTime * 2.0 * PI / 120.0) / stepA;
    float idx = floor(k + 0.5);
    float len = (mod(idx, 10.0) < 0.5 ? 7.0 : 3.0) * DP;
    float w = 0.6 * DP;
    float tick = smoothstep(w + PX, w, abs(k - idx) * stepA * r)
               * smoothstep(rr - len - PX, rr - len, r) * smoothstep(rr + PX, rr, r);
    float nodeY = 0.22;
    float conn = smoothstep(w + PX, w, abs(d.x)) * step(nodeY + 7.0 * DP, p.y) * step(p.y, c.y - rr - 6.0 * DP);
    float nd = length(p - vec2(c.x, nodeY));
    float ring = smoothstep(w + PX, w, abs(nd - 7.0 * DP));
    float node = 1.0 - smoothstep(3.0 * DP - PX, 3.0 * DP, nd);
    return vec4(max(tick * 0.45, max(conn, ring) * 0.35), node, 0.0, 0.0);
}

// A thin square frame with corner marks around c.
float frame(vec2 p, vec2 c, float hs) {
    vec2 d = abs(p - c) - vec2(hs);
    float edge = abs(max(d.x, d.y));
    float w = 0.6 * DP;
    float line = smoothstep(w + PX, w, edge) * step(max(d.x, d.y), 1.0);
    vec2 q = abs(p - c);
    float corner = step(hs - 10.0 * DP, min(q.x, q.y)) * smoothstep(w * 2.5 + PX, w * 2.5, edge);
    return max(line * 0.28, corner * 0.6);
}

void main() {
    PX = 1.0 / uRes.y;
    DP = 1.0 / 540.0;
    float aspect = uRes.x / uRes.y;
    vec2 p = gl_FragCoord.xy / uRes.y; // x in [0, aspect], y in [0, 1] from the bottom
    vec3 bg;
    vec3 col;
    // Only pixels near the metal pay for the shape and reflection; the rest are a cheap background.
    if (uKind < 0.5) {
        vec2 c = vec2(0.68 * aspect, 0.60);
        float dist = length(p - c);
        bg = vec3(0.071) * (1.0 + 0.45 / (1.0 + dist * dist * 12.0));
        col = bg;
        float cover = 0.0;
        if (dist < 0.24 * 1.2) {
            vec4 b = blob(p, c, 0.24, 0.0);
            cover = b.w;
            if (cover > 0.0) col = mix(bg, metal(b.xyz, CHROME), cover);
        }
        float rr = 0.24 * 1.32;
        if (abs(dist - rr) < 9.0 * DP || (abs(p.x - c.x) < 9.0 * DP && p.y < c.y - rr)) {
            vec4 o = ticks(p, c, 0.24);
            col = mix(col, vec3(0.95), o.x * (1.0 - cover));
            col = mix(col, ACCENT, o.y);
        }
    } else if (uKind < 1.5) {
        vec2 c = vec2(0.70 * aspect, 0.55);
        float dist = length(p - c);
        bg = vec3(0.055, 0.047, 0.035) * (1.0 + 0.6 / (1.0 + dist * dist * 10.0));
        col = bg;
        float cover = 0.0;
        vec2 sq = c + (p - c) * vec2(0.90, 1.24); // squashed: a wide, low pour rather than Chrome's round drop
        if (length(sq - c) < 0.25 * 1.2) {
            vec4 b = blob(sq, c, 0.25, 2.3);
            cover = b.w;
            if (cover > 0.0) col = mix(bg, metal(b.xyz, GOLD), cover);
        }
        vec2 fq = abs(p - c);
        if (abs(max(fq.x, fq.y) - 0.25 * 1.30) < 4.0 * DP) col = mix(col, vec3(0.95, 0.80, 0.50), frame(p, c, 0.25 * 1.30) * (1.0 - cover));
    } else {
        vec2 c = vec2(0.70 * aspect, 0.55);
        float dist = length(p - c);
        bg = vec3(0.062, 0.062, 0.070) * (1.0 + 0.5 / (1.0 + dist * dist * 10.0));
        col = bg;
        if (dist < 0.42) {
            float goldShare;
            vec4 b = alloy(p, c, goldShare);
            if (b.w > 0.0) col = mix(bg, metal(b.xyz, mix(CHROME, GOLD, smoothstep(0.35, 0.65, goldShare))), b.w);
        }
    }
    col += (hash(gl_FragCoord.xy + fract(uTime)) - 0.5) / 255.0; // breaks up banding in the dark gradients
    gl_FragColor = vec4(col, 1.0);
}
"""
}
