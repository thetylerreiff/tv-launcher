package co.thetylerreiff.gallery

import android.annotation.SuppressLint
import android.opengl.GLES30
import android.util.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.floor

/**
 * The Survey pack, drawn on the GPU as crisp line work: contour maps, a radar scope, a wireframe globe.
 * Terrain heights come from a small half-float texture made once on the CPU, so each pixel only needs
 * one filtered lookup; screen-space derivatives keep every line about one pixel wide at any slope.
 */
object SurveyGl {

    const val TERRAIN = 3
    const val RADAR = 4
    const val GLOBE = 5

    class Program(val id: Int, val aPos: Int, val uRes: Int, val uTime: Int, val uKind: Int, val uDay: Int, val uHeight: Int, val texture: Int, val uCorner: Int)

    private const val W = 512
    private const val H = 288

    fun build(): Program {
        val id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, shader(GLES30.GL_VERTEX_SHADER, VERT))
        GLES30.glAttachShader(id, shader(GLES30.GL_FRAGMENT_SHADER, FRAG))
        GLES30.glLinkProgram(id)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "survey program: " + GLES30.glGetProgramInfoLog(id) }
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        heights.position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16F, W, H, 0, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, heights)
        return Program(id, GLES30.glGetAttribLocation(id, "aPos"), GLES30.glGetUniformLocation(id, "uRes"),
            GLES30.glGetUniformLocation(id, "uTime"), GLES30.glGetUniformLocation(id, "uKind"),
            GLES30.glGetUniformLocation(id, "uDay"), GLES30.glGetUniformLocation(id, "uHeight"), tex[0], GLES30.glGetUniformLocation(id, "uCorner"))
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "survey shader: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }

    /** [wide] widens the quiet area behind the header, for the theme picker, whose title block is wider than the clock. */
    fun draw(p: Program, w: Int, h: Int, time: Float, kind: Int, day: Float, wide: Boolean = false) {
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(p.id)
        GLES30.glUniform2f(p.uRes, w.toFloat(), h.toFloat())
        GLES30.glUniform1f(p.uTime, time)
        GLES30.glUniform1f(p.uKind, kind.toFloat())
        GLES30.glUniform1f(p.uDay, day)
        if (wide) GLES30.glUniform2f(p.uCorner, 2.2f, 1.35f) else GLES30.glUniform2f(p.uCorner, 1f, 1f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, p.texture)
        GLES30.glUniform1i(p.uHeight, 0)
        GLES30.glEnableVertexAttribArray(p.aPos)
        GLES30.glVertexAttribPointer(p.aPos, 2, GLES30.GL_FLOAT, false, 0, MetalGl.quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Invented terrain (value-noise fbm), stored as half floats; made once per process. */
    private val heights: ShortBuffer by lazy { heightBits() }

    @SuppressLint("HalfFloat") // the buffer holds raw half-float bits for an R16F texture, on purpose
    private fun heightBits(): ShortBuffer {
        val buf = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        val k = 1.5f
        val aspect = 16f / 9f
        for (y in 0 until H) for (x in 0 until W) {
            buf.put(Half.toHalf(fbm((x + 0.5f) / W * aspect * k, (y + 0.5f) / H * k)))
        }
        buf.position(0)
        return buf
    }

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    private fun noise(x: Float, y: Float, seed: Int): Float {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val fx = x - ix
        val fy = y - iy
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = hash(ix, iy, seed)
        val b = hash(ix + 1, iy, seed)
        val c = hash(ix, iy + 1, seed)
        val d = hash(ix + 1, iy + 1, seed)
        return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy
    }

    private fun fbm(x: Float, y: Float): Float {
        var sum = 0f
        var amp = 0.5f
        var fx = x
        var fy = y
        for (o in 0 until 4) { sum += amp * noise(fx, fy, 17 + o); fx *= 2.03f; fy *= 2.03f; amp *= 0.42f }
        return sum / 0.9f
    }

    private const val VERT = """#version 300 es
in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    private val FRAG get() = FRAG_SRC.replace("CONTACTS", contacts() + "\n" + land())

    /** Radar contacts (x, y in scope radii, bearing) and continent caps (unit centre, threshold), as GLSL constants. */
    private fun contacts(): String {
        fun h(n: Double) = (Math.sin(n * 127.1) * 43758.5453).let { it - Math.floor(it) }
        val rows = (0 until 7).map { i ->
            val a = (h(i + 1.0) * 2 - 1) * Math.PI
            val r = 0.22 + 0.70 * h(i + 11.0)
            "vec3(%.5f, %.5f, %.5f)".format(java.util.Locale.US, r * Math.cos(a), r * Math.sin(a), a)
        }
        return "const vec3 CONTACT[7] = vec3[7](${rows.joinToString(", ")});"
    }

    private fun land(): String {
        val caps = listOf(
            doubleArrayOf(0.6, 0.45, 0.66, 0.90), doubleArrayOf(-0.5, 0.2, 0.84, 0.88), doubleArrayOf(0.1, -0.5, 0.86, 0.92),
            doubleArrayOf(-0.7, -0.3, -0.64, 0.86), doubleArrayOf(0.75, 0.1, -0.65, 0.89), doubleArrayOf(-0.1, 0.8, -0.6, 0.93),
        )
        val rows = caps.map { (x, y, z, k) ->
            val n = Math.sqrt(x * x + y * y + z * z)
            "vec4(%.5f, %.5f, %.5f, %.3f)".format(java.util.Locale.US, x / n, y / n, z / n, k)
        }
        return "const vec4 LAND[6] = vec4[6](${rows.joinToString(", ")});"
    }

    private const val FRAG_SRC = """#version 300 es
precision highp float;
uniform vec2 uRes;
uniform float uTime;
uniform float uKind;
uniform float uDay;
uniform sampler2D uHeight;
uniform vec2 uCorner; // scales the quiet area behind the clock (wider in the picker)
out vec4 fragColor;

const float PI = 3.14159265;
const float TAU = 6.2831853;
const vec3 INK = vec3(0.86, 0.91, 0.95);
const vec3 ACCENT = vec3(0.31, 0.89, 1.0);

float DP; // pixels per density-independent pixel (1/540 of the height)

// Coverage of a line of half-width w px, at distance d px from its centre.
float stroke(float d, float w) { return clamp(w + 0.5 - d, 0.0, 1.0); }
float disc(float d, float r) { return clamp(r + 0.5 - d, 0.0, 1.0); }
CONTACTS
float grain(vec2 p) { return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715)))); }

