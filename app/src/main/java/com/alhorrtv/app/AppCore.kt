package com.alhorrtv.app

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import android.widget.ImageView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AppLog {
    fun info(message: String) = android.util.Log.i("AlhorrTV", message)
    fun warn(message: String) = android.util.Log.w("AlhorrTV", message)
}

class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)
    private val alias = "alhorr_session_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(alias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? = try {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        if (bytes.size < 13) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
        }
    } catch (_: Exception) { null }

    fun save(server: String, username: String, password: String) = prefs.edit()
        .putString("server", encrypt(server.trim().trimEnd('/')))
        .putString("username", encrypt(username))
        .putString("password", encrypt(password)).apply()
    fun server(): String? = prefs.getString("server", null)?.let(::decrypt)
    fun username(): String? = prefs.getString("username", null)?.let(::decrypt)
    fun password(): String? = prefs.getString("password", null)?.let(::decrypt)
    fun clear() = prefs.edit().clear().apply()

    /** Accounts saved on this device (encrypted as one JSON blob). The "current" login above is stored separately. */
    data class SavedAccount(val server: String, val username: String, val password: String)

    fun accounts(): List<SavedAccount> = try {
        val raw = prefs.getString("accounts", null)?.let(::decrypt) ?: return emptyList()
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val u = o.optString("u"); val p = o.optString("p")
            if (u.isBlank() || p.isBlank()) null else SavedAccount(o.optString("s"), u, p)
        }
    } catch (_: Exception) { emptyList() }

    private fun writeAccounts(list: List<SavedAccount>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("s", it.server).put("u", it.username).put("p", it.password)) }
        prefs.edit().putString("accounts", encrypt(arr.toString())).apply()
    }

    /** Adds the account, or refreshes its password when the same server + username is already saved. */
    fun addAccount(server: String, username: String, password: String) {
        if (username.isBlank() || password.isBlank()) return
        val srv = server.trim().trimEnd('/')
        val rest = accounts().filterNot { it.server == srv && it.username == username }
        writeAccounts(rest + SavedAccount(srv, username, password))
    }

    fun removeAccount(server: String, username: String) {
        val srv = server.trim().trimEnd('/')
        writeAccounts(accounts().filterNot { it.server == srv && it.username == username })
    }
}

