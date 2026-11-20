package com.alhorrtv.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Cinematic featured banner aligned 1:1 with Web FeaturedBanner.tsx.
 */
internal class FeaturedBanner(
    context: Context,
    private val repo: XtreamRepository,
    private val kind: String,
    private val onOpen: (ContentEntity) -> Unit
) : FrameLayout(context) {

    private class Info(
        val year: String = "",
        val genres: List<String> = emptyList(),
        val plot: String = "",
        val rating: String = "",
        val duration: String = "",
        val seasons: Int = 0,
        val backdrop: String = ""
    )

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, Info>()
    private val inFlight = HashSet<String>()
    private var items: List<ContentEntity> = emptyList()
    private var index = 0
    private var released = false
    private var running = false

    private val baseImage = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.72f }
    private val backdropImage = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private val chipsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
    private val titleView = context.tv("", 24f, UI.TEXT, true)
    private val plotView = context.tv("", 12.5f, Color.parseColor("#CBD5E1"))
    private val dots = LinearLayout(context)
    private lateinit var prevArrow: TextView
    private lateinit var nextArrow: TextView

    var onWatch: ((ContentEntity) -> Unit)? = null
    val hasItems: Boolean get() = items.isNotEmpty()

    private val posterView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val posterCard = FrameLayout(context)
    private val textBox = LinearLayout(context)
    private val buttonsRow = LinearLayout(context)
    private var showPlot = true

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> if (!dragging) {
                val dx = ev.x - downX; val dy = ev.y - downY
                if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                } else if (items.size > 1 && Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    dragging = true
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                }
            }
        }
        if (dragging) {
            if (ev.actionMasked == MotionEvent.ACTION_UP) {
                val dx = ev.x - downX
                dragging = false
                if (Math.abs(dx) > context.dp(40)) {
                    show(if (dx < 0) index + 1 else index - 1)
                    restartTimer()
                }
            } else if (ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                dragging = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val ph = (h * 0.78f).toInt()
        val pw = (ph * 0.67f).toInt()
        (posterCard.layoutParams as? LayoutParams)?.let { lp ->
            if (lp.height != ph || lp.width != pw) {
                lp.height = ph; lp.width = pw; posterCard.layoutParams = lp
            }
        }
        val showPoster = w >= context.dp(500)
        posterCard.visibility = if (showPoster) View.VISIBLE else View.GONE
        (textBox.layoutParams as? LayoutParams)?.let { lp ->
            val leftSpace = if (showPoster) pw + context.dp(58) else context.dp(36)
            if (lp.leftMargin != leftSpace) {
                lp.leftMargin = leftSpace
                textBox.layoutParams = lp
            }
        }
        showPlot = h >= context.dp(230)
        titleView.maxLines = if (h < context.dp(190)) 1 else 2
        if (!showPlot) plotView.visibility = View.GONE
        else if (plotView.text.isNotBlank()) plotView.visibility = View.VISIBLE
    }

    private fun watchButton(click: () -> Unit): LinearLayout {
        val label = context.tv("▶  شاهد الآن", 13.5f, Color.parseColor("#001018"), true).apply {
            gravity = Gravity.CENTER
        }
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(context.dp(20), 0, context.dp(20), 0)
            val idle = context.roundBg(UI.NEON, 12)
            val focused = context.roundBg(Color.parseColor("#36D5FF"), 12, Color.WHITE, 2)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), idle)
            }
            isFocusable = true; isClickable = true; isFocusableInTouchMode = false
            addView(label)
            setOnClickListener { click() }
        }
        return btn
    }

    private fun detailsButton(click: () -> Unit): LinearLayout {
        val icon = context.tv("ⓘ", 14f, UI.NEON, true)
        val text = context.tv("التفاصيل", 13.5f, Color.WHITE, true).apply {
            setPadding(context.dp(6), 0, 0, 0)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(context.dp(16), 0, context.dp(16), 0)
            val idle = context.roundBg(0xD91C2230.toInt(), 12, UI.STROKE, 1)
            val focused = context.roundBg(UI.STROKE, 12, UI.NEON, 2)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), idle)
            }
            isFocusable = true; isClickable = true; isFocusableInTouchMode = false
            addView(icon)
            addView(text)
            setOnClickListener { click() }
        }
    }

    private val rotate = object : Runnable {
        override fun run() {
            if (!running || released) return
            if (items.size > 1) show(index + 1)
            ui.postDelayed(this, ROTATE_MS)
        }
    }

    init {
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = context.roundBg(Color.parseColor("#131722"), 16, UI.STROKE, 1)
        clipRounded(context.dpf(16))

        addView(baseImage, LayoutParams(-1, -1))
        addView(backdropImage, LayoutParams(-1, -1))

        // RTL cinematic scrims matching Web FeaturedBanner.tsx
        addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.RIGHT_LEFT,
                intArrayOf(0xF20A0C10.toInt(), 0xBF0A0C10.toInt(), 0x400A0C10)
            )
        }, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xFF0A0C10.toInt(), 0x000A0C10, 0x660A0C10)
            )
        }, LayoutParams(-1, -1))

        titleView.apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.START
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        plotView.apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.START
            setLineSpacing(0f, 1.18f); visibility = View.GONE
        }

        textBox.apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(chipsRow, LinearLayout.LayoutParams(-1, -2))
            addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
            addView(plotView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
        }
        addView(textBox, LayoutParams(-1, -2, Gravity.RIGHT or Gravity.TOP).apply {
            rightMargin = context.dp(42)
            leftMargin = context.dp(200)
            topMargin = context.dp(22)
        })

        buttonsRow.apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val watchBtn = watchButton { items.getOrNull(index)?.let { (onWatch ?: onOpen)(it) } }
        val detailsBtn = detailsButton { items.getOrNull(index)?.let(onOpen) }
        detailsBtn.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN && items.size > 1) {
                show(index + 1); restartTimer(); true
            } else false
        }
        buttonsRow.addView(watchBtn, LinearLayout.LayoutParams(-2, context.dp(40)).apply { marginEnd = context.dp(10) })
        buttonsRow.addView(detailsBtn, LinearLayout.LayoutParams(-2, context.dp(40)))
        addView(buttonsRow, LayoutParams(-2, -2, Gravity.RIGHT or Gravity.BOTTOM).apply {
            rightMargin = context.dp(42); bottomMargin = context.dp(28)
        })

        // Left poster preview card
        posterCard.apply {
            background = context.roundBg(UI.PANEL2, 12, UI.STROKE, 2)
            clipRounded(context.dpf(12))
            setOnClickListener { items.getOrNull(index)?.let(onOpen) }
            addView(posterView, LayoutParams(-1, -1))
        }
        addView(posterCard, LayoutParams(context.dp(120), context.dp(180), Gravity.LEFT or Gravity.CENTER_VERTICAL).apply {
            leftMargin = context.dp(42)
        })

        // Left & Right slide arrows matching Web
        fun arrowBtn(symbol: String, layoutGravity: Int, click: () -> Unit): TextView = context.tv(symbol, 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = context.roundBg(0x80000000.toInt(), 99, 0x26FFFFFF, 1)
            isFocusable = false; isClickable = true
            setOnClickListener { if (items.size > 1) { click(); restartTimer() } }
            addView(this, LayoutParams(context.dp(30), context.dp(30), layoutGravity).apply {
                leftMargin = context.dp(8); rightMargin = context.dp(8)
            })
        }
        prevArrow = arrowBtn("›", Gravity.RIGHT or Gravity.CENTER_VERTICAL) { show(index - 1) }
        nextArrow = arrowBtn("‹", Gravity.LEFT or Gravity.CENTER_VERTICAL) { show(index + 1) }

        // Dots pill at bottom center
        dots.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = context.roundBg(0x73000000, 99)
            setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
        }
        addView(dots, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = context.dp(10) })

        val surface = View(context).apply { setOnClickListener { items.getOrNull(index)?.let(onOpen) }; isFocusable = false }
        addView(surface, 4, LayoutParams(-1, -1))
    }

    fun setItems(list: List<ContentEntity>) {
        val same = list.size == items.size && list.indices.all { list[it].contentId == items[it].contentId }
        if (same) return
        items = list
        index = 0
        rebuildDots()
        val multi = list.size > 1
        prevArrow.visibility = if (multi) View.VISIBLE else View.GONE
        nextArrow.visibility = if (multi) View.VISIBLE else View.GONE
        dots.visibility = if (multi) View.VISIBLE else View.GONE
        if (list.isEmpty()) { ui.removeCallbacks(rotate); return }
        show(0)
        restartTimer()
    }

    private fun rebuildDots() {
        dots.removeAllViews()
        if (items.size < 2) return
        for (i in items.indices) {
            dots.addView(View(context), LinearLayout.LayoutParams(context.dp(6), context.dp(6)).apply {
                setMargins(context.dp(3), 0, context.dp(3), 0)
            })
        }
        updateDots()
    }

    private fun updateDots() {
        for (i in 0 until dots.childCount) {
            val selected = i == index
            dots.getChildAt(i).apply {
                background = context.roundBg(if (selected) UI.NEON else 0x59FFFFFF, 99)
                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                    width = context.dp(if (selected) 20 else 6)
                    height = context.dp(6)
                }
            }
        }
    }

    private fun show(target: Int) {
        if (items.isEmpty()) return
        val n = items.size
        index = ((target % n) + n) % n
        val item = items[index]
        titleView.text = item.name
        backdropImage.animate().cancel(); backdropImage.alpha = 0f
        ImageLoader.bind(context, baseImage, item.imageUrl, UI.PANEL2, item.name, item.kind, priority = true, maxPx = 1280)
        ImageLoader.bind(context, posterView, item.imageUrl, UI.PANEL2, item.name, item.kind, priority = true, maxPx = 640)
        updateDots()
        applyInfo(item)
        fetchInfo(item)
    }

    private fun renderChips(item: ContentEntity, info: Info?) {
        chipsRow.removeAllViews()
        // Gold Featured Badge
        chipsRow.addView(context.tv("رائج الآن ★", 10.5f, UI.GOLD, true).apply {
            background = context.roundBg(0x33FFB800, 6, 0x66FFB800, 1)
            setPadding(context.dp(9), context.dp(2), context.dp(9), context.dp(2))
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(6) })

        val rating = info?.rating?.takeIf { it.isNotBlank() } ?: item.rating
        val chipLabels = ArrayList<String>()
        if (rating.isNotBlank()) chipLabels += "★ $rating"
        chipLabels += if (item.kind == Kinds.SERIES) "مسلسل" else "فيلم"
        if (info != null) {
            if (info.year.isNotBlank()) chipLabels += info.year
            if (item.kind == Kinds.MOVIES && info.duration.isNotBlank()) chipLabels += info.duration
            else if (item.kind == Kinds.SERIES && info.seasons > 0) {
                chipLabels += if (info.seasons == 1) "موسم واحد" else "${info.seasons} مواسم"
            }
            info.genres.firstOrNull()?.takeIf { it.isNotBlank() }?.let { chipLabels += it }
        }
        chipLabels.take(5).forEach { txt ->
            chipsRow.addView(context.tv(txt, 10.5f, Color.parseColor("#E2E8F0"), true).apply {
                background = context.roundBg(0x1AFFFFFF, 6)
                setPadding(context.dp(8), context.dp(2), context.dp(8), context.dp(2))
                maxLines = 1
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = context.dp(6) })
        }
    }

    private fun applyInfo(item: ContentEntity) {
        val info = cache[item.contentId]
        renderChips(item, info)
        plotView.text = info?.plot.orEmpty()
        plotView.visibility = if (!showPlot || info == null || info.plot.isBlank()) View.GONE else View.VISIBLE
        if (info != null && info.backdrop.isNotBlank()) {
            val id = item.contentId
            ImageLoader.bind(context, backdropImage, info.backdrop, UI.PANEL2, priority = true, maxPx = 1280, fade = false) { ok ->
                if (ok && !released && items.getOrNull(index)?.contentId == id) {
                    backdropImage.animate().alpha(1f).setDuration(350).start()
                }
            }
        }
    }

    private fun fetchInfo(item: ContentEntity) {
        val id = item.contentId
        if (cache.containsKey(id) || !inFlight.add(id)) return
        val action = if (item.kind == Kinds.SERIES) "get_series_info" else "get_vod_info"
        io.execute {
            val info = try { parse(repo.details(action, id), item.kind) } catch (_: Throwable) { Info() }
            ui.post {
                inFlight.remove(id)
                if (released) return@post
                cache[id] = info
                items.getOrNull(index)?.takeIf { it.contentId == id }?.let { applyInfo(it) }
            }
        }
    }

    private fun parse(json: JSONObject, itemKind: String): Info {
        val node = json.optJSONObject("info") ?: json
        val data = json.optJSONObject("movie_data")
        val rawYear = node.str("releasedate", "release_date", "releaseDate", "year").ifBlank { data.str("year") }.take(4)
        val seasons = if (itemKind == Kinds.SERIES)
            json.optJSONArray("seasons")?.length()?.takeIf { it > 0 } ?: json.optJSONObject("episodes")?.length() ?: 0
        else 0
        val secs = node.optLong("duration_secs", 0L)
        val clock = node.str("duration")
        val duration = if (itemKind == Kinds.MOVIES) formatDuration(secs, clock) else ""
        return Info(
            year = rawYear.takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty(),
            genres = splitNames(node.str("genre")).take(3),
            plot = node.str("plot", "description", "overview"),
            rating = cleanRating(node.str("rating", "rating_5based")),
            duration = duration,
            seasons = seasons,
            backdrop = node.firstImage("backdrop_path", "cover_big", "movie_image", "backdrop")
        )
    }

    private fun restartTimer() { ui.removeCallbacks(rotate); if (running && items.size > 1) ui.postDelayed(rotate, ROTATE_MS) }
    fun start() { if (released) return; running = true; restartTimer() }
    fun stop() { running = false; ui.removeCallbacks(rotate) }
    fun release() { released = true; stop(); ui.removeCallbacksAndMessages(null); io.shutdownNow() }

    private companion object { const val ROTATE_MS = 9000L }
}

/** Single-row adapter that hosts the banner + section title above the poster grid (used through a ConcatAdapter). */
internal class BrowseHeaderAdapter(private val header: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var enabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) notifyItemInserted(0) else notifyItemRemoved(0)
        }

    override fun getItemCount(): Int = if (enabled) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        (header.parent as? ViewGroup)?.removeView(header)
        header.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return object : RecyclerView.ViewHolder(header) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
}
