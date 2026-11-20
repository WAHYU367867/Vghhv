package com.alhorrtv.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * App-wide content sync. Content is downloaded once, kept in the local database, and only refreshed when it is
 * older than the selected refresh interval (or when the user asks). Entering/leaving screens never triggers a re-download.
 */
object SyncManager {
    interface Listener {
        fun onSyncState(kind: String, running: Boolean, loaded: Int, ok: Boolean?)
        /** The category list of [kind] has just been downloaded (it is small and arrives long before the content itself). */
        fun onCategoriesReady(kind: String) {}
    }

    private const val SETTINGS = "content_refresh"
    const val MODE_SIX_HOURS = "six_hours"
    const val MODE_DAILY = "daily"
    const val MODE_MANUAL = "manual"
    fun refreshMode(context: Context): String = context.applicationContext
        .getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getString("mode", MODE_SIX_HOURS) ?: MODE_SIX_HOURS
    fun setRefreshMode(context: Context, mode: String) {
        if (mode !in setOf(MODE_SIX_HOURS, MODE_DAILY, MODE_MANUAL)) return
        context.applicationContext.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putString("mode", mode).apply()
    }
    fun refreshLabel(mode: String): String = when (mode) {
        MODE_DAILY -> "كل 24 ساعة"
        MODE_MANUAL -> "يدوي فقط"
        else -> "كل 6 ساعات"
    }
    private fun refreshInterval(mode: String): Long = when (mode) {
        MODE_DAILY -> 24L * 60 * 60 * 1000
        MODE_MANUAL -> 0L
        else -> 6L * 60 * 60 * 1000
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val active = ConcurrentHashMap<String, Int>()
    private val categoryLocks = ConcurrentHashMap<String, Any>()
    /** Used only to RETRY a failed download one at a time (providers that allow a single API connection); first attempts run in parallel. */
    private val gate = java.util.concurrent.Semaphore(1)
    private val errors = ConcurrentHashMap<String, String>()
    /** Short reason of the last failed sync of [kind] (null when the last sync worked). */
    fun lastError(kind: String): String? = errors[kind]

    fun add(listener: Listener) { listeners.addIfAbsent(listener) }
    fun remove(listener: Listener) { listeners.remove(listener) }
    private val finished = ConcurrentHashMap<String, Long>()
    /** When the last announced sync of [kind] ended. Screens use it to notice a sync that finished while they were stopped. */
    fun finishedAt(kind: String): Long = finished[kind] ?: 0L
    fun isRunning(kind: String): Boolean = active.containsKey(kind)
    fun loaded(kind: String): Int = active[kind] ?: 0

    private fun publish(kind: String, running: Boolean, loaded: Int, ok: Boolean?) {
        main.post { listeners.forEach { it.onSyncState(kind, running, loaded, ok) } }
    }

    fun ensureAll(context: Context, server: String, user: String, pass: String, force: Boolean = false) {
        Kinds.CONTENT.forEach { ensure(context, server, user, pass, it, force) }
    }

    private fun catPrefs(context: Context) = context.applicationContext.getSharedPreferences("sync_meta", Context.MODE_PRIVATE)

    /**
     * True when the categories of [kinds] were already downloaded once (even if the provider returned none, or the user
     * hid them all), so opening a section never shows a loading dialog or hits the network again.
     */
    fun categoriesCached(context: Context, server: String, user: String, pass: String, kinds: List<String> = Kinds.CONTENT): Boolean {
        return try {
            val repo = XtreamRepository(context.applicationContext, server, user, pass)
            val prefs = catPrefs(context)
            kinds.all { kind ->
                prefs.getBoolean("cat_$kind", false) ||
                    repo.categories(kind).isNotEmpty() || repo.categories(HiddenStore.hiddenKind(kind)).isNotEmpty()
            }
        } catch (_: Exception) { false }
    }

    /** Downloads only the categories that are missing, only for [kinds]. Returns false if one of them failed. */
    fun ensureCategories(context: Context, server: String, user: String, pass: String, kinds: List<String> = Kinds.CONTENT): Boolean {
        return try {
            val repo = XtreamRepository(context.applicationContext, server, user, pass)
            val prefs = catPrefs(context)
            var allLoaded = true
            kinds.forEach { kind ->
                val have = prefs.getBoolean("cat_$kind", false) ||
                    repo.categories(kind).isNotEmpty() || repo.categories(HiddenStore.hiddenKind(kind)).isNotEmpty()
                if (!have && !loadCategories(repo, kind, prefs)) allLoaded = false
            }
            allLoaded
        } catch (_: Exception) {
            false
        }
    }

    private fun loadCategories(repo: XtreamRepository, kind: String, prefs: android.content.SharedPreferences): Boolean {
        val lock = categoryLocks.getOrPut(kind) { Any() }
        synchronized(lock) {
            return try {
                if (repo.categories(kind).isNotEmpty()) { prefs.edit().putBoolean("cat_$kind", true).apply(); true }
                else {
                    val r = repo.syncCategories(kind)
                    // Remember it only when real categories arrived; an empty answer (throttled/odd server) must be retried later.
                    if (r.isSuccess && (r.getOrNull() ?: 0) > 0) prefs.edit().putBoolean("cat_$kind", true).apply()
                    r.isSuccess
                }
            } catch (_: Exception) { false }
        }
    }

    fun ensure(context: Context, server: String, user: String, pass: String, kind: String, force: Boolean = false) {
        if (server.isBlank() || kind !in Kinds.CONTENT) return
        val app = context.applicationContext
        if (active.putIfAbsent(kind, 0) != null) return
        pool.execute {
            var announced = false
            var success = false
            var total = 0
            try {
                val prefs = app.getSharedPreferences("sync_meta", Context.MODE_PRIVATE)
                val repo = XtreamRepository(app, server, user, pass)
                // A different server/account must never reuse (or mix with) the previous one's cached library.
                val signature = "${server.trim().trimEnd('/').lowercase()}|${user.trim().lowercase()}"
                val oldSignature = prefs.getString("sig_$kind", null)
                if (oldSignature != null && oldSignature != signature) { repo.clearKind(kind); prefs.edit().remove("t_$kind").remove("cat_$kind").apply() }
                if (oldSignature != signature) prefs.edit().putString("sig_$kind", signature).apply()
                val last = prefs.getLong("t_$kind", 0L)
                val interval = refreshInterval(refreshMode(app))
                val fresh = !force && last > 0 && (interval == 0L || System.currentTimeMillis() - last < interval) && repo.count(kind) > 0
                if (fresh) return@execute // nothing was announced, so there is nothing to finish
                publish(kind, true, 0, null); announced = true
                // Categories first and outside any queue: they are small, so the sidebar fills in right away.
                val hadCategories = repo.categories(kind).isNotEmpty()
                if (loadCategories(repo, kind, prefs) && !hadCategories) main.post { listeners.forEach { it.onCategoriesReady(kind) } }
                run {
                    val progress: (Int) -> Unit = { loaded -> active[kind] = loaded; publish(kind, true, loaded, null) }
                    // First attempt runs in parallel with the other sections (fast on normal servers).
                    // Short patience on the first try (a server that sends nothing for 30 s is stuck), then the category-by-category fallback.
                    var result = repo.sync(kind, 0, progress, readTimeoutMs = 30000)
                    if (result.isFailure) result = repo.syncByCategories(kind, 0, progress)
                    if (result.isFailure) {
                        // Retry one section at a time and with another User-Agent: some servers throttle parallel or unknown clients.
                        gate.acquire()
                        try {
                            var attempt = 0
                            while (result.isFailure && attempt < 2) {
                                attempt++
                                Thread.sleep(2000L * attempt)
                                result = repo.sync(kind, attempt, progress)
                            }
                        } finally { gate.release() }
                    }
                    total = result.getOrNull() ?: 0
                    success = result.isSuccess && total > 0
                    when {
                        result.isFailure -> errors[kind] = result.exceptionOrNull()?.message?.take(60) ?: "خطأ في الاتصال"
                        total == 0 -> errors[kind] = "الخادم أعاد قائمة فارغة"
                        else -> errors.remove(kind)
                    }
                    if (success) prefs.edit().putLong("t_$kind", System.currentTimeMillis()).apply()
                }
            } catch (_: Throwable) {
                // Includes OutOfMemoryError: the "loading" state must always end, whatever went wrong.
                success = false
                errors[kind] = "خطأ غير متوقع"
            } finally {
                active.remove(kind)
                if (announced) {
                    finished[kind] = System.currentTimeMillis()
                    publish(kind, false, total, success)
                }
            }
        }
    }

    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences("sync_meta", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
