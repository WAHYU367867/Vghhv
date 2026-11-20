package com.alhorrtv.app

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

/** Everything the movie page shows that comes from get_vod_info. Parsed off the main thread. */
private data class MovieInfo(
    val plot: String = "", val year: String = "", val rating: String = "", val genres: List<String> = emptyList(),
    val duration: String = "", val age: String = "", val director: String = "", val cast: String = "",
    val country: String = "", val poster: String = "", val backdrop: String = ""
)

class MovieDetailActivity : FullscreenActivity() {
    private val ioExecutor = Executors.newFixedThreadPool(2)       // network (details, stream url)
    private val dbExecutor = Executors.newSingleThreadExecutor()   // local database
    private lateinit var client: XtreamRepository
    private lateinit var database: ContentDatabase
    private lateinit var heroBg: ImageView
    private lateinit var poster: ImageView
    private lateinit var pillRow: FlowLayout
    private lateinit var description: ExpandableText
    private lateinit var detailsState: DetailStateView
    private lateinit var play: TextView
    private lateinit var favorite: TextView
    private lateinit var infoButton: TextView
    private lateinit var castHost: LinearLayout
    private lateinit var similarHost: LinearLayout

    private var id = ""; private var name = ""; private var ext = "mp4"
    private var username = ""; private var password = ""; private var server = ""
    private var posterUrl = ""
    private var loadGeneration = 0
    private var favoriteOn = false
    private var resumeMs = 0L
    private var preparing = false
    private var info = MovieInfo()
    private var infoLoaded = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        username = intent.getStringExtra("username").orEmpty(); password = intent.getStringExtra("password").orEmpty()
        server = intent.getStringExtra("server").orEmpty()
        client = XtreamRepository(this, server, username, password); database = ContentDatabase.get(this)
        id = intent.getStringExtra("vod_id").orEmpty(); name = intent.getStringExtra("title") ?: "تفاصيل الفيلم"
        ext = intent.getStringExtra("extension")?.ifBlank { "mp4" } ?: "mp4"
        posterUrl = intent.getStringExtra("image").orEmpty()
        buildUi()
        loadLocal(withSimilar = true)
        loadDetails()
    }

    override fun onResume() {
        super.onResume()
        if (::play.isInitialized && id.isNotBlank()) loadLocal(withSimilar = false)
    }

    private fun buildUi() {
        poster = detailPoster()
        ImageLoader.bind(this, poster, posterUrl, UI.PANEL2, name, Kinds.MOVIES, priority = true, maxPx = 900, fade = true)

        val titleView = tv(name, 28f, UI.TEXT, true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; alignToContent()
        }
        pillRow = FlowLayout(this).apply { hGap = dp(8); vGap = dp(8) }
        description = ExpandableText(this, 3)
        detailsState = DetailStateView(this)

        play = heroActionButton("▶  تشغيل", HeroBtnStyle.PRIMARY).apply { setOnClickListener { startPlayback() } }
        favorite = heroActionButton("☆ المفضلة", HeroBtnStyle.SECONDARY).apply { setOnClickListener { toggleFavorite() } }
        infoButton = heroActionButton("ⓘ معلومات", HeroBtnStyle.SECONDARY).apply { setOnClickListener { showInfoDialog() } }

        val buttons = FlowLayout(this).apply { hGap = dp(10); vGap = dp(10) }
        buttons.addView(play)
        buttons.addView(favorite)
        buttons.addView(infoButton)

        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        textColumn.addView(pillRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textColumn.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        textColumn.addView(detailsState, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        textColumn.addView(description, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        textColumn.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        val (heroBanner, bgView) = buildHeroBanner(poster, textColumn, 152, 228) { finish() }
        heroBg = bgView
        bindHeroBackdrop(posterUrl)

        castHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        similarHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val lowerBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(28), dp(12), dp(28), dp(28))
        }
        lowerBody.addView(castHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        lowerBody.addView(similarHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(22) })

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        page.addView(heroBanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page.addView(lowerBody, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            background = HexPatternDrawable()
            addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)

        play.isEnabled = id.isNotBlank(); play.alpha = if (id.isNotBlank()) 1f else 0.5f
        description.setContent("")
        detailsState.loading("جارٍ تحميل التفاصيل…")
        play.post { if (!userHasNavigated) play.requestFocus() }
    }

    private fun bindHeroBackdrop(url: String?) {
        val value = url.orEmpty().trim()
        if (value.isBlank() || !(value.startsWith("http://", true) || value.startsWith("https://", true))) return
        heroBg.visibility = View.VISIBLE
        ImageLoader.bind(this, heroBg, value, UI.BG, priority = true, maxPx = 1280, fade = true)
    }

    // ------------------------------------------------------------ data

    private fun parse(details: JSONObject?): MovieInfo {
        if (details == null) return MovieInfo()
        val data = details.optJSONObject("movie_data")
        val node = details.optJSONObject("info") ?: details
        val year = node.str("releasedate", "releaseDate", "release_date", "year").ifBlank { data.str("year") }.take(4)
        val seconds = node.optLong("duration_secs", 0L)
        return MovieInfo(
            plot = node.str("plot", "description", "overview"),
            year = year.takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty(),
            rating = cleanRating(node.str("rating", "rating_5based").ifBlank { "" }),
            genres = splitNames(node.str("genre", "genres")).take(3),
            duration = formatDuration(seconds, node.str("duration")),
            age = node.str("age", "mpaa_rating"),
            director = node.str("director"), cast = node.str("cast", "actors"), country = node.str("country"),
            poster = node.firstImage("movie_image", "cover_big", "cover"),
            backdrop = node.firstImage("backdrop_path", "backdrop")
        )
    }

    private fun loadDetails() {
        val generation = ++loadGeneration
        detailsState.loading("جارٍ تحميل التفاصيل…")
        if (id.isBlank()) { finishDetails(generation, MovieInfo(), failed = true); return }
        ioExecutor.execute {
            var parsed = MovieInfo(); var failed = false
            try { parsed = parse(client.details("get_vod_info", id)) } catch (_: Throwable) { failed = true }
            runOnUiThread { finishDetails(generation, parsed, failed) }
        }
    }

    private fun finishDetails(generation: Int, parsed: MovieInfo, failed: Boolean) {
        if (generation != loadGeneration || isFinishing || isDestroyed) return
        info = parsed; infoLoaded = !failed
        if (parsed.backdrop.isNotBlank()) bindHeroBackdrop(parsed.backdrop)
        else if (parsed.poster.isNotBlank()) bindHeroBackdrop(parsed.poster)
        if (posterUrl.isBlank() && parsed.poster.isNotBlank()) {
            posterUrl = parsed.poster
            ImageLoader.bind(this, poster, posterUrl, UI.PANEL2, name, Kinds.MOVIES, priority = true, maxPx = 900, fade = true)
        }
        pillRow.removeAllViews()
        if (parsed.rating.isNotBlank()) pillRow.addView(ratingBadge("★ ${parsed.rating}"))
        if (parsed.year.isNotBlank()) pillRow.addView(glassBadge(parsed.year))
        if (parsed.duration.isNotBlank()) pillRow.addView(glassBadge(parsed.duration))
        if (parsed.genres.isNotEmpty()) pillRow.addView(genreBadge(parsed.genres.joinToString("، ")))

        if (failed) {
            description.setContent("")
            detailsState.error("تعذر تحميل تفاصيل الفيلم. يمكنك تشغيله الآن أو إعادة المحاولة.") { loadDetails() }
        } else {
            detailsState.hide()
            if (parsed.plot.isBlank()) description.setContent("لا يوجد وصف متاح لهذا الفيلم.", muted = true)
            else description.setContent(parsed.plot)
        }
        showCast(parsed.cast)
    }

    private fun showCast(raw: String) {
        castHost.removeAllViews()
        val names = splitNames(raw)
        if (names.isEmpty()) { castHost.visibility = View.GONE; return }
        castHost.addView(sectionTitle("أبطال العمل"))
        castHost.addView(castRow(names))
        castHost.visibility = View.VISIBLE
    }

    private fun showSimilar(items: List<ContentEntity>) {
        if (items.isEmpty()) return
        similarHost.removeAllViews()
        similarHost.addView(sectionTitle("قد يعجبك أيضًا"))
        similarHost.addView(similarRow(items) { Nav.open(this, it, server, username, password) })
        similarHost.visibility = View.VISIBLE
    }

    private fun loadLocal(withSimilar: Boolean) {
        dbExecutor.execute {
            val dao = database.contentDao()
            val mark = try { dao.getMark(Kinds.MOVIES, id) } catch (_: Throwable) { null }
            val similar: List<ContentEntity> = if (!withSimilar) emptyList() else try {
                val category = dao.getContent(Kinds.MOVIES, id)?.category.orEmpty()
                if (category.isBlank()) emptyList()
                else client.browse(Kinds.MOVIES, listOf(category), "", 30, 0).filter { it.contentId != id }.take(12)
            } catch (_: Throwable) { emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                favoriteOn = mark?.favorite == true; resumeMs = mark?.positionMs ?: 0L
                updateFavorite(); updatePlayLabel()
                if (withSimilar) showSimilar(similar)
            }
        }
    }

    private fun updateFavorite() {
        favorite.text = if (favoriteOn) "♥ في المفضلة" else "☆ المفضلة"
        favorite.applyHeroButtonStyle(if (favoriteOn) HeroBtnStyle.FAV_ACTIVE else HeroBtnStyle.SECONDARY)
    }

    private fun updatePlayLabel() {
        if (preparing) return
        play.text = if (resumeMs >= 15_000L) "▶  استئناف المشاهدة · ${formatTimeLabel(resumeMs)}" else "▶  تشغيل"
    }

    private fun formatTimeLabel(ms: Long): String {
        val minutes = (ms / 60000L).coerceAtLeast(1L)
        return if (minutes >= 60L) "${minutes / 60}س ${minutes % 60}د" else "$minutes د"
    }

    private fun toggleFavorite() {
        favorite.isClickable = false
        dbExecutor.execute {
            val result: Boolean? = try {
                val item = database.contentDao().getContent(Kinds.MOVIES, id)
                if (item == null) null else client.toggleFavorite(item)
            } catch (_: Throwable) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                favorite.isClickable = true
                if (result == null) Toast.makeText(this, "تعذر تحديث المفضلة", Toast.LENGTH_SHORT).show()
                else {
                    favoriteOn = result; updateFavorite()
                    Toast.makeText(this, if (result) "أضيف إلى المفضلة" else "أزيل من المفضلة", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startPlayback() {
        if (id.isBlank() || preparing) return
        preparing = true
        play.isClickable = false; play.alpha = 0.6f; play.text = "جارٍ التحضير…"
        ioExecutor.execute {
            val url = try { client.movieMediaUrl(id, ext) } catch (_: Throwable) { buildMediaUrl(server, "movie", username, password, id, ext) }
            runOnUiThread {
                preparing = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                play.isClickable = true; play.alpha = 1f; updatePlayLabel()
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra("server", server).putExtra("username", username).putExtra("password", password)
                    .putExtra("url", url).putExtra("title", name)
                    .putExtra("image", posterUrl)
                    .putExtra("mark_kind", "get_vod_streams").putExtra("mark_id", id))
            }
        }
    }

    private fun showInfoDialog() {
        showStyledInfoDialog(
            "تفاصيل الفيلم",
            listOf(
                "المخرج" to info.director,
                "الدولة" to info.country,
                "سنة الإصدار" to info.year,
                "المدة" to info.duration,
                "التصنيف" to info.genres.joinToString("، "),
                "الفئة العمرية" to info.age
            ),
            info.plot
        )
    }

    override fun onDestroy() {
        ioExecutor.shutdownNow(); dbExecutor.shutdownNow()
        super.onDestroy()
    }
}
