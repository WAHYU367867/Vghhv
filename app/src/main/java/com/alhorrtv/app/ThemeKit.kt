package com.alhorrtv.app

import android.content.Context

/**
 * App-wide visual theme. Two complete themes share the same screens, data and player:
 *  - CLASSIC   (المظهر الأساسي)          — the original design.
 *  - CINEMATIC (المظهر السينمائي الحديث) — burgundy / black glass design with purple, blue, cyan and gold accents.
 * The choice is stored in the "appearance" preferences (key "cinematic") and survives restarts and logins.
 * [UI] colours and the shared backdrops read [isCinematic], so every screen restyles when it is (re)created.
 */
object AppTheme {
    const val CLASSIC = 0
    const val CINEMATIC = 1

    /** The classic theme was removed: the modern cinematic design is the only one. */
    val current: Int get() = CINEMATIC
    const val version: Int = 0
    val isCinematic: Boolean get() = true

    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {}
    fun set(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") theme: Int) {}
}

/** Round translucent bubble with a tinted vector icon (used by the cinematic Home sidebar and toolbar). */
internal fun Context.iconBubble(res: Int, tint: Int, sizeDp: Int): android.widget.ImageView = android.widget.ImageView(this).apply {
    setImageResource(res); setColorFilter(tint)
    val pad = dp(sizeDp / 5 + 1); setPadding(pad, pad, pad, pad)
    background = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(android.graphics.Color.argb(46, android.graphics.Color.red(tint), android.graphics.Color.green(tint), android.graphics.Color.blue(tint)))
        setStroke(dp(1), android.graphics.Color.argb(130, android.graphics.Color.red(tint), android.graphics.Color.green(tint), android.graphics.Color.blue(tint)))
    }
    layoutParams = android.widget.LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}