// A faint coordinate grid, every 48dp, centred on the screen.
float grid(vec2 frag) {
    float s = 48.0 * DP;
    vec2 g = abs(mod(frag - uRes * 0.5 + s * 0.5, s) - s * 0.5);
    return max(stroke(g.x, 0.5), stroke(g.y, 0.5));
}

// Terrain contours at uv: minor and major (every fifth) line coverage, and the height itself.
vec3 contours(vec2 uv) {
    float h = texture(uHeight, uv).r;
    float f = h * 40.0;
    float near = floor(f + 0.5);
    float d = abs(f - near) / max(fwidth(f), 1e-4);
    bool major = mod(near, 5.0) < 0.5;
    float graze = smoothstep(0.004, 0.012, fwidth(f)); // where a level only grazes a near-flat peak the line breaks up; fade it
    return vec3(major ? 0.0 : stroke(d, 0.45), major ? stroke(d, 0.85) : 0.0, h) * vec3(graze, graze, 1.0);
}

// Square corner brackets of half-size s px around a point at offset q px.
float brackets(vec2 q, float s, float b) {
    q = abs(q);
    float hx = stroke(abs(q.y - s), 0.6) * step(s - b, q.x) * step(q.x, s + 0.6);
    float vy = stroke(abs(q.x - s), 0.6) * step(s - b, q.y) * step(q.y, s + 0.6);
    return max(hx, vy);
}