object ArabicSearch {
    fun normalize(value: String): String {
        val digits = StringBuilder(value.length)
        for (ch in value.lowercase()) digits.append(if (ch in '\u0660'..'\u0669') '0' + (ch - '\u0660') else ch)
        return digits.toString()
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ى', 'ي').replace('ة', 'ه')
            .replace(Regex("[\\u064B-\\u065F]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    }
}

data class StreamContent(
    val id: String,
    val name: String,
    val category: String,
    val imageUrl: String,
    val extension: String,
    val rating: String = ""
)

internal object StreamingJsonObjects {
    /**
     * Emits every item object of a list response one at a time. Supports both shapes Xtream panels use:
     * a JSON array of objects, and an object whose values are the items (keyed by id).
     */
    fun forEach(input: InputStream, onObject: (String) -> Unit) {
        InputStreamReader(input, Charsets.UTF_8).buffered(8192).use { reader ->
            val text = StringBuilder(4096)
            var depth = 0; var inString = false; var escaped = false
            var rootKnown = false; var base = 0; var capturing = false
            while (true) {
                val value = reader.read(); if (value < 0) break
                val c = value.toChar()
                if (!rootKnown) {
                    if (c.isWhitespace() || c == '\uFEFF') continue
                    rootKnown = true
                    if (c == '{') { base = 1; depth = 1; continue }   // dictionary root: items are its values
                }
                if (inString) {
                    if (capturing) text.append(c)
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                } else when (c) {
                    '"' -> { inString = true; if (capturing) text.append(c) }
                    '{' -> {
                        if (!capturing && depth == base) { capturing = true; text.setLength(0) }
                        depth++; if (capturing) text.append(c)
                    }
                    '}' -> {
                        depth--
                        if (capturing) { text.append(c); if (depth == base) { onObject(text.toString()); capturing = false } }
                    }
                    else -> if (capturing) text.append(c)
                }
                if (text.length > 2 * 1024 * 1024) throw IllegalStateException("عنصر JSON أكبر من الحد الآمن")
            }
            if (capturing) throw IllegalStateException("استجابة JSON غير مكتملة")
        }
    }
}

class XtreamRepository(context: Context, server: String, private val username: String, private val password: String) {
    private val appContext = context.applicationContext
    private val database = ContentDatabase.get(context.applicationContext)
    private val dao = database.contentDao()
    private val server = server.trim().trimEnd('/')
    private val isFree = FreeCatalog.isFreeServer(this.server)

    init {
        if (isFree) FreeCatalog.init(appContext)
    }

    private fun query(params: Map<String, String>): String = params.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }

    private val userAgents = listOf(
        "AL-HUR-AL-ASLI/1.0 (Android)",
        "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "IPTVSmartersPro"
    )

    /** Opens a player_api call, following redirects by hand (HttpURLConnection will not follow http -> https). */
    private fun open(action: String, readTimeoutMs: Int = 120000, uaIndex: Int = 0, extra: Map<String, String> = emptyMap()): HttpURLConnection {
        val params = LinkedHashMap<String, String>().apply {
            put("username", username)
            put("password", password)
            if (action.isNotBlank()) put("action", action)
            putAll(extra)
        }
        var target = URL("$server/player_api.php?${query(params)}")
        for (hop in 0..5) {
            val connection = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = readTimeoutMs; requestMethod = "GET"; instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", userAgents[uaIndex.coerceIn(0, userAgents.lastIndex)])
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location"); connection.disconnect()
                if (location.isNullOrBlank()) throw IllegalStateException("HTTP $code")
                target = URL(target, location); continue
            }
            return connection
        }
        throw IllegalStateException("تحويلات كثيرة")
    }

    private fun parseObject(raw: String): StreamContent? = try {
        val item = JSONObject(raw)
        val id = item.optString("stream_id", item.optString("series_id", item.optString("id"))).trim()
        val name = item.optString("name", item.optString("title", "بدون عنوان")).trim()
        if (id.isBlank() || name.isBlank() || name == "null") null else StreamContent(
            id, name, item.optString("category_id", item.optString("category_name", "")),
            item.optString("stream_icon", item.optString("cover", item.optString("cover_big", item.optString("movie_image")))),
            item.optString("container_extension", ""),
            Rating.parse(item.optString("rating", ""), item.optString("rating_5based", ""))
        )
    } catch (_: Exception) { null }

    /** Downloads the whole list in ONE request and streams it object-by-object into the local database. */
    fun sync(
        action: String, uaIndex: Int = 0, onProgress: (loaded: Int) -> Unit,
        categoryId: String? = null, token: String = java.util.UUID.randomUUID().toString(),
        seenIds: HashSet<String> = HashSet(), baseSaved: Int = 0, finalize: Boolean = true,
        readTimeoutMs: Int = 120000
    ): Result<Int> {
        var saved = 0
        return try {
            // Commit small pages so browsing screens can show the first items while a large catalog is still downloading.
            val batch = ArrayList<ContentEntity>(40)
            val hiddenCats = HiddenStore.hiddenCategories(appContext, action)
            val hiddenIds = HiddenStore.hiddenItemIds(appContext, action)
            val hiddenKind = HiddenStore.hiddenKind(action)
            fun flush() {
                if (batch.isEmpty()) return
                val copy = batch.toList(); batch.clear()
                database.runInTransaction { dao.upsertBatch(copy) }
                saved += copy.size; onProgress(baseSaved + saved)
            }
            if (isFree) {
                val items = FreeCatalog.freeItems(appContext, action, token)
                for (item in items) {
                    if (!seenIds.add(item.contentId)) continue
                    val rowKind = if (item.contentId in hiddenIds || item.category in hiddenCats) hiddenKind else action
                    batch.add(if (rowKind == action) item else item.copy(kind = rowKind))
                    if (batch.size >= 200) flush()
                }
                flush()
                if (saved > 0 && finalize) database.runInTransaction { dao.deleteStale(action, token); dao.deleteStale(hiddenKind, token) }
                return Result.success(saved)
            }
            val connection = open(action, readTimeoutMs, uaIndex, if (categoryId != null) mapOf("category_id" to categoryId) else emptyMap())
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                StreamingJsonObjects.forEach(connection.inputStream) { raw ->
                    val item = parseObject(raw) ?: return@forEach
                    if (!seenIds.add(item.id)) return@forEach
                    val rowKind = if (item.id in hiddenIds || item.category in hiddenCats) hiddenKind else action
                    batch.add(ContentEntity(rowKind, item.id, item.name, ArabicSearch.normalize(item.name),
                        item.category, item.imageUrl, item.extension, token, item.rating))
                    if (batch.size >= 200) flush()
                }
            } finally { connection.disconnect() }
            flush()
            if (saved > 0 && finalize) database.runInTransaction { dao.deleteStale(action, token); dao.deleteStale(hiddenKind, token) }
            Result.success(saved)
        } catch (e: Exception) {
            AppLog.warn("sync failed for $action: ${e.javaClass.simpleName} ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Fallback when the one-request download of the whole catalog keeps failing (slow/limited servers): downloads the
     * catalog category by category in small requests. Every finished category is already saved, so a slow or
     * interrupted download still leaves content on the device. Nothing is deleted here (a full sync cleans up later).
     */
    fun syncByCategories(kind: String, uaIndex: Int, onProgress: (loaded: Int) -> Unit): Result<Int> {
        if (isFree) return sync(kind, uaIndex, onProgress)
        val ids = (dao.categories(kind) + dao.categories(HiddenStore.hiddenKind(kind))).map { it.categoryId }.distinct()
        if (ids.isEmpty()) return Result.failure(IllegalStateException("لا توجد تصنيفات"))
        val token = java.util.UUID.randomUUID().toString()
        val seen = HashSet<String>()
        var total = 0
        var lastError: Throwable? = null
        for (id in ids) {
            val r = sync(kind, uaIndex, onProgress, categoryId = id, token = token, seenIds = seen, baseSaved = total,
                finalize = false, readTimeoutMs = 45000)
            if (r.isSuccess) total += r.getOrNull() ?: 0 else lastError = r.exceptionOrNull()
        }
        return if (total > 0) Result.success(total) else Result.failure(lastError ?: IllegalStateException("الخادم أعاد قائمة فارغة"))
    }

    fun syncCategories(kind: String): Result<Int> {
        if (isFree) {
            return try {
                val hiddenCats = HiddenStore.hiddenCategories(appContext, kind)
                val categories = FreeCatalog.freeCategories(appContext, kind).map { cat ->
                    if (cat.categoryId in hiddenCats) cat.copy(kind = HiddenStore.hiddenKind(kind)) else cat
                }
                if (categories.isNotEmpty()) database.runInTransaction {
                    dao.upsertCategories(categories)
                    val ids = categories.map { it.categoryId }
                    dao.deleteMissingCategories(kind, ids)
                    dao.deleteMissingCategories(HiddenStore.hiddenKind(kind), ids)
                }
                Result.success(categories.size)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        val categoryAction = when (kind) {
            "get_live_streams" -> "get_live_categories"
            "get_vod_streams" -> "get_vod_categories"
            "get_series" -> "get_series_categories"
            else -> return Result.failure(IllegalArgumentException("نوع غير مدعوم"))
        }
        val connection = try { open(categoryAction, 30000) } catch (e: Exception) {
            AppLog.warn("category sync failed for $kind: ${e.javaClass.simpleName} ${e.message}")
            return Result.failure(e)
        }
        return try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            val categories = ArrayList<CategoryEntity>()
            val hiddenCats = HiddenStore.hiddenCategories(appContext, kind)
            StreamingJsonObjects.forEach(connection.inputStream) { raw ->
                val obj = try { JSONObject(raw) } catch (_: Exception) { return@forEach }
                val id = obj.optString("category_id", obj.optString("id")).trim()
                val name = obj.optString("category_name", obj.optString("name")).trim()
                if (id.isNotBlank() && name.isNotBlank()) categories.add(CategoryEntity(if (id in hiddenCats) HiddenStore.hiddenKind(kind) else kind, id, name, obj.optString("parent_id", "")))
            }
            if (categories.isNotEmpty()) database.runInTransaction {
                dao.upsertCategories(categories)
                val ids = categories.map { it.categoryId }
                dao.deleteMissingCategories(kind, ids)   // drop categories of a previous server/account
                dao.deleteMissingCategories(HiddenStore.hiddenKind(kind), ids)
            }
            Result.success(categories.size)
        } catch (e: Exception) {
            AppLog.warn("category sync failed for $kind: ${e.javaClass.simpleName}")
            Result.failure(e)
        } finally { connection.disconnect() }
    }

    fun page(action: String, limit: Int, offset: Int, categoryId: String = ""): List<ContentEntity> =
        dao.pageByCategory(action, categoryId, limit.coerceIn(1, 100), offset.coerceAtLeast(0))

    fun latest(action: String, limit: Int): List<ContentEntity> = dao.latest(action, limit.coerceIn(1, 40), 0)
    fun trending(action: String, limit: Int): List<ContentEntity> = dao.trending(action, limit.coerceIn(1, 40))

    fun categories(action: String): List<CategoryEntity> = dao.categories(action)
    fun categoryName(action: String, categoryId: String): String = dao.categories(action).firstOrNull { it.categoryId == categoryId }?.categoryName ?: categoryId

    fun search(query: String, limit: Int, offset: Int): List<ContentEntity> {
        val normalized = ArabicSearch.normalize(query); if (normalized.isBlank()) return emptyList()
        return dao.searchPage("%$normalized%", limit.coerceIn(1, 100), offset.coerceAtLeast(0))
    }

    fun searchChannels(query: String, categoryId: String, limit: Int, offset: Int): List<ContentEntity> {
        val normalized = ArabicSearch.normalize(query)
        return dao.searchChannels(categoryId, "%$normalized%", limit.coerceIn(1, 100), offset.coerceAtLeast(0))
    }

    fun saved(followed: Boolean, limit: Int, offset: Int): List<ContentEntity> =
        if (followed) dao.followed(limit.coerceIn(1, 100), offset.coerceAtLeast(0)) else dao.favorites(limit.coerceIn(1, 100), offset.coerceAtLeast(0))

    fun mark(kind: String, id: String): UserMarkEntity? = dao.getMark(kind, id)
    fun toggleFavorite(item: ContentEntity): Boolean {
        val current = dao.getMark(item.kind, item.contentId) ?: UserMarkEntity(item.kind, item.contentId)
        val next = current.copy(favorite = !current.favorite, updatedAt = System.currentTimeMillis())
        if (!next.favorite && !next.followed && next.positionMs == 0L) dao.deleteMark(item.kind, item.contentId) else dao.saveMark(next)
        return next.favorite
    }
    fun toggleFollow(item: ContentEntity): Boolean {
        val current = dao.getMark(item.kind, item.contentId) ?: UserMarkEntity(item.kind, item.contentId)
        val next = current.copy(followed = !current.followed, updatedAt = System.currentTimeMillis())
        if (!next.favorite && !next.followed && next.positionMs == 0L) dao.deleteMark(item.kind, item.contentId) else dao.saveMark(next)
        return next.followed
    }
    fun saveProgress(kind: String, id: String, positionMs: Long) {
        val current = dao.getMark(kind, id) ?: UserMarkEntity(kind, id)
        dao.saveMark(current.copy(positionMs = positionMs.coerceAtLeast(0), updatedAt = System.currentTimeMillis()))
    }
    fun channelNeighbor(id: String, categoryId: String, direction: Int): ContentEntity? {
        val current = dao.getContent("get_live_streams", id) ?: return null
        return if (direction >= 0) dao.nextInCategory(current.kind, categoryId, current.name, current.contentId)
            ?: dao.firstInCategory(current.kind, categoryId)
        else dao.previousInCategory(current.kind, categoryId, current.name, current.contentId)
            ?: dao.firstInCategory(current.kind, categoryId)
    }
    fun count(action: String): Int = dao.count(action)

    /** Removes the cached items and categories of one section (used when the server or account changed). */
    fun clearKind(kind: String) {
        database.runInTransaction {
            dao.clearContent(kind); dao.clearCategories(kind)
            dao.clearContent(HiddenStore.hiddenKind(kind)); dao.clearCategories(HiddenStore.hiddenKind(kind))
        }
    }

    // ------------------------------------------------------------------ privacy lock (hide / show)

    /** Categories of [kind] with their hidden state, visible ones first by name. Call off the main thread. */
    fun manageCategories(kind: String): List<Pair<CategoryEntity, Boolean>> =
        dao.categories(kind).map { it to false } + dao.categories(HiddenStore.hiddenKind(kind)).map { it to true }

    fun setCategoryHidden(kind: String, categoryId: String, hidden: Boolean) {
        val hk = HiddenStore.hiddenKind(kind)
        HiddenStore.setCategoryHidden(appContext, kind, categoryId, hidden)
        database.runInTransaction {
            if (hidden) { dao.hideCategoryItems(kind, hk, categoryId); dao.hideCategoryRow(kind, hk, categoryId) }
            else { dao.showCategoryItems(kind, hk, categoryId, HiddenStore.hiddenItemIds(appContext, kind).toList()); dao.showCategoryRow(kind, hk, categoryId) }
        }
    }

    fun setItemHidden(kind: String, id: String, name: String, hidden: Boolean) {
        val hk = HiddenStore.hiddenKind(kind)
        HiddenStore.setItemHidden(appContext, kind, id, name, hidden)
        database.runInTransaction {
            if (hidden) dao.hideItem(kind, hk, id)
            else {
                // An item that sits in a still-hidden category stays hidden.
                val category = dao.getContent(hk, id)?.category
                if (category == null || category !in HiddenStore.hiddenCategories(appContext, kind)) dao.showItem(kind, hk, id)
            }
        }
    }

    /** Puts every hidden row back and forgets the password and all hidden lists (the "forgot password" reset). */
    fun showEverything() {
        database.runInTransaction { dao.showAllContent(); dao.showAllCategories() }
        HiddenStore.clearAll(appContext)
    }

    fun details(action: String, id: String): JSONObject {
        if (isFree) return FreeCatalog.freeDetails(action, id)
        val key = if (action == "get_vod_info") "vod_id" else "series_id"
        val connection = open(action, readTimeoutMs = 35000, uaIndex = 0, extra = mapOf(key to id))
        return try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            JSONObject(readLimited(connection.inputStream, 8 * 1024 * 1024))
        } finally { connection.disconnect() }
    }

    /** Time zone the provider reports in server_info (used only when a programme time arrives as text without an epoch value). */
    private fun serverZone(): java.util.TimeZone {
        EpgCache.zone?.let { return it }
        val id = try { account()?.optJSONObject("server_info")?.optString("timezone", "").orEmpty() } catch (_: Exception) { "" }
        val zone = if (id.isNotBlank() && java.util.TimeZone.getAvailableIDs().contains(id)) java.util.TimeZone.getTimeZone(id) else java.util.TimeZone.getDefault()
        EpgCache.zone = zone
        return zone
    }

    /**
     * Short EPG of one live channel through the documented Xtream action get_short_epg (stream_id + limit), using the same
     * server, credentials and redirect handling as every other call. Success with an empty list means "the provider has no
     * guide data for this channel"; failure means the request itself failed.
     */
    fun shortEpg(streamId: String, limit: Int = 8): Result<List<EpgEntry>> {
        if (isFree) return Result.success(emptyList())
        return try {
            val connection = open("get_short_epg", 20000, 0, mapOf("stream_id" to streamId, "limit" to limit.coerceIn(1, 20).toString()))
            try {
                if (connection.responseCode !in 200..299) return Result.failure(IllegalStateException("HTTP ${connection.responseCode}"))
                val text = readLimited(connection.inputStream, 1024 * 1024).trim()
                val list = ArrayList<EpgEntry>()
                val array = if (text.startsWith("{")) JSONObject(text).optJSONArray("epg_listings") else null
                if (array != null) {
                    fun time(o: JSONObject, epochKey: String, textKey: String): Long {
                        val epoch = o.optLong(epochKey, 0L)
                        if (epoch > 0L) return epoch * 1000L
                        val value = o.optString(textKey, "").trim()
                        if (value.isEmpty()) return 0L
                        return try {
                            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ENGLISH).apply { timeZone = serverZone() }.parse(value)?.time ?: 0L
                        } catch (_: Exception) { 0L }
                    }
                    for (i in 0 until array.length()) {
                        val o = array.optJSONObject(i) ?: continue
                        val start = time(o, "start_timestamp", "start"); val end = time(o, "stop_timestamp", "end")
                        if (start <= 0L || end <= start) continue
                        list.add(EpgEntry(decodeEpgText(o.optString("title", "")), decodeEpgText(o.optString("description", "")), start, end))
                    }
                }
                Result.success(list.sortedBy { it.startMs })
            } finally { connection.disconnect() }
        } catch (e: Exception) { Result.failure(e) }
    }

    fun mediaUrl(kind: String, id: String, extension: String): String = buildMediaUrl(server, kind, username, password, id, extension)

    /** Some providers omit the authoritative movie extension from the catalogue. */
    fun movieMediaUrl(id: String, fallbackExtension: String): String {
        if (isFree) return FreeCatalog.resolveMediaUrl("movie", id, fallbackExtension)
        val details = runCatching { details("get_vod_info", id) }.getOrNull()
        val movieData = details?.optJSONObject("movie_data")
        val info = details?.optJSONObject("info")
        val extension = listOf(
            movieData?.optString("container_extension"),
            info?.optString("container_extension"),
            fallbackExtension
        ).firstOrNull { !it.isNullOrBlank() && it != "null" } ?: "mp4"
        return mediaUrl("movie", id, extension)
    }

    fun browse(kind: String, ids: List<String>?, query: String, limit: Int, offset: Int): List<ContentEntity> {
        if (kind != Kinds.ALL) {
            val all = if (ids == null) 1 else 0
            val like = "%${ArabicSearch.normalize(query)}%"
            val lim = limit.coerceIn(1, 200); val off = offset.coerceAtLeast(0)
            return if (kind == Kinds.LIVE) dao.browseByName(kind, all, ids ?: emptyList(), like, lim, off)
            else dao.browseNewest(kind, all, ids ?: emptyList(), like, lim, off)
        }
        return browseAny(kind, ids, query, limit, offset)
    }

    private fun browseAny(kind: String, ids: List<String>?, query: String, limit: Int, offset: Int): List<ContentEntity> =
        dao.browse(if (kind == Kinds.ALL) "" else kind, if (ids == null) 1 else 0, ids ?: emptyList(),
            "%${ArabicSearch.normalize(query)}%", if (kind == Kinds.LIVE) 1 else 0, limit.coerceIn(1, 200), offset.coerceAtLeast(0))

    fun countBrowse(kind: String, ids: List<String>?, query: String): Int {
        // No search text: answer from the indexes instead of scanning every row.
        if (kind != Kinds.ALL && query.isBlank()) {
            return if (ids == null) dao.count(kind) else if (ids.isEmpty()) 0 else dao.countInCategories(kind, ids)
        }
        return countAny(kind, ids, query)
    }

    private fun countAny(kind: String, ids: List<String>?, query: String): Int =
        dao.countBrowse(if (kind == Kinds.ALL) "" else kind, if (ids == null) 1 else 0, ids ?: emptyList(), "%${ArabicSearch.normalize(query)}%")

    fun categoryCounts(kind: String): Map<String, Int> = dao.categoryCounts(kind).associate { it.categoryId to it.cnt }
    fun randomPosters(kind: String, limit: Int): List<ContentEntity> = dao.randomWithImage(kind, limit)
    fun favoriteKeys(): Set<String> = dao.favorites(2000, 0).map { "${it.kind}:${it.contentId}" }.toSet()
    fun continueItems(): List<ContentEntity> =
        (dao.continueWatching(200, 0) + dao.followed(200, 0)).distinctBy { "${it.kind}:${it.contentId}" }

    fun account(): JSONObject? = if (isFree) FreeCatalog.freeAccountInfo() else try {
        val connection = open("", readTimeoutMs = 15000, uaIndex = 0)
        try {
            if (connection.responseCode !in 200..299) null else JSONObject(readLimited(connection.inputStream, 1024 * 1024))
        } finally { connection.disconnect() }
    } catch (_: Exception) { null }

    private fun readLimited(input: InputStream, maxBytes: Int): String {
        input.use { stream ->
            val out = StringBuilder(); val reader = InputStreamReader(stream, Charsets.UTF_8); val buffer = CharArray(8192); var total = 0
            while (true) {
                val count = reader.read(buffer); if (count < 0) break
                total += count * 2
                if (total > maxBytes) throw IllegalStateException("تفاصيل JSON أكبر من الحد الآمن")
                out.append(buffer, 0, count)
            }
            return out.toString()
        }
    }
}

internal fun buildMediaUrl(server: String, kind: String, username: String, password: String, id: String, extension: String): String {
    if (FreeCatalog.isFreeServer(server)) return FreeCatalog.resolveMediaUrl(kind, id, extension)
    val cleanExtension = extension.trim().trimStart('.').substringBefore('?').ifBlank { if (kind == "live") "ts" else "mp4" }
    val cleanId = id.trim().substringBefore('?').substringBefore('#')
    return "${server.trim().trimEnd('/')}/$kind/${URLEncoder.encode(username, "UTF-8")}/${URLEncoder.encode(password, "UTF-8")}/$cleanId.$cleanExtension"
}

/**
 * Image loader with an instant branded placeholder, RAM-adaptive memory cache + disk cache, in-flight request
 * deduplication, throttled disk trimming, redirect following (http -> https), and a dedicated queue for detail screens.
 * A view is only updated while its tag still matches the request, so a recycled row never shows another title's image.
 */
object ImageLoader {
    private const val USER_AGENT = "AL-HUR-AL-ASLI/1.0"
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val DISK_LIMIT_BYTES = 128L * 1024 * 1024
    private const val DISK_TARGET_BYTES = 96L * 1024 * 1024
    @Volatile private var memory: LruCache<String, Bitmap>? = null
    private val cacheLock = Any()
    private val inFlightLocks = ConcurrentHashMap<String, Any>()
    private val diskWrites = AtomicInteger(0)
    @Volatile private var lastDiskTrimAt = 0L

    // Lists: bounded queue, stale requests are dropped while scrolling.
    private val listExecutor = ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(96), ThreadPoolExecutor.DiscardOldestPolicy())
    // Detail screens: its own threads and an unbounded queue, so the poster/backdrop/episode stills are never discarded.
    private val detailExecutor = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private val main = Handler(Looper.getMainLooper())

    private fun cache(context: Context): LruCache<String, Bitmap> {
        memory?.let { return it }
        return synchronized(cacheLock) {
            memory ?: run {
                val am = context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memClassMb = am?.memoryClass ?: 128
                // Allocate ~1/8 of heap (bounded 16 MB..48 MB) so low-RAM TVs avoid OOM and flagship phones keep rich posters hot.
                val maxKb = (memClassMb * 1024 / 8).coerceIn(16 * 1024, 48 * 1024)
                object : LruCache<String, Bitmap>(maxKb) {
                    override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
                }.also { memory = it }
            }
        }
    }

    /** Trims memory cache gracefully when Android signals memory pressure. */
    fun trimMemory(level: Int) {
        val m = memory ?: return
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> m.evictAll()
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> m.trimToSize(m.maxSize() / 2)
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> m.trimToSize(m.maxSize() * 3 / 4)
        }
    }

