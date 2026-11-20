package com.alhorrtv.app

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Dashboard: aligned 1:1 with Web HomeScreen.tsx while keeping full D-pad & touch ergonomics. */
class HomeActivity : FullscreenActivity(), SyncManager.Listener {
    private val executor = Executors.newSingleThreadExecutor()
    private val netExecutor = Executors.newCachedThreadPool()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var credentials: CredentialStore
    private lateinit var repo: XtreamRepository
    private var server = ""; private var user = ""; private var pass = ""
    private var userInfo: JSONObject? = null

    private lateinit var statusChip: TextView
    private lateinit var expiryChip: TextView
    private lateinit var devicesChip: TextView
    private lateinit var syncLine: TextView
    private lateinit var emptyCard: LinearLayout
    private lateinit var emptyMsg: TextView

    private lateinit var moviesAdapter: PosterAdapter
    private lateinit var seriesAdapter: PosterAdapter
    private lateinit var newMoviesAdapter: PosterAdapter
    private lateinit var newSeriesAdapter: PosterAdapter
    private lateinit var moviesTitle: TextView
    private lateinit var seriesTitle: TextView
    private lateinit var moviesRail: RecyclerView
    private lateinit var seriesRail: RecyclerView
    private lateinit var newMoviesTitle: TextView
    private lateinit var newSeriesTitle: TextView
    private lateinit var newMoviesRail: RecyclerView
    private lateinit var newSeriesRail: RecyclerView

    private var banner: FeaturedBanner? = null
    @Volatile private var popularMoviesCache: List<ContentEntity> = emptyList()
    @Volatile private var popularSeriesCache: List<ContentEntity> = emptyList()
    private lateinit var continueAdapter: PosterAdapter
    private lateinit var continueTitle: TextView
    private lateinit var continueRail: RecyclerView
    private var continueSub: TextView? = null
    private var favSub: TextView? = null
    private var adultNavRow: View? = null

    private val railPool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(0, 24)
        setMaxRecycledViews(1, 8)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        credentials = CredentialStore(this)
        server = intent.getStringExtra("server") ?: credentials.server().orEmpty()
        user = intent.getStringExtra("username") ?: credentials.username().orEmpty()
        pass = intent.getStringExtra("password") ?: credentials.password().orEmpty()
        if (server.isBlank() || user.isBlank()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        userInfo = try { intent.getStringExtra("user_info")?.let(::JSONObject) } catch (_: Exception) { null }
        repo = XtreamRepository(this, server, user, pass)

        val fav: (ContentEntity) -> Unit = { item ->
            executor.execute {
                repo.toggleFavorite(item)
                val favs = repo.favoriteKeys()
                ui.post {
                    if (isFinishing || isDestroyed) return@post
                    favSub?.text = if (favs.isNotEmpty()) "${favs.size} عنصر" else "محتواك المفضل"
                    moviesAdapter.setFavorites(favs)
                    seriesAdapter.setFavorites(favs)
                    newMoviesAdapter.setFavorites(favs)
                    newSeriesAdapter.setFavorites(favs)
                    continueAdapter.setFavorites(favs)
                }
            }
        }
        moviesAdapter = PosterAdapter({ Nav.open(this, it, server, user, pass) }, fav, railWidthDp = 138, showBadge = true)
        seriesAdapter = PosterAdapter({ Nav.open(this, it, server, user, pass) }, fav, railWidthDp = 138, showBadge = true)
        newMoviesAdapter = PosterAdapter({ Nav.open(this, it, server, user, pass) }, fav, railWidthDp = 138, showBadge = true)
        newSeriesAdapter = PosterAdapter({ Nav.open(this, it, server, user, pass) }, fav, railWidthDp = 138, showBadge = true)
        continueAdapter = PosterAdapter({ resumeOrOpen(it) }, fav, railWidthDp = 132, showBadge = true)

        buildCinematicUi()
        showAccount()
        SyncManager.ensureAll(this, server, user, pass)
        netExecutor.execute {
            val info = repo.account()?.optJSONObject("user_info")
            if (info != null) {
                userInfo = info
                ui.post { if (!isFinishing && !isDestroyed) showAccount() }
            }
        }
    }

