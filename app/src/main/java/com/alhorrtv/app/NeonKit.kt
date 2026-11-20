package com.alhorrtv.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/**
 * Light-weight "neon glass" surface used by the redesigned Settings screen: a translucent vertical-gradient fill, a
 * left-to-right gradient border and an optional glow made of two wider, fainter strokes (no blur filters, so it stays
 * cheap and works with hardware acceleration on low-memory Android TV boxes).
 * [glowW] is always reserved inside the bounds, so a view keeps the same body size whether or not its glow is drawn.
 */
internal class NeonDrawable(
    private val radius: Float,
    private val fillTop: Int,
    private val fillBottom: Int,
    private val border: IntArray,
    private val borderW: Float,
    private val glowW: Float,
    private val glowAlpha: Int
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var corner = radius

    override fun onBoundsChange(b: Rect) {
        val inset = glowW + borderW / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        corner = (radius - inset).coerceAtLeast(2f)
        fillPaint.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(), fillTop, fillBottom, Shader.TileMode.CLAMP)
        strokePaint.shader = LinearGradient(b.left.toFloat(), 0f, b.right.toFloat().coerceAtLeast(b.left + 1f), 0f, border, null, Shader.TileMode.CLAMP)
    }

    override fun draw(canvas: Canvas) {
        if (rect.isEmpty) return
        canvas.drawRoundRect(rect, corner, corner, fillPaint)
        if (glowW > 0f && glowAlpha > 0) {
            strokePaint.strokeWidth = borderW + glowW * 2f; strokePaint.alpha = glowAlpha / 3
            canvas.drawRoundRect(rect, corner, corner, strokePaint)
            strokePaint.strokeWidth = borderW + glowW; strokePaint.alpha = glowAlpha
            canvas.drawRoundRect(rect, corner, corner, strokePaint)
        }
        strokePaint.strokeWidth = borderW; strokePaint.alpha = 255
        canvas.drawRoundRect(rect, corner, corner, strokePaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Shared palette matching the rest of the app (obsidian surfaces, electric cyan focus/primary, teal active, gold highlights). */
internal object Neon {
    val VIOLET = Color.parseColor("#00D2FF")
    val MAGENTA = Color.parseColor("#0A8FB5")
    val BLUE = Color.parseColor("#3D7BFF")
    val CYAN = Color.parseColor("#2FD8FF")
    val TEAL = Color.parseColor("#18E0C0")
    val GOLD = Color.parseColor("#FFB800")
    val NEON = Color.parseColor("#00D2FF")
    val RED = Color.parseColor("#FF7B8B")
    val TEXT = Color.WHITE
    val MUTED: Int get() = UI.MUTED

    val BORDER_IDLE: IntArray get() = intArrayOf(UI.STROKE, UI.NEON_DEEP, UI.STROKE)
    val BORDER_FOCUS: IntArray get() = intArrayOf(NEON, CYAN, TEAL)
    val BORDER_PANEL: IntArray get() = intArrayOf(UI.STROKE, UI.alpha(NEON, 160), UI.STROKE)
    val BORDER_SELECTED: IntArray get() = intArrayOf(NEON, TEAL, GOLD)

    private fun flat(radiusPx: Float, fill: Int, stroke: Int = 0, strokeDp: Float = 0f) = GradientDrawable().apply {
        cornerRadius = radiusPx; setColor(fill)
        if (strokeDp > 0f) setStroke((strokeDp * android.content.res.Resources.getSystem().displayMetrics.density).toInt().coerceAtLeast(1), stroke)
    }

    /** Settings card: obsidian card fill with slate hairline border; focused = elevated card + 2dp cyan focus border. */
    fun card(radiusPx: Float, borderPx: Float, glowPx: Float): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), flat(radiusPx, UI.PANEL2, UI.NEON, 2f))
        addState(intArrayOf(), flat(radiusPx, UI.PANEL, UI.STROKE, 1f))
    }

    /** Sidebar entry: idle transparent, selected = card surface with cyan hairline border, focused = elevated surface + 2dp cyan border. */
    fun nav(radiusPx: Float, borderPx: Float, glowPx: Float): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), flat(radiusPx, UI.STROKE, UI.NEON, 2f))
        addState(intArrayOf(android.R.attr.state_selected), flat(radiusPx, UI.PANEL2, UI.alpha(UI.NEON, 120), 1f))
        addState(intArrayOf(), flat(radiusPx, Color.TRANSPARENT))
    }

    /** Action button: obsidian card with subtle tinted fill/edge; focused = solid electric cyan with crisp contrast. */
    fun button(radiusPx: Float, borderPx: Float, glowPx: Float, top: Int, bottom: Int, edge: IntArray): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), flat(radiusPx, UI.NEON, Color.WHITE, 1.5f))
        addState(intArrayOf(), GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = radiusPx
            val border = edge.firstOrNull() ?: UI.STROKE
            setStroke((1.2f * android.content.res.Resources.getSystem().displayMetrics.density).toInt().coerceAtLeast(1), border)
        })
    }

    /** Big static panel with obsidian background and subtle slate border. */
    fun panel(radiusPx: Float, borderPx: Float, glowPx: Float): Drawable = flat(radiusPx, UI.PANEL, UI.STROKE, 1f)
}
