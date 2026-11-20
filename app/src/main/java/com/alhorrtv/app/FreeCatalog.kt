package com.alhorrtv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Isolated free catalog activated ONLY by the free credentials (Alhur / Alhur123).
 * Completely separated from the paid Xtream server: never sends free credentials to Xtream,
 * and never mixes free content into a paid Xtream session.
 */
internal object FreeCatalog {
    const val FREE_USER = "Alhur"
    const val FREE_PASS = "Alhur123"
    const val FREE_SERVER = "free://alhur.tv"
    const val PAID_DEFAULT_SERVER = "http://2.ztoyou.site/"

    private const val LIVE_M3U_URL = "https://iptv-org.github.io/iptv/languages/ara.m3u"
    private const val M3U_CACHE_FILE = "free_ara_channels_v1.m3u"
    private const val URL_MAP_FILE = "free_media_urls_v1.json"
    private const val M3U_REFRESH_MS = 6L * 60 * 60 * 1000L

    private val urlMap = ConcurrentHashMap<String, String>()
    private val initLock = Any()
    @Volatile private var loadedContextFilesDir: File? = null
    @Volatile private var cachedLiveSnapshot: Pair<List<CategoryEntity>, List<ContentEntity>>? = null
    @Volatile private var cachedLiveAt = 0L

    fun isFreeCredentials(username: String?, password: String?): Boolean =
        username?.trim()?.equals(FREE_USER, ignoreCase = true) == true &&
            password?.trim()?.equals(FREE_PASS, ignoreCase = true) == true

    fun isFreeServer(server: String?): Boolean =
        server?.trim()?.startsWith("free://", ignoreCase = true) == true

    fun resolveServer(
        savedServer: String?,
        username: String?,
        password: String?,
        defaultPaidServer: String = PAID_DEFAULT_SERVER
    ): String {
        if (isFreeCredentials(username, password)) return FREE_SERVER
        val trimmed = savedServer?.trim().orEmpty()
        return if (trimmed.isNotBlank() && !isFreeServer(trimmed)) trimmed else defaultPaidServer
    }

    fun freeAccountInfo(): JSONObject {
        val userInfo = JSONObject()
            .put("username", FREE_USER)
            .put("status", "Active")
            .put("auth", 1)
            .put("exp_date", 0L)
            .put("is_trial", "0")
            .put("active_cons", "1")
            .put("max_connections", "مفتوح")
        val serverInfo = JSONObject()
            .put("url", "free.alhur.tv")
            .put("timezone", "Asia/Riyadh")
        return JSONObject()
            .put("user_info", userInfo)
            .put("server_info", serverInfo)
            .put("auth", 1)
    }

    fun init(context: Context) {
        val dir = context.applicationContext.filesDir
        if (loadedContextFilesDir == dir && urlMap.isNotEmpty()) return
        synchronized(initLock) {
            loadedContextFilesDir = dir
            seedBuiltinUrls()
            loadPersistedUrls(dir)
        }
    }

    private fun key(kind: String, id: String): String = "$kind:${id.trim()}"

    private fun registerUrl(kind: String, id: String, streamUrl: String) {
        if (id.isBlank() || streamUrl.isBlank()) return
        urlMap[key(kind, id)] = streamUrl
    }

    fun resolveMediaUrl(kind: String, id: String, fallbackExtension: String): String {
        val normalizedKind = when (kind.lowercase()) {
            "live", Kinds.LIVE -> "live"
            "movie", Kinds.MOVIES -> "movie"
            "series", Kinds.SERIES -> "series"
            else -> kind.lowercase()
        }
        urlMap[key(normalizedKind, id)]?.let { return it }
        loadedContextFilesDir?.let { loadPersistedUrls(it) }
        seedBuiltinUrls()
        return urlMap[key(normalizedKind, id)]
            ?: urlMap[key("live", id)]
            ?: urlMap[key("movie", id)]
            ?: urlMap[key("series", id)]
            ?: fallbackExtension.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: ""
    }

    private fun persistUrls(context: Context) {
        try {
            val dir = context.applicationContext.filesDir
            loadedContextFilesDir = dir
            val obj = JSONObject()
            urlMap.forEach { (k, v) -> obj.put(k, v) }
            val tmp = File(dir, "$URL_MAP_FILE.tmp")
            val target = File(dir, URL_MAP_FILE)
            tmp.writeText(obj.toString(), Charsets.UTF_8)
            tmp.renameTo(target)
        } catch (_: Exception) { }
    }

