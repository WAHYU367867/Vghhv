package com.alhorrtv.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Series-level fields from get_series_info. */
private data class SeriesInfo(
    val plot: String = "", val year: String = "", val rating: String = "", val genres: List<String> = emptyList(),
    val cast: String = "", val poster: String = "", val backdrop: String = ""
)

class SeriesDetailActivity : FullscreenActivity() {
    private val ioExecutor = Executors.newFixedThreadPool(2)       // network + saving the server response
    private val dbExecutor = Executors.newSingleThreadExecutor()   // local database reads
    private lateinit var database: ContentDatabase
    private lateinit var client: XtreamRepository
    private lateinit var heroBg: ImageView
    private lateinit var poster: ImageView
    private lateinit var pillRow: FlowLayout
    private lateinit var description: ExpandableText
    private lateinit var play: TextView
    private lateinit var favoriteButton: TextView
    private lateinit var followButton: TextView
    private lateinit var infoButton: TextView
    private lateinit var seasonsRow: LinearLayout
    private lateinit var seasonScroll: HorizontalScrollView
    private lateinit var noteView: TextView
    private lateinit var episodesState: DetailStateView
    private lateinit var episodes: RecyclerView
    private lateinit var episodeGrid: GridLayoutManager
    private lateinit var episodesColumn: LinearLayout
    private lateinit var sideColumn: LinearLayout
    private lateinit var castHost: LinearLayout
    private lateinit var similarHost: LinearLayout
    private val episodeAdapter = EpisodeAdapter()

