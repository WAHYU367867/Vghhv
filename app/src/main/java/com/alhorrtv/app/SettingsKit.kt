package com.alhorrtv.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Pill switch (track + knob) used by the Settings rows. The row around it owns focus and clicks. */
internal class NeonSwitch(context: Context) : View(context) {
    var checked: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()

    init { isFocusable = false; isClickable = false }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = resources.displayMetrics.density
        setMeasuredDimension((46 * d).toInt(), (26 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = resources.displayMetrics.density
        track.color = if (checked) Neon.TEAL else Color.argb(90, 255, 255, 255)
        rect.set(0f, 0f, w, h); canvas.drawRoundRect(rect, h / 2f, h / 2f, track)
        val r = h / 2f - 3f * d
        canvas.drawCircle(if (checked) w - h / 2f else h / 2f, h / 2f, r, knob)
    }
}
