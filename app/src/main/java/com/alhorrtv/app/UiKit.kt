package com.alhorrtv.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object UI {
    private fun pick(classic: String, cinematic: String): Int = Color.parseColor(if (AppTheme.isCinematic) cinematic else classic)
    // Deep-space palette from the supplied redesign (AlhorrColors): background, surface, card, focused surface.
    val BG: Int get() = Color.parseColor("#080E1E")
    val PANEL: Int get() = Color.parseColor("#CC131722")
    val PANEL2: Int get() = Color.parseColor("#1C2230")
    val STROKE: Int get() = Color.parseColor("#253048")
    val NEON = Color.parseColor("#00D2FF")
    val NEON_DEEP = Color.parseColor("#0A8FB5")
    val LIVE_RED = Color.parseColor("#FF3131")
    val TEXT = Color.WHITE
    val MUTED: Int get() = Color.parseColor("#A0AEC0")
    val MUTED_DIM = Color.parseColor("#64748B")
    val LIVE: Int get() = pick("#299BFF", "#2FB4FF")
    val MOVIE: Int get() = pick("#F24569", "#E8384F")
    val SERIES: Int get() = pick("#A34DFF", "#B05CFF")
    val BLUE: Int get() = pick("#4F7BFF", "#4F8CFF")
    val PINK: Int get() = pick("#E252A8", "#E8559E")
    val GREEN = Color.parseColor("#25D9BD")
    // Shared by the redesigned Series / Movies screens.
    val VIOLET: Int get() = NEON
    val VIOLET_DEEP: Int get() = NEON_DEEP
    val ELECTRIC: Int get() = pick("#5EA8FF", "#36D5FF")
    val FOCUS_RING: Int get() = NEON
    val GOLD = Color.parseColor("#FFB800")

    fun accent(kind: String): Int = when (kind) {
        Kinds.LIVE -> LIVE; Kinds.MOVIES -> MOVIE; Kinds.SERIES -> SERIES
        Kinds.FAVORITES -> PINK; Kinds.CONTINUE -> LIVE; else -> BLUE
    }
    fun alpha(color: Int, a: Int): Int = ColorUtils.setAlphaComponent(color, a)
}

/** Common full-screen host for every app screen, including the TV player. */
open class FullscreenActivity : Activity() {
    private var themeVersionAtCreate = 0
    /** Screens return false when recreating them would be harmful (the player must never restart because of a theme change). */
    protected open val followsTheme: Boolean get() = true
    /** Unified 960dp-wide landscape canvas so TVs, tablets, and Android phones render the exact same horizontal proportions. */
    protected open val usesTvCanvas: Boolean get() = true

    override fun attachBaseContext(base: Context) {
        if (!usesTvCanvas) {
            super.attachBaseContext(base)
            return
        }
        val dm = base.resources.displayMetrics
        val longSidePx = maxOf(dm.widthPixels, dm.heightPixels)
        val cfg = Configuration(base.resources.configuration)
        cfg.densityDpi = (longSidePx / 960f * 160f).toInt().coerceIn(120, 640)
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    override fun onCreate(state: Bundle?) {
        FreeCatalog.init(this)
        BackdropArt.init(this)
        AppTheme.init(this)
        themeVersionAtCreate = AppTheme.version
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        applyImmersiveFullscreen()
    }

    // ---- Focus memory -------------------------------------------------------------------------------------------
    // When the window comes back (from the player, another screen, or a touch/remote mode switch) Android moves the focus
    // to the FIRST focusable view, which is the back arrow. Remember the view that had focus and give it back, unless the
    // person has already pressed a key since returning.
    private var lastFocusedRef: java.lang.ref.WeakReference<View>? = null
    private var savedFocusRef: java.lang.ref.WeakReference<View>? = null
    private var focusListenerAdded = false
    /** True once a key was pressed on this screen since it was last resumed. Screens use it to avoid stealing focus from the person. */
    protected var userHasNavigated = false
        private set
    protected open val restoresFocus: Boolean get() = true

    private val focusTracker = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (newFocus != null) lastFocusedRef = java.lang.ref.WeakReference(newFocus)
    }

    override fun onStart() {
        super.onStart()
        if (!focusListenerAdded) { window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(focusTracker); focusListenerAdded = true }
    }

    private val restoreFocusTask = Runnable { restoreSavedFocus(0) }