    private fun buildCinematicUi() {
        val root = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = HexPatternDrawable()
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(row, FrameLayout.LayoutParams(-1, -1))

        // ---- Permanent Right Sidebar (RTL) matching Web HomeScreen.tsx
        val sideShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(12), dp(10), dp(10))
            background = roundBg(UI.PANEL, 16, UI.alpha(UI.STROKE, 210), 1)
            clipRounded(dpf(16))
        }
        sideShell.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_alhorr_login)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "الحر الأصلي"
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        sideShell.addView(tv("ALHUR TV", 11.5f, UI.NEON, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, dp(4), 0, dp(8))
        }, LinearLayout.LayoutParams(-1, -2))
        sideShell.addView(View(this).apply { setBackgroundColor(UI.alpha(UI.STROKE, 180)) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { bottomMargin = dp(8) })

        val navScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        navScroll.addView(side, FrameLayout.LayoutParams(-1, -2))
        sideShell.addView(navScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        var firstNav: View? = null
        fun nav(label: String, subtitle: String, iconRes: Int, accent: Int, onClick: () -> Unit): TextView {
            val titleTv = tv(label, 12.5f, Color.WHITE, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val subTv = tv(subtitle, 9.5f, UI.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = roundBg(UI.PANEL2, 12, Color.TRANSPARENT, 1)
                makeFocusable(1.02f, UI.NEON, 2, 12)
                setOnClickListener { onClick() }
                val baseFocus = onFocusChangeListener
                setOnFocusChangeListener { v, focused ->
                    baseFocus?.onFocusChange(v, focused)
                    titleTv.setTextColor(if (focused) UI.NEON else Color.WHITE)
                }
            }
            item.addView(iconBubble(iconRes, accent, 30))
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, dp(2), 0)
            }
            texts.addView(titleTv)
            texts.addView(subTv)
            item.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            side.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            if (firstNav == null) firstNav = item
            return subTv
        }

        nav("البث المباشر", "شاهد القنوات المباشرة", R.drawable.ic_set_live, Color.parseColor("#2FB4FF")) { openBrowse(Kinds.LIVE) }
        nav("الأفلام", "مكتبة الأفلام", R.drawable.ic_set_movie, Color.parseColor("#36D5FF")) { openBrowse(Kinds.MOVIES) }
        nav("المسلسلات", "مسلسلات تلفزيونية", R.drawable.ic_set_series, Color.parseColor("#B05CFF")) { openBrowse(Kinds.SERIES) }
        continueSub = nav("متابعة المشاهدة", "أكمل من حيث توقفت", R.drawable.ic_set_clock, Color.parseColor("#25D9BD")) { openBrowse(Kinds.CONTINUE) }
        nav("التصنيفات", "تصفح حسب الفئات", R.drawable.ic_set_grid, Color.parseColor("#FFB800")) { chooseCategoryKind() }
        favSub = nav("المفضلة", "محتواك المفضل", R.drawable.ic_set_heart, Color.parseColor("#E8384F")) { openBrowse(Kinds.FAVORITES) }
        val adultSubTv = nav("Adult 18+", "قنوات للبالغين فقط (+18)", R.drawable.ic_set_adult18, Color.parseColor("#E8384F")) {
            if (!AdultStore.isEnabled(this)) return@nav
            AdultAgeGate.show(this, onConfirmed = {
                startActivity(Intent(this, AdultActivity::class.java))
            })
        }
        adultNavRow = (adultSubTv.parent?.parent as? View)?.apply {
            visibility = if (AdultStore.isEnabled(this@HomeActivity)) View.VISIBLE else View.GONE
        }
        nav("الإعدادات", "الحساب والمشغلات", R.drawable.ic_set_gear, Color.parseColor("#A0AEC0")) { showSettings() }

        row.addView(sideShell, LinearLayout.LayoutParams(dp(194), -1).apply { marginEnd = dp(12) })

        // ---- Main column (left in RTL): Top Toolbar + Scrollable Body
        val mainColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(mainColumn, LinearLayout.LayoutParams(0, -1, 1f))

        // Top Toolbar matching Web HomeScreen.tsx
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = roundBg(UI.PANEL, 16, UI.alpha(UI.STROKE, 210), 1)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }

        // Right side in RTL: 3 Account Info Chips inside #1C2230 pill strip
        val chipsStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = roundBg(UI.PANEL2, 12, UI.alpha(UI.STROKE, 190), 1)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        fun chipColumn(label: String, ltr: Boolean): Pair<LinearLayout, TextView> {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(2), dp(6), dp(2))
            }
            col.addView(tv(label, 9.5f, UI.MUTED).apply { gravity = Gravity.CENTER })
            val valTv = tv("—", 11.5f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (ltr) textDirection = View.TEXT_DIRECTION_LTR
            }
            col.addView(valTv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(1) })
            return col to valTv
        }
        val (statusCol, statusVal) = chipColumn("الحالة", false)
        val (expiryCol, expiryVal) = chipColumn("الانتهاء", true)
        val (devicesCol, devicesVal) = chipColumn("الأجهزة", true)
        statusChip = statusVal; expiryChip = expiryVal; devicesChip = devicesVal

        chipsStrip.addView(statusCol, LinearLayout.LayoutParams(0, -2, 1f))
        chipsStrip.addView(View(this).apply { setBackgroundColor(UI.STROKE) }, LinearLayout.LayoutParams(dp(1), dp(26)))
        chipsStrip.addView(expiryCol, LinearLayout.LayoutParams(0, -2, 1.15f))
        chipsStrip.addView(View(this).apply { setBackgroundColor(UI.STROKE) }, LinearLayout.LayoutParams(dp(1), dp(26)))
        chipsStrip.addView(devicesCol, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(chipsStrip, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })

        // Left side in RTL: Horizontal Single-Line Action Buttons (البحث / الإعدادات / خروج)
        fun horizontalToolBtn(
            iconRes: Int,
            label: String,
            iconTint: Int,
            textColor: Int,
            focusBorder: Int,
            onClick: () -> Unit
        ): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundBg(UI.PANEL2, 12, UI.STROKE, 1)
            makeFocusable(1.03f, focusBorder, 2, 12)
            addView(ImageView(this@HomeActivity).apply {
                setImageResource(iconRes)
                setColorFilter(iconTint)
            }, LinearLayout.LayoutParams(dp(15), dp(15)))
            addView(tv(label, 11.5f, textColor, true).apply {
                setPadding(dp(6), 0, 0, 0)
                maxLines = 1
            })
            setOnClickListener { onClick() }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        actions.addView(
            horizontalToolBtn(R.drawable.ic_set_search, "البحث", UI.NEON, Color.WHITE, UI.NEON) {
                openBrowse(Kinds.ALL, focusSearch = true)
            },
            LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) }
        )
        actions.addView(
            horizontalToolBtn(R.drawable.ic_set_gear, "الإعدادات", UI.MUTED, Color.WHITE, UI.NEON) {
                showSettings()
            },
            LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(6) }
        )
        actions.addView(
            horizontalToolBtn(
                R.drawable.ic_set_logout,
                "خروج",
                Color.parseColor("#FF7B8B"),
                Color.parseColor("#FF7B8B"),
                Color.parseColor("#E8384F")
            ) { confirmLogout() },
            LinearLayout.LayoutParams(-2, dp(38))
        )
        header.addView(actions)
        mainColumn.addView(header, LinearLayout.LayoutParams(-1, -2))

        syncLine = tv("", 11.5f, UI.GREEN, true).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
        }
        mainColumn.addView(syncLine)

        val mainScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(16))
        }
        mainScroll.addView(main, FrameLayout.LayoutParams(-1, -2))
        mainColumn.addView(mainScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        // Featured Banner
        val featured = FeaturedBanner(this, repo, Kinds.MOVIES) { item -> Nav.open(this, item, server, user, pass) }
        featured.onWatch = { item -> watchNow(item) }
        featured.visibility = View.GONE
        banner = featured
        main.addView(featured, LinearLayout.LayoutParams(-1, dp(290)))

        // Content Rails matching Web HomeScreen.tsx
        fun railBox(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundBg(UI.PANEL, 16, UI.alpha(UI.STROKE, 210), 1)
        }
        val continueBox = railBox().apply { visibility = View.GONE }
        continueTitle = railTitle("متابعة المشاهدة", Kinds.CONTINUE, continueBox)
        continueRail = rail(continueAdapter, continueBox)
        main.addView(continueBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val moviesBox = railBox().apply { visibility = View.GONE }
        moviesTitle = railTitle("أفلام رائجة ★", Kinds.MOVIES, moviesBox)
        moviesRail = rail(moviesAdapter, moviesBox)
        main.addView(moviesBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val newMoviesBox = railBox().apply { visibility = View.GONE }
        newMoviesTitle = railTitle("أفلام جديدة", Kinds.MOVIES, newMoviesBox)
        newMoviesRail = rail(newMoviesAdapter, newMoviesBox)
        main.addView(newMoviesBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val seriesBox = railBox().apply { visibility = View.GONE }
        seriesTitle = railTitle("مسلسلات رائجة ★", Kinds.SERIES, seriesBox)
        seriesRail = rail(seriesAdapter, seriesBox)
        main.addView(seriesBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val newSeriesBox = railBox().apply { visibility = View.GONE }
        newSeriesTitle = railTitle("مسلسلات جديدة", Kinds.SERIES, newSeriesBox)
        newSeriesRail = rail(newSeriesAdapter, newSeriesBox)
        main.addView(newSeriesBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // Empty State Hint Card matching Web HomeScreen.tsx
        emptyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundBg(Color.parseColor("#131722"), 16, UI.STROKE, 1)
            setPadding(dp(24), dp(30), dp(24), dp(30))
            visibility = View.GONE
        }
        emptyMsg = tv("جارٍ تنزيل مكتبة القنوات والأفلام والمسلسلات…", 13.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
        }
        emptyCard.addView(emptyMsg)
        emptyCard.addView(tv("تحديث المكتبة الآن", 12f, Color.parseColor("#001018"), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = roundBg(UI.NEON, 12)
            makeFocusable(1.04f, Color.WHITE, 2, 12)
            setOnClickListener { SyncManager.ensureAll(this@HomeActivity, server, user, pass, force = true) }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        main.addView(emptyCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        setContentView(root)
        firstNav?.post { if (!isFinishing && !isDestroyed) firstNav?.requestFocus() }
    }

    private fun watchNow(item: ContentEntity) {
        if (item.kind != Kinds.MOVIES) {
            Nav.open(this, item, server, user, pass)
            return
        }
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("server", server).putExtra("username", user).putExtra("password", pass)
            .putExtra("url", buildMediaUrl(server, "movie", user, pass, item.contentId, item.extension))
            .putExtra("title", item.name).putExtra("image", item.imageUrl)
            .putExtra("mark_kind", "get_vod_streams").putExtra("mark_id", item.contentId))
    }

    private fun resumeOrOpen(item: ContentEntity) {
        executor.execute {
            val position = repo.mark(item.kind, item.contentId)?.positionMs ?: 0L
            val point = if (item.kind == Kinds.SERIES) ResumeStore.seriesPoint(this, item.contentId) else null
            ui.post {
                if (isFinishing || isDestroyed) return@post
                val player = Intent(this, PlayerActivity::class.java)
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

    private fun railTitle(label: String, kind: String, root: LinearLayout): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = tv(label, 16.5f, UI.TEXT, true)
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tv("عرض الكل  ‹", 12f, UI.accent(kind), true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            makeFocusable(1.04f, UI.NEON, 1, 8)
            setOnClickListener { openBrowse(kind) }
        })
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        row.visibility = View.GONE
        title.tag = row
        return title
    }

    private fun rail(adapter: PosterAdapter, root: LinearLayout): RecyclerView {
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@HomeActivity, LinearLayoutManager.HORIZONTAL, false)
            this.adapter = adapter; isNestedScrollingEnabled = false; clipToPadding = false; visibility = View.GONE
            overScrollMode = View.OVER_SCROLL_NEVER; itemAnimator = null
            setRecycledViewPool(railPool); setItemViewCacheSize(8)
        }
        root.addView(rv, LinearLayout.LayoutParams(-1, -2))
        return rv
    }

    // ---- Styled Dark Modals matching Web HomeScreen.tsx

    private fun darkDialog(widthDp: Int, cancelable: Boolean = true, fill: (LinearLayout, Dialog) -> Unit): Dialog {
        val before = currentFocus
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(cancelable)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = roundBg(Color.parseColor("#131722"), 16, UI.STROKE, 1)
            clipRounded(dpf(16))
        }
        fill(panel, dialog)
        dialog.setOnDismissListener {
            ui.post { before?.takeIf { it.isShown && it.isFocusable }?.requestFocus() }
        }
        dialog.setContentView(panel)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(dp(widthDp), WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.75f)
        }
        return dialog
    }

    private fun chooseCategoryKind() {
        darkDialog(380) { panel, dialog ->
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
            }
            head.addView(tv("تصفح التصنيفات", 15.5f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(tv("✕", 14f, UI.MUTED, true).apply {
                gravity = Gravity.CENTER
                makeFocusable(1.08f, UI.NEON, 1, 8)
                setOnClickListener { dialog.dismiss() }
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            panel.addView(head)
            panel.addView(View(this).apply { setBackgroundColor(UI.STROKE) }, LinearLayout.LayoutParams(-1, dp(1)))

            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            Kinds.CONTENT.forEachIndexed { idx, k ->
                val itemRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    val idle = roundBg(UI.PANEL2, 12, UI.STROKE, 1)
                    val focused = roundBg(Color.parseColor("#253048"), 12, UI.NEON, 2)
                    background = StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), focused)
                        addState(intArrayOf(android.R.attr.state_pressed), focused)
                        addState(intArrayOf(), idle)
                    }
                    isFocusable = true; isClickable = true
                    setOnClickListener {
                        dialog.dismiss()
                        openBrowse(k, openCategories = true)
                    }
                }
                itemRow.addView(tv(Kinds.title(k), 13.5f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
                itemRow.addView(tv("‹", 18f, UI.NEON, true))
                body.addView(itemRow, LinearLayout.LayoutParams(-1, -2).apply {
                    if (idx < Kinds.CONTENT.size - 1) bottomMargin = dp(10)
                })
                if (idx == 0) itemRow.post { itemRow.requestFocus() }
            }
            panel.addView(body)
        }
    }

    private fun openBrowse(kind: String, focusSearch: Boolean = false, openCategories: Boolean = false) {
        val launch = {
            Nav.browse(this, kind, server, user, pass, focusSearch = focusSearch, openCategories = openCategories)
        }
        if (kind != Kinds.ALL && kind !in Kinds.CONTENT) { launch(); return }
        val needed = if (kind == Kinds.ALL) Kinds.CONTENT else listOf(kind)
        executor.execute {
            if (SyncManager.categoriesCached(applicationContext, server, user, pass, needed)) {
                ui.post { if (!isFinishing && !isDestroyed) launch() }
                return@execute
            }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                val progressDialog = darkDialog(360, cancelable = false) { panel, _ ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(20), dp(18), dp(20), dp(18))
                    }
                    row.addView(ProgressBar(this).apply {
                        isIndeterminate = true
                        indeterminateTintList = android.content.res.ColorStateList.valueOf(UI.NEON)
                    }, LinearLayout.LayoutParams(dp(28), dp(28)))
                    val texts = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), 0, 0, 0)
                    }
                    texts.addView(tv("تحميل التصنيفات لأول مرة", 13.5f, Color.WHITE, true))
                    val name = Kinds.title(kind).takeIf { kind != Kinds.ALL } ?: "التصنيفات"
                    texts.addView(tv("جارٍ تجهيز $name…", 11.5f, UI.MUTED).apply { setPadding(0, dp(3), 0, 0) })
                    row.addView(texts)
                    panel.addView(row)
                }
                netExecutor.execute {
                    val ready = SyncManager.ensureCategories(applicationContext, server, user, pass, needed)
                    ui.post {
                        if (isFinishing || isDestroyed) return@post
                        progressDialog.dismiss()
                        if (ready) launch() else darkDialog(380) { panel, d ->
                            val box = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(20), dp(18), dp(20), dp(18))
                            }
                            box.addView(tv("تعذر تحميل التصنيفات", 15f, Color.WHITE, true))
                            box.addView(tv("تعذر جلب التصنيفات من مزود الخدمة. تحقق من الاتصال ثم حاول مرة أخرى.", 12f, UI.MUTED).apply {
                                setPadding(0, dp(8), 0, dp(16))
                            })
                            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
                            val close = tv("إغلاق", 12f, Color.WHITE, true).apply {
                                gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8))
                                background = roundBg(UI.PANEL2, 12); makeFocusable(1.04f, UI.NEON, 2, 12)
                                setOnClickListener { d.dismiss() }
                            }
                            val retry = tv("إعادة المحاولة", 12f, Color.parseColor("#001018"), true).apply {
                                gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8))
                                background = roundBg(UI.NEON, 12); makeFocusable(1.04f, Color.WHITE, 2, 12)
                                setOnClickListener { d.dismiss(); openBrowse(kind, focusSearch, openCategories) }
                            }
                            btns.addView(close, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
                            btns.addView(retry)
                            box.addView(btns)
                            panel.addView(box)
                            close.post { close.requestFocus() }
                        }
                    }
                }
            }
        }
    }

    private fun showAccount() {
        val isFree = FreeCatalog.isFreeServer(server)
        val info = userInfo
        val status = info?.optString("status", "")?.takeIf { it.isNotBlank() && it != "null" }
            ?: if (isFree) "Active" else null
        statusChip.text = when {
            isFree -> "● مجاني نشط"
            status?.lowercase() == "active" -> "● نشط"
            status?.lowercase() == "expired" -> "منتهي"
            status != null -> status
            else -> "غير محدد"
        }
        statusChip.setTextColor(if (isFree || status.equals("active", true)) UI.GREEN else UI.MUTED)

        val exp = info?.optLong("exp_date", 0L) ?: 0L
        expiryChip.text = when {
            isFree -> "مفتوح (مجاني)"
            exp > 0L -> SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date(exp * 1000L))
            else -> "غير محدد"
        }
        val max = info?.optString("max_connections", "")?.takeIf { it.isNotBlank() && it != "null" }
        val used = info?.optString("active_cons", "")?.takeIf { it.isNotBlank() && it != "null" }
        devicesChip.text = when {
            isFree -> "مجاني"
            max != null -> "${used ?: 0} / $max"
            else -> "—"
        }
    }

    private fun loadDashboard() {
        executor.execute {
            val latestMovies = repo.latest(Kinds.MOVIES, 20)
            val trending = (repo.trending(Kinds.MOVIES, 10) + repo.trending(Kinds.SERIES, 10))
                .sortedByDescending { Rating.value(it.rating) }.take(10)
            val latestSeries = repo.latest(Kinds.SERIES, 20)
            val popularMovies = popularMoviesCache.takeIf { it.isNotEmpty() } ?: repo.randomPosters(Kinds.MOVIES, 20).also { popularMoviesCache = it }
            val popularSeries = popularSeriesCache.takeIf { it.isNotEmpty() } ?: repo.randomPosters(Kinds.SERIES, 20).also { popularSeriesCache = it }
            val resumeItems = repo.continueItems()
            val resume = resumeItems.size
            val favs = repo.favoriteKeys()
            ui.post {
                if (isFinishing || isDestroyed) return@post
                continueSub?.text = if (resume > 0) "$resume عنصر لإكماله" else "أكمل من حيث توقفت"
                favSub?.text = if (favs.isNotEmpty()) "${favs.size} عنصر" else "محتواك المفضل"
                continueAdapter.submit(resumeItems, favs, false)
                moviesAdapter.submit(popularMovies, favs, false)
                seriesAdapter.submit(popularSeries, favs, false)
                newMoviesAdapter.submit(latestMovies, favs, false)
                newSeriesAdapter.submit(latestSeries, favs, false)

                fun show(title: TextView, rail: RecyclerView, has: Boolean) {
                    (title.tag as? View)?.visibility = if (has) View.VISIBLE else View.GONE
                    rail.visibility = if (has) View.VISIBLE else View.GONE
                    (rail.parent as? View)?.visibility = if (has) View.VISIBLE else View.GONE
                }
                show(continueTitle, continueRail, resumeItems.isNotEmpty())
                show(moviesTitle, moviesRail, popularMovies.isNotEmpty())
                show(newMoviesTitle, newMoviesRail, latestMovies.isNotEmpty())
                show(seriesTitle, seriesRail, popularSeries.isNotEmpty())
                show(newSeriesTitle, newSeriesRail, latestSeries.isNotEmpty())

                banner?.setItems(trending)
                banner?.visibility = if (trending.isEmpty()) View.GONE else View.VISIBLE

                val allEmpty = trending.isEmpty() && latestMovies.isEmpty() && latestSeries.isEmpty() &&
                    popularMovies.isEmpty() && popularSeries.isEmpty()
                emptyCard.visibility = if (allEmpty) View.VISIBLE else View.GONE
                if (allEmpty) {
                    val running = Kinds.CONTENT.any { SyncManager.isRunning(it) }
                    emptyMsg.text = if (running) "جارٍ تنزيل مكتبة القنوات والأفلام والمسلسلات…" else "لم يتم تحميل أي محتوى بعد."
                }
            }
        }
    }

    private fun showSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            putExtra("server", server)
            putExtra("username", user)
            putExtra("password", pass)
            putExtra("user_info", userInfo?.toString())
        })
    }

    private fun confirmLogout() {
        darkDialog(380) { panel, dialog ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(18))
            }
            box.addView(tv("تسجيل الخروج", 15.5f, Color.WHITE, true))
            box.addView(tv("سيتم حذف المحتوى المحفوظ على الجهاز وبيانات الدخول.", 12.5f, UI.MUTED).apply {
                setPadding(0, dp(8), 0, dp(16))
            })
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            val cancel = tv("إلغاء", 12.5f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = roundBg(UI.PANEL2, 12)
                makeFocusable(1.04f, UI.NEON, 2, 12)
                setOnClickListener { dialog.dismiss() }
            }
            val exit = tv("خروج", 12.5f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(8), dp(18), dp(8))
                background = roundBg(Color.parseColor("#E8384F"), 12)
                makeFocusable(1.04f, Color.WHITE, 2, 12)
                setOnClickListener {
                    dialog.dismiss()
                    credentials.clear()
                    SyncManager.reset(this@HomeActivity)
                    executor.execute {
                        try { ContentDatabase.get(this@HomeActivity).clearAllTables() } catch (_: Exception) { }
                        ui.post {
                            startActivity(Intent(this@HomeActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra("no_auto", true))
                            finish()
                        }
                    }
                }
            }
            btns.addView(cancel, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            btns.addView(exit)
            box.addView(btns)
            panel.addView(box)
            cancel.post { cancel.requestFocus() }
        }
    }

    override fun onSyncState(kind: String, running: Boolean, loaded: Int, ok: Boolean?) {
        if (isFinishing || isDestroyed) return
        val anyRunning = Kinds.CONTENT.any { SyncManager.isRunning(it) } || running
        syncLine.visibility = if (anyRunning) View.VISIBLE else View.GONE
        if (anyRunning) syncLine.text = "⟳ جارٍ تحديث المكتبة في الخلفية…"
        val now = android.os.SystemClock.uptimeMillis()
        if (!running || (loaded >= 40 && now - lastDashboardAt > 4000L)) {
            lastDashboardAt = now
            loadDashboard()
        }
    }
    private var lastDashboardAt = 0L

    private fun refreshSyncLine() {
        val anyRunning = Kinds.CONTENT.any { SyncManager.isRunning(it) }
        syncLine.visibility = if (anyRunning) View.VISIBLE else View.GONE
        if (anyRunning) syncLine.text = "⟳ جارٍ تحديث المكتبة في الخلفية…"
    }

    override fun onStart() { super.onStart(); SyncManager.add(this); refreshSyncLine(); banner?.start() }
    override fun onResume() {
        super.onResume()
        adultNavRow?.visibility = if (AdultStore.isEnabled(this)) View.VISIBLE else View.GONE
        refreshSyncLine()
        loadDashboard()
    }
    override fun onStop() { SyncManager.remove(this); banner?.stop(); super.onStop() }
    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        banner?.release()
        executor.shutdownNow()
        netExecutor.shutdownNow()
        super.onDestroy()
    }
}
