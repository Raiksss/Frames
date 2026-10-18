package ee.raiks.frames

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/** Draws over the viewfinder: the look's vignette, live film grain, the 3:2 film frame,
 *  and an optional rule-of-thirds grid. */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var vignette = 0f
        set(v) { field = v; invalidate() }

    var showGrid = false
        set(v) { field = v; invalidate() }

    /** 0 = off. Same scale as Film.grain. */
    var grain = 0f
        set(v) { field = v; grainPaint.alpha = (v * 900).toInt().coerceIn(0, 140); invalidate() }

    var grainSize = 1f

    /** Show the 3:2 (35mm) frame by masking the sides of the 3:4 viewfinder. */
    var crop32 = false
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 1.2f * density
    }
    private val vignettePaint = Paint()
    private val maskPaint = Paint().apply { color = Color.argb(235, 0, 0, 0) }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1f * density
    }

    // A tile of random grey noise, shifted randomly every frame so the grain "dances" like film.
    private val noiseTile: Bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
        // Each pixel lightens or darkens a little; drawn normally over the camera image.
        val px = IntArray(160 * 160) {
            val v = Random.nextInt(256) - 128
            if (v >= 0) Color.argb(v, 255, 255, 255) else Color.argb(-v, 0, 0, 0)
        }
        setPixels(px, 0, 160, 0, 0, 160, 160)
    }
    private val grainShader = BitmapShader(noiseTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    private val grainMatrix = Matrix()
    private val grainPaint = Paint().apply { shader = grainShader }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        // Image area (3:2 inside the 3:4 viewfinder → narrower)
        var left = 0f; var right = w
        if (crop32) {
            val iw = h / 1.5f
            left = (w - iw) / 2f; right = left + iw
        }

        if (grain > 0f) {
            grainMatrix.setScale(grainSize * density * 0.7f, grainSize * density * 0.7f)
            grainMatrix.postTranslate(Random.nextFloat() * 160f, Random.nextFloat() * 160f)
            grainShader.setLocalMatrix(grainMatrix)
            canvas.drawRect(left, 0f, right, h, grainPaint)
            postInvalidateDelayed(42) // ~24 fps, like film
        }
        if (vignette > 0f) {
            vignettePaint.shader = Looks.vignetteShader(right - left, h, vignette)
            canvas.save()
            canvas.translate(left, 0f)
            canvas.drawRect(0f, 0f, right - left, h, vignettePaint)
            canvas.restore()
        }
        if (crop32) {
            canvas.drawRect(0f, 0f, left, h, maskPaint)
            canvas.drawRect(right, 0f, w, h, maskPaint)
            canvas.drawLine(left, 0f, left, h, framePaint)
            canvas.drawLine(right, 0f, right, h, framePaint)
        }
        if (showGrid) {
            val iw = right - left
            for (i in 1..2) {
                canvas.drawLine(left + iw * i / 3f, 0f, left + iw * i / 3f, h, gridPaint)
                canvas.drawLine(left, h * i / 3f, right, h * i / 3f, gridPaint)
            }
        }
    }
}
