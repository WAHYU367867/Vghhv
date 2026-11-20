package com.alhorrtv.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Shared pieces of the movie and series detail screens (ALHUR TV purple on the existing dark theme). */
internal object DetailUi {
    val PURPLE: Int get() = UI.NEON
    private fun pick(classic: String, cinematic: String): Int = Color.parseColor(if (AppTheme.isCinematic) cinematic else classic)
    val PURPLE_DEEP: Int get() = UI.NEON_DEEP
    val CARD: Int get() = UI.PANEL
    val CARD_DEEP: Int get() = UI.PANEL2
    val CARD_STROKE: Int get() = UI.STROKE
    val PANEL_STROKE: Int get() = UI.STROKE
    val SOFT_TEXT: Int get() = UI.MUTED
    val GOLD: Int = Color.parseColor("#FFB800")
}

// ---------------------------------------------------------------- data helpers

/** First non-blank value among [keys]; the literal string "null" that some providers send counts as blank. */
internal fun JSONObject?.str(vararg keys: String): String {
    if (this == null) return ""
    for (key in keys) {
        val value = optString(key, "").trim()
        if (value.isNotEmpty() && !value.equals("null", ignoreCase = true)) return value
    }
    return ""
}

/** First http(s) image among [keys]; providers send either a string or an array of strings. */
internal fun JSONObject?.firstImage(vararg keys: String): String {
    if (this == null) return ""
    for (key in keys) {
        when (val value = opt(key)) {
            is JSONArray -> for (i in 0 until value.length()) {
                val candidate = value.optString(i, "").trim()
                if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) return candidate
            }
            is String -> {
                val candidate = value.trim()
                if (candidate.startsWith("http://", true) || candidate.startsWith("https://", true)) return candidate
            }
        }
    }
    return ""
}

internal fun cleanRating(raw: String): String {
    val value = raw.trim().replace(',', '.').toDoubleOrNull() ?: return ""
    return if (value > 0.0) String.format(Locale.US, "%.1f", value) else ""
}

internal fun splitNames(raw: String): List<String> =
    raw.split(",", "،", "/", "|").map { it.trim() }.filter { it.isNotEmpty() && !it.equals("null", true) }.distinct()

private fun secondsFromClock(text: String): Long {
    val parts = text.trim().split(":")
    if (parts.size != 3) return 0L
    val h = parts[0].trim().toLongOrNull() ?: return 0L
    val m = parts[1].trim().toLongOrNull() ?: return 0L
    val s = parts[2].trim().toLongOrNull() ?: 0L
    return h * 3600 + m * 60 + s
}

/** "1 س 52 د" from seconds, or from an "01:52:30" style string. Empty when unknown. */
internal fun formatDuration(seconds: Long, clock: String): String {
    val total = if (seconds > 0) seconds else secondsFromClock(clock)
    if (total <= 0) return ""
    val h = total / 3600; val m = (total % 3600) / 60
    return when {
        h > 0 && m > 0 -> "$h س $m د"
        h > 0 -> "$h س"
        m > 0 -> "$m د"
        else -> ""
    }
}

/** "52 دقيقة" for an episode card, empty when unknown. */
internal fun formatMinutes(seconds: Long, clock: String): String {
    val total = if (seconds > 0) seconds else secondsFromClock(clock)
    val minutes = total / 60
    return if (minutes > 0) "$minutes دقيقة" else ""
}

// ---------------------------------------------------------------- building blocks

internal enum class HeroBtnStyle { PRIMARY, SECONDARY, FAV_ACTIVE, FOLLOW_ACTIVE }

internal fun Context.detailBrand(): View {
    if (AppTheme.isCinematic) return ImageView(this).apply {
        setImageResource(R.drawable.logo_alhorr_login); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "الحر الأصلي"
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(46))
    }
    return TextView(this).apply {
        val label = SpannableString("ALHUR TV")
        label.setSpan(ForegroundColorSpan(DetailUi.PURPLE), 6, 8, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text = label; textSize = 19f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        textDirection = View.TEXT_DIRECTION_LTR
    }
}