    private fun loadPersistedUrls(dir: File) {
        try {
            val file = File(dir, URL_MAP_FILE)
            if (!file.exists() || file.length() <= 0L) return
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "")
                if (v.isNotBlank()) urlMap[k] = v
            }
        } catch (_: Exception) { }
    }

    private fun seedBuiltinUrls() {
        builtinChannels.forEach { registerUrl("live", it.id, it.streamUrl) }
        freeMoviesList.forEach { registerUrl("movie", it.id, it.streamUrl) }
        freeSeriesList.forEach { ser ->
            ser.episodes.forEach { ep -> registerUrl("series", ep.id, ep.streamUrl) }
        }
    }

    // ---------------------------------------------------------------- categories & items

    fun freeCategories(context: Context, kind: String): List<CategoryEntity> {
        init(context)
        return when (kind) {
            Kinds.LIVE -> liveSnapshot(context).first
            Kinds.MOVIES -> movieCategories
            Kinds.SERIES -> seriesCategories
            else -> emptyList()
        }
    }

    fun freeItems(context: Context, kind: String, token: String): List<ContentEntity> {
        init(context)
        val list = when (kind) {
            Kinds.LIVE -> liveSnapshot(context).second
            Kinds.MOVIES -> freeMoviesList.map { m ->
                registerUrl("movie", m.id, m.streamUrl)
                ContentEntity(
                    kind = Kinds.MOVIES,
                    contentId = m.id,
                    name = m.title,
                    normalizedName = ArabicSearch.normalize(m.title),
                    category = m.categoryId,
                    imageUrl = m.posterUrl,
                    extension = "mp4",
                    syncToken = token,
                    rating = m.rating,
                    idNum = m.id.toLongOrNull() ?: 0L
                )
            }
            Kinds.SERIES -> freeSeriesList.map { s ->
                s.episodes.forEach { ep -> registerUrl("series", ep.id, ep.streamUrl) }
                ContentEntity(
                    kind = Kinds.SERIES,
                    contentId = s.id,
                    name = s.title,
                    normalizedName = ArabicSearch.normalize(s.title),
                    category = s.categoryId,
                    imageUrl = s.posterUrl,
                    extension = "mp4",
                    syncToken = token,
                    rating = s.rating,
                    idNum = s.id.toLongOrNull() ?: 0L
                )
            }
            else -> emptyList()
        }
        if (kind == Kinds.LIVE) {
            return list.map { it.copy(syncToken = token) }
        }
        persistUrls(context)
        return list
    }

    fun freeDetails(action: String, id: String): JSONObject {
        if (action == "get_vod_info") {
            val m = freeMoviesList.firstOrNull { it.id == id } ?: return JSONObject()
            registerUrl("movie", m.id, m.streamUrl)
            val info = JSONObject()
                .put("name", m.title)
                .put("plot", m.plot)
                .put("description", m.plot)
                .put("releasedate", m.year)
                .put("year", m.year)
                .put("rating", m.rating)
                .put("genre", m.genre)
                .put("director", m.director)
                .put("actors", m.cast)
                .put("cast", m.cast)
                .put("country", m.country)
                .put("duration", m.duration)
                .put("duration_secs", m.durationSecs)
                .put("movie_image", m.posterUrl)
                .put("cover_big", m.posterUrl)
                .put("backdrop_path", JSONArray().put(m.posterUrl))
                .put("container_extension", "mp4")
            val movieData = JSONObject()
                .put("stream_id", m.id.toIntOrNull() ?: m.id)
                .put("name", m.title)
                .put("container_extension", "mp4")
            return JSONObject().put("info", info).put("movie_data", movieData)
        }
        if (action == "get_series_info") {
            val s = freeSeriesList.firstOrNull { it.id == id } ?: return JSONObject()
            val info = JSONObject()
                .put("name", s.title)
                .put("plot", s.plot)
                .put("description", s.plot)
                .put("releaseDate", s.year)
                .put("year", s.year)
                .put("rating", s.rating)
                .put("genre", s.genre)
                .put("cast", s.cast)
                .put("cover", s.posterUrl)
                .put("cover_big", s.posterUrl)
                .put("backdrop_path", JSONArray().put(s.posterUrl))
            val seasons = JSONArray().put(
                JSONObject()
                    .put("season_number", 1)
                    .put("name", "الموسم 1")
                    .put("episode_count", s.episodes.size)
                    .put("cover", s.posterUrl)
            )
            val epArray = JSONArray()
            s.episodes.forEachIndexed { idx, ep ->
                registerUrl("series", ep.id, ep.streamUrl)
                val epInfo = JSONObject()
                    .put("movie_image", ep.imageUrl.ifBlank { s.posterUrl })
                    .put("cover_big", ep.imageUrl.ifBlank { s.posterUrl })
                    .put("duration", ep.duration)
                    .put("duration_secs", ep.durationSecs)
                    .put("plot", ep.plot)
                epArray.put(
                    JSONObject()
                        .put("id", ep.id)
                        .put("episode_num", (idx + 1).toString())
                        .put("title", ep.title)
                        .put("container_extension", "mp4")
                        .put("season", 1)
                        .put("info", epInfo)
                )
            }
            val episodesObj = JSONObject().put("1", epArray)
            return JSONObject()
                .put("info", info)
                .put("seasons", seasons)
                .put("episodes", episodesObj)
        }
        return JSONObject()
    }

    // ---------------------------------------------------------------- live Arabic channels loader

    @Synchronized
    private fun liveSnapshot(context: Context): Pair<List<CategoryEntity>, List<ContentEntity>> {
        val now = System.currentTimeMillis()
        cachedLiveSnapshot?.let { snap ->
            if (now - cachedLiveAt < 5 * 60 * 1000L && snap.second.isNotEmpty()) return snap
        }
        val m3uText = fetchOrLoadM3u(context)
        val parsed = if (!m3uText.isNullOrBlank()) parseArabicM3u(m3uText) else null
        val result = if (parsed != null && parsed.second.size >= 20) parsed else buildBuiltinLiveSnapshot()
        result.second.forEach { ch ->
            // URL map is already populated during parse/builtin; ensure persistence on disk.
        }
        persistUrls(context)
        cachedLiveSnapshot = result
        cachedLiveAt = now
        return result
    }

    private fun fetchOrLoadM3u(context: Context): String? {
        val file = File(context.applicationContext.filesDir, M3U_CACHE_FILE)
        val cacheFresh = file.exists() && file.length() > 1024L &&
            (System.currentTimeMillis() - file.lastModified()) < M3U_REFRESH_MS
        if (cacheFresh) {
            try { return file.readText(Charsets.UTF_8) } catch (_: Exception) { }
        }
        val downloaded = downloadText(LIVE_M3U_URL)
        if (!downloaded.isNullOrBlank() && downloaded.contains("#EXTINF")) {
            try {
                val tmp = File(file.parentFile, "$M3U_CACHE_FILE.tmp")
                tmp.writeText(downloaded, Charsets.UTF_8)
                tmp.renameTo(file)
            } catch (_: Exception) { }
            return downloaded
        }
        return if (file.exists() && file.length() > 1024L) {
            try { file.readText(Charsets.UTF_8) } catch (_: Exception) { null }
        } else null
    }

    private fun downloadText(startUrl: String): String? {
        return try {
            var current = startUrl
            for (hop in 0..4) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 12000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "AL-HUR-AL-ASLI/1.0")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) return null
                    current = URL(URL(current), loc).toString()
                    continue
                }
                try {
                    if (code !in 200..299) return null
                    return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    conn.disconnect()
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private val logoRegex = Regex("tvg-logo=\"([^\"]*)\"")
    private val groupRegex = Regex("group-title=\"([^\"]*)\"")

    private fun mapArabicLiveCategory(rawGroup: String, channelName: String): Pair<String, String> {
        val g = rawGroup.lowercase()
        val n = channelName.lowercase()
        return when {
            g.contains("news") || g.contains("business") || n.contains("jazeera") || n.contains("arabiya") ||
                n.contains("hadath") || n.contains("ekhbariya") || n.contains("sky news") ->
                "fc_live_news" to "أخبار عربية واقتصاد"
            g.contains("sport") || n.contains("sport") || n.contains("riyadia") || n.contains("arryadia") ->
                "fc_live_sports" to "رياضة عربية"
            g.contains("religious") || n.contains("quran") || n.contains("sunnah") || n.contains("makkah") || n.contains("iqraa") ->
                "fc_live_religious" to "قرآن ودينية"
            g.contains("kids") || g.contains("animation") || n.contains("spacetoon") || n.contains("taha") || n.contains("atfal") ->
                "fc_live_kids" to "أطفال وكرتون"
            g.contains("movie") || g.contains("series") || g.contains("comedy") || n.contains("aflam") ||
                n.contains("drama") || n.contains("bab al hara") || n.contains("maraya") ->
                "fc_live_movies" to "أفلام ومسلسلات"
            g.contains("documentary") || g.contains("education") || g.contains("culture") || g.contains("science") ->
                "fc_live_doc" to "وثائقيات وثقافة"
            g.contains("music") ->
                "fc_live_music" to "موسيقى وطرب"
            else ->
                "fc_live_general" to "قنوات عربية عامة"
        }
    }

    private fun parseArabicM3u(text: String): Pair<List<CategoryEntity>, List<ContentEntity>> {
        val lines = text.lines()
        val categories = LinkedHashMap<String, CategoryEntity>()
        val items = ArrayList<ContentEntity>()
        val seenStreamUrls = HashSet<String>()
        var nextId = 910001
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("#EXTINF")) {
                var j = i + 1
                while (j < lines.size && (lines[j].trim().isEmpty() || lines[j].trim().startsWith("#"))) {
                    j++
                }
                val streamUrl = if (j < lines.size) lines[j].trim() else ""
                val rawTitle = line.substringAfterLast(',').trim()
                val blocked = rawTitle.contains("[Geo-blocked]", ignoreCase = true) ||
                    streamUrl.contains("[") ||
                    !(streamUrl.startsWith("https://", true) || streamUrl.startsWith("http://", true))
                if (!blocked && rawTitle.isNotBlank() && seenStreamUrls.add(streamUrl)) {
                    val cleanName = rawTitle
                        .replace(Regex("\\s*\\[[^\\]]*]"), "")
                        .replace(Regex("\\s*\\(\\d{3,4}p\\)"), "")
                        .trim()
                    val logo = logoRegex.find(line)?.groupValues?.getOrNull(1).orEmpty().trim()
                    val grp = groupRegex.find(line)?.groupValues?.getOrNull(1).orEmpty()
                    val (catId, catName) = mapArabicLiveCategory(grp, cleanName)
                    categories.putIfAbsent(catId, CategoryEntity(Kinds.LIVE, catId, catName, ""))
                    val cid = (nextId++).toString()
                    registerUrl("live", cid, streamUrl)
                    items.add(
                        ContentEntity(
                            kind = Kinds.LIVE,
                            contentId = cid,
                            name = cleanName,
                            normalizedName = ArabicSearch.normalize(cleanName),
                            category = catId,
                            imageUrl = logo,
                            extension = "m3u8",
                            syncToken = "",
                            rating = "",
                            idNum = cid.toLong()
                        )
                    )
                }
                i = j + 1
            } else {
                i++
            }
        }
        return categories.values.toList() to items
    }

    private fun buildBuiltinLiveSnapshot(): Pair<List<CategoryEntity>, List<ContentEntity>> {
        val cats = LinkedHashMap<String, CategoryEntity>()
        val items = ArrayList<ContentEntity>()
        builtinChannels.forEach { ch ->
            cats.putIfAbsent(ch.catId, CategoryEntity(Kinds.LIVE, ch.catId, ch.catName, ""))
            registerUrl("live", ch.id, ch.streamUrl)
            items.add(
                ContentEntity(
                    kind = Kinds.LIVE,
                    contentId = ch.id,
                    name = ch.name,
                    normalizedName = ArabicSearch.normalize(ch.name),
                    category = ch.catId,
                    imageUrl = ch.logoUrl,
                    extension = "m3u8",
                    syncToken = "",
                    rating = "",
                    idNum = ch.id.toLong()
                )
            )
        }
        return cats.values.toList() to items
    }

    // ---------------------------------------------------------------- curated verified data models

    private data class FreeChannel(
        val id: String,
        val name: String,
        val catId: String,
        val catName: String,
        val streamUrl: String,
        val logoUrl: String = ""
    )

    private data class FreeMovie(
        val id: String,
        val title: String,
        val categoryId: String,
        val posterUrl: String,
        val streamUrl: String,
        val year: String,
        val rating: String,
        val duration: String,
        val durationSecs: Long,
        val genre: String,
        val director: String,
        val cast: String,
        val country: String,
        val plot: String
    )

    private data class FreeEpisode(
        val id: String,
        val title: String,
        val streamUrl: String,
        val imageUrl: String,
        val duration: String,
        val durationSecs: Long,
        val plot: String
    )

    private data class FreeSeries(
        val id: String,
        val title: String,
        val categoryId: String,
        val posterUrl: String,
        val year: String,
        val rating: String,
        val genre: String,
        val cast: String,
        val plot: String,
        val episodes: List<FreeEpisode>
    )

    // Verified HTTP-200 free-to-air Arabic TV channels (offline fallback when M3U fetch fails)
    private val builtinChannels = listOf(
        // News
        FreeChannel("919001", "الجزيرة أخبار 2", "fc_live_news", "أخبار عربية واقتصاد", "https://live-hls-web-aja2-gcp.thehlive.com/AJA2/index.m3u8"),
        FreeChannel("919002", "العربية الحدث", "fc_live_news", "أخبار عربية واقتصاد", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-hadath/2ff87ec4c2f3ede35295a20637d9f8fd/index.m3u8"),
        FreeChannel("919003", "الإخبارية السعودية", "fc_live_news", "أخبار عربية واقتصاد", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-al-ekhbaria/297b3ef1cd0633ad9cfba7473a686a06/index.m3u8"),
        FreeChannel("919004", "التلفزيون العربي", "fc_live_news", "أخبار عربية واقتصاد", "https://live.kwikmotion.com/alaraby1live/alaraby_abr/playlist.m3u8"),
        FreeChannel("919005", "العربي 2", "fc_live_news", "أخبار عربية واقتصاد", "https://live.kwikmotion.com/alaraby2live/alaraby2.smil/playlist.m3u8"),
        FreeChannel("919006", "سكاي نيوز عربية", "fc_live_news", "أخبار عربية واقتصاد", "https://live-stream.skynewsarabia.com/c-horizontal-channel/horizontal-stream/index.m3u8"),
        FreeChannel("919007", "فرانس 24 عربية", "fc_live_news", "أخبار عربية واقتصاد", "https://live.france24.com/hls/live/2037222-b/F24_AR_HI_HLS/master_5000.m3u8"),
        FreeChannel("919008", "العربية بيزنس", "fc_live_news", "أخبار عربية واقتصاد", "https://live.alarabiya.net/alarabiapublish/aswaaq.smil/playlist.m3u8"),
        FreeChannel("919009", "CNBC عربية", "fc_live_news", "أخبار عربية واقتصاد", "https://cnbc-live.akamaized.net/cnbc/master.m3u8"),

        // General & Entertainment
        FreeChannel("919010", "MBC 1", "fc_live_general", "قنوات عربية عامة", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-1/15cf99af5de54063fdabfefe66adc075/index.m3u8"),
        FreeChannel("919011", "MBC 4", "fc_live_general", "قنوات عربية عامة", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-4/24f134f1cd63db9346439e96b86ca6ed/index.m3u8"),
        FreeChannel("919012", "MBC دراما", "fc_live_general", "قنوات عربية عامة", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-drama/2c28a458e2f3253e678b07ac7d13fe71/index.m3u8"),
        FreeChannel("919013", "LBC الفضائية", "fc_live_general", "قنوات عربية عامة", "http://185.9.2.18/chid_327/index.m3u8"),

        // Movies & Series channels
        FreeChannel("919014", "قناة أفلام", "fc_live_movies", "أفلام ومسلسلات", "https://shd-amg-fast.edgenextcdn.net/tx001/playlist.m3u8"),
        FreeChannel("919015", "ميكس هوليوود", "fc_live_movies", "أفلام ومسلسلات", "https://ml-pull-hwc.myco.io/MixTV/hls/index.m3u8"),
        FreeChannel("919016", "أفلام أكشن", "fc_live_movies", "أفلام ومسلسلات", "https://shd-amg-fast.edgenextcdn.net/tx011/playlist.m3u8"),
        FreeChannel("919017", "أفلام إثارة", "fc_live_movies", "أفلام ومسلسلات", "https://shd-amg-fast.edgenextcdn.net/tx012/playlist.m3u8"),
        FreeChannel("919018", "آي فيلم عربية", "fc_live_movies", "أفلام ومسلسلات", "https://live.presstv.co.uk/hls/ifilmar.m3u8"),
        FreeChannel("919019", "MBC مصر دراما", "fc_live_movies", "أفلام ومسلسلات", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-masr-drama/567b703c19ede6598222de81b0e4508b/index.m3u8"),
        FreeChannel("919020", "MBC+ دراما", "fc_live_movies", "أفلام ومسلسلات", "https://shd-gcp-live.edgenextcdn.net/live/bitmovin-mbc-plus-drama/e37251ec2aac8f6c98f75cd0fa37cd28/index.m3u8"),
        FreeChannel("919021", "باب الحارة", "fc_live_movies", "أفلام ومسلسلات", "https://shd-amg-fast.edgenextcdn.net/tx010/playlist.m3u8"),
        FreeChannel("919022", "مرايا", "fc_live_movies", "أفلام ومسلسلات", "https://shd-amg-fast.edgenextcdn.net/tx008/playlist.m3u8"),

        // Sports
        FreeChannel("919023", "الكويت الرياضية", "fc_live_sports", "رياضة عربية", "https://kwtspta.cdn.mangomolo.com/sp/smil:sp.stream.smil/chunklist.m3u8"),
        FreeChannel("919024", "الرياضية المغربية", "fc_live_sports", "رياضة عربية", "http://149.100.11.252:8000/play/a065/index.m3u8"),
        FreeChannel("919025", "العراقية الرياضية", "fc_live_sports", "رياضة عربية", "https://imn-live.esite-lab.com/hls/iraqia-sports-1.m3u8"),
        FreeChannel("919026", "البحرين الرياضية 1", "fc_live_sports", "رياضة عربية", "https://5c7b683162943.streamlock.net/live/ngrp:sportsone_all/playlist.m3u8"),

        // Kids
        FreeChannel("919027", "سبيستون عربية", "fc_live_kids", "أطفال وكرتون", "https://live-uae-next.spacetoongo.com/ST_MENA_NEXT/hls/r9p2hjipmw2kl.m3u8"),
        FreeChannel("919028", "قناة طه للأطفال", "fc_live_kids", "أطفال وكرتون", "https://stream.starmenajo.com/hls/app/live/ts:fhd.m3u8"),
        FreeChannel("919029", "أطفال ومواهب", "fc_live_kids", "أطفال وكرتون", "https://5aafcc5de91f1.streamlock.net/atfal1.com/atfal2/playlist.m3u8"),

        // Quran & Religious
        FreeChannel("919030", "القرآن الكريم - مكة", "fc_live_religious", "قرآن ودينية", "http://m.live.net.sa:1935/live/quran/gmswf.m3u8"),
        FreeChannel("919031", "السنة النبوية - المدينة", "fc_live_religious", "قرآن ودينية", "http://m.live.net.sa:1935/live/sunnah/gmswf.m3u8"),
        FreeChannel("919032", "تلفزيون مكة", "fc_live_religious", "قرآن ودينية", "https://media2.streambrothers.com:1936/8122/8122/playlist.m3u8"),
        FreeChannel("919033", "قناة اقرأ", "fc_live_religious", "قرآن ودينية", "https://playlist.fasttvcdn.com/pl/dlkqw1ftuvuuzkcb4pxdcg/Iqraafasttv1/playlist.m3u8"),
        FreeChannel("919034", "القرآن الكريم - قطر", "fc_live_religious", "قرآن ودينية", "https://qatartv.akamaized.net/hls/live/20000612/qtvquran/master.m3u8"),

        // Documentary
        FreeChannel("919035", "الشرق ديسكفري", "fc_live_doc", "وثائقيات وثقافة", "https://svs.itworkscdn.net/asharqdiscoverylive/asharqd.smil/playlist_dvr.m3u8"),
        FreeChannel("919036", "الشرق الوثائقية", "fc_live_doc", "وثائقيات وثقافة", "https://svs.itworkscdn.net/asharqdocumentarylive/asharqdocumentary.smil/playlist_dvr.m3u8"),
        FreeChannel("919037", "برامج العربية", "fc_live_doc", "وثائقيات وثقافة", "https://live.alarabiya.net/alarabiapublish/aaprograms.smil/playlist.m3u8")
    )

    private val movieCategories = listOf(
        CategoryEntity(Kinds.MOVIES, "fc_mov_anim", "رسوم متحركة وعائلي", ""),
        CategoryEntity(Kinds.MOVIES, "fc_mov_action", "أكشن ومغامرات", ""),
        CategoryEntity(Kinds.MOVIES, "fc_mov_comedy", "كوميديا كلاسيكية", ""),
        CategoryEntity(Kinds.MOVIES, "fc_mov_mystery", "غموض وتشويق", "")
    )

    private val freeMoviesList = listOf(
        FreeMovie(
            id = "920001",
            title = "سينتل — رحلة التنين (Sintel)",
            categoryId = "fc_mov_anim",
            posterUrl = "https://archive.org/services/img/Sintel",
            streamUrl = "https://archive.org/download/Sintel/sintel-2048-stereo.mp4",
            year = "2010",
            rating = "8.4",
            duration = "15 د",
            durationSecs = 888,
            genre = "فانتازيا، مغامرة، رسوم متحركة",
            director = "كولين ليفي",
            cast = "هالينا راين، ثوم هوفمان",
            country = "هولندا",
            plot = "فتاة وحيدة تدعى سينتل تنطلق في رحلة شاقة عبر الجبال والصحارى للبحث عن التنين الصغير الذي اعتنت به وصار صديقها الوحيد."
        ),
        FreeMovie(
            id = "920002",
            title = "بيغ باك باني (Big Buck Bunny)",
            categoryId = "fc_mov_anim",
            posterUrl = "https://archive.org/services/img/BigBuckBunny_328",
            streamUrl = "https://archive.org/download/BigBuckBunny_328/BigBuckBunny_512kb.mp4",
            year = "2008",
            rating = "8.1",
            duration = "10 د",
            durationSecs = 596,
            genre = "رسوم متحركة، كوميديا، عائلي",
            director = "ساشا غويدجلب",
            cast = "إنتاج استوديو بليندر المفتوح",
            country = "هولندا",
            plot = "أرنب طيب القلب يعيش بسلام في الغابة، لكن ثلاثة قوارض مشاغبة يعتدون على الفراشات، فيقرر الدفاع عن أصدقائه بخطة ذكية."
        ),
        FreeMovie(
            id = "920003",
            title = "حلم الفيلة (Elephants Dream)",
            categoryId = "fc_mov_anim",
            posterUrl = "https://archive.org/services/img/ElephantsDream",
            streamUrl = "https://archive.org/download/ElephantsDream/ed_hd.mp4",
            year = "2006",
            rating = "7.5",
            duration = "11 د",
            durationSecs = 654,
            genre = "خيال علمي، رسوم متحركة",
            director = "باسام كردلي",
            cast = "تايغو جيرناندت، كاس يانسن",
            country = "هولندا",
            plot = "رحلة بصرية داخل عالم آلي عملاق حيث يستكشف إيمو وبروغ أسرار الآلة التي تحيط بهما."
        ),
        FreeMovie(
            id = "920004",
            title = "كتاب الأدغال الكلاسيكي (Jungle Book)",
            categoryId = "fc_mov_action",
            posterUrl = "https://archive.org/services/img/JungleBook",
            streamUrl = "https://archive.org/download/JungleBook/Jungle_Book.mp4",
            year = "1942",
            rating = "7.9",
            duration = "108 د",
            durationSecs = 6480,
            genre = "مغامرات، عائلي، دراما",
            director = "زولتان كوردا",
            cast = "سابو، جوزيف كالييا، جون كوالين",
            country = "الولايات المتحدة",
            plot = "قصة ماوكلي الفتى الذي نشأ بين حيوانات الأدغال ثم عاد إلى قرية البشر ليكتشف الصراع بين طبيعة الغابة وجشع الذهب."
        ),
        FreeMovie(
            id = "920005",
            title = "الجنرال — باستر كيتون (The General)",
            categoryId = "fc_mov_action",
            posterUrl = "https://archive.org/services/img/The_General_Buster_Keaton",
            streamUrl = "https://archive.org/download/The_General_Buster_Keaton/The_General.mp4",
            year = "1926",
            rating = "8.7",
            duration = "78 د",
            durationSecs = 4680,
            genre = "أكشن، مغامرات، كوميديا",
            director = "باستر كيتون",
            cast = "باستر كيتون، ماريون ماك",
            country = "الولايات المتحدة",
            plot = "مهندس قطارات شجاع يطارد بمفرده قاطرته البخارية المسروقة «الجنرال» وحبيبته خلف خطوط العدو في مغامرة مطاردات مذهلة."
        ),
        FreeMovie(
            id = "920006",
            title = "فتاة الجمعة (His Girl Friday)",
            categoryId = "fc_mov_comedy",
            posterUrl = "https://archive.org/services/img/HisGirlFriday",
            streamUrl = "https://archive.org/download/HisGirlFriday/MoviePowderPresentsHisGirlFriday.mp4",
            year = "1940",
            rating = "8.3",
            duration = "92 د",
            durationSecs = 5520,
            genre = "كوميديا، دراما",
            director = "هاوارد هوكس",
            cast = "كاري غرانت، روزاليند راسل، رالف بيلامي",
            country = "الولايات المتحدة",
            plot = "رئيس تحرير صحيفة يحاول استعادة زوجته السابقة وأفضل مراسلة لديه قبل زواجها من رجل آخر عبر قصة صحفية مثيرة."
        ),
        FreeMovie(
            id = "920007",
            title = "مهرجان روائع تشارلي شابلن",
            categoryId = "fc_mov_comedy",
            posterUrl = "https://archive.org/services/img/charlie_chaplin_film_fest",
            streamUrl = "https://archive.org/download/charlie_chaplin_film_fest/charlie_chaplin_film_fest.mp4",
            year = "1938",
            rating = "8.2",
            duration = "75 د",
            durationSecs = 4500,
            genre = "كوميديا، عائلي",
            director = "تشارلي شابلن",
            cast = "تشارلي شابلن، إدنا بيرفيانس",
            country = "الولايات المتحدة",
            plot = "باقة كلاسيكية من أجمل مغامرات الصعلوك الطريف ومواقفه الكوميدية الخالدة مع السينما الصامتة."
        ),
        FreeMovie(
            id = "920008",
            title = "منزل التل المسكون (House on Haunted Hill)",
            categoryId = "fc_mov_mystery",
            posterUrl = "https://archive.org/services/img/house_on_haunted_hill_ipod",
            streamUrl = "https://archive.org/download/house_on_haunted_hill_ipod/house_on_haunted_hill.mp4",
            year = "1959",
            rating = "7.6",
            duration = "75 د",
            durationSecs = 4500,
            genre = "غموض، تشويق",
            director = "ويليام كاسل",
            cast = "فينسنت برايس، كارول أوهمارت",
            country = "الولايات المتحدة",
            plot = "مليونير غريب الأطوار يعرض مبلغاً ضخماً على خمسة غرباء إذا استطاعوا قضاء ليلة كاملة في قصر مهجور مليء بالأسرار."
        ),
        FreeMovie(
            id = "920009",
            title = "السرعة والغضب الكلاسيكي (1955)",
            categoryId = "fc_mov_action",
            posterUrl = "https://archive.org/services/img/TheFastandtheFuriousJohnIreland1954goofyrip",
            streamUrl = "https://archive.org/download/TheFastandtheFuriousJohnIreland1954goofyrip/TheFastandtheFuriousJohnIreland1954goofyrip.mp4",
            year = "1955",
            rating = "7.1",
            duration = "73 د",
            durationSecs = 4380,
            genre = "أكشن، سباقات، جريمة",
            director = "جون آيرلاند",
            cast = "جون آيرلاند، دوروثي مالون",
            country = "الولايات المتحدة",
            plot = "شاب متهم زوراً يهرب بسيارة رياضية ويشارك في سباق عبر الحدود لإثبات براءته وسط مطاردات الشرطة."
        ),
        FreeMovie(
            id = "920010",
            title = "عازف المزمار (The Pied Piper of Hamelin)",
            categoryId = "fc_mov_anim",
            posterUrl = "https://archive.org/services/img/The_Pied_Piper_of_Hamelin",
            streamUrl = "https://archive.org/download/The_Pied_Piper_of_Hamelin/pied.mp4",
            year = "1957",
            rating = "7.4",
            duration = "87 د",
            durationSecs = 5220,
            genre = "عائلي، مغامرة، موسيقي",
            director = "بريتاش ويندست",
            cast = "فان جونسون، كلود راينز",
            country = "الولايات المتحدة",
            plot = "الحكاية الشهيرة لمدينة هاملين وعازف المزمار الغامض الذي يخلص أهل المدينة من أزمة صعبة ثم يطالبهم بالوفاء بوعدهم."
        ),
        FreeMovie(
            id = "920011",
            title = "شبح الأوبرا الكلاسيكي (1925)",
            categoryId = "fc_mov_mystery",
            posterUrl = "https://archive.org/services/img/the-phantom-of-the-opera-1925",
            streamUrl = "https://archive.org/download/the-phantom-of-the-opera-1925/The%20Phantom%20of%20the%20Opera%201925.mp4",
            year = "1925",
            rating = "7.8",
            duration = "93 د",
            durationSecs = 5580,
            genre = "غموض، دراما، كلاسيكي",
            director = "روبرت جوليان",
            cast = "لون تشاني، ماري فيلبين",
            country = "الولايات المتحدة",
            plot = "شبح غامض يختبئ في سراديب دار أوبرا باريس ويسعى لجعل المغنية الشابة كريستين نجمة العرض الأولى."
        ),
        FreeMovie(
            id = "920012",
            title = "نوسفيراتو — سمفونية الغموض (1922)",
            categoryId = "fc_mov_mystery",
            posterUrl = "https://archive.org/services/img/nosferatu-1922",
            streamUrl = "https://archive.org/download/nosferatu-1922/Nosferatu%20%281922%29.mp4",
            year = "1922",
            rating = "8.0",
            duration = "94 د",
            durationSecs = 5640,
            genre = "غموض، كلاسيكي",
            director = "إف دبليو مورناو",
            cast = "ماكس شريك، غوستاف فون فانغنهايم",
            country = "ألمانيا",
            plot = "تحفة سينمائية كلاسيكية عن كاتب شاب يسافر إلى قلعة نائية لإتمام صفقة عقارية مع نبيل غريب الأطوار."
        )
    )

    private val seriesCategories = listOf(
        CategoryEntity(Kinds.SERIES, "fc_ser_arabic", "برامج عربية وإسلامية", ""),
        CategoryEntity(Kinds.SERIES, "fc_ser_anim", "مسلسلات كرتون كلاسيكية", ""),
        CategoryEntity(Kinds.SERIES, "fc_ser_classic", "روائع الكوميديا الكلاسيكية", "")
    )

    private val freeSeriesList = listOf(
        FreeSeries(
            id = "930001",
            title = "الإتقان لتلاوة القرآن — د. أيمن سويد",
            categoryId = "fc_ser_arabic",
            posterUrl = "https://archive.org/services/img/Iqraa_aletqan_Aymn-Sewd-el-moslem.com",
            year = "2011",
            rating = "9.2",
            genre = "قرآن وتجويد، تعليمي عربي",
            cast = "الدكتور أيمن رشدي سويد",
            plot = "سلسلة حلقات عربية تعليمية متكاملة في أحكام التجويد ومخارج الحروف وتلاوة القرآن الكريم بأسلوب مبسط وواضح.",
            episodes = listOf(
                FreeEpisode(
                    "940001",
                    "الحلقة 1 — قواعد التلاوة ومخارج الحروف",
                    "https://archive.org/download/Iqraa_aletqan_Aymn-Sewd-el-moslem.com/0037-Iqraa-12-03-2011_aletqan_Aymn-Sewd.mp4",
                    "https://archive.org/services/img/Iqraa_aletqan_Aymn-Sewd-el-moslem.com",
                    "45 د", 2700,
                    "درس تطبيقي في ضبط مخارج الحروف وتلاوة الآيات القرآنية."
                ),
                FreeEpisode(
                    "940002",
                    "الحلقة 2 — تطبيقات عملية في التجويد",
                    "https://archive.org/download/Iqraa_aletqan_Aymn-Sewd-el-moslem.com/0041-Iqraa-07-05-2011_aletqan_Aymn-Sewd.mp4",
                    "https://archive.org/services/img/Iqraa_aletqan_Aymn-Sewd-el-moslem.com",
                    "45 د", 2700,
                    "تطبيق عملي مع تصحيح التلاوة وأحكام المدود."
                ),
                FreeEpisode(
                    "940003",
                    "الحلقة 3 — إتقان الحروف والصفات",
                    "https://archive.org/download/Iqraa_aletqan_Aymn-Sewd-el-moslem.com/0042-Iqraa-14-05-2011_aletqan_Aymn-Sewd.mp4",
                    "https://archive.org/services/img/Iqraa_aletqan_Aymn-Sewd-el-moslem.com",
                    "45 د", 2700,
                    "شرح صفات الحروف وكيفية النطق السليم أثناء التلاوة."
                ),
                FreeEpisode(
                    "940004",
                    "الحلقة 4 — مختارات قرآنية مع الشرح",
                    "https://archive.org/download/Iqraa_aletqan_Aymn-Sewd-el-moslem.com/0041-Iqraa-07-05-2011_aletqan_Aymn-Sewd-S.mp4",
                    "https://archive.org/services/img/Iqraa_aletqan_Aymn-Sewd-el-moslem.com",
                    "30 د", 1800,
                    "مراجعة لأهم قواعد التجويد في السور القرآنية."
                )
            )
        ),
        FreeSeries(
            id = "930002",
            title = "مغامرات بوباي البحار (Popeye)",
            categoryId = "fc_ser_anim",
            posterUrl = "https://archive.org/services/img/popeye_patriotic_popeye",
            year = "1954",
            rating = "8.5",
            genre = "رسوم متحركة، كوميديا، أطفال",
            cast = "جاك ميرسر، ماي كويستيل",
            plot = "مجموعة من الحلقات الكرتونية الكلاسيكية الممتعة للبحار الشهير بوباي ومغامراته الطريفة مع السبانخ.",
            episodes = listOf(
                FreeEpisode(
                    "940011",
                    "الحلقة 1 — بوباي الوطني",
                    "https://archive.org/download/popeye_patriotic_popeye/popeye_patriotic_popeye_512kb.mp4",
                    "https://archive.org/services/img/popeye_patriotic_popeye",
                    "7 د", 420,
                    "بوباي في مغامرة مشوقة ومضحكة للدفاع عن السلامة العامة."
                ),
                FreeEpisode(
                    "940012",
                    "الحلقة 2 — المحقق بوباي",
                    "https://archive.org/download/popeye_private_eye_popeye/popeye_private_eye_popeye_512kb.mp4",
                    "https://archive.org/services/img/popeye_private_eye_popeye",
                    "7 د", 420,
                    "بوباي يعمل محققاً خاصاً لحل لغز اختفاء غامض."
                ),
                FreeEpisode(
                    "940013",
                    "الحلقة 3 — ليلة أرق بوباي",
                    "https://archive.org/download/popeye_shuteye_popeye/popeye_shuteye_popeye_512kb.mp4",
                    "https://archive.org/services/img/popeye_shuteye_popeye",
                    "7 د", 420,
                    "مواقف كوميدية حين يحاول بوباي النوم وسط إزعاج غير متوقع."
                ),
                FreeEpisode(
                    "940014",
                    "الحلقة 4 — بوباي لا يخاف الأشباح",
                    "https://archive.org/download/popeye_i_dont_scare/popeye_i_dont_scare_512kb.mp4",
                    "https://archive.org/services/img/popeye_i_dont_scare",
                    "7 د", 420,
                    "بوباي يتحدى الخرافات في ليلة مليئة بالمفاجآت المضحكة."
                ),
                FreeEpisode(
                    "940015",
                    "الحلقة 5 — تاكسي بوباي السريع",
                    "https://archive.org/download/popeye_taxi-turvey/popeye_taxi-turvey_512kb.mp4",
                    "https://archive.org/services/img/popeye_taxi-turvey",
                    "7 د", 420,
                    "منافسة مجنونة بسيارات الأجرة في شوارع المدينة."
                ),
                FreeEpisode(
                    "940016",
                    "الحلقة 6 — مفاجأة الهالوين",
                    "https://archive.org/download/popeye_fright_to_the_finish/popeye_fright_to_the_finish_512kb.mp4",
                    "https://archive.org/services/img/popeye_fright_to_the_finish",
                    "7 د", 420,
                    "مغامرة طريفة في القصر القديم مع بوباي وأوليف."
                )
            )
        ),
        FreeSeries(
            id = "930003",
            title = "سلسلة كرتون بيتي بوب الكلاسيكية",
            categoryId = "fc_ser_anim",
            posterUrl = "https://archive.org/services/img/BettyBoopCartoons",
            year = "1936",
            rating = "7.9",
            genre = "رسوم متحركة، موسيقي، كلاسيكي",
            cast = "ماي كويستيل، استوديو فلايشر",
            plot = "حلقات كرتونية موسيقية كلاسيكية من الثلاثينيات برسومات يدوية جميلة ومواقف طريفة مناسبة للعائلة.",
            episodes = listOf(
                FreeEpisode(
                    "940021",
                    "الحلقة 1 — أغنية في اليوم",
                    "https://archive.org/download/BettyBoopCartoons/Betty_Boop_A_Song_a_Day_1936_512kb.mp4",
                    "https://archive.org/services/img/BettyBoopCartoons",
                    "7 د", 420,
                    "حكاية موسيقية مرحة في عيادة الطبيب."
                ),
                FreeEpisode(
                    "940022",
                    "الحلقة 2 — قراءة الكف الطريفة",
                    "https://archive.org/download/BettyBoopCartoons/Betty_Boop_Is_My_Palm_Read_1932_512kb.mp4",
                    "https://archive.org/services/img/BettyBoopCartoons",
                    "7 د", 420,
                    "مواقف كوميدية مع التوقعات والطالع في المهرجان."
                ),
                FreeEpisode(
                    "940023",
                    "الحلقة 3 — نشاط وحيوية",
                    "https://archive.org/download/BettyBoopCartoons/Betty_Boop_More_Pep_1936_512kb.mp4",
                    "https://archive.org/services/img/BettyBoopCartoons",
                    "7 د", 420,
                    "مغامرة مليئة بالحركة والحيوية في المطبخ."
                ),
                FreeEpisode(
                    "940024",
                    "الحلقة 4 — عطسة الموسم",
                    "https://archive.org/download/BettyBoopCartoons/Betty_Boops_KerCho_1932_512kb.mp4",
                    "https://archive.org/services/img/BettyBoopCartoons",
                    "7 د", 420,
                    "سباق ومواقف ساخرة تنتهي بنهاية غير متوقعة."
                )
            )
        ),
        FreeSeries(
            id = "930004",
            title = "سلسلة الكوميديا الكلاسيكية الخالدة",
            categoryId = "fc_ser_classic",
            posterUrl = "https://archive.org/services/img/TheThreeStooges",
            year = "1934",
            rating = "8.3",
            genre = "كوميديا، كلاسيكي",
            cast = "مو هاوارد، لاري فاين، كيرلي، تشارلي شابلن",
            plot = "مختارات من أشهر الحلقات الكوميدية الكلاسيكية المفتوحة في تاريخ السينما والتلفزيون.",
            episodes = listOf(
                FreeEpisode(
                    "940031",
                    "الحلقة 1 — المهرجون الثلاثة في ورطة",
                    "https://archive.org/download/TheThreeStooges/TheThreeStooges-001-WomanHaters1934moeLarryCurleydivxdabaron19m20s.mp4",
                    "https://archive.org/services/img/TheThreeStooges",
                    "19 د", 1160,
                    "ثلاثي الكوميديا الشهير في أولى مغامراتهم المضحكة بالقطار."
                ),
                FreeEpisode(
                    "940032",
                    "الحلقة 2 — مغامرات شابلن الكوميدية",
                    "https://archive.org/download/charlie_chaplin_film_fest/charlie_chaplin_film_fest.mp4",
                    "https://archive.org/services/img/charlie_chaplin_film_fest",
                    "75 د", 4500,
                    "مختارات ممتعة من أفلام تشارلي شابلن القصيرة الكلاسيكية."
                )
            )
        )
    )
}