    override fun onStop() {
        if (restoresFocus) savedFocusRef = lastFocusedRef
        window.decorView.removeCallbacks(restoreFocusTask)
        if (focusListenerAdded) {
            try { window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(focusTracker) } catch (_: Throwable) {}
            focusListenerAdded = false
        }
        super.onStop()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) userHasNavigated = true
        return super.dispatchKeyEvent(event)
    }

    private fun restoreSavedFocus(attempt: Int) {
        if (!restoresFocus || userHasNavigated || isFinishing || isDestroyed) return
        val v = savedFocusRef?.get() ?: return
        if (v.isAttachedToWindow && v.isShown && v.isFocusable && v.isEnabled && currentFocus !== v) v.requestFocus()
        val delays = longArrayOf(0, 100, 250, 500, 900)
        if (attempt + 1 < delays.size) window.decorView.postDelayed({ restoreSavedFocus(attempt + 1) }, delays[attempt + 1] - delays[attempt])
    }

    override fun onResume() {
        super.onResume()
        if (followsTheme && themeVersionAtCreate != AppTheme.version) { recreate(); return }
        applyImmersiveFullscreen()
        userHasNavigated = false
        if (savedFocusRef?.get() != null) window.decorView.post(restoreFocusTask)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ImageLoader.trimMemory(level)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveFullscreen()
    }

    protected fun applyImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}

internal object BackdropArt {
    @Volatile var spaceBitmap: android.graphics.Bitmap? = null
        private set

    fun init(context: Context) {
        if (spaceBitmap != null) return
        try {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            spaceBitmap = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.bg_art_space, opts)
        } catch (_: Throwable) { }
    }
}

/** Illustrated hexagonal star-lattice & cinema backdrop cached per view size for fast hardware-accelerated drawing. */
internal class HexPatternDrawable : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint()
    private val bmpMatrix = android.graphics.Matrix()
    private var shaders: Array<android.graphics.Shader>? = null
    private var cachedW = -1
    private var cachedH = -1

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val bmp = BackdropArt.spaceBitmap
        if (bmp != null && !bmp.isRecycled) {
            val bw = bmp.width.toFloat().coerceAtLeast(1f)
            val bh = bmp.height.toFloat().coerceAtLeast(1f)
            val scale = maxOf(w / bw, h / bh)
            val dx = (w - bw * scale) * 0.5f + bounds.left
            val dy = (h - bh * scale) * 0.5f + bounds.top
            bmpMatrix.reset()
            bmpMatrix.setScale(scale, scale)
            bmpMatrix.postTranslate(dx, dy)
            canvas.drawBitmap(bmp, bmpMatrix, paint)
            scrimPaint.color = 0x58060A18
            canvas.drawRect(bounds, scrimPaint)
            return
        }
        if (shaders == null || cachedW != bounds.width() || cachedH != bounds.height()) {
            cachedW = bounds.width(); cachedH = bounds.height()
            shaders = arrayOf(
                android.graphics.LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(0xFF070A10.toInt(), 0xFF0C111D.toInt(), 0xFF080A12.toInt()),
                    floatArrayOf(0f, 0.55f, 1f),
                    android.graphics.Shader.TileMode.CLAMP
                ),
                android.graphics.RadialGradient(
                    w * 0.88f, h * 0.06f, maxOf(w, h) * 0.58f,
                    0x2600D2FF, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP
                ),
                android.graphics.RadialGradient(
                    w * 0.10f, h * 0.92f, maxOf(w, h) * 0.52f,
                    0x180A8FB5, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP
                ),
                android.graphics.RadialGradient(
                    w * 0.50f, h * 0.0f, maxOf(w, h) * 0.40f,
                    0x12FFB800, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP
                )
            )
        }
        shaders!!.forEach { shader ->
            paint.shader = shader
            canvas.drawRect(bounds, paint)
        }
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
}

/** Full-screen cinematic backdrop shared by detail screens. */
internal class CinematicBackdrop(context: Context) : FrameLayout(context) {
    private val posterLayer = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0.34f
        visibility = View.GONE
    }

    init {
        background = HexPatternDrawable()
        addView(posterLayer, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC080B12.toInt(), 0xB80A0D16.toInt(), 0xF207090E.toInt())
            )
        }, LayoutParams(-1, -1))
    }

    fun setPoster(url: String?) {
        val value = url.orEmpty().trim()
        if (value.isBlank() || !(value.startsWith("http://", true) || value.startsWith("https://", true))) return
        posterLayer.visibility = View.VISIBLE
        ImageLoader.bind(context, posterLayer, value, UI.BG, priority = true, maxPx = 1280, fade = true)
    }
}