internal fun Context.detailHeader(title: String, onBack: () -> Unit): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
    addView(tv("→", 20f, UI.TEXT, true).apply {
        gravity = Gravity.CENTER
        background = roundBg(UI.PANEL2, 22, UI.STROKE, 1)
        contentDescription = "رجوع"
        makeFocusable(1.08f, UI.NEON, 2, 22); setOnClickListener { onBack() }
    }, LinearLayout.LayoutParams(dp(44), dp(44)))
    addView(tv(title, 19f, UI.TEXT, true).apply {
        setPadding(dp(14), 0, dp(14), 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    addView(detailBrand())
}

internal fun Context.heroBackButton(onBack: () -> Unit): TextView =
    tv("→", 18f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        background = roundBg(0xD9131722.toInt(), 12, 0xFF253048.toInt(), 1)
        contentDescription = "رجوع"
        makeFocusable(1.06f, UI.NEON, 2, 12)
        setOnClickListener { onBack() }
    }

internal fun Context.detailPoster(): ImageView = ImageView(this).apply {
    scaleType = ImageView.ScaleType.CENTER_CROP
    background = roundBg(0xFF131722.toInt(), 16, 0xFF253048.toInt(), 2)
    clipRounded(dpf(16))
}

internal fun Context.detailButton(label: String, primary: Boolean): TextView =
    heroActionButton(label, if (primary) HeroBtnStyle.PRIMARY else HeroBtnStyle.SECONDARY)

internal fun Context.heroActionButton(label: String, style: HeroBtnStyle = HeroBtnStyle.SECONDARY): TextView =
    tv(label, 13.5f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        applyHeroButtonStyle(style)
    }

internal fun TextView.applyHeroButtonStyle(style: HeroBtnStyle) {
    val ctx = context
    val idleBg: android.graphics.drawable.Drawable
    val focusBg: android.graphics.drawable.Drawable
    val idleColor: Int
    val focusColor: Int
    when (style) {
        HeroBtnStyle.PRIMARY -> {
            idleBg = ctx.roundBg(0xFF00D2FF.toInt(), 12)
            focusBg = ctx.roundBg(0xFF36D5FF.toInt(), 12, Color.WHITE, 2)
            idleColor = 0xFF001018.toInt()
            focusColor = 0xFF001018.toInt()
        }
        HeroBtnStyle.FAV_ACTIVE -> {
            idleBg = ctx.roundBg(0x33E8384F, 12, 0xFFE8384F.toInt(), 1)
            focusBg = ctx.roundBg(0x4DE8384F, 12, 0xFF00D2FF.toInt(), 2)
            idleColor = 0xFFFF7B8B.toInt()
            focusColor = 0xFFFF7B8B.toInt()
        }
        HeroBtnStyle.FOLLOW_ACTIVE -> {
            idleBg = ctx.roundBg(0x3325D9BD, 12, 0xFF25D9BD.toInt(), 1)
            focusBg = ctx.roundBg(0x4D25D9BD, 12, 0xFF00D2FF.toInt(), 2)
            idleColor = 0xFF25D9BD.toInt()
            focusColor = 0xFF25D9BD.toInt()
        }
        HeroBtnStyle.SECONDARY -> {
            idleBg = ctx.roundBg(0xFF131722.toInt(), 12, 0xFF253048.toInt(), 1)
            focusBg = ctx.roundBg(0xFF1C2230.toInt(), 12, 0xFF00D2FF.toInt(), 2)
            idleColor = Color.WHITE
            focusColor = Color.WHITE
        }
    }
    background = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), focusBg)
        addState(intArrayOf(android.R.attr.state_pressed), focusBg)
        addState(intArrayOf(), idleBg)
    }
    setTextColor(if (isFocused) focusColor else idleColor)
    isFocusable = true
    isClickable = true
    isFocusableInTouchMode = false
    setOnFocusChangeListener { v, focused ->
        setTextColor(if (focused) focusColor else idleColor)
        if (android.os.Build.VERSION.SDK_INT >= 21) v.elevation = if (focused) ctx.dpf(6) else 0f
    }
}

