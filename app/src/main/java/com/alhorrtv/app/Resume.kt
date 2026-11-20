package com.alhorrtv.app

import android.content.Context
import org.json.JSONObject

/** Where a series was left: which episode and how far in. Movies keep theirs in the user_marks table; series need the episode too. */
internal data class SeriesPoint(val episodeId: String, val positionMs: Long, val extension: String, val title: String)

internal object ResumeStore {
    private const val FILE = "resume_points"
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun saveSeries(ctx: Context, seriesId: String, episodeId: String, positionMs: Long, extension: String, title: String) {
        if (seriesId.isBlank() || episodeId.isBlank() || positionMs <= 0L) return
        val json = JSONObject().put("e", episodeId).put("p", positionMs).put("x", extension).put("t", title)
        prefs(ctx).edit().putString("series_$seriesId", json.toString()).apply()
    }

    fun seriesPoint(ctx: Context, seriesId: String): SeriesPoint? = try {
        prefs(ctx).getString("series_$seriesId", null)?.let { raw ->
            val o = JSONObject(raw)
            SeriesPoint(o.optString("e"), o.optLong("p"), o.optString("x", "mp4"), o.optString("t")).takeIf { it.episodeId.isNotBlank() }
        }
    } catch (_: Exception) { null }

    fun clearSeries(ctx: Context, seriesId: String) { prefs(ctx).edit().remove("series_$seriesId").apply() }
}