/** Branded placeholder shown immediately when a poster is missing or slow. */
internal class PosterFallbackDrawable(label: String, kind: String) : android.graphics.drawable.Drawable() {
    private val titleHash = label.hashCode().ushr(1)
    private val palette = when ((titleHash + kind.hashCode()).mod(4)) {
        0 -> intArrayOf(Color.parseColor("#112D3A"), Color.parseColor("#0A0D16"), Color.parseColor("#00D2FF"))
        1 -> intArrayOf(Color.parseColor("#3B1628"), Color.parseColor("#0A0C14"), Color.parseColor("#E8384F"))
        2 -> intArrayOf(Color.parseColor("#113836"), Color.parseColor("#0A0D16"), Color.parseColor("#25D9BD"))
        else -> intArrayOf(Color.parseColor("#162846"), Color.parseColor("#0A0D16"), Color.parseColor("#4F8CFF"))
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringRect = android.graphics.RectF()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        paint.style = Paint.Style.FILL
        paint.shader = android.graphics.LinearGradient(0f, 0f, w, h, palette[0], palette[1], android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = android.graphics.RadialGradient(w * .75f, h * .18f, maxOf(w, h) * .68f, palette[2], Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
        paint.alpha = 72; canvas.drawRect(bounds, paint); paint.shader = null; paint.alpha = 255

        paint.color = Color.argb(30, 255, 255, 255); paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f
        ringRect.set(w * .07f, h * .05f, w * .93f, h * .95f)
        canvas.drawRoundRect(ringRect, w * .07f, w * .07f, paint)

        paint.style = Paint.Style.FILL; paint.color = UI.alpha(palette[2], 45)
        val cx = w / 2f; val cy = h * .46f; val r = minOf(w, h) * .16f
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = UI.alpha(palette[2], 190)
        canvas.drawCircle(cx, cy, r, paint)

        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        val tri = Path().apply {
            moveTo(cx - r * .28f, cy - r * .36f)
            lineTo(cx + r * .44f, cy)
            lineTo(cx - r * .28f, cy + r * .36f)
            close()
        }
        canvas.drawPath(tri, paint)

        paint.color = Color.argb(190, 255, 255, 255); paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = (w * .072f).coerceIn(10f, 22f)
        canvas.drawText("ALHUR TV", cx, cy + r + h * .11f, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

object Kinds {
    const val LIVE = "get_live_streams"
    const val MOVIES = "get_vod_streams"
    const val SERIES = "get_series"
    const val FAVORITES = "favorites"
    const val CONTINUE = "continue"
    const val ALL = "all"
    val CONTENT = listOf(LIVE, MOVIES, SERIES)

    fun title(kind: String): String = when (kind) {
        LIVE -> "البث المباشر"; MOVIES -> "الأفلام"; SERIES -> "المسلسلات"
        FAVORITES -> "المفضلة"; CONTINUE -> "متابعة المشاهدة"; else -> "البحث الشامل"
    }
    fun icon(kind: String): String = when (kind) {
        LIVE -> "📡"; MOVIES -> "🎬"; SERIES -> "📺"; FAVORITES -> "♥"; CONTINUE -> "⏱"; else -> "🔍"
    }
    fun unit(kind: String): String = when (kind) { LIVE -> "قناة"; MOVIES -> "فيلم"; SERIES -> "مسلسل"; else -> "مقطع" }
    fun badge(kind: String): String = when (kind) { LIVE -> "مباشر"; MOVIES -> "فيلم"; SERIES -> "مسلسل"; else -> "" }
}

internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
internal fun Context.dpf(v: Int): Float = v * resources.displayMetrics.density

internal fun Context.roundBg(fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill); cornerRadius = dpf(radiusDp)
        if (strokeDp > 0) setStroke(maxOf(1, dp(strokeDp)), strokeColor)
    }

internal fun Context.gradientBg(colors: IntArray, radiusDp: Int, orientation: GradientDrawable.Orientation,
                                strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
    GradientDrawable(orientation, colors).apply {
        cornerRadius = dpf(radiusDp)
        if (strokeDp > 0) setStroke(maxOf(1, dp(strokeDp)), strokeColor)
    }

internal fun View.clipRounded(radiusPx: Float) {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, radiusPx) }
    }
    clipToOutline = true
}

internal fun Context.tv(value: String, sp: Float, color: Int = UI.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = value; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

internal fun View.makeFocusable(scale: Float = 1.06f, ringColor: Int = UI.NEON, ringDp: Int = 2, radiusDp: Int = 12) {
    isFocusable = true; isClickable = true; isFocusableInTouchMode = false
    foreground = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            setColor(UI.alpha(ringColor, 26))
            setStroke(context.dp(maxOf(2, ringDp)), ringColor)
            cornerRadius = context.dpf(radiusDp)
        })
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }
    setOnFocusChangeListener { v, focused ->
        if (android.os.Build.VERSION.SDK_INT >= 21) v.elevation = if (focused) context.dpf(6) else 0f
    }
}