internal fun Context.pill(text: String, color: Int = UI.TEXT): TextView =
    tv(text, 12f, color, true).apply {
        gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(10), dp(4), dp(10), dp(4))
        background = roundBg(0x1AFFFFFF, 8)
    }

internal fun Context.ratingBadge(text: String): TextView =
    tv(text, 11.5f, 0xFFFFB800.toInt(), true).apply {
        gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(10), dp(4), dp(10), dp(4))
        background = roundBg(0x33FFB800, 8, 0x66FFB800, 1)
    }

internal fun Context.glassBadge(text: String): TextView =
    tv(text, 11.5f, 0xFFE2E8F0.toInt(), true).apply {
        gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(10), dp(4), dp(10), dp(4))
        background = roundBg(0x1AFFFFFF, 8)
    }

internal fun Context.seasonsBadge(text: String): TextView =
    tv(text, 11.5f, 0xFFE2B8FF.toInt(), true).apply {
        gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(10), dp(4), dp(10), dp(4))
        background = roundBg(0x33B05CFF, 8, 0x66B05CFF, 1)
    }

internal fun Context.genreBadge(text: String): TextView =
    tv(text, 11.5f, 0xFF00D2FF.toInt(), true).apply {
        gravity = Gravity.CENTER; maxLines = 1; setPadding(dp(10), dp(4), dp(10), dp(4))
        background = roundBg(0x2600D2FF, 8, 0x4D00D2FF, 1)
    }

/** Glass card that holds a detail-screen section when needed. */
internal fun Context.detailSectionBg(): GradientDrawable = roundBg(UI.alpha(DetailUi.CARD, 208), 18, DetailUi.PANEL_STROKE, 1).let { it as GradientDrawable }

internal fun Context.sectionTitle(text: String): TextView =
    tv(text, 17f, UI.TEXT, true).apply { setPadding(0, dp(8), 0, dp(10)) }

/** Title / description text: Arabic aligns right, English aligns left, like the reference design. */
internal fun TextView.alignToContent() {
    textAlignment = View.TEXT_ALIGNMENT_TEXT_START
    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
}

/** Wraps its children onto several rows (RTL aware) so the rating / year / genre pills never run off screen. */
internal class FlowLayout(context: Context) : ViewGroup(context) {
    var hGap = 0
    var vGap = 0

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthSpec)
        var x = 0; var y = 0; var rowHeight = 0; var usedWidth = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val cw = child.measuredWidth; val ch = child.measuredHeight
            if (x > 0 && x + cw > maxWidth) { y += rowHeight + vGap; x = 0; rowHeight = 0 }
            x += cw + hGap; rowHeight = maxOf(rowHeight, ch); usedWidth = maxOf(usedWidth, x - hGap)
        }
        val width = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY) maxWidth else minOf(usedWidth, maxWidth)
        setMeasuredDimension(width, y + rowHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var x = 0; var y = 0; var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val cw = child.measuredWidth; val ch = child.measuredHeight
            if (x > 0 && x + cw > width) { y += rowHeight + vGap; x = 0; rowHeight = 0 }
            val left = if (rtl) width - x - cw else x
            child.layout(left, y, left + cw, y + ch)
            x += cw + hGap; rowHeight = maxOf(rowHeight, ch)
        }
    }
}

/** Description with a real "عرض المزيد / عرض أقل" toggle that only appears when the text is actually cut. */
internal class ExpandableText(context: Context, private val collapsedLines: Int) : LinearLayout(context) {
    private val body: TextView = context.tv("", 13f, 0xFFCBD5E1.toInt()).apply {
        setLineSpacing(0f, 1.25f); alignToContent()
        maxLines = collapsedLines; ellipsize = TextUtils.TruncateAt.END
    }
    private val toggle: TextView = context.tv("", 12.5f, 0xFF00D2FF.toInt(), true).apply {
        setPadding(0, context.dp(6), 0, context.dp(2)); visibility = View.GONE
        makeFocusable(1.03f)
    }
    private var expanded = false

