package com.alhorrtv.app

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/** One screen for every section: live channels, movies, series, favorites, continue-watching and global search. */
class BrowseActivity : FullscreenActivity(), SyncManager.Listener {
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var repo: XtreamRepository
    private lateinit var adapter: PosterAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var grid: GridLayoutManager
    private lateinit var searchBox: EditText
    private lateinit var chipRow: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var emptyView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var categoryPanel: LinearLayout
    private lateinit var categoryList: LinearLayout
    private lateinit var headerCountBadge: TextView

    private var kind = Kinds.MOVIES
    private var server = ""; private var user = ""; private var pass = ""
    private var accent = UI.MOVIE
    private var sections: List<SectionInfo> = emptyList()
    private var categories: List<CategoryEntity> = emptyList()
    private var categoryCounts: Map<String, Int> = emptyMap()
    private var selectedSection: SectionInfo? = null
    private var selectedCategory: CategoryEntity? = null
    private var query = ""
    private var generation = 0
    private var loading = false
    private var endReached = false
    private var offset = 0
    private var lastRefresh = 0L
    private var seenSyncFinish = -1L
    private var initialFocusSet = false
    private var favorites: Set<String> = emptySet()
    private val compactLayout get() = false
    private val pageSize = 60
    private val isContent get() = kind in Kinds.CONTENT
    private val searchKind get() = kind
    private var pendingFocusKey: String? = null
    private var railScrollY = 0
    private var railFocusKey: String? = null
    private var railRestorePending = false
    private val categoryRowViews = HashMap<String, View>()
    private var categoryScroll: ScrollView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        kind = intent.getStringExtra("kind") ?: Kinds.MOVIES
        server = intent.getStringExtra("server").orEmpty(); user = intent.getStringExtra("username").orEmpty(); pass = intent.getStringExtra("password").orEmpty()
        accent = UI.accent(kind)
        repo = XtreamRepository(this, server, user, pass)
        buildUi()
        adapter = PosterAdapter({ item ->
            if (kind == Kinds.CONTINUE) resumeOrOpen(item) else Nav.open(this, item, server, user, pass)
        }, { item ->
            if (kind == Kinds.CONTINUE) {
                executor.execute {
                    repo.saveProgress(item.kind, item.contentId, 0L)
                    if (item.kind == Kinds.SERIES) ResumeStore.clearSeries(this, item.contentId)
                    ui.post { reload() }
                }
            } else toggleFavorite(item)
        }, showBadge = kind !in Kinds.CONTENT)
        recycler.adapter = adapter
        if (intent.getBooleanExtra("focus_search", false)) {
            initialFocusSet = true
            searchBox.post { searchBox.requestFocus() }
        } else if (intent.getBooleanExtra("open_categories", false)) {
            initialFocusSet = true
            pendingFocusKey = "all"
        }
        if (isContent) SyncManager.ensure(this, server, user, pass, kind)
        refreshAll()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = HexPatternDrawable()
        }

        // Top Header Bar (matches Web BrowseScreen sticky header)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xE60A0C10.toInt())
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        val back = tv("→", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = roundBg(0xFF131722.toInt(), 10, 0xFF253048.toInt(), 1)
            makeFocusable(1.06f, UI.NEON, 2, 10)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(38), dp(38)))
        header.addView(tv(Kinds.title(kind), 18f, Color.WHITE, true).apply {
            setPadding(dp(12), 0, dp(10), 0)
        })
        headerCountBadge = tv("0 ${Kinds.unit(kind)}", 11.5f, 0xFF00D2FF.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = roundBg(0xFF1C2230.toInt(), 6, 0xFF253048.toInt(), 1)
            visibility = if (kind == Kinds.ALL && query.isBlank()) View.GONE else View.VISIBLE
        }
        header.addView(headerCountBadge, LinearLayout.LayoutParams(-2, -2))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))

        // Search input inside the header (matches Web BrowseScreen)
        val clearSearch = tv("×", 20f, UI.MUTED, true).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            makeFocusable(1.06f, UI.NEON, 2, 10)
        }
        searchBox = EditText(this).apply {
            hint = "ابحث في ${Kinds.title(kind)}…"
            setHintTextColor(0xFF94A3B8.toInt()); setTextColor(Color.WHITE); textSize = 13f; setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH; inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = roundBg(0xFF131722.toInt(), 10, 0xFF253048.toInt(), 1)
            setPadding(dp(34), 0, dp(34), 0)
            setOnFocusChangeListener { v, focused ->
                v.background = roundBg(0xFF131722.toInt(), 10, if (focused) 0xFF00D2FF.toInt() else 0xFF253048.toInt(), if (focused) 2 else 1)
            }
            addTextChangedListener(object : TextWatcher {
                private var pending: Runnable? = null
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                    pending?.let(ui::removeCallbacks)
                    clearSearch.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                    pending = Runnable {
                        val newQuery = s?.toString().orEmpty().trim()
                        if (newQuery == query) return@Runnable
                        val flipped = newQuery.isBlank() != query.isBlank()
                        query = newQuery; reload(); if (flipped) renderCategoryPanel()
                    }
                    ui.postDelayed(pending!!, 350)
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        val searchHost = FrameLayout(this).apply {
            addView(searchBox, FrameLayout.LayoutParams(-1, dp(38)))
            addView(ImageView(this@BrowseActivity).apply {
                setImageResource(R.drawable.ic_set_search); setColorFilter(0xFFA0AEC0.toInt())
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(10) })
            addView(clearSearch, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin = dp(6) })
        }
        clearSearch.setOnClickListener { searchBox.setText(""); searchBox.requestFocus() }
        header.addView(searchHost, LinearLayout.LayoutParams(dp(290), dp(38)))

        if (kind == Kinds.LIVE) {
            header.addView(tv("📅  دليل البرامج", 12f, 0xFF00D2FF.toInt(), true).apply {
                gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0); contentDescription = "دليل البرامج"
                background = roundBg(0xFF131722.toInt(), 10, 0xFF253048.toInt(), 1)
                makeFocusable(1.04f, UI.NEON, 2, 10)
                setOnClickListener { openGuide() }
            }, LinearLayout.LayoutParams(-2, dp(38)).apply { marginStart = dp(10) })
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        root.addView(View(this).apply { setBackgroundColor(0xFF253048.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))

        // Main Split: Categories Sidebar (right in RTL) + Grid (left)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(14), dp(16), dp(10))
            minimumHeight = 0
        }
        categoryPanel = buildCategoryPanel()
        categoryPanel.visibility = if (isContent && !compactLayout) View.VISIBLE else View.GONE
        body.addView(categoryPanel, LinearLayout.LayoutParams(if (compactLayout) 0 else dp(205), -1).apply {
            marginEnd = dp(14)
        })

        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            minimumWidth = 0
            minimumHeight = 0
        }
        body.addView(contentColumn, LinearLayout.LayoutParams(0, -1, 1f).apply { width = 0; weight = 1f })

        val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        scroll.addView(chipRow)
        scroll.visibility = if (compactLayout && isContent) View.VISIBLE else View.GONE
        if (isContent) contentColumn.addView(scroll, LinearLayout.LayoutParams(-1, if (compactLayout) dp(48) else 0))

        statusView = tv("", 12.5f, UI.TEXT, true).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        contentColumn.addView(statusView, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(6) })

        val host = FrameLayout(this).apply { minimumHeight = 0 }
        recycler = RecyclerView(this).apply {
            clipToPadding = false; setPadding(0, 0, 0, dp(14)); overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null; setItemViewCacheSize(24)
        }
        val widthPx = resources.displayMetrics.widthPixels
        val spans = when {
            compactLayout -> 2
            widthPx >= 1800 -> if (kind == Kinds.LIVE) 5 else 6
            widthPx >= 1400 -> 5
            else -> if (kind == Kinds.LIVE) 4 else 5
        }
        grid = GridLayoutManager(this, spans)
        recycler.layoutManager = grid
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0 && grid.findLastVisibleItemPosition() >= adapter.itemCount - spans * 3) loadMore()
            }
        })
        host.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        emptyView = tv("", 14f, UI.MUTED, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = roundBg(0xCC131722.toInt(), 16, 0xD9253048.toInt(), 1)
            visibility = View.GONE
        }
        host.addView(emptyView, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = dp(8) })
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE; indeterminateTintList = ColorStateList.valueOf(UI.NEON) }
        host.addView(progress, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(40) })
        contentColumn.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun resumeOrOpen(item: ContentEntity) {
        executor.execute {
            val position = repo.mark(item.kind, item.contentId)?.positionMs ?: 0L
            val point = if (item.kind == Kinds.SERIES) ResumeStore.seriesPoint(this, item.contentId) else null
            ui.post {
                if (isFinishing || isDestroyed) return@post
                val player = android.content.Intent(this, PlayerActivity::class.java)
                    .putExtra("server", server).putExtra("username", user).putExtra("password", pass)
                when {
                    item.kind == Kinds.SERIES && point != null && point.positionMs > 15_000L -> startActivity(player
                        .putExtra("url", buildMediaUrl(server, "series", user, pass, point.episodeId, point.extension))
                        .putExtra("title", point.title.ifBlank { item.name })
                        .putExtra("mark_kind", item.kind).putExtra("mark_id", item.contentId)
                        .putExtra("series_id", item.contentId).putExtra("series_name", item.name)
                        .putExtra("series_image", item.imageUrl).putExtra("episode_id", point.episodeId))
                    item.kind == Kinds.MOVIES && position > 15_000L -> startActivity(player
                        .putExtra("url", buildMediaUrl(server, "movie", user, pass, item.contentId, item.extension))
                        .putExtra("title", item.name).putExtra("image", item.imageUrl)
                        .putExtra("mark_kind", "get_vod_streams").putExtra("mark_id", item.contentId))
                    else -> Nav.open(this, item, server, user, pass)
                }
            }
        }
    }

    private fun openGuide() {
        startActivity(android.content.Intent(this, EpgActivity::class.java)
            .putExtra("server", server).putExtra("username", user).putExtra("password", pass))
    }

    private fun buildCategoryPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundBg(0xC7131722.toInt(), 16, 0xD9253048.toInt(), 1)
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }
        categoryScroll = scroll
        categoryList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        scroll.addView(categoryList)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return panel
    }

    /** Category row matching Web BrowseScreen: active = solid #00D2FF fill + dark text #001018. */
    private fun addFlatRow(key: String, label: String, count: Int, selected: Boolean, boldIdle: Boolean, click: () -> Unit) {
        val r = dpf(10)
        fun shape(fill: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = r }
        val dark = 0xFF001018.toInt()
        val item = LinearLayout(this).apply {
            tag = key
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(if (selected) 0xFF36D5FF.toInt() else 0xFF1C2230.toInt())
                    cornerRadius = r
                    setStroke(dp(2), if (selected) Color.WHITE else UI.NEON)
                })
                addState(intArrayOf(android.R.attr.state_selected), shape(0xFF00D2FF.toInt()))
                addState(intArrayOf(), shape(Color.TRANSPARENT))
            }
            isSelected = selected
            makeFocusable(1.02f, UI.NEON, 0, 10)
            setOnClickListener { pendingFocusKey = key; leaveSearch(); click() }
        }
        val idleTextColor = if (selected) dark else if (boldIdle) 0xFFE2E8F0.toInt() else 0xFFCBD5E1.toInt()
        val idleCountColor = if (selected) 0xCC001018.toInt() else 0xBF94A3B8.toInt()
        val name = tv(label, 12f, idleTextColor, selected || boldIdle).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            if (label.any { it in 'A'..'Z' || it in 'a'..'z' }) textDirection = View.TEXT_DIRECTION_LTR
        }
        item.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        val countView = if (count > 0 || label == "الكل") tv("$count", 11f, idleCountColor, selected) else null
        if (countView != null) item.addView(countView, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        val base = item.onFocusChangeListener
        item.setOnFocusChangeListener { v, focused ->
            base?.onFocusChange(v, focused)
            name.setTextColor(when {
                selected -> dark
                focused -> Color.WHITE
                else -> idleTextColor
            })
            name.typeface = if (focused || selected || boldIdle) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            countView?.setTextColor(if (selected) 0xCC001018.toInt() else if (focused) Color.WHITE else idleCountColor)
        }
        categoryRowViews[key] = item
        categoryList.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
    }

    private fun addSidebarSubheader(title: String, color: Int, topDivider: Boolean = false) {
        if (topDivider) {
            categoryList.addView(View(this).apply { setBackgroundColor(0xB3253048.toInt()) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(6); bottomMargin = dp(6) })
        }
        categoryList.addView(tv(title, 10.5f, color, true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            gravity = Gravity.START
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun renderCategoryPanel() {
        if (!isContent || !::categoryList.isInitialized) return
        val counts = categoryCounts
        val searching = query.isNotBlank()
        if (!railRestorePending) {
            railScrollY = categoryScroll?.scrollY ?: 0
            railFocusKey = categoryList.focusedChild?.tag as? String
        }
        if (pendingFocusKey != null) { railFocusKey = pendingFocusKey; pendingFocusKey = null }
        railRestorePending = true
        categoryScroll?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        categoryRowViews.clear()
        categoryList.removeAllViews()

        addFlatRow("all", "الكل", counts.values.sum(), !searching && selectedSection == null && selectedCategory == null, true) {
            selectedSection = null; selectedCategory = null; renderChips(); reload()
        }

        if (sections.isNotEmpty()) {
            addSidebarSubheader("أقسام ذكية", 0xFF00D2FF.toInt())
            sections.forEach { sec ->
                addFlatRow("sec:${sec.key}", "${sec.icon} ${sec.label}", sec.count, !searching && selectedSection?.key == sec.key, true) {
                    selectedSection = sec; selectedCategory = null; renderChips(); reload()
                }
            }
        }

        val nonEmptyCats = categories.filter { (counts[it.categoryId] ?: 0) > 0 }
        if (nonEmptyCats.isNotEmpty()) {
            addSidebarSubheader("التصنيفات", 0xFFA0AEC0.toInt(), topDivider = true)
            nonEmptyCats.forEach { category ->
                addFlatRow("cat:${category.categoryId}", category.categoryName, counts[category.categoryId] ?: 0, !searching && selectedCategory?.categoryId == category.categoryId, false) {
                    selectedSection = null; selectedCategory = category; renderChips(); reload()
                }
            }
        }

        val list = categoryList
        list.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (list.viewTreeObserver.isAlive) list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (!railRestorePending) return
                railRestorePending = false
                val scroll = categoryScroll ?: return
                scroll.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                scroll.scrollTo(0, railScrollY)
                val targetKey = railFocusKey
                if (targetKey != null) {
                    val target = categoryRowViews[targetKey]
                    if (target != null && target.isFocusable) target.requestFocus()
                }
                railFocusKey = null
            }
        })
    }

    private fun refreshAll() {
        executor.execute {
            if (isContent) {
                categories = repo.categories(kind).sortedWith(compareBy<CategoryEntity> { if (it.categoryName.any { ch -> ch in '\u0600'..'\u06FF' }) 0 else 1 }.thenBy { it.categoryName })
                categoryCounts = repo.categoryCounts(kind)
                sections = Sections.build(kind, categories, categoryCounts)
                    .also { s -> selectedSection = selectedSection?.let { cur -> s.firstOrNull { it.key == cur.key } } }
            }
            favorites = repo.favoriteKeys()
            ui.post { renderCategoryPanel(); renderChips(); reload() }
        }
    }

    private fun renderChips() {
        if (!isContent) return
        renderCategoryPanel()
        chipRow.removeAllViews()
        fun add(label: String, selected: Boolean, onClick: () -> Unit) {
            chipRow.addView(chip(label, selected, accent).apply { setOnClickListener { onClick() } },
                LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) })
        }
        add("الكل", selectedSection == null && selectedCategory == null) { selectedSection = null; selectedCategory = null; renderChips(); reload() }
        sections.forEach { s ->
            add("${s.icon} ${s.label}  ${s.count}", selectedSection?.key == s.key) { selectedSection = s; selectedCategory = null; renderChips(); reload() }
        }
        add(selectedCategory?.let { "☰ ${it.categoryName}" } ?: "☰ التصنيفات", selectedCategory != null) {
            if (compactLayout) showCategories()
            else if (::categoryList.isInitialized) categoryList.getChildAt(0)?.requestFocus()
        }
    }

    private fun showCategories() {
        if (!isContent) return
        val before = currentFocus
        executor.execute {
            val counts = categoryCounts
            val list = categories.filter { (counts[it.categoryId] ?: 0) > 0 }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (list.isEmpty()) { Toast.makeText(this, "لا توجد تصنيفات محمّلة بعد", Toast.LENGTH_SHORT).show(); return@post }
                val dialog = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                    background = roundBg(0xFF131722.toInt(), 16, UI.STROKE, 1)
                    clipRounded(dpf(16))
                }
                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(12), dp(18), dp(12))
                }
                head.addView(tv("التصنيفات", 15f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
                head.addView(tv("✕", 14f, UI.MUTED, true).apply {
                    gravity = Gravity.CENTER; makeFocusable(1.06f, UI.NEON, 1, 8)
                    setOnClickListener { dialog.dismiss() }
                }, LinearLayout.LayoutParams(dp(28), dp(28)))
                panel.addView(head)
                panel.addView(View(this).apply { setBackgroundColor(UI.STROKE) }, LinearLayout.LayoutParams(-1, dp(1)))
                val itemsCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(14)) }
                val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(itemsCol) }
                val choices: List<Pair<String, CategoryEntity?>> = listOf("الكل (${counts.values.sum()})" to null) +
                    list.map { "${it.categoryName} (${counts[it.categoryId] ?: 0})" to it }
                var firstRow: View? = null
                choices.forEach { (label, cat) ->
                    val active = selectedCategory?.categoryId == cat?.categoryId && (cat != null || selectedSection == null)
                    val row = tv(label, 13f, Color.WHITE, active).apply {
                        setPadding(dp(12), dp(9), dp(12), dp(9))
                        val idle = if (active) roundBg(0x3300D2FF, 10, UI.NEON, 1) else roundBg(UI.PANEL2, 10, UI.STROKE, 1)
                        val focused = roundBg(0x4D00D2FF, 10, Color.WHITE, 2)
                        background = android.graphics.drawable.StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_focused), focused)
                            addState(intArrayOf(android.R.attr.state_pressed), focused)
                            addState(intArrayOf(), idle)
                        }
                        isFocusable = true; isClickable = true
                        setOnClickListener {
                            dialog.dismiss()
                            selectedSection = null; selectedCategory = cat
                            renderChips(); reload()
                        }
                    }
                    itemsCol.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
                    if (firstRow == null || active) firstRow = row
                }
                panel.addView(scroll, LinearLayout.LayoutParams(-1, dp(300)))
                dialog.setOnDismissListener { ui.post { before?.takeIf { it.isShown && it.isFocusable }?.requestFocus() } }
                dialog.setContentView(panel)
                dialog.show()
                dialog.window?.apply {
                    setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                    setLayout(dp(380), WindowManager.LayoutParams.WRAP_CONTENT)
                    setDimAmount(0.75f)
                }
                firstRow?.post { firstRow?.requestFocus() }
            }
        }
    }

    private fun leaveSearch() { if (query.isNotBlank()) { query = ""; if (::searchBox.isInitialized) searchBox.setText("") } }

    private fun currentIds(): List<String>? = selectedCategory?.let { listOf(it.categoryId) } ?: selectedSection?.ids

    private fun reload() {
        generation++; offset = 0; endReached = false; loading = false
        val gen = generation
        val q = query; val ids = currentIds()
        executor.execute {
            if (gen != generation) return@execute
            val globalSearch = q.isNotBlank() && kind != Kinds.FAVORITES && kind != Kinds.CONTINUE
            val total = when {
                globalSearch -> repo.countBrowse(searchKind, null, q)
                kind == Kinds.FAVORITES || kind == Kinds.CONTINUE -> -1
                kind == Kinds.ALL && q.isBlank() -> 0
                else -> repo.countBrowse(kind, ids, q)
            }
            ui.post {
                if (gen == generation && ::headerCountBadge.isInitialized) {
                    if (total >= 0) {
                        headerCountBadge.visibility = View.VISIBLE
                        headerCountBadge.text = "$total ${Kinds.unit(kind)}"
                    }
                }
            }
        }
        loadMore(true)
    }

    private fun loadMore(first: Boolean = false) {
        if (loading || endReached) return
        loading = true
        val gen = generation; val q = query; val ids = currentIds(); val start = offset
        executor.execute {
            if (gen != generation) return@execute
            val rows: List<ContentEntity> = when {
                q.isNotBlank() && kind != Kinds.FAVORITES && kind != Kinds.CONTINUE -> repo.browse(searchKind, null, q, pageSize, start)
                kind == Kinds.FAVORITES || kind == Kinds.CONTINUE -> {
                    if (start > 0) emptyList() else {
                        val all = if (kind == Kinds.FAVORITES) repo.saved(false, 100, 0).let { it + repo.saved(false, 100, 100) } else repo.continueItems()
                        val n = ArabicSearch.normalize(q)
                        if (n.isBlank()) all else all.filter { ArabicSearch.normalize(it.name).contains(n) }
                    }
                }
                kind == Kinds.ALL && q.isBlank() -> emptyList()
                else -> repo.browse(kind, ids, q, pageSize, start)
            }
            val favs = repo.favoriteKeys()
            ui.post {
                if (gen != generation) return@post
                favorites = favs
                adapter.showBadge = kind !in Kinds.CONTENT || (searchKind == Kinds.ALL && q.isNotBlank())
                adapter.submit(rows, favs, append = !first)
                offset += rows.size
                endReached = rows.size < pageSize && !(isContent && SyncManager.isRunning(kind))
                loading = false
                updateEmpty()
                if (!initialFocusSet && !userHasNavigated && currentFocus == null && rows.isNotEmpty()) {
                    initialFocusSet = true
                    recycler.post { if (!userHasNavigated && currentFocus == null) recycler.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
                if (kind == Kinds.FAVORITES || kind == Kinds.CONTINUE) {
                    headerCountBadge.visibility = View.VISIBLE
                    headerCountBadge.text = "${adapter.itemCount} ${Kinds.unit(kind)}"
                }
            }
        }
    }

    private fun updateEmpty() {
        if (adapter.itemCount > 0) { emptyView.visibility = View.GONE; return }
        val syncing = isContent && SyncManager.isRunning(kind)
        if (syncing) { emptyView.visibility = View.GONE; return }
        emptyView.visibility = View.VISIBLE
        emptyView.text = when {
            kind == Kinds.ALL && query.isBlank() -> "اكتب اسم قناة أو فيلم أو مسلسل للبحث"
            query.isNotBlank() -> "لا توجد نتائج مطابقة للبحث."
            kind == Kinds.FAVORITES -> "قائمة المفضلة فارغة حاليًا."
            kind == Kinds.CONTINUE -> "لا توجد عناصر في سجل المتابعة حاليًا."
            else -> "لا توجد عناصر في هذا التصنيف."
        }
    }

    private fun setRetry(on: Boolean) {
        statusView.isClickable = on; statusView.isFocusable = on
        if (on) {
            statusView.makeFocusable(1.03f, UI.FOCUS_RING, 2, 14)
            statusPill(UI.MOVIE)
            statusView.setOnClickListener {
                setRetry(false)
                statusView.visibility = View.GONE
                SyncManager.ensure(this, server, user, pass, kind, force = true)
            }
        } else {
            statusView.foreground = null
        }
    }

    private fun statusPill(color: Int) {
        statusView.setPadding(dp(16), dp(7), dp(16), dp(7))
        statusView.background = roundBg(UI.alpha(color, 45), 18, UI.alpha(color, 190), 1)
        statusView.setTextColor(UI.TEXT)
    }

    private fun toggleFavorite(item: ContentEntity) {
        executor.execute {
            val on = repo.toggleFavorite(item)
            val favs = repo.favoriteKeys()
            ui.post {
                favorites = favs; adapter.setFavorites(favs)
                Toast.makeText(this, if (on) "أضيف إلى المفضلة" else "أزيل من المفضلة", Toast.LENGTH_SHORT).show()
                if (kind == Kinds.FAVORITES && !on) reload()
            }
        }
    }

    override fun onCategoriesReady(kind: String) {
        if (kind == this.kind && !isFinishing && !isDestroyed) refreshAll()
    }

    override fun onSyncState(kind: String, running: Boolean, loaded: Int, ok: Boolean?) {
        if (kind != this.kind || isFinishing || isDestroyed) return
        progress.visibility = if (running) View.VISIBLE else View.GONE
        if (running) {
            setRetry(false)
            statusView.visibility = View.GONE
            val now = SystemClock.uptimeMillis()
            if (loaded > adapter.itemCount && !loading && now - lastRefresh > 400) {
                lastRefresh = now
                endReached = false
                loadMore(first = adapter.itemCount == 0)
            }
            updateEmpty()
        } else if (ok == true) {
            seenSyncFinish = SyncManager.finishedAt(kind)
            setRetry(false)
            statusView.visibility = View.GONE
            refreshAll()
        } else if (ok == false) {
            seenSyncFinish = SyncManager.finishedAt(kind)
            statusView.visibility = View.VISIBLE
            val reason = SyncManager.lastError(kind)?.let { " ($it)" }.orEmpty()
            statusView.text = if (adapter.itemCount == 0) "تعذر تحميل القسم$reason. اضغط هنا لإعادة المحاولة" else "تعذر تحديث القسم$reason، يعرض المحتوى المحفوظ. اضغط هنا لإعادة المحاولة"
            setRetry(true)
            updateEmpty()
        }
    }

    override fun onStart() {
        super.onStart()
        SyncManager.add(this)
        if (!isContent) return
        if (SyncManager.isRunning(kind)) {
            onSyncState(kind, true, SyncManager.loaded(kind), null)
        } else {
            progress.visibility = View.GONE
            val finishedAt = SyncManager.finishedAt(kind)
            if (seenSyncFinish < 0L) seenSyncFinish = finishedAt
            else if (finishedAt > seenSyncFinish) {
                seenSyncFinish = finishedAt
                statusView.visibility = View.GONE
                refreshAll()
            }
            updateEmpty()
        }
    }

    override fun onResume() {
        super.onResume()
        if (kind == Kinds.FAVORITES || kind == Kinds.CONTINUE) reload()
    }

    override fun onStop() { SyncManager.remove(this); super.onStop() }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }
}