/** Button from the redesign: sleek glass card when idle, glowing neon-cyan gradient with dark text when focused. */
internal fun TextView.neonButton(primary: Boolean, radiusDp: Int = 10) {
    val ctx = context
    val idle = if (primary) ctx.gradientBg(intArrayOf(UI.PANEL2, UI.alpha(UI.NEON_DEEP, 70)), radiusDp, GradientDrawable.Orientation.TL_BR, UI.alpha(UI.NEON, 140), 1)
        else ctx.roundBg(UI.alpha(UI.PANEL2, 160), radiusDp, UI.STROKE, 1)
    val focusedBg = ctx.gradientBg(intArrayOf(UI.NEON, Color.parseColor("#5CE6FF")), radiusDp, GradientDrawable.Orientation.TL_BR)
    background = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focusedBg)
        addState(intArrayOf(android.R.attr.state_pressed), focusedBg)
        addState(intArrayOf(), idle)
    }
    setTextColor(UI.TEXT)
    makeFocusable(1f, UI.NEON, 0, radiusDp)
    val base = onFocusChangeListener
    setOnFocusChangeListener { v, focused ->
        base?.onFocusChange(v, focused)
        setTextColor(if (focused) Color.parseColor("#001018") else UI.TEXT)
    }
}

internal fun Context.chip(label: String, selected: Boolean, accent: Int): TextView =
    tv(label, 13f, if (selected) Color.parseColor("#001018") else UI.MUTED, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8))
        background = if (selected) roundBg(UI.NEON, 20) else roundBg(UI.PANEL2, 20, UI.STROKE, 1)
        makeFocusable(1.04f, UI.NEON, 2, 20)
    }

internal fun Context.actionButton(label: String, accent: Int, filled: Boolean): TextView =
    tv(label, 15f, if (filled) Color.BLACK else UI.TEXT, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(16), dp(12), dp(16), dp(12))
        background = if (filled) roundBg(accent, 14) else roundBg(UI.alpha(accent, 30), 14, accent, 1)
        makeFocusable()
    }

internal fun Context.circleIcon(symbol: String, accent: Int, sizeDp: Int): TextView =
    tv(symbol, sizeDp * 0.40f, accent, true).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(UI.alpha(accent, 48)) }
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

/** A frame whose height is always width * ratio (poster 2:3 => 1.5, thumbnail 16:9 => 0.5625). */
internal class AspectFrame(context: Context, private val ratio: Float) : FrameLayout(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((width * ratio).toInt(), MeasureSpec.EXACTLY))
    }
}

internal object Nav {
    private fun creds(i: Intent, server: String, user: String, pass: String): Intent =
        i.putExtra("server", server).putExtra("username", user).putExtra("password", pass)

    fun browse(ctx: Context, kind: String, server: String, user: String, pass: String,
               openCategories: Boolean = false, focusSearch: Boolean = false) {
        ctx.startActivity(creds(Intent(ctx, BrowseActivity::class.java), server, user, pass)
            .putExtra("kind", kind).putExtra("open_categories", openCategories).putExtra("focus_search", focusSearch))
    }

    fun open(ctx: Context, item: ContentEntity, server: String, user: String, pass: String) {
        when (item.kind) {
            Kinds.SERIES -> ctx.startActivity(creds(Intent(ctx, SeriesDetailActivity::class.java), server, user, pass)
                .putExtra("series_id", item.contentId).putExtra("series_name", item.name).putExtra("series_image", item.imageUrl))
            Kinds.MOVIES -> ctx.startActivity(creds(Intent(ctx, MovieDetailActivity::class.java), server, user, pass)
                .putExtra("vod_id", item.contentId).putExtra("title", item.name).putExtra("image", item.imageUrl).putExtra("extension", item.extension))
            else -> ctx.startActivity(creds(Intent(ctx, PlayerActivity::class.java), server, user, pass)
                .putExtra("url", buildMediaUrl(server, "live", user, pass, item.contentId, item.extension))
                .putExtra("title", item.name).putExtra("image", item.imageUrl).putExtra("content_id", item.contentId).putExtra("category_id", item.category)
                .putExtra("mark_kind", Kinds.LIVE).putExtra("mark_id", item.contentId))
        }
    }
}


/** Ratings arrive as text from the provider ("7.8", "8", "0", "N/A"); everything is normalised to a 0-10 scale. */
internal object Rating {
    /** Prefers the 10-point value, falls back to the 5-point one (x2). Returns "" when there is no usable rating. */
    fun parse(rating: String, rating5: String): String {
        val ten = rating.trim().replace(',', '.').toDoubleOrNull()
        val five = rating5.trim().replace(',', '.').toDoubleOrNull()
        val v = when {
            ten != null && ten > 0.0 -> ten
            five != null && five > 0.0 -> five * 2.0
            else -> return ""
        }.coerceAtMost(10.0)
        return String.format(java.util.Locale.US, "%.1f", v)
    }
    fun value(r: String): Double = r.toDoubleOrNull() ?: 0.0
}
