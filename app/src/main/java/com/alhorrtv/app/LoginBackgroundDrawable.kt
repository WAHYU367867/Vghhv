package com.alhorrtv.app

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Static cinematic backdrop for the login screen: dimmed tilted "poster" tiles on the left, violet glow behind the logo,
 * gold and violet light sweeps at the bottom. It is drawn once into a half-resolution bitmap (no images are loaded and
 * nothing animates), so it is cheap on low-memory TVs.
 */
internal class LoginBackgroundDrawable : Drawable() {
    private var cache: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onBoundsChange(bounds: Rect) { cache?.recycle(); cache = null }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bmp = cache ?: render(b.width(), b.height()).also { cache = it }
        canvas.drawBitmap(bmp, null, b, paint)
    }

    private fun render(w: Int, h: Int): Bitmap {
        val bw = (w / 2).coerceAtLeast(2); val bh = (h / 2).coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(bw / 1920f, bh / 1080f)                       // everything below is drawn in a 1920x1080 design space
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val clamp = Shader.TileMode.CLAMP

        p.shader = LinearGradient(0f, 0f, 0f, 1080f, 0xFF070A10.toInt(), 0xFF0C111D.toInt(), clamp)
        c.drawRect(0f, 0f, 1920f, 1080f, p); p.shader = null

        // Tilted poster tiles (abstract: tinted gradient + head-and-shoulders silhouette), faded out towards the right.
        val layer = c.saveLayer(0f, 0f, 1920f, 1080f, null)
        c.save(); c.rotate(-9f, 420f, 520f)
        val tints = intArrayOf(0xFF112D3A.toInt(), 0xFF1D3A7A.toInt(), 0xFF113836.toInt(), 0xFF1C2230.toInt(), 0xFF2B1C28.toInt(), 0xFF162846.toInt())
        var n = 0; var col = 0; var x = -260f
        while (x < 1400f) {
            var y = -300f + (col % 2) * 130f
            while (y < 1350f) {
                p.shader = LinearGradient(x, y, x, y + 250f, tints[(n * 5 + col * 2) % tints.size], 0xFF07081A.toInt(), clamp)
                c.drawRoundRect(x, y, x + 175f, y + 250f, 12f, 12f, p)
                p.shader = null; p.color = 0x70000000
                c.save(); c.clipRect(x, y, x + 175f, y + 250f)
                c.drawCircle(x + 88f, y + 105f, 34f, p)
                c.drawOval(x + 22f, y + 150f, x + 153f, y + 310f, p)
                c.restore()
                n++; y += 272f
            }
            x += 197f; col++
        }
        c.restore()
        p.shader = LinearGradient(0f, 0f, 1150f, 0f, 0xB0FFFFFF.toInt(), 0x00FFFFFF, clamp)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, 1920f, 1080f, p)
        p.xfermode = null; p.shader = null
        c.restoreToCount(layer)

        // Cyan glow behind the logo.
        p.shader = RadialGradient(560f, 500f, 640f, intArrayOf(0x5500D2FF, 0x260A8FB5, 0x00000000), floatArrayOf(0f, 0.55f, 1f), clamp)
        c.drawRect(0f, 0f, 1920f, 1080f, p)
        // Keep the right-hand side calm behind the login panel.
        p.shader = LinearGradient(760f, 0f, 1250f, 0f, 0x00070A10, 0xDD070A10.toInt(), clamp)
        c.drawRect(0f, 0f, 1920f, 1080f, p)
        // Top and bottom vignette.
        p.shader = LinearGradient(0f, 0f, 0f, 300f, 0xC0000000.toInt(), 0x00000000, clamp)
        c.drawRect(0f, 0f, 1920f, 300f, p)
        p.shader = LinearGradient(0f, 780f, 0f, 1080f, 0x00000000, 0xB0000000.toInt(), clamp)
        c.drawRect(0f, 780f, 1920f, 1080f, p)
        p.shader = null

        fun sweep(path: Path, width: Float, x0: Float, x1: Float, from: Int, to: Int) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
                shader = LinearGradient(x0, 0f, x1, 0f, from, to, clamp)
            }
            sp.strokeWidth = width * 3.5f; sp.maskFilter = BlurMaskFilter(width * 2.5f, BlurMaskFilter.Blur.NORMAL); sp.alpha = 110
            c.drawPath(path, sp)
            sp.maskFilter = null; sp.strokeWidth = width; sp.alpha = 255
            c.drawPath(path, sp)
        }
        val gold = 0xFFFFB800.toInt(); val goldFade = 0x00FFB800
        val cyan = 0xFF00D2FF.toInt(); val cyanFade = 0x0000D2FF
        sweep(Path().apply { moveTo(-60f, 640f); cubicTo(260f, 700f, 520f, 850f, 1000f, 965f) }, 8f, 0f, 1000f, gold, goldFade)
        sweep(Path().apply { moveTo(-60f, 700f); cubicTo(280f, 745f, 560f, 885f, 1040f, 995f) }, 4f, 0f, 1040f, gold, goldFade)
        sweep(Path().apply { moveTo(-60f, 790f); cubicTo(300f, 790f, 700f, 930f, 1300f, 1015f) }, 7f, 0f, 1300f, cyan, cyanFade)
        sweep(Path().apply { moveTo(900f, 1110f); cubicTo(1350f, 1005f, 1700f, 930f, 1990f, 790f) }, 7f, 900f, 1920f, cyanFade, cyan)
        sweep(Path().apply { moveTo(1000f, 1130f); cubicTo(1400f, 1040f, 1750f, 970f, 1990f, 860f) }, 3.5f, 1000f, 1920f, goldFade, gold)
        return bmp
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.OPAQUE
}
