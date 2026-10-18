package ee.raiks.frames

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Film-stock character: per-channel tone curves, grain, halation and a 3:2 frame. */
class Film(
    val red: IntArray,      // 256-entry tone curves
    val green: IntArray,
    val blue: IntArray,
    val grain: Float,       // 0..~0.2, strength
    val grainSize: Float,   // 1 = fine (100 ISO) .. 2 = coarse (400 pushed)
    val halation: Float,    // 0..1, red-orange glow around highlights
    val halationColor: Int = Color.rgb(255, 70, 30),
)

/** A colour "look". Simple looks are a colour matrix + vignette; film looks add a [Film].
 *  The look is shown live in the viewfinder and baked into the JPEG.*/
class Look(
    val name: String,
    val short: String,
    val matrix: ColorMatrix,
    val vignette: Float = 0f,
    val film: Film? = null,
) {
    val identity: Boolean
        get() = film == null && vignette == 0f && matrix.array.contentEquals(ColorMatrix().array)

    /** 35mm film looks are framed 3:2, like a real negative. */
    val crop32: Boolean get() = film != null

    /** Single colour matrix that best approximates the full look, for the live viewfinder. */
    val previewMatrix: ColorMatrix by lazy { if (film == null) matrix else Looks.fitMatrix(this) }
}

object Looks {
    private fun cm(vararg v: Float) = ColorMatrix(v)

    private fun contrast(c: Float, lift: Float = 0f): ColorMatrix {
        val t = 128f * (1f - c) + lift
        return cm(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)
    }