    init {
        orientation = VERTICAL
        addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(toggle, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        toggle.setOnClickListener { expanded = !expanded; render() }
        body.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> evaluate() }
    }

    fun setContent(text: String, muted: Boolean = false) {
        body.text = text
        body.setTextColor(if (muted) UI.MUTED else 0xFFCBD5E1.toInt())
        expanded = false
        visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        render()
    }

    private fun render() {
        body.maxLines = if (expanded) Int.MAX_VALUE else collapsedLines
        toggle.text = if (expanded) "عرض أقل" else "عرض المزيد"
        body.post { evaluate() }
    }

    private fun evaluate() {
        val layout = body.layout ?: return
        val last = layout.lineCount - 1
        val cut = last >= 0 && layout.getEllipsisCount(last) > 0
        val wanted = if (expanded || cut) View.VISIBLE else View.GONE
        if (toggle.visibility != wanted) toggle.visibility = wanted
    }
}

/** Loading / empty / error block with a real retry button. Hidden when the content is ready. */
internal class DetailStateView(context: Context) : LinearLayout(context) {
    private val spinner = ProgressBar(context).apply { isIndeterminate = true }
    private val message: TextView = context.tv("", 13.5f, UI.MUTED).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
    private val retry: TextView = context.detailButton("إعادة المحاولة", false)

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(context.dp(16), context.dp(22), context.dp(16), context.dp(22))
        addView(spinner, LayoutParams(context.dp(34), context.dp(34)))
        addView(message, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(10) })
        addView(retry, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(12) })
        visibility = View.GONE
    }

    fun loading(text: String) {
        spinner.visibility = View.VISIBLE; message.text = text; retry.visibility = View.GONE; visibility = View.VISIBLE
    }

    fun empty(text: String, onRetry: (() -> Unit)? = null) = show(text, onRetry)
    fun error(text: String, onRetry: (() -> Unit)?) = show(text, onRetry)

    private fun show(text: String, onRetry: (() -> Unit)?) {
        spinner.visibility = View.GONE; message.text = text
        if (onRetry != null) { retry.visibility = View.VISIBLE; retry.setOnClickListener { onRetry() } } else retry.visibility = View.GONE
        visibility = View.VISIBLE
    }

    fun hide() { visibility = View.GONE }
}

/** Full-bleed cinematic hero banner matching Web MovieDetailScreen & SeriesDetailScreen. */
internal fun Context.buildHeroBanner(
    poster: View,
    textColumn: View,
    posterWidthDp: Int,
    posterHeightDp: Int,
    onBack: () -> Unit
): Pair<FrameLayout, ImageView> {
    val banner = FrameLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
    val heroBg = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0.45f
        visibility = View.GONE
    }
    banner.addView(heroBg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    // Bottom gradient scrim
    banner.addView(View(this).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xFF0A0C10.toInt(), 0xB30A0C10.toInt(), 0x660A0C10)
        )
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    // RTL right-to-left gradient scrim
    banner.addView(View(this).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.RIGHT_LEFT,
            intArrayOf(0xE60A0C10.toInt(), 0x660A0C10, 0x000A0C10)
        )
    }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

    val inner = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(28), dp(16), dp(28), dp(26))
    }
    val backBtn = heroBackButton(onBack)
    inner.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

    val heroRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        gravity = Gravity.TOP
    }
    heroRow.addView(poster, LinearLayout.LayoutParams(dp(posterWidthDp), dp(posterHeightDp)))
    heroRow.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(22) })
    inner.addView(heroRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })

    banner.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return banner to heroBg
}