void main() {
    DP = uRes.y / 540.0;
    vec2 frag = gl_FragCoord.xy;
    vec2 uv = frag / uRes;
    float aspect = uRes.x / uRes.y;
    vec2 p = vec2(uv.x * aspect, uv.y); // screen-height units, y up
    vec3 col = mix(vec3(0.022, 0.029, 0.038), vec3(0.036, 0.048, 0.062), uDay) * (1.15 - 0.3 * uv.y);
    col = mix(col, INK, 0.045 * grid(frag));

    if (uKind < 3.5) {
        // Terrain: contour map; a slow sweep brightens and tints the lines as it passes the marked site.
        vec3 c = contours(uv);
        float corner = 1.0 - smoothstep(0.7, 1.1, length(vec2(uv.x / 0.48, (1.0 - uv.y) / 0.50) / uCorner)); // flat over the clock and date, then easing out
        c.xy *= 1.0 - 0.85 * corner;
        vec2 site = vec2(0.64 * aspect, 0.60);
        vec2 d = p - site;
        col += vec3(0.025) * (c.z - 0.45);
        if (c.x + c.y > 0.0) { // only line pixels pay for the sweep
            float behind = mod(mod(uTime * 0.35, TAU) - PI - atan(d.y, d.x), TAU);
            float trail = exp(-behind * 1.6);
            col = mix(col, mix(INK, ACCENT, 0.8 * trail), c.x * (0.26 + 0.40 * trail) + c.y * (0.55 + 0.40 * trail));
        }
        vec2 q = d * uRes.y;
        float r = length(q);
        col = mix(col, ACCENT, max(stroke(abs(r - 16.0 * DP), 0.7), disc(r, 2.5 * DP)));
        col = mix(col, ACCENT, 0.85 * brackets(q, 28.0 * DP, 9.0 * DP));
    } else if (uKind < 4.5) {
        // Radar: range rings, bearing ticks and a rotating sweep over a faint map; blips flare as it passes.
        vec2 c = vec2(0.68 * aspect, 0.585);
        float R = 0.36;
        vec2 d = p - c;
        float rpx = length(d) * uRes.y;
        float Rpx = R * uRes.y;
        float ang = atan(d.y, d.x);
        float sweep = PI - mod(uTime * 0.3, TAU); // clockwise, one turn in about 21s
        float behind = mod(ang - sweep, TAU);
        if (rpx < Rpx) {
            vec3 ct = contours(uv);
            col = mix(col, INK, ct.x * 0.08 + ct.y * 0.14);
            float ringStep = Rpx / 4.0;
            col = mix(col, INK, 0.22 * stroke(abs(mod(rpx + ringStep * 0.5, ringStep) - ringStep * 0.5), 0.5));
            col = mix(col, INK, 0.10 * max(stroke(abs(d.x * uRes.y), 0.5), stroke(abs(d.y * uRes.y), 0.5)));
            col += ACCENT * 0.10 * exp(-behind * 2.4);
            col = mix(col, ACCENT, 0.85 * stroke(behind * rpx, 0.6));
            for (int i = 0; i < 7; i++) {
                float dd = length(p - (c + CONTACT[i].xy * R)) * uRes.y;
                if (dd < 10.0 * DP) { // only pixels near a contact pay for its glow
                    float glow = exp(-mod(CONTACT[i].z - sweep, TAU) * 0.7);
                    col = mix(col, mix(INK, ACCENT, glow), disc(dd, 2.8 * DP) * (0.25 + 0.75 * glow));
                    col = mix(col, ACCENT, 0.6 * glow * stroke(abs(dd - 8.0 * DP), 0.6));
                }
            }
        }
        col = mix(col, INK, 0.6 * stroke(abs(rpx - Rpx), 0.7));
        float deg = (ang + PI) / TAU * 72.0;
        float k = floor(deg + 0.5);
        float len = (mod(k, 6.0) < 0.5 ? 10.0 : 5.0) * DP;
        float inBand = step(Rpx + 4.0 * DP, rpx) * step(rpx, Rpx + 4.0 * DP + len);
        col = mix(col, INK, 0.55 * inBand * stroke(abs(deg - k) * (TAU / 72.0) * rpx, 0.5));
    } else {
        // Globe: a turning wireframe with dot-matrix continents, and a satellite on a tilted orbit.
        vec2 c = vec2(0.68 * aspect, 0.57);
        float R = 0.30;
        vec2 d = (p - c) / R;
        float rr = length(d);
        float spin = uTime * 0.06;
        // Orbit: a tilted ellipse; the half behind the globe is hidden.
        const vec2 OA = vec2(0.93937271, -0.34289781); // cos, sin of the orbit tilt (-0.35)
        vec2 od = vec2(d.x * OA.x - d.y * OA.y, d.x * OA.y + d.y * OA.x);
        float e = length(od / vec2(1.45, 0.34));
        float hidden = step(0.0, od.y) * step(rr, 1.0);
        if (abs(e - 1.0) < 0.25) { // only pixels near the orbit: distance in pixels from e's analytic gradient
            float eGrad = length(od / vec2(1.45 * 1.45, 0.34 * 0.34)) / max(e, 1e-3) / (R * uRes.y);
            col = mix(col, INK, 0.30 * (1.0 - hidden) * stroke(abs(e - 1.0) / max(eGrad, 1e-5), 0.5));
        }
        if (rr < 1.0) {
            vec3 s = vec3(d, sqrt(1.0 - rr * rr));
            const vec2 TL = vec2(0.92106099, 0.38941834); // cos, sin of the axial tilt (0.40)
            vec3 q = vec3(s.x, TL.x * s.y - TL.y * s.z, TL.y * s.y + TL.x * s.z);
            float lon = atan(q.x, q.z) + spin;
            float lat = asin(clamp(q.y, -1.0, 1.0));
            float cl = sqrt(max(1.0 - q.y * q.y, 0.0)); // cos(lat), without the trig
            float g = PI / 12.0;
            float flon = lon / g;
            float flt = lat / g;
            float dl = abs(flon - floor(flon + 0.5)) / max(fwidth(flon), 1e-4);
            float dt = abs(flt - floor(flt + 0.5)) / max(fwidth(flt), 1e-4);
            float shade = 0.35 + 0.65 * s.z;
            float meridian = stroke(dl, 0.45);
            if (cl < 0.5) meridian *= smoothstep(0.0, 0.5, cl) * mix(step(0.5, fract(flon * 0.5)), 1.0, smoothstep(0.2, 0.45, cl)); // near the poles every other meridian drops out
            col = mix(col, INK, 0.20 * shade * max(meridian, stroke(dt, 0.45)));
            vec3 w = vec3(cl * sin(lon), q.y, cl * cos(lon));
            float land = -1.0;
            for (int i = 0; i < 6; i++) land = max(land, dot(w, LAND[i].xyz) - LAND[i].w);
            // Ragged coastlines from the height map along longitude. The map doesn't tile, so the raggedness
            // fades out near its two seam longitudes, where the coast is just the smooth cap.
            float su = lon / PI;
            float seam = abs(fract(su) - 0.5) * 2.0;
            land += 0.50 * (texture(uHeight, vec2(su, lat / PI + 0.5)).r - 0.5) * (1.0 - smoothstep(0.88, 1.0, seam));
            vec2 cell = mod(frag, 5.0 * DP) - 2.5 * DP;
            col = mix(col, INK, 0.42 * shade * step(0.0, land) * disc(length(cell), 1.2 * DP));
        }
        col = mix(col, INK, 0.5 * stroke(abs(rr - 1.0) * R * uRes.y, 0.6));
        col += ACCENT * 0.06 * exp(-max(rr - 1.0, 0.0) * 14.0) * step(1.0, rr);
        float th = uTime * 0.22;
        vec2 sp = vec2(1.45 * cos(th), 0.34 * sin(th));
        vec2 sat = c + R * vec2(sp.x * OA.x + sp.y * OA.y, -sp.x * OA.y + sp.y * OA.x);
        float sd = length(p - sat) * uRes.y;
        float satHidden = step(0.0, sp.y) * step(length((sat - c) / R), 1.0);
        col = mix(col, ACCENT, (1.0 - satHidden) * max(disc(sd, 3.0 * DP), 0.7 * stroke(abs(sd - 8.0 * DP), 0.6)));
    }
    col += (grain(frag + fract(uTime)) - 0.5) / 255.0;
    fragColor = vec4(col, 1.0);
}
"""
}
