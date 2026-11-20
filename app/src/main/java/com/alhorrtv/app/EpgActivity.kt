package com.alhorrtv.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/** Thin progress bar of the programme on air; fills from the right (RTL). */
internal class ProgressStrip(context: Context) : View(context) {
    var fraction: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UI.PANEL2 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UI.NEON }
    private val rect = RectF()
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        rect.set(0f, 0f, w, h); canvas.drawRoundRect(rect, r, r, track)
        if (fraction > 0f) { rect.set(w * (1f - fraction), 0f, w, h); canvas.drawRoundRect(rect, r, r, fill) }
    }
}

/**
 * دليل البرامج — 1:1 aligned with Web EpgScreen.tsx.
 */
class EpgActivity : FullscreenActivity() {
    private val io = Executors.newFixedThreadPool(2)
    private val db = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var repo: XtreamRepository
    private var server = ""; private var user = ""; private var pass = ""

    private val channels = ArrayList<ContentEntity>()
    private lateinit var adapter: ChannelAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var chipRow: LinearLayout
    private lateinit var notice: TextView
    private lateinit var emptyView: TextView
    private lateinit var detail: LinearLayout
    private var categories: List<CategoryEntity> = emptyList()
    private var categoryId = ""
    private var offset = 0
    private var loading = false
    private var endReached = false
    private var generation = 0
    private var focusedId = ""
    private var focusRequested = false

    private val pending = HashSet<String>()
    private var attempts = 0
    private var withData = 0
    private var unsupported = false
    private val clock = SimpleDateFormat("HH:mm", Locale.ENGLISH)