    private fun clean(url: String?): String {
        val value = url?.trim().orEmpty()
        if (value.isEmpty() || value.equals("null", ignoreCase = true)) return ""
        if (!(value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true))) return ""
        return value.replace(" ", "%20")
    }

    /**
     * @param priority use the detail-screen queue and retry once on failure.
     * @param maxPx    0 = small list thumbnail; otherwise decode up to this size (poster / backdrop).
     * @param fade     cross-fade from the placeholder to the real image.
     * @param onDone   called on the main thread with true when the real image is shown, false when the placeholder stays.
     */
    fun bind(context: Context, view: ImageView, url: String?, fallback: Int = Color.rgb(35, 35, 55),
             placeholderLabel: String = "", placeholderKind: String = "",
             priority: Boolean = false, maxPx: Int = 0, fade: Boolean = false, onDone: ((Boolean) -> Unit)? = null) {
        val target = clean(url)
        val key = "$maxPx|$target"
        view.tag = key
        if (target.isEmpty()) {
            view.setImageDrawable(
                if (placeholderLabel.isNotBlank()) PosterFallbackDrawable(placeholderLabel, placeholderKind)
                else ColorDrawable(fallback)
            )
            onDone?.invoke(false)
            return
        }
        val memCache = cache(context)
        memCache.get(key)?.let { cached ->
            if (!cached.isRecycled) {
                view.setImageBitmap(cached)
                onDone?.invoke(true)
                return
            }
            memCache.remove(key)
        }
        val placeholder: Drawable = if (placeholderLabel.isNotBlank()) PosterFallbackDrawable(placeholderLabel, placeholderKind)
            else ColorDrawable(fallback)
        view.setImageDrawable(placeholder)

        val app = context.applicationContext
        val task = Runnable {
            if (view.tag != key) return@Runnable
            var bitmap: Bitmap? = try { load(app, target, maxPx) } catch (_: Throwable) { null }
            if (bitmap == null && priority && view.tag == key) {
                try { Thread.sleep(900) } catch (_: InterruptedException) { }
                if (view.tag == key) bitmap = try { load(app, target, maxPx) } catch (_: Throwable) { null }
            }
            val result = bitmap
            if (result != null) memCache.put(key, result)
            main.post {
                if (view.tag != key) return@post
                if (result != null) {
                    if (fade) {
                        val transition = TransitionDrawable(arrayOf(placeholder, BitmapDrawable(view.resources, result)))
                        transition.isCrossFadeEnabled = true
                        view.setImageDrawable(transition); transition.startTransition(200)
                        main.postDelayed({ if (view.tag == key) view.setImageBitmap(result) }, 240)
                    } else view.setImageBitmap(result)
                }
                onDone?.invoke(result != null)
            }
        }
        try { (if (priority) detailExecutor else listExecutor).execute(task) } catch (_: Exception) { onDone?.invoke(false) }
    }

    /** Opens the image, following redirects by hand because HttpURLConnection will not follow http -> https. */
    private fun open(start: String): HttpURLConnection? {
        var current = start
        for (hop in 0..4) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.connectTimeout = 7000; connection.readTimeout = 10000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "image/*,*/*;q=0.8")
            val code = try { connection.responseCode } catch (_: Exception) { connection.disconnect(); return null }
            if (code in 200..299) return connection
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) return null
                current = try { URL(URL(current), location).toString() } catch (_: Exception) { return null }
                continue
            }
            connection.disconnect()
            return null
        }
        return null
    }

    private fun cacheFile(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.cacheDir, "img_$hash")
    }

    private fun load(context: Context, url: String, maxPx: Int): Bitmap? {
        val file = cacheFile(context, url)
        if (file.exists() && file.length() in 1..MAX_FILE_BYTES) {
            decodeSampled(file.absolutePath, maxPx)?.let { return it }
            file.delete() // a partial or corrupt cache entry must not block the image forever
        }
        // Coalesce concurrent requests for the same URL so scrolling never spawns duplicate downloads.
        val urlLock = inFlightLocks.getOrPut(file.name) { Any() }
        return try {
            synchronized(urlLock) {
                if (file.exists() && file.length() in 1..MAX_FILE_BYTES) {
                    decodeSampled(file.absolutePath, maxPx)?.let { return@synchronized it }
                    file.delete()
                }
                val connection = open(url) ?: return@synchronized null
                try {
                    val temp = File(file.parentFile, file.name + ".tmp")
                    var total = 0L
                    var ok = true
                    try {
                        connection.inputStream.use { input -> FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                total += count
                                if (total > MAX_FILE_BYTES) { ok = false; break }
                                output.write(buffer, 0, count)
                            }
                        } }
                    } catch (_: Exception) { ok = false }
                    if (!ok || total <= 0L || !temp.renameTo(file)) { temp.delete(); return@synchronized null }
                    maybeTrimDiskCache(context.cacheDir)
                    val bitmap = decodeSampled(file.absolutePath, maxPx)
                    if (bitmap == null) file.delete()
                    bitmap
                } finally { connection.disconnect() }
            }
        } finally {
            inFlightLocks.remove(file.name, urlLock)
        }
    }

    /** Clears the cached pictures (memory + disk) and returns the freed bytes. The library, favorites, progress and login are not touched. */
    @Synchronized fun clearCache(context: Context): Long {
        memory?.evictAll()
        var freed = 0L
        context.cacheDir.listFiles { file -> file.isFile && file.name.startsWith("img_") }?.forEach { file ->
            val size = file.length()
            if (file.delete()) freed += size
        }
        return freed
    }

    private fun maybeTrimDiskCache(cacheDir: File) {
        val count = diskWrites.incrementAndGet()
        val now = System.currentTimeMillis()
        if (count % 32 != 0 && now - lastDiskTrimAt < 30_000L) return
        lastDiskTrimAt = now
        trimDiskCache(cacheDir)
    }

    @Synchronized private fun trimDiskCache(cacheDir: File) {
        val files = cacheDir.listFiles { file -> file.isFile && file.name.startsWith("img_") && !file.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_LIMIT_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_TARGET_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun decodeSampled(path: String, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val limitW = if (maxPx > 0) maxPx else 420
        val limitH = if (maxPx > 0) maxPx else 300
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= limitW && bounds.outHeight / (sample * 2) >= limitH) sample *= 2
        if (bounds.outWidth / sample > limitW * 2 || bounds.outHeight / sample > limitH * 2) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (maxPx > 0) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        })
    }
}