/** Poster (left) + text (right) panel. */
internal fun Context.detailHero(wide: Boolean, poster: View, text: View, posterWidthDp: Int, posterHeightDp: Int): LinearLayout =
    LinearLayout(this).apply {
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = roundBg(UI.alpha(DetailUi.CARD, 208), 20, DetailUi.PANEL_STROKE, 1)
        if (wide) {
            orientation = LinearLayout.HORIZONTAL
            addView(poster, LinearLayout.LayoutParams(dp(posterWidthDp), dp(posterHeightDp)))
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(22) })
        } else {
            orientation = LinearLayout.VERTICAL
            addView(poster, LinearLayout.LayoutParams(dp(posterWidthDp), dp(posterHeightDp)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })
            addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

/** Web-style cast cards: rounded #131722 card with cyan circle initials and actor name. */
internal fun Context.castRow(names: List<String>): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    names.take(12).forEach { name ->
        val initials = name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = roundBg(0xFF131722.toInt(), 12, 0xFF253048.toInt(), 1)
        }
        card.addView(tv(initials.ifBlank { "★" }, 12.5f, 0xFF00D2FF.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x2600D2FF)
                setStroke(dp(1), 0x6600D2FF)
            }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        card.addView(tv(name, 11.5f, 0xFFE2E8F0.toInt(), true).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setPadding(0, dp(8), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(card, LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, dp(10), 0) })
    }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
        addView(row)
    }
}

/** Similar titles from the same category in the local database (real data, each opens its own detail screen). */
internal fun Context.similarRow(items: List<ContentEntity>, onClick: (ContentEntity) -> Unit): View {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    items.forEach { item ->
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val frame = AspectFrame(this, 1.5f).apply {
            background = roundBg(UI.PANEL2, 12, UI.STROKE, 1); clipRounded(dpf(12))
            makeFocusable(1.06f, UI.NEON, 2, 12); contentDescription = item.name
            setOnClickListener { onClick(item) }
        }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ImageLoader.bind(this, image, item.imageUrl, UI.PANEL2, item.name, item.kind)
        column.addView(frame, LinearLayout.LayoutParams(dp(116), ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(tv(item.name, 11.5f, UI.TEXT, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG; setPadding(dp(2), dp(6), dp(2), 0)
        }, LinearLayout.LayoutParams(dp(116), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), dp(12), dp(4)) })
    }
    return HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false
        addView(row)
    }
}

/** Dark styled info modal matching Web MovieDetailScreen / SeriesDetailScreen. */
internal fun Context.showStyledInfoDialog(title: String, rows: List<Pair<String, String>>, plot: String) {
    val dialog = android.app.Dialog(this)
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = roundBg(0xFF131722.toInt(), 16, 0xFF253048.toInt(), 1)
    }
    val head = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
    }
    head.addView(tv(title, 15f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val closeBtn = tv("×", 20f, 0xFF94A3B8.toInt(), true).apply {
        gravity = Gravity.CENTER
        makeFocusable(1.06f, UI.NEON, 1, 8)
        setOnClickListener { dialog.dismiss() }
    }
    head.addView(closeBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
    card.addView(head)
    card.addView(View(this).apply { setBackgroundColor(0xFF253048.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

    val body = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(18))
    }
    rows.filter { it.second.isNotBlank() }.forEach { (k, v) ->
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(tv(k, 12.5f, 0xFFA0AEC0.toInt()), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(View(this).apply {}, LinearLayout.LayoutParams(dp(16), 1, 1f))
        row.addView(tv(v, 12.5f, Color.WHITE, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textAlignment = View.TEXT_ALIGNMENT_VIEW_END },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(row)
    }
    if (plot.isNotBlank()) {
        body.addView(View(this).apply { setBackgroundColor(0xFF253048.toInt()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) })
        body.addView(tv(plot, 12.5f, 0xFFCBD5E1.toInt()).apply { setLineSpacing(0f, 1.25f); alignToContent() })
    }
    val scroll = android.widget.ScrollView(this).apply {
        isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
        addView(body)
    }
    card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val beforeFocus = (this as? android.app.Activity)?.currentFocus
    dialog.setContentView(card)
    dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    dialog.window?.setLayout(dp(420), ViewGroup.LayoutParams.WRAP_CONTENT)
    dialog.setOnDismissListener {
        beforeFocus?.takeIf { it.isShown && it.isFocusable }?.let { v -> v.post { v.requestFocus() } }
    }
    dialog.show()
    closeBtn.post { closeBtn.requestFocus() }
}