    private val tick = object : Runnable {
        override fun run() {
            val lm = recycler.layoutManager as? LinearLayoutManager
            if (lm != null && channels.isNotEmpty()) {
                val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0); val last = lm.findLastVisibleItemPosition()
                if (last >= first) adapter.notifyItemRangeChanged(first, last - first + 1)
            }
            channels.firstOrNull { it.contentId == focusedId }?.let { showDetail(it) }
            ui.postDelayed(this, 30_000L)
        }
    }

    private fun cacheKey(id: String) = "$server|$user|$id"
    private fun fmt(ms: Long): String = clock.format(java.util.Date(ms))

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        server = intent.getStringExtra("server").orEmpty()
        user = intent.getStringExtra("username").orEmpty()
        pass = intent.getStringExtra("password").orEmpty()
        if (server.isBlank() || user.isBlank()) { finish(); return }
        repo = XtreamRepository(this, server, user, pass)
        buildUi()
        db.execute {
            val cats = try { repo.categories(Kinds.LIVE) } catch (_: Exception) { emptyList() }
            ui.post { if (!isFinishing && !isDestroyed) { categories = cats; renderChips() } }
        }
        loadMore()
    }

    override fun onResume() { super.onResume(); ui.removeCallbacks(tick); ui.postDelayed(tick, 30_000L) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { ui.removeCallbacksAndMessages(null); io.shutdownNow(); db.shutdownNow(); super.onDestroy() }

    private fun buildUi() {
        val compact = resources.configuration.screenWidthDp < 480
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = HexPatternDrawable()
        }

        // Sticky Header matching Web EpgScreen.tsx
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE60A0C10.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        header.addView(tv("→", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = roundBg(Color.parseColor("#131722"), 10, UI.STROKE, 1)
            contentDescription = "رجوع"
            makeFocusable(1.04f, UI.NEON, 2, 10)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))

        header.addView(tv("دليل البرامج", 18f, UI.TEXT, true).apply {
            setPadding(dp(12), 0, dp(12), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        header.addView(tv("↻  تحديث", 12f, UI.NEON, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = roundBg(Color.parseColor("#131722"), 10, UI.STROKE, 1)
            makeFocusable(1.04f, UI.NEON, 2, 10)
            setOnClickListener { refreshGuide() }
        }, LinearLayout.LayoutParams(-2, dp(36)))
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(this).apply { setBackgroundColor(UI.STROKE) }, LinearLayout.LayoutParams(-1, dp(1)))

        // Category Chips bar ("الكل" + categories)
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        chipScroll.addView(chipRow)
        root.addView(chipScroll, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(this).apply { setBackgroundColor(UI.alpha(UI.STROKE, 150)) }, LinearLayout.LayoutParams(-1, dp(1)))

        notice = tv("", 12f, Color.parseColor("#F5D477"), true).apply {
            gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundBg(UI.alpha(Color.parseColor("#F5D477"), 28), 12, UI.alpha(Color.parseColor("#F5D477"), 120), 1)
        }
        root.addView(notice, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), 0) })

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val listHost = FrameLayout(this)
        adapter = ChannelAdapter()
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@EpgActivity)
            this.adapter = this@EpgActivity.adapter
            clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER; itemAnimator = null
            setItemViewCacheSize(16)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val lm = rv.layoutManager as LinearLayoutManager
                    if (dy > 0 && lm.findLastVisibleItemPosition() >= channels.size - 8) loadMore()
                }
            })
        }
        listHost.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        emptyView = tv("", 13.5f, UI.MUTED).apply {
            gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(24), dp(24), dp(24), dp(24))
            background = roundBg(Color.parseColor("#131722"), 16, UI.STROKE, 1)
        }
        listHost.addView(emptyView, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        body.addView(listHost, LinearLayout.LayoutParams(0, -1, 1.35f))

        // Right/Left Schedule Panel (col-span-5 in Web)
        detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val detailScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            background = roundBg(0xD1131722.toInt(), 16, UI.alpha(UI.STROKE, 215), 1)
            addView(detail)
        }
        if (!compact) body.addView(detailScroll, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(14) })
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        showDetail(null)
    }

    private fun renderChips(focusCategoryId: String? = null) {
        chipRow.removeAllViews()
        var toFocus: View? = null
        fun add(label: String, id: String) {
            val selected = id == categoryId
            val c = tv(label, 12f, if (selected) Color.parseColor("#001018") else Color.parseColor("#CBD5E1"), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = if (selected) roundBg(UI.NEON, 99) else roundBg(Color.parseColor("#131722"), 99, UI.STROKE, 1)
                makeFocusable(1.04f, UI.NEON, 2, 99)
                setOnClickListener { selectCategory(id) }
            }
            if (focusCategoryId != null && focusCategoryId == id) toFocus = c
            chipRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        add("الكل", "")
        categories.forEach { add(it.categoryName, it.categoryId) }
        toFocus?.let { target -> chipRow.post { target.requestFocus() } }
    }

    private fun selectCategory(id: String) {
        if (id == categoryId) return
        categoryId = id; renderChips(focusCategoryId = id); resetList(preserveFocus = true)
    }

    private fun resetList(preserveFocus: Boolean = false) {
        generation++; channels.clear(); adapter.notifyDataSetChanged()
        offset = 0; endReached = false; loading = false
        focusRequested = preserveFocus; focusedId = ""
        emptyView.visibility = View.GONE; showDetail(null); loadMore()
    }

    private fun loadMore() {
        if (loading || endReached) return
        loading = true
        val gen = generation; val cat = categoryId; val start = offset
        db.execute {
            val page = try { repo.page(Kinds.LIVE, 40, start, cat) } catch (_: Exception) { emptyList() }
            ui.post {
                if (isFinishing || isDestroyed || gen != generation) return@post
                loading = false
                if (page.size < 40) endReached = true
                offset += page.size
                val from = channels.size
                channels.addAll(page)
                adapter.notifyItemRangeInserted(from, page.size)
                if (channels.isEmpty()) {
                    emptyView.text = "لا توجد قنوات في هذا التصنيف."
                    emptyView.visibility = View.VISIBLE
                } else if (focusedId.isBlank()) {
                    focusedId = channels[0].contentId
                    showDetail(channels[0])
                    request(channels[0])
                }
                if (!focusRequested && !userHasNavigated && currentFocus == null && channels.isNotEmpty()) {
                    focusRequested = true
                    recycler.post {
                        if (!userHasNavigated && currentFocus == null) {
                            recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                        }
                    }
                }
            }
        }
    }

    private fun refreshGuide() {
        EpgCache.clear(); synchronized(pending) { pending.clear() }
        attempts = 0; withData = 0; unsupported = false; notice.visibility = View.GONE
        adapter.notifyDataSetChanged(); channels.firstOrNull { it.contentId == focusedId }?.let { showDetail(it) }
    }

    private fun request(item: ContentEntity) {
        if (unsupported) return
        val key = cacheKey(item.contentId)
        if (EpgCache.get(key) != null) return
        synchronized(pending) { if (!pending.add(key)) return }
        io.execute {
            val result = repo.shortEpg(item.contentId)
            val list = result.getOrNull()
            EpgCache.put(key, list ?: emptyList(), when {
                list == null -> EpgCache.TTL_FAILED
                list.isEmpty() -> EpgCache.TTL_EMPTY
                else -> EpgCache.TTL_DATA
            })
            ui.post {
                synchronized(pending) { pending.remove(key) }
                if (isFinishing || isDestroyed) return@post
                attempts++; if (!list.isNullOrEmpty()) withData++
                if (!unsupported && attempts >= 8 && withData == 0) {
                    unsupported = true
                    notice.text = "لا تتوفر بيانات دليل البرامج لهذه القنوات. قد لا يدعم المزوّد الدليل أو لا تحتوي القنوات على معرّف دليل (EPG). يمكنك تشغيل القنوات كالمعتاد."
                    notice.visibility = View.VISIBLE
                }
                adapter.channelUpdated(item.contentId)
                if (item.contentId == focusedId) showDetail(item)
            }
        }
    }

    // ------------------------------------------------------------------ detail panel

    private fun showDetail(item: ContentEntity?) {
        detail.removeAllViews()
        if (item == null) {
            detail.addView(tv("اختر قناة لعرض جدول برامجها.", 12.5f, UI.MUTED).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(48), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }

        // Header with Channel Name + Primary Cyan Watch Button
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        head.addView(tv(item.name, 16f, UI.TEXT, true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })

        val watchBtn = tv("▶  مشاهدة", 12.5f, Color.parseColor("#001018"), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            val idle = roundBg(UI.NEON, 12)
            val focused = roundBg(Color.parseColor("#36D5FF"), 12, Color.WHITE, 2)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), idle)
            }
            makeFocusable(1.04f, UI.NEON, 0, 12)
            setOnClickListener { Nav.open(this@EpgActivity, item, server, user, pass) }
        }
        head.addView(watchBtn)
        detail.addView(head, LinearLayout.LayoutParams(-1, -2))
        detail.addView(View(this).apply { setBackgroundColor(UI.STROKE) }, LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(12) })

        val list = EpgCache.get(cacheKey(item.contentId))
        val now = System.currentTimeMillis()
        if (list == null) {
            detail.addView(tv("جارٍ تحميل جدول البرامج…", 12.5f, UI.MUTED).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        if (list.isEmpty()) {
            detail.addView(tv("لا تتوفر جدول برامج تفصيلية لهذه القناة.", 12.5f, UI.MUTED).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }

        list.forEach { e ->
            val isNow = now >= e.startMs && now < e.endMs
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = if (isNow) roundBg(0x2600D2FF, 12, UI.NEON, 1)
                else roundBg(UI.PANEL2, 12, UI.STROKE, 1)
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(tv("${fmt(e.startMs)} - ${fmt(e.endMs)}", 11f, UI.NEON, true).apply {
                textDirection = View.TEXT_DIRECTION_LTR
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (isNow) {
                top.addView(tv("يُعرض الآن", 11f, UI.GREEN, true))
            }
            card.addView(top, LinearLayout.LayoutParams(-1, -2))
            card.addView(tv(e.title.ifBlank { "—" }, 13f, UI.TEXT, true).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            if (e.description.isNotBlank()) {
                card.addView(tv(e.description, 11f, UI.MUTED).apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.15f)
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            detail.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    // ------------------------------------------------------------------ rows

    private inner class RowView(ctx: Context) : LinearLayout(ctx) {
        val logoWrap = FrameLayout(ctx).apply {
            background = roundBg(Color.parseColor("#0F131F"), 10, UI.STROKE, 1)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val logo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val name = ctx.tv("", 13.5f, UI.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val remMin = ctx.tv("", 11f, UI.GREEN, true).apply { maxLines = 1; visibility = View.GONE }
        val nowTitle = ctx.tv("", 12f, Color.parseColor("#CBD5E1")).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val progress = ProgressStrip(ctx)
        val nextText = ctx.tv("", 11f, UI.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val playBtn = ctx.tv("▶", 13f, UI.NEON, true).apply {
            gravity = Gravity.CENTER
            background = roundBg(0x3300D2FF, 99)
            isFocusable = false; isClickable = true
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(10), dp(12), dp(10))

            logoWrap.addView(logo, FrameLayout.LayoutParams(-1, -1))
            addView(logoWrap, LayoutParams(dp(54), dp(48)))

            val center = LinearLayout(ctx).apply { orientation = VERTICAL }
            val topLine = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            topLine.addView(name, LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            topLine.addView(remMin)
            center.addView(topLine, LayoutParams(-1, -2))
            center.addView(nowTitle, LayoutParams(-1, -2).apply { topMargin = dp(3) })
            center.addView(progress, LayoutParams(-1, dp(5)).apply { topMargin = dp(5) })
            center.addView(nextText, LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(center, LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(12) })

            addView(playBtn, LayoutParams(dp(36), dp(36)))
        }

        fun bind(item: ContentEntity) {
            val active = item.contentId == focusedId
            background = if (active) roundBg(0xE0182034.toInt(), 16, UI.NEON, 1)
            else roundBg(0xC7131722.toInt(), 16, UI.alpha(UI.STROKE, 215), 1)
            name.text = item.name
            ImageLoader.bind(context, logo, item.imageUrl, UI.PANEL2, item.name, Kinds.LIVE)
            playBtn.setOnClickListener { Nav.open(this@EpgActivity, item, server, user, pass) }
            render(EpgCache.get(cacheKey(item.contentId)))
        }

        fun render(list: List<EpgEntry>?) {
            val now = System.currentTimeMillis()
            if (list == null) {
                nowTitle.text = "جارٍ الجلب…"
                remMin.visibility = View.GONE
                progress.visibility = View.GONE
                nextText.visibility = View.GONE
                return
            }
            val current = list.firstOrNull { now >= it.startMs && now < it.endMs }
            val next = list.firstOrNull { it.startMs > now }
            if (current != null) {
                val left = ((current.endMs - now) / 60_000L).toInt().coerceAtLeast(1)
                nowTitle.text = "الآن: ${current.title.ifBlank { "—" }}"
                remMin.text = "متبقي $left د"
                remMin.visibility = View.VISIBLE
                progress.visibility = View.VISIBLE
                progress.fraction = (now - current.startMs).toFloat() / (current.endMs - current.startMs)
            } else {
                nowTitle.text = "لا توجد بيانات برنامج حالي"
                remMin.visibility = View.GONE
                progress.visibility = View.GONE
            }
            if (next != null) {
                nextText.text = "التالي (${fmt(next.startMs)}): ${next.title}"
                nextText.visibility = View.VISIBLE
            } else {
                nextText.visibility = View.GONE
            }
        }
    }

    private inner class RowHolder(val row: RowView) : RecyclerView.ViewHolder(row) { var item: ContentEntity? = null }

    private inner class ChannelAdapter : RecyclerView.Adapter<RowHolder>() {
        init { setHasStableIds(true) }
        override fun getItemCount(): Int = channels.size
        override fun getItemId(position: Int): Long {
            val id = channels[position].contentId
            var h = -3750763034362895579L
            for (i in 0 until id.length) {
                h = (h xor id[i].code.toLong()) * 1099511628211L
            }
            return h
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val row = RowView(parent.context)
            row.layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
            row.makeFocusable(1.015f, UI.NEON, 2, 16)
            val holder = RowHolder(row)
            val baseListener = row.onFocusChangeListener
            row.setOnFocusChangeListener { v, focused ->
                baseListener?.onFocusChange(v, focused)
                if (focused) holder.item?.let { selectRow(it) }
            }
            row.setOnClickListener {
                holder.item?.let { item ->
                    if (focusedId == item.contentId) {
                        Nav.open(this@EpgActivity, item, server, user, pass)
                    } else {
                        selectRow(item)
                    }
                }
            }
            return holder
        }

        private fun selectRow(item: ContentEntity) {
            val prev = focusedId
            focusedId = item.contentId
            showDetail(item)
            request(item)
            if (prev.isNotBlank() && prev != item.contentId) channelUpdated(prev)
            channelUpdated(item.contentId)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val item = channels[position]
            holder.item = item
            holder.row.bind(item)
            request(item)
        }

        fun channelUpdated(id: String) {
            val index = channels.indexOfFirst { it.contentId == id }
            if (index >= 0) notifyItemChanged(index)
        }
    }
}