    private fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }
    private fun tint(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }
    private fun mono(r: Float, g: Float, b: Float) =
        cm(r, g, b, 0f, 0f, r, g, b, 0f, 0f, r, g, b, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    private fun chain(vararg ms: ColorMatrix) = ColorMatrix().apply { ms.forEach { postConcat(it) } }

    /** Smooth monotone tone curve through (in, out) points in 0..1, as a 256-entry lookup table. */
    private fun curve(vararg p: Float): IntArray {
        val n = p.size / 2
        val xs = FloatArray(n) { p[it * 2] }
        val ys = FloatArray(n) { p[it * 2 + 1] }
        // Fritsch–Carlson monotone cubic interpolation
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]) }
        val m = FloatArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
            val a = m[i] / d[i]; val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9f) { val t = 3f / kotlin.math.sqrt(s); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
        }
        return IntArray(256) { v ->
            val x = v / 255f
            var k = 0
            while (k < n - 2 && x > xs[k + 1]) k++
            val h = xs[k + 1] - xs[k]
            val t = ((x - xs[k]) / h).coerceIn(0f, 1f)
            val t2 = t * t; val t3 = t2 * t
            val y = (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] +
                (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * m[k + 1]
            (y * 255f).roundToInt().coerceIn(0, 255)
        }
    }

    // ---------------------------------------------------------------------------------
    // Looks. The film looks are inspired by classic Kodak 35mm stocks (original names).
    // ---------------------------------------------------------------------------------

    val all = listOf(
        Look("Natural", "Natural", ColorMatrix()),
        Look("Classic", "Classic", chain(saturation(0.85f), tint(1.05f, 1.0f, 0.92f), contrast(1.08f, 6f)), 0.35f),
        Look("Vivid", "Vivid", chain(saturation(1.35f), contrast(1.12f)), 0.15f),
        Look("Chrome", "Chrome", chain(saturation(0.8f), tint(0.96f, 1.0f, 1.06f), contrast(1.18f)), 0.25f),
        Look("Mono", "Mono", chain(mono(0.299f, 0.587f, 0.114f), contrast(1.15f)), 0.3f),
        Look("Mono HC", "Mono HC", chain(mono(0.6f, 0.35f, 0.05f), contrast(1.45f, -4f)), 0.45f),

        // Portra-style: soft contrast, warm creamy skin, pastel colour, lifted blacks
        Look("35mm Portrait 400", "Portrait",
            chain(saturation(0.82f), tint(1.03f, 1.0f, 0.95f)), 0.22f,
            Film(
                red = curve(0f, .06f, .25f, .27f, .5f, .54f, .75f, .79f, 1f, .97f),
                green = curve(0f, .05f, .25f, .25f, .5f, .52f, .75f, .78f, 1f, .96f),
                blue = curve(0f, .07f, .25f, .25f, .5f, .49f, .75f, .73f, 1f, .92f),
                grain = 0f, grainSize = 1.4f, halation = .15f)),

        // Gold-style: golden warmth, rich yellows and reds, sunny highlights
        Look("35mm Golden 200", "Golden",
            chain(saturation(1.1f), tint(1.06f, 1.0f, 0.86f)), 0.3f,
            Film(
                red = curve(0f, .04f, .25f, .25f, .5f, .57f, .75f, .85f, 1f, 1f),
                green = curve(0f, .03f, .25f, .22f, .5f, .53f, .75f, .81f, 1f, .97f),
                blue = curve(0f, .04f, .25f, .19f, .5f, .45f, .75f, .72f, 1f, .88f),
                grain = 0f, grainSize = 1.3f, halation = .22f)),

        // UltraMax-style: punchy consumer film, saturated, warm, visible grain
        Look("35mm Everyday 400", "Everyday",
            chain(saturation(1.22f), tint(1.04f, 1.0f, 0.92f)), 0.32f,
            Film(
                red = curve(0f, .03f, .25f, .21f, .5f, .54f, .75f, .85f, 1f, 1f),
                green = curve(0f, .02f, .25f, .2f, .5f, .52f, .75f, .83f, 1f, .99f),
                blue = curve(0f, .04f, .25f, .19f, .5f, .48f, .75f, .78f, 1f, .94f),
                grain = 0f, grainSize = 1.7f, halation = .2f)),

        // Ektar-style: very saturated, deep reds and blues, fine grain, clean whites
        Look("35mm Saturate 100", "Saturate",
            chain(saturation(1.42f), tint(1.01f, 0.99f, 1.02f)), 0.2f,
            Film(
                red = curve(0f, 0f, .25f, .2f, .5f, .52f, .75f, .84f, 1f, 1f),
                green = curve(0f, 0f, .25f, .19f, .5f, .5f, .75f, .82f, 1f, 1f),
                blue = curve(0f, .01f, .25f, .2f, .5f, .51f, .75f, .83f, 1f, 1f),
                grain = 0f, grainSize = 1.0f, halation = .1f)),

        // Vision3 500T-style tungsten cinema film: cool cast, teal shadows, strong red halation
        Look("35mm Cine 500T", "500T",
            chain(saturation(0.95f), tint(0.9f, 0.98f, 1.12f)), 0.35f,
            Film(
                red = curve(0f, .02f, .25f, .22f, .5f, .52f, .75f, .8f, 1f, .97f),
                green = curve(0f, .05f, .25f, .25f, .5f, .51f, .75f, .77f, 1f, .95f),
                blue = curve(0f, .1f, .25f, .29f, .5f, .52f, .75f, .76f, 1f, .93f),
                grain = 0f, grainSize = 1.6f, halation = .8f, halationColor = Color.rgb(255, 45, 25))),

        // Tri-X-style black & white: gritty grain, deep blacks, bright highlights
        Look("35mm Grit 400 B&W", "Grit",
            mono(0.35f, 0.5f, 0.15f), 0.4f,
            Film(
                red = curve(0f, .03f, .2f, .12f, .5f, .5f, .8f, .9f, 1f, 1f),
                green = curve(0f, .03f, .2f, .12f, .5f, .5f, .8f, .9f, 1f, 1f),
                blue = curve(0f, .03f, .2f, .12f, .5f, .5f, .8f, .9f, 1f, 1f),
                grain = 0f, grainSize = 1.9f, halation = 0f)),
    )

    // ---------------------------------------------------------------------------------
    // Rendering the saved JPEG
    // ---------------------------------------------------------------------------------

    /** Rotates the sensor image upright, crops 3:2 for film, applies the look. Recycles [src]. */
    fun render(src: Bitmap, look: Look, rotation: Int): Bitmap {
        val swap = rotation % 180 != 0
        var w = if (swap) src.height else src.width
        var h = if (swap) src.width else src.height
        // 3:2 crop: trim the short side of a 4:3 frame (portrait 2:3 or landscape 3:2)
        if (look.crop32) {
            if (h >= w) w = min(w, (h / 1.5f).roundToInt())
            else h = min(h, (w / 1.5f).roundToInt())
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // Drawn centred, so a crop trims equal amounts from both sides.
        c.save()
        c.translate(w / 2f, h / 2f)
        c.rotate(rotation.toFloat())
        c.translate(-src.width / 2f, -src.height / 2f)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(look.matrix)
        })
        c.restore()
        src.recycle()

        look.film?.let { film ->
            if (film.halation > 0f) addHalation(out, film)
            applyCurvesAndGrain(out, film)
        }
        if (look.vignette > 0f) {
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = vignetteShader(w.toFloat(), h.toFloat(), look.vignette)
            })
        }
        return out
    }

    /** Red-orange glow bleeding from bright highlights, like light scattering in the film base. */
    private fun addHalation(bmp: Bitmap, film: Film) {
        val w = bmp.width; val h = bmp.height
        val sw = max(8, w / 16); val sh = max(8, h / 16)
        // Downscale (cheap blur), keep only highlights, upscale (more blur), tint and screen-blend.
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, true)
        val mask = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val r = Color.red(film.halationColor) / 255f
        val g = Color.green(film.halationColor) / 255f
        val b = Color.blue(film.halationColor) / 255f
        // luminance → (lum - 190) * 6, tinted
        val k = 6f
        val off = -190f * k
        val lr = .299f * k; val lg = .587f * k; val lb = .114f * k
        val highlight = cm(
            lr * r, lg * r, lb * r, 0f, off * r,
            lr * g, lg * g, lb * g, 0f, off * g,
            lr * b, lg * b, lb * b, 0f, off * b,
            0f, 0f, 0f, 1f, 0f
        )
        Canvas(mask).drawBitmap(small, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(highlight) })
        small.recycle()
        val tiny = Bitmap.createScaledBitmap(mask, max(4, sw / 2), max(4, sh / 2), true)
        mask.recycle()
        val glow = Bitmap.createScaledBitmap(tiny, w, h, true)
        tiny.recycle()
        Canvas(bmp).drawBitmap(glow, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            alpha = (255 * film.halation).roundToInt().coerceIn(0, 255)
        })
        glow.recycle()
    }

    /** Tone curves per channel, then film grain (strongest in the midtones, like real film). */
    private fun applyCurvesAndGrain(bmp: Bitmap, film: Film) {
        val w = bmp.width; val h = bmp.height
        val rC = film.red; val gC = film.green; val bC = film.blue
        val rng = java.util.Random()
        val band = 256 // process in strips to keep memory low
        val px = IntArray(w * band)
        val amp = film.grain * 255f
        var y0 = 0
        while (y0 < h) {
            val rows = min(band, h - y0)
            bmp.getPixels(px, 0, w, 0, y0, w, rows)
            for (i in 0 until w * rows) {
                val x = i % w
                val yy = y0 + i / w
                val p = px[i]
                var r = rC[(p shr 16) and 0xFF]
                var g = gC[(p shr 8) and 0xFF]
                var b = bC[p and 0xFF]
                if (amp > 0f) {
                    // Clumped grain: a hash of the coarse grain cell (bigger cells = coarser film): hash of the coarse grain cell, plus a fine per-pixel part
                    val cx = (x / film.grainSize).toInt()
                    val cy = (yy / film.grainSize).toInt()
                    val n = (hash(cx, cy) * 0.75f + (rng.nextFloat() - 0.5f) * 0.5f)
                    val lum = (r * 299 + g * 587 + b * 114) / 1000
                    val weight = 1f - abs(lum - 128) / 160f // midtones get the most grain
                    val d = (n * amp * weight.coerceAtLeast(0.2f)).toInt()
                    r = (r + d).coerceIn(0, 255); g = (g + d).coerceIn(0, 255); b = (b + d).coerceIn(0, 255)
                }
                px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(px, 0, w, 0, y0, w, rows)
            y0 += rows
        }
    }

    /** Deterministic pseudo-random value in -0.5..0.5 for a grain cell. */
    private fun hash(x: Int, y: Int): Float {
        var n = x * 374761393 + y * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return ((n and 0xFFFF) / 65535f) - 0.5f
    }

    // ---------------------------------------------------------------------------------
    // Live viewfinder approximation
    // ---------------------------------------------------------------------------------

    /** Least-squares fit of a single 4x5 colour matrix to the full look (matrix + curves),
     *  so the viewfinder shows the film colour live. Grain is drawn separately as an overlay. */
    fun fitMatrix(look: Look): ColorMatrix {
        val film = look.film ?: return look.matrix
        val m = look.matrix.array
        val steps = 6
        // Normal equations: (XᵀX) β = Xᵀy for each output channel, X rows = [r, g, b, 1]
        val xtx = Array(4) { DoubleArray(4) }
        val xty = Array(3) { DoubleArray(4) }
        for (ri in 0 until steps) for (gi in 0 until steps) for (bi in 0 until steps) {
            val r = ri * 255.0 / (steps - 1); val g = gi * 255.0 / (steps - 1); val b = bi * 255.0 / (steps - 1)
            val x = doubleArrayOf(r, g, b, 1.0)
            fun ch(row: Int) = (m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4])
                .roundToInt().coerceIn(0, 255)
            val out = doubleArrayOf(
                film.red[ch(0)].toDouble(), film.green[ch(1)].toDouble(), film.blue[ch(2)].toDouble()
            )
            for (i in 0 until 4) {
                for (j in 0 until 4) xtx[i][j] += x[i] * x[j]
                for (c in 0 until 3) xty[c][i] += x[i] * out[c]
            }
        }
        val res = FloatArray(20)
        for (c in 0 until 3) {
            val beta = solve4(xtx, xty[c])
            res[c * 5] = beta[0].toFloat(); res[c * 5 + 1] = beta[1].toFloat()
            res[c * 5 + 2] = beta[2].toFloat(); res[c * 5 + 4] = beta[3].toFloat()
        }
        res[18] = 1f
        return ColorMatrix(res)
    }

    private fun solve4(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray {
        val a = Array(4) { i -> DoubleArray(5) { j -> if (j < 4) a0[i][j] else b0[i] } }
        for (col in 0 until 4) {
            var piv = col
            for (r in col + 1 until 4) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            val t = a[col]; a[col] = a[piv]; a[piv] = t
            for (r in 0 until 4) if (r != col) {
                val f = a[r][col] / a[col][col]
                for (k in col until 5) a[r][k] -= f * a[col][k]
            }
        }
        return DoubleArray(4) { a[it][4] / a[it][it] }
    }

    fun vignetteShader(w: Float, h: Float, strength: Float) = RadialGradient(
        w / 2f, h / 2f, hypot(w, h) / 2f,
        intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((255 * strength).toInt(), 0, 0, 0)),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP
    )
}
