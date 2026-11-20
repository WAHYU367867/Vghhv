package com.alhorrtv.app

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/** One programme of a live channel. Times are absolute epoch milliseconds (UTC), so they display correctly in any time zone. */
data class EpgEntry(val title: String, val description: String, val startMs: Long, val endMs: Long)

/**
 * In-memory EPG cache: avoids asking the provider again for the same channel. A channel's guide is reused for 10 minutes
 * (and only while one of its programmes is still running); "no data" and failed requests are remembered for a shorter time,
 * so scrolling never hammers the server.
 */
internal object EpgCache {
    private class Slot(val list: List<EpgEntry>, val expiresAt: Long)
    private val map = ConcurrentHashMap<String, Slot>()
    @Volatile var zone: TimeZone? = null     // the provider's own time zone, only needed to read text timestamps

    const val TTL_DATA = 10 * 60_000L
    const val TTL_EMPTY = 15 * 60_000L
    const val TTL_FAILED = 2 * 60_000L

    fun get(key: String, now: Long = System.currentTimeMillis()): List<EpgEntry>? {
        val slot = map[key] ?: return null
        if (now > slot.expiresAt) { map.remove(key); return null }
        if (slot.list.isNotEmpty() && slot.list.none { it.endMs > now }) { map.remove(key); return null }
        return slot.list
    }

    fun put(key: String, list: List<EpgEntry>, ttlMs: Long) {
        if (map.size > 600) map.clear()                 // keeps memory bounded on low-RAM TVs
        map[key] = Slot(list, System.currentTimeMillis() + ttlMs)
    }

    fun clear() = map.clear()
}

/**
 * Xtream's get_short_epg returns titles and descriptions base64-encoded. Decodes when the text really is base64 of valid UTF-8,
 * otherwise returns it unchanged (some panels send plain text).
 */
internal fun decodeEpgText(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return ""
    if (text.length % 4 == 0 && text.all { (it in 'A'..'Z') || (it in 'a'..'z') || (it in '0'..'9') || it == '+' || it == '/' || it == '=' }) {
        try {
            val bytes = android.util.Base64.decode(text, android.util.Base64.DEFAULT)
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val decoded = decoder.decode(ByteBuffer.wrap(bytes)).toString().trim()
            if (decoded.isNotEmpty() && decoded.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return decoded
        } catch (_: Exception) { }
    }
    return text
}
