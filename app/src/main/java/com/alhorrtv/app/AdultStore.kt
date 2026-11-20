package com.alhorrtv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal data class AdultChannel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val category: String,
    val logoUrl: String,
    val sourceLabel: String,
    val addedAt: Long
)

/**
 * Isolated storage and legal-source M3U/URL manager for the independent Adult 18+ section.
 * Never touches ContentDatabase, XtreamRepository, FreeCatalog, CredentialStore, or user_marks.
 */
internal object AdultStore {
    private const val PREFS_NAME = "adult_section_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val CHANNELS_FILE = "adult_channels_v1.json"

    @Volatile
    private var sessionAgeConfirmed = false

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) sessionAgeConfirmed = false
    }

    fun isAgeConfirmed(): Boolean = sessionAgeConfirmed
    fun confirmAge() { sessionAgeConfirmed = true }
    fun clearAgeConfirmation() { sessionAgeConfirmed = false }

    fun isValidHttpUrl(raw: String?): Boolean {
        val value = raw?.trim().orEmpty()
        if (!(value.startsWith("http://", true) || value.startsWith("https://", true))) return false
        return try {
            val url = URL(value)
            url.host.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun getChannels(context: Context): List<AdultChannel> {
        val file = File(context.applicationContext.filesDir, CHANNELS_FILE)
        if (!file.exists() || file.length() <= 0L) return emptyList()
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            val out = ArrayList<AdultChannel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("streamUrl", "").trim()
                val name = o.optString("name", "").trim()
                if (name.isBlank() || !isValidHttpUrl(url)) continue
                out += AdultChannel(
                    id = o.optString("id", "adult_$i"),
                    name = name,
                    streamUrl = url,
                    category = o.optString("category", "مصادر مصرح بها").ifBlank { "مصادر مصرح بها" },
                    logoUrl = o.optString("logoUrl", "").takeIf { isValidHttpUrl(it) }.orEmpty(),
                    sourceLabel = o.optString("sourceLabel", "مصدر مصرح به").ifBlank { "مصدر مصرح به" },
                    addedAt = o.optLong("addedAt", 0L)
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveChannels(context: Context, list: List<AdultChannel>) {
        try {
            val arr = JSONArray()
            list.forEach { ch ->
                arr.put(
                    JSONObject()
                        .put("id", ch.id)
                        .put("name", ch.name)
                        .put("streamUrl", ch.streamUrl)
                        .put("category", ch.category)
                        .put("logoUrl", ch.logoUrl)
                        .put("sourceLabel", ch.sourceLabel)
                        .put("addedAt", ch.addedAt)
                )
            }
            val dir = context.applicationContext.filesDir
            val tmp = File(dir, "$CHANNELS_FILE.tmp")
            val target = File(dir, CHANNELS_FILE)
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            tmp.renameTo(target)
        } catch (_: Exception) { }
    }

    fun addChannel(
        context: Context,
        name: String,
        streamUrl: String,
        category: String = "مصادر مصرح بها",
        logoUrl: String = "",
        sourceLabel: String = "مصدر مباشر مصرح به"
    ): AdultChannel {
        val cleanUrl = streamUrl.trim()
        val item = AdultChannel(
            id = "adult_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
            name = name.trim(),
            streamUrl = cleanUrl,
            category = category.trim().ifBlank { "مصادر مصرح بها" },
            logoUrl = logoUrl.trim().takeIf { isValidHttpUrl(it) }.orEmpty(),
            sourceLabel = sourceLabel.trim().ifBlank { "مصدر مباشر مصرح به" },
            addedAt = System.currentTimeMillis()
        )
        val existing = getChannels(context).filterNot { it.streamUrl == cleanUrl }
        saveChannels(context, listOf(item) + existing)
        return item
    }

    fun importChannels(context: Context, items: List<AdultChannel>): Int {
        if (items.isEmpty()) return 0
        val existing = getChannels(context)
        val seen = existing.map { it.streamUrl }.toHashSet()
        val fresh = items.filter { isValidHttpUrl(it.streamUrl) && seen.add(it.streamUrl) }
        if (fresh.isNotEmpty()) saveChannels(context, fresh + existing)
        return fresh.size
    }

    fun removeChannel(context: Context, id: String) {
        val next = getChannels(context).filterNot { it.id == id }
        saveChannels(context, next)
    }

    fun parseM3u(rawText: String, sourceLabel: String = "قائمة M3U مصرح بها"): List<AdultChannel> {
        val logoRegex = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
        val groupRegex = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
        val result = ArrayList<AdultChannel>()
        var pendingName = ""
        var pendingLogo = ""
        var pendingCategory = "مصادر مصرح بها"

        rawText.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                pendingLogo = logoRegex.find(line)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                pendingCategory = groupRegex.find(line)?.groupValues?.getOrNull(1)?.trim()
                    ?.ifBlank { "مصادر مصرح بها" } ?: "مصادر مصرح بها"
                val comma = line.lastIndexOf(',')
                pendingName = if (comma >= 0) line.substring(comma + 1).trim() else ""
                return@forEach
            }
            if (line.startsWith("#")) return@forEach
            if (isValidHttpUrl(line)) {
                result += AdultChannel(
                    id = "adult_${System.currentTimeMillis()}_${result.size}_${UUID.randomUUID().toString().take(4)}",
                    name = pendingName.ifBlank { "قناة ${result.size + 1}" },
                    streamUrl = line,
                    category = pendingCategory,
                    logoUrl = pendingLogo.takeIf { isValidHttpUrl(it) }.orEmpty(),
                    sourceLabel = sourceLabel,
                    addedAt = System.currentTimeMillis()
                )
            }
            pendingName = ""
            pendingLogo = ""
            pendingCategory = "مصادر مصرح بها"
        }
        return result
    }

    fun fetchM3u(url: String): String {
        var current = url.trim()
        for (hop in 0..4) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "AL-HUR-AL-ASLI/1.0 (Android)")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) throw IllegalStateException("HTTP $code")
                current = URL(URL(current), loc).toString()
                continue
            }
            try {
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val sb = StringBuilder()
                    val buf = CharArray(8192)
                    var total = 0
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > 4 * 1024 * 1024) throw IllegalStateException("قائمة كبيرة جداً")
                        sb.append(buf, 0, n)
                    }
                    sb.toString()
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("تحويلات كثيرة")
    }
}