    private var username = ""; private var password = ""; private var server = ""; private var seriesId = ""
    private var seriesName = ""
    private var selectedSeason = ""
    private var lastOpenedEpisode = ""
    private var focusAnchor = ANCHOR_NONE
    private var returningFromPlayer = false
    private var pendingRestore = false
    private var posterUrl = ""
    private var info = SeriesInfo()
    private var seasonCount = 0
    private var episodeCount = 0
    private var resumeEpisodeId = ""
    private var resumePositionMs = 0L
    @Volatile private var refreshing = false
    private val seasonLabels = ConcurrentHashMap<String, String>()
    private val episodeDurations = ConcurrentHashMap<String, String>()
    private val tabKeys = ArrayList<String>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        username = intent.getStringExtra("username").orEmpty(); password = intent.getStringExtra("password").orEmpty()
        server = intent.getStringExtra("server").orEmpty(); seriesId = intent.getStringExtra("series_id").orEmpty()
        seriesName = intent.getStringExtra("series_name") ?: "تفاصيل المسلسل"
        posterUrl = intent.getStringExtra("series_image").orEmpty()
        state?.let {
            selectedSeason = it.getString("season").orEmpty()
            lastOpenedEpisode = it.getString("last_ep").orEmpty()
            focusAnchor = it.getInt("anchor", ANCHOR_NONE)
            pendingRestore = lastOpenedEpisode.isNotBlank() && focusAnchor != ANCHOR_NONE
        }
        ResumeStore.seriesPoint(this, seriesId)?.let {
            resumeEpisodeId = it.episodeId
            resumePositionMs = it.positionMs
        }
        client = XtreamRepository(this, server, username, password); database = ContentDatabase.get(this)
        buildUi()
        start()
    }

    override fun onResume() {
        super.onResume()
        if (::favoriteButton.isInitialized && seriesId.isNotBlank()) refreshMarks()
        if (returningFromPlayer) {
            returningFromPlayer = false
            val previous = resumeEpisodeId
            ResumeStore.seriesPoint(this, seriesId)?.let { resumeEpisodeId = it.episodeId; resumePositionMs = it.positionMs }
            updatePlayLabel()
            episodeAdapter.notifyEpisode(previous); episodeAdapter.notifyEpisode(resumeEpisodeId)
            window.decorView.postDelayed({ if (!isFinishing && !isDestroyed) restoreFocus() }, 60)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("season", selectedSeason)
        outState.putString("last_ep", lastOpenedEpisode)
        outState.putInt("anchor", focusAnchor)
    }

    /** Puts the remote focus back exactly where the person pressed (the play button or the episode card). */
    private fun restoreFocus(attempt: Int = 0) {
        if (focusAnchor == ANCHOR_EPISODE && lastOpenedEpisode.isNotBlank()) {
            val pos = episodeAdapter.positionOf(lastOpenedEpisode)
            if (pos >= 0) {
                episodeAdapter.ensureVisible(pos)
                val target = episodes.findViewHolderForAdapterPosition(pos)?.itemView
                if (target != null) { target.requestFocus(); return }
                if (attempt < 4) episodes.postDelayed({ restoreFocus(attempt + 1) }, 60)
                return
            }
        }
        if (play.isEnabled) play.requestFocus()
    }

    // ------------------------------------------------------------ UI

    private fun buildUi() {
        poster = detailPoster()
        ImageLoader.bind(this, poster, posterUrl, UI.PANEL2, seriesName, Kinds.SERIES, priority = true, maxPx = 600, fade = true)

        val titleView = tv(seriesName, 28f, UI.TEXT, true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; alignToContent()
        }
        pillRow = FlowLayout(this).apply { hGap = dp(8); vGap = dp(8) }
        description = ExpandableText(this, 3)

        play = heroActionButton("▶  تشغيل الحلقة الأولى", HeroBtnStyle.PRIMARY).apply {
            isEnabled = false; alpha = 0.5f
            setOnClickListener { episodeAdapter.resumeOrFirst()?.let { playEpisode(it, fromPlay = true) } }
        }
        updatePlayLabel()
        favoriteButton = heroActionButton("☆ المفضلة", HeroBtnStyle.SECONDARY).apply { setOnClickListener { toggleMark(false) } }
        followButton = heroActionButton("+ متابعة", HeroBtnStyle.SECONDARY).apply { setOnClickListener { toggleMark(true) } }
        infoButton = heroActionButton("ⓘ معلومات", HeroBtnStyle.SECONDARY).apply { setOnClickListener { showInfoDialog() } }

        val actions = FlowLayout(this).apply { hGap = dp(10); vGap = dp(10) }
        actions.addView(play)
        actions.addView(favoriteButton)
        actions.addView(followButton)
        actions.addView(infoButton)

        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        textColumn.addView(pillRow, LinearLayout.LayoutParams(-1, -2))
        textColumn.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        textColumn.addView(description, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        textColumn.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        val (heroBanner, bgView) = buildHeroBanner(poster, textColumn, 146, 219) { finish() }
        heroBg = bgView
        bindHeroBackdrop(posterUrl)

        // ---- seasons + episodes (grid cards matching Web SeriesDetailScreen)
        seasonsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        seasonScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; visibility = View.GONE
            clipToPadding = true; clipChildren = true; addView(seasonsRow)
        }
        noteView = tv("", 12f, UI.MUTED).apply { setPadding(0, dp(4), 0, dp(4)); visibility = View.GONE }
        episodesState = DetailStateView(this)
        episodeGrid = GridLayoutManager(this, 3).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (episodeAdapter.getItemViewType(position) == TYPE_MORE) spanCount else 1
            }
        }
        episodes = RecyclerView(this).apply {
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutManager = episodeGrid
            adapter = episodeAdapter
            itemAnimator = null
            visibility = View.GONE
            isNestedScrollingEnabled = false
            setItemViewCacheSize(12)
        }

        episodesColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        episodesColumn.addView(seasonScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        episodesColumn.addView(noteView, LinearLayout.LayoutParams(-1, -2))
        episodesColumn.addView(episodesState, LinearLayout.LayoutParams(-1, -2))
        episodesColumn.addView(episodes, LinearLayout.LayoutParams(-1, -2))

        castHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        similarHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        sideColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            visibility = View.GONE
        }
        sideColumn.addView(castHost, LinearLayout.LayoutParams(-1, -2))
        sideColumn.addView(similarHost, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        val splitBody = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(28), dp(14), dp(28), dp(28))
        }
        splitBody.addView(episodesColumn, LinearLayout.LayoutParams(0, -2, 7f))
        splitBody.addView(sideColumn, LinearLayout.LayoutParams(0, -2, 5f).apply { marginStart = dp(22) })

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        page.addView(heroBanner, LinearLayout.LayoutParams(-1, -2))
        page.addView(splitBody, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            background = HexPatternDrawable()
            addView(page, ViewGroup.LayoutParams(-1, -2))
        }
        setContentView(scroll)

        description.setContent("")
        renderPills()
        showEpisodesLoading()
    }

    private fun bindHeroBackdrop(url: String?) {
        val value = url.orEmpty().trim()
        if (value.isBlank() || !(value.startsWith("http://", true) || value.startsWith("https://", true))) return
        heroBg.visibility = View.VISIBLE
        ImageLoader.bind(this, heroBg, value, UI.BG, priority = true, maxPx = 1280, fade = true)
    }

    private fun updateSplitColumns() {
        val hasSide = castHost.visibility == View.VISIBLE || similarHost.visibility == View.VISIBLE
        sideColumn.visibility = if (hasSide) View.VISIBLE else View.GONE
        val desiredSpans = if (hasSide) 2 else 3
        if (episodeGrid.spanCount != desiredSpans) {
            episodeGrid.spanCount = desiredSpans
            episodeAdapter.notifyDataSetChanged()
        }
    }

    private fun updatePlayLabel() {
        if (!::play.isInitialized) return
        play.text = if (resumeEpisodeId.isNotBlank() && resumePositionMs > 0L) {
            "▶  متابعة · ${formatTimeLabel(resumePositionMs)}"
        } else if (resumeEpisodeId.isNotBlank()) {
            "▶  متابعة"
        } else {
            "▶  تشغيل الحلقة الأولى"
        }
    }

    private fun formatTimeLabel(ms: Long): String {
        val minutes = (ms / 60000L).coerceAtLeast(1L)
        return if (minutes >= 60L) "${minutes / 60}س ${minutes % 60}د" else "$minutes د"
    }

    private fun renderPills() {
        pillRow.removeAllViews()
        if (info.rating.isNotBlank()) pillRow.addView(ratingBadge("★ ${info.rating}"))
        if (info.year.isNotBlank()) pillRow.addView(glassBadge(info.year))
        if (seasonCount > 0) pillRow.addView(seasonsBadge(if (seasonCount == 1) "1 مواسم" else "$seasonCount مواسم"))
        if (info.genres.isNotEmpty()) pillRow.addView(genreBadge(info.genres.joinToString("، ")))
    }

    // ------------------------------------------------------------ episodes area states

    private fun showEpisodesLoading() {
        episodes.visibility = View.GONE; seasonScroll.visibility = View.GONE; noteView.visibility = View.GONE
        episodesState.loading("جارٍ تحميل الحلقات…")
    }

    private fun showEpisodesReady(note: String?) {
        episodesState.hide()
        seasonScroll.visibility = if (seasonsRow.childCount > 0) View.VISIBLE else View.GONE
        episodes.visibility = View.VISIBLE
        if (note.isNullOrBlank()) noteView.visibility = View.GONE else { noteView.text = note; noteView.visibility = View.VISIBLE }
    }

    private fun showEpisodesProblem(message: String, canRetry: Boolean) {
        episodes.visibility = View.GONE; seasonScroll.visibility = View.GONE; noteView.visibility = View.GONE
        episodesState.error(message, if (canRetry) ({ retryRefresh() }) else null)
    }

    private fun retryRefresh() {
        if (refreshing) return
        showEpisodesLoading()
        ioExecutor.execute { refreshFromServer(hadCache = false) }
    }

    // ------------------------------------------------------------ data

    private fun start() {
        if (seriesId.isBlank()) { showEpisodesProblem("معرّف المسلسل غير متوفر.", canRetry = false); return }
        dbExecutor.execute {
            val cachedSeasons: List<String> = try {
                database.episodeDao().seasons(seriesId).sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
            } catch (_: Throwable) { emptyList() }
            val mark = try { database.contentDao().getMark(Kinds.SERIES, seriesId) } catch (_: Throwable) { null }
            val similar: List<ContentEntity> = try {
                val category = database.contentDao().getContent(Kinds.SERIES, seriesId)?.category.orEmpty()
                if (category.isBlank()) emptyList()
                else client.browse(Kinds.SERIES, listOf(category), "", 30, 0).filter { it.contentId != seriesId }.take(12)
            } catch (_: Throwable) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateMarkButtons(mark); showSimilar(similar)
                if (cachedSeasons.isNotEmpty()) {
                    seasonCount = cachedSeasons.size; renderPills()
                    val first = selectedSeason.takeIf { it in cachedSeasons } ?: cachedSeasons.first()
                    buildSeasonTabs(cachedSeasons); showSeason(first)
                    showEpisodesReady("جارٍ تحديث الحلقات…")
                }
            }
            ioExecutor.execute { refreshFromServer(hadCache = cachedSeasons.isNotEmpty()) }
        }
    }

    private fun parseInfo(seriesInfo: JSONObject?): SeriesInfo {
        if (seriesInfo == null) return SeriesInfo()
        val year = seriesInfo.str("releaseDate", "releasedate", "release_date", "year").take(4)
        return SeriesInfo(
            plot = seriesInfo.str("plot", "description", "overview"),
            year = year.takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty(),
            rating = cleanRating(seriesInfo.str("rating", "rating_5based")),
            genres = splitNames(seriesInfo.str("genre", "genres")).take(3),
            cast = seriesInfo.str("cast", "actors"),
            poster = seriesInfo.firstImage("cover", "cover_big", "movie_image"),
            backdrop = seriesInfo.firstImage("backdrop_path", "cover_big", "cover")
        )
    }

    /** Runs on an IO thread. Always ends by telling the UI exactly one of: episodes ready, empty, or failed. */
    private fun refreshFromServer(hadCache: Boolean) {
        if (refreshing) return
        refreshing = true
        var temporaryId: String? = null
        try {
            val response = client.details("get_series_info", seriesId)
            val parsedInfo = parseInfo(response.optJSONObject("info"))
            response.optJSONArray("seasons")?.let { seasonArray ->
                for (i in 0 until seasonArray.length()) seasonArray.optJSONObject(i)?.let {
                    val number = it.optString("season_number", it.optString("id"))
                    if (number.isNotBlank()) seasonLabels[number] = it.optString("name", "الموسم $number")
                }
            }
            val temp = "$seriesId:${UUID.randomUUID()}"
            temporaryId = temp
            val batch = ArrayList<EpisodeEntity>(100); val seasonKeys = LinkedHashSet<String>(); var total = 0
            fun saveBatch() { if (batch.isNotEmpty()) { database.runInTransaction { database.episodeDao().upsertBatch(batch.toList()) }; batch.clear() } }
            fun addEpisode(season: String, ep: JSONObject) {
                val episodeId = ep.optString("id", ep.optString("stream_id")).trim(); if (episodeId.isBlank()) return
                val episodeInfo = ep.optJSONObject("info")
                val number = ep.optString("episode_num", "0")
                val title = ep.optString("title", ep.optString("name", "الحلقة $number"))
                val image = episodeInfo.str("movie_image", "cover_big").ifBlank { ep.str("movie_image", "cover") }
                val extension = listOf(ep.optString("container_extension"), episodeInfo?.optString("container_extension"))
                    .firstOrNull { it?.isNotBlank() == true && it != "null" } ?: "mp4"
                val duration = formatMinutes(episodeInfo?.optLong("duration_secs", 0L) ?: 0L, episodeInfo.str("duration"))
                if (duration.isNotBlank()) episodeDurations[episodeId] = duration
                val key = season.ifBlank { "1" }; seasonKeys.add(key)
                batch.add(EpisodeEntity(temp, key, episodeId, title, image, extension, number))
                total++; if (batch.size >= 100) saveBatch()
            }
            fun extract(season: String, raw: Any?) {
                when (raw) {
                    is JSONArray -> for (i in 0 until raw.length()) {
                        when (val item = raw.opt(i)) {
                            is JSONObject -> addEpisode(season, item)
                            is JSONArray -> extract((i + 1).toString(), item)
                        }
                    }
                    is JSONObject -> { val keys = raw.keys(); while (keys.hasNext()) { val key = keys.next(); extract(key, raw.opt(key)) } }
                }
            }
            extract("1", response.opt("episodes")); saveBatch()
            if (total > 0) database.runInTransaction { database.episodeDao().clearSeries(seriesId); database.episodeDao().renameSeries(temp, seriesId) }
            else database.episodeDao().clearSeries(temp)
            temporaryId = null
            val ordered = seasonKeys.sortedWith(compareBy({ it.toIntOrNull() ?: Int.MAX_VALUE }, { it }))
            val count = total
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                info = parsedInfo
                if (parsedInfo.backdrop.isNotBlank()) bindHeroBackdrop(parsedInfo.backdrop)
                else if (parsedInfo.poster.isNotBlank()) bindHeroBackdrop(parsedInfo.poster)
                if (posterUrl.isBlank() && parsedInfo.poster.isNotBlank()) {
                    posterUrl = parsedInfo.poster
                    ImageLoader.bind(this, poster, posterUrl, UI.PANEL2, seriesName, Kinds.SERIES, priority = true, maxPx = 900, fade = true)
                }
                description.setContent(parsedInfo.plot.ifBlank { "لا يوجد وصف متاح لهذا المسلسل." }, muted = parsedInfo.plot.isBlank())
                showCast(parsedInfo.cast)
                if (count == 0) {
                    renderPills()
                    if (hadCache) showEpisodesReady("تعذر تحديث الحلقات؛ تظهر الحلقات المحفوظة")
                    else showEpisodesProblem("لا توجد حلقات متاحة لهذا المسلسل حاليًا.", canRetry = true)
                    return@runOnUiThread
                }
                seasonCount = ordered.size; episodeCount = count; renderPills()
                val keep = selectedSeason.takeIf { it in ordered } ?: ordered.first()
                buildSeasonTabs(ordered); showSeason(keep)
                showEpisodesReady(null)
            }
        } catch (_: Throwable) {
            try { temporaryId?.let { database.episodeDao().clearSeries(it) } } catch (_: Throwable) { }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                description.setContent(info.plot.ifBlank { "" })
                if (hadCache) showEpisodesReady("تعذر التحديث؛ تظهر الحلقات المحفوظة")
                else showEpisodesProblem("تعذر تحميل حلقات المسلسل. تحقق من الاتصال ثم أعد المحاولة.", canRetry = true)
            }
        } finally {
            refreshing = false
        }
    }

    private fun showCast(raw: String) {
        castHost.removeAllViews()
        val names = splitNames(raw)
        if (names.isEmpty()) {
            castHost.visibility = View.GONE
            updateSplitColumns()
            return
        }
        castHost.addView(sectionTitle("أبطال العمل"))
        castHost.addView(castRow(names))
        castHost.visibility = View.VISIBLE
        updateSplitColumns()
    }

    private fun showSimilar(items: List<ContentEntity>) {
        if (items.isEmpty()) return
        similarHost.removeAllViews()
        similarHost.addView(sectionTitle("قد يعجبك أيضًا"))
        similarHost.addView(similarRow(items) { Nav.open(this, it, server, username, password) })
        similarHost.visibility = View.VISIBLE
        updateSplitColumns()
    }

    // ------------------------------------------------------------ seasons

    private fun seasonTitle(key: String): String =
        if (key.toIntOrNull() != null) "الموسم $key" else (seasonLabels[key] ?: "الموسم $key")

    private fun styleSeasonChip(chip: TextView, on: Boolean) {
        chip.background = if (on) {
            roundBg(0xFF00D2FF.toInt(), 12)
        } else {
            roundBg(0xFF131722.toInt(), 12, 0xFF253048.toInt(), 1)
        }
        chip.setTextColor(if (on) 0xFF001018.toInt() else 0xFFCBD5E1.toInt())
    }

    private fun buildSeasonTabs(keys: List<String>) {
        val focusedSeasonKey = seasonsRow.findFocus()?.tag as? String
        var restoreChip: View? = null
        seasonsRow.removeAllViews()
        tabKeys.clear(); tabKeys.addAll(keys)
        keys.forEach { key ->
            val chip = tv(seasonTitle(key), 12.5f, 0xFFCBD5E1.toInt(), true).apply {
                tag = key
                gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)); maxLines = 1
                makeFocusable(1.04f, UI.NEON, 2, 12); setOnClickListener { showSeason(key) }
            }
            if (focusedSeasonKey == key) restoreChip = chip
            styleSeasonChip(chip, key == selectedSeason)
            seasonsRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(2), dp(8), dp(4)) })
        }
        if (episodes.visibility == View.VISIBLE) seasonScroll.visibility = if (keys.isNotEmpty()) View.VISIBLE else View.GONE
        restoreChip?.let { chip -> seasonsRow.post { chip.requestFocus() } }
    }

    private fun showSeason(key: String) {
        val sameSeason = selectedSeason == key
        selectedSeason = key
        for (i in 0 until seasonsRow.childCount) {
            val chip = seasonsRow.getChildAt(i) as? TextView ?: continue
            styleSeasonChip(chip, tabKeys.getOrNull(i) == key)
        }
        dbExecutor.execute {
            val rows: List<EpisodeEntity> = try { database.episodeDao().page(seriesId, key, 2000, 0) } catch (_: Throwable) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed || selectedSeason != key) return@runOnUiThread
                val changed = episodeAdapter.submit(rows)
                if (changed && !(sameSeason && episodeAdapter.itemCount > 0 && currentFocus != null)) episodes.scrollToPosition(0)
                play.isEnabled = rows.isNotEmpty(); play.alpha = if (rows.isNotEmpty()) 1f else 0.5f
                if (rows.isNotEmpty()) {
                    if (pendingRestore) { pendingRestore = false; episodes.post { restoreFocus() } }
                    else if (!userHasNavigated && currentFocus == null) play.requestFocus()
                }
                if (rows.isEmpty() && episodes.visibility == View.VISIBLE) noteView.apply { text = "لا توجد حلقات متاحة لهذا المسلسل حاليًا."; visibility = View.VISIBLE }
            }
        }
    }

    // ------------------------------------------------------------ favorites / follow / info

    private fun updateMarkButtons(mark: UserMarkEntity?) {
        val fav = mark?.favorite == true
        val fol = mark?.followed == true
        favoriteButton.text = if (fav) "♥ في المفضلة" else "☆ المفضلة"
        favoriteButton.applyHeroButtonStyle(if (fav) HeroBtnStyle.FAV_ACTIVE else HeroBtnStyle.SECONDARY)
        followButton.text = if (fol) "✓ ضمن المتابعة" else "+ متابعة"
        followButton.applyHeroButtonStyle(if (fol) HeroBtnStyle.FOLLOW_ACTIVE else HeroBtnStyle.SECONDARY)
    }

    private fun refreshMarks() {
        dbExecutor.execute {
            val mark = try { database.contentDao().getMark(Kinds.SERIES, seriesId) } catch (_: Throwable) { null }
            runOnUiThread { if (!isFinishing && !isDestroyed) updateMarkButtons(mark) }
        }
    }

    private fun toggleMark(follow: Boolean) {
        val button = if (follow) followButton else favoriteButton
        button.isClickable = false
        dbExecutor.execute {
            var mark: UserMarkEntity? = null; var failed = false
            try {
                val dao = database.contentDao()
                val item = dao.getContent(Kinds.SERIES, seriesId)
                    ?: ContentEntity(Kinds.SERIES, seriesId, seriesName, ArabicSearch.normalize(seriesName), "", posterUrl, "", "")
                if (follow) client.toggleFollow(item) else client.toggleFavorite(item)
                mark = dao.getMark(Kinds.SERIES, seriesId)
            } catch (_: Throwable) { failed = true }
            val result = mark
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                button.isClickable = true
                if (failed) Toast.makeText(this, "تعذر تحديث القائمة", Toast.LENGTH_SHORT).show() else updateMarkButtons(result)
            }
        }
    }

    private fun showInfoDialog() {
        showStyledInfoDialog(
            "معلومات المسلسل",
            listOf(
                "سنة الإصدار" to info.year,
                "التصنيف" to info.genres.joinToString("، "),
                "المواسم" to "${maxOf(seasonCount, 1)}"
            ),
            info.plot
        )
    }

    // ------------------------------------------------------------ playback

    private fun playEpisode(ep: EpisodeEntity, fromPlay: Boolean = false) {
        val previous = lastOpenedEpisode
        lastOpenedEpisode = ep.episodeId
        focusAnchor = if (fromPlay) ANCHOR_PLAY else ANCHOR_EPISODE
        returningFromPlayer = true
        episodeAdapter.notifyEpisode(previous); episodeAdapter.notifyEpisode(ep.episodeId)
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("url", client.mediaUrl("series", ep.episodeId, ep.extension)).putExtra("title", episodeCode(ep))
            .putExtra("mark_kind", "get_series").putExtra("mark_id", seriesId)
            .putExtra("server", server).putExtra("username", username).putExtra("password", password)
            .putExtra("series_id", seriesId).putExtra("series_name", seriesName)
            .putExtra("series_image", posterUrl)
            .putExtra("episode_id", ep.episodeId))
    }

    private fun episodeCode(ep: EpisodeEntity): String {
        val season = ep.seasonKey.filter { it.isDigit() }.toIntOrNull() ?: 1
        val episode = ep.episodeNumber.filter { it.isDigit() }.toIntOrNull() ?: 1
        return "S%02dE%02d".format(java.util.Locale.US, season, episode)
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow(); dbExecutor.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ episode grid cards (Web SeriesDetailScreen match)

    private inner class EpisodeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = ArrayList<EpisodeEntity>()
        private var limit = FIRST_PAGE
        private val activeBg by lazy { roundBg(0xFF131722.toInt(), 14, 0xFF00D2FF.toInt(), 2) }
        private val normalBg by lazy { roundBg(0xFF131722.toInt(), 14, 0xFF253048.toInt(), 1) }

        init { setHasStableIds(true) }

        fun first(): EpisodeEntity? = items.firstOrNull()
        fun resumeOrFirst(): EpisodeEntity? = items.firstOrNull { it.episodeId == resumeEpisodeId } ?: first()
        fun submit(list: List<EpisodeEntity>): Boolean {
            if (items.size == list.size && items.indices.all { items[it].episodeId == list[it].episodeId }) return false
            items.clear(); items.addAll(list); limit = FIRST_PAGE; notifyDataSetChanged(); return true
        }
        fun positionOf(id: String): Int = items.indexOfFirst { it.episodeId == id }
        fun ensureVisible(pos: Int) { if (pos >= limit) { limit = pos + 1 + MORE_PAGE; notifyDataSetChanged() } }
        fun notifyEpisode(id: String) {
            if (id.isBlank()) return
            val p = positionOf(id); if (p in 0 until shown()) notifyItemChanged(p)
        }

        private fun shown() = minOf(items.size, limit)
        override fun getItemCount() = shown() + if (items.size > limit) 1 else 0
        override fun getItemViewType(position: Int) = if (position < shown()) TYPE_EPISODE else TYPE_MORE
        override fun getItemId(position: Int): Long {
            if (position >= shown()) return -2L
            val id = items[position].episodeId
            var h = -3750763034362895579L
            for (i in 0 until id.length) {
                h = (h xor id[i].code.toLong()) * 1099511628211L
            }
            return h
        }

        private inner class EpisodeHolder(
            val card: LinearLayout,
            val image: ImageView,
            val number: TextView,
            val title: TextView,
            val duration: TextView,
            val resumeBadge: TextView
        ) : RecyclerView.ViewHolder(card)
        private inner class MoreHolder(val label: TextView) : RecyclerView.ViewHolder(label)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_MORE) {
                val label = tv("", 12.5f, 0xFF00D2FF.toInt(), true).apply {
                    gravity = Gravity.CENTER; setPadding(dp(18), dp(10), dp(18), dp(10))
                    background = roundBg(0xFF131722.toInt(), 12, 0xFF253048.toInt(), 1)
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(5), dp(10), dp(5), dp(6)) }
                    makeFocusable(1.02f, UI.NEON, 2, 12)
                    setOnClickListener { limit += MORE_PAGE; notifyDataSetChanged() }
                }
                return MoreHolder(label)
            }
            val card = LinearLayout(this@SeriesDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(6))
                }
                clipRounded(dpf(14))
                makeFocusable(1.03f, UI.NEON, 2, 14)
            }
            val thumb = AspectFrame(this@SeriesDetailActivity, 0.5625f).apply {
                background = roundBg(0xFF0F131F.toInt(), 0)
            }
            val image = ImageView(this@SeriesDetailActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            thumb.addView(image, FrameLayout.LayoutParams(-1, -1))
            thumb.addView(View(this@SeriesDetailActivity).apply { setBackgroundColor(0x59000000) },
                FrameLayout.LayoutParams(-1, -1))
            thumb.addView(tv("▶", 13f, 0xFF001018.toInt(), true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE600D2FF.toInt()) }
            }, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))

            val duration = tv("", 10f, Color.WHITE, true).apply {
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = roundBg(0xBF000000.toInt(), 5)
                visibility = View.GONE
            }
            thumb.addView(duration, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply {
                leftMargin = dp(8); bottomMargin = dp(8)
            })

            val resumeBadge = tv("", 10f, 0xFF001018.toInt(), true).apply {
                setPadding(dp(7), dp(2), dp(7), dp(2))
                background = roundBg(0xFF00D2FF.toInt(), 5)
                visibility = View.GONE
            }
            thumb.addView(resumeBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.RIGHT).apply {
                rightMargin = dp(8); topMargin = dp(8)
            })

            card.addView(thumb, LinearLayout.LayoutParams(-1, -2))

            val texts = LinearLayout(this@SeriesDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(10), dp(11), dp(11))
            }
            val number = tv("", 11f, 0xFFB05CFF.toInt(), true).apply { maxLines = 1; gravity = Gravity.START }
            val title = tv("", 12.5f, Color.WHITE, true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; alignToContent(); setPadding(0, dp(3), 0, 0)
            }
            texts.addView(number)
            texts.addView(title)
            card.addView(texts, LinearLayout.LayoutParams(-1, -2))

            val holder = EpisodeHolder(card, image, number, title, duration, resumeBadge)
            card.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION && position < shown()) playEpisode(items[position], fromPlay = false)
            }
            return holder
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is MoreHolder) {
                holder.label.text = "عرض المزيد من الحلقات (${items.size - limit})"
                return
            }
            val h = holder as EpisodeHolder
            val ep = items[position]
            val current = ep.episodeId == lastOpenedEpisode
            val nextUp = ep.episodeId == resumeEpisodeId
            h.card.background = (if (current || nextUp) activeBg else normalBg).constantState?.newDrawable()?.mutate()
            val number = ep.episodeNumber.filter { it.isDigit() }.toIntOrNull()?.toString() ?: ep.episodeNumber
            h.number.text = "الحلقة $number"
            val raw = ep.title.trim()
            h.title.text = if (raw.isBlank()) "الحلقة $number" else raw
            val minutes = episodeDurations[ep.episodeId].orEmpty()
            h.duration.text = minutes
            h.duration.visibility = if (minutes.isBlank()) View.GONE else View.VISIBLE
            h.resumeBadge.visibility = if (nextUp) View.VISIBLE else View.GONE
            if (nextUp) {
                h.resumeBadge.text = if (resumePositionMs > 0L) "متابعة · ${formatTimeLabel(resumePositionMs)}" else "متابعة"
            }
            ImageLoader.bind(this@SeriesDetailActivity, h.image, ep.imageUrl.ifBlank { posterUrl }, 0xFF0F131F.toInt(), "الحلقة $number $seriesName", Kinds.SERIES, priority = true, maxPx = 640, fade = true)
        }
    }

    private companion object {
        const val ANCHOR_NONE = 0
        const val ANCHOR_PLAY = 1
        const val ANCHOR_EPISODE = 2
        const val FIRST_PAGE = 24
        const val MORE_PAGE = 24
        const val TYPE_EPISODE = 0
        const val TYPE_MORE = 1
    }
}
