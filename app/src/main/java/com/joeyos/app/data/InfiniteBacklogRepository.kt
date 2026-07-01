package com.joeyos.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class IBCompletion(
    val gameName: String,
    val completionDate: String?,   // "2026-06-17" or null
    val isFullyCompleted: Boolean
)

data class IBResult(
    val completionsByYear: Map<Int, List<IBCompletion>>,
    val undatedCount: Int,
    val totalCompleted: Int,
    val fetchedAt: Long = System.currentTimeMillis()
)

sealed class IBStatus {
    object NotConfigured               : IBStatus()
    data class Success(val data: IBResult) : IBStatus()
    data class Error(val message: String)  : IBStatus()
}

class InfiniteBacklogRepository(context: Context) {

    private val prefs = context.getSharedPreferences("infinitebacklog", Context.MODE_PRIVATE)

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(v) = prefs.edit().putString("username", v).apply()

    private var cachedUserId: Int
        get() = prefs.getInt("user_id", -1)
        set(v) = prefs.edit().putInt("user_id", v).apply()

    val isConfigured: Boolean get() = username.isNotBlank()

    private var cache: IBResult? = null
    private val cacheTtlMs = 4 * 60 * 60 * 1000L

    val manualRefreshCooldownMs = 30 * 60 * 1000L // 30 minutes
    var lastManualRefreshAt: Long
        get() = prefs.getLong(KEY_LAST_MANUAL_REFRESH, 0L)
        private set(v) = prefs.edit().putLong(KEY_LAST_MANUAL_REFRESH, v).apply()

    init {
        cache = prefs.getString(KEY_CACHE, null)?.let { deserializeResult(it) }
    }

    fun clearCache() {
        cache = null
        cachedUserId = -1
        prefs.edit().remove(KEY_CACHE).apply()
    }

    suspend fun fetchCompletions(forceRefresh: Boolean = false): IBStatus {
        if (!isConfigured) return IBStatus.NotConfigured

        val cached = cache
        if (!forceRefresh && cached != null &&
            System.currentTimeMillis() - cached.fetchedAt < cacheTtlMs) {
            return IBStatus.Success(cached)
        }

        return withContext(Dispatchers.IO) {
            try {
                var uid = cachedUserId
                if (uid < 0) {
                    uid = resolveUserId(username)
                        ?: return@withContext IBStatus.Error("User \"$username\" not found")
                    cachedUserId = uid
                }

                val completions = mutableListOf<IBCompletion>()
                for (type in listOf("campaignCompleted", "completed")) {
                    completions += fetchAllPages(uid, type)
                }
                // Dedup: prefer completed (100%) if both appear for the same game
                val seen = mutableSetOf<String>()
                val deduped = (completions.filter { it.isFullyCompleted } +
                               completions.filter { !it.isFullyCompleted })
                    .filter { seen.add(it.gameName) }

                val withDate  = deduped.filter { it.completionDate != null }
                val undated   = deduped.count  { it.completionDate == null }

                val byYear = withDate
                    .groupBy { it.completionDate!!.take(4).toIntOrNull() ?: 0 }
                    .mapValues { (_, v) -> v.sortedByDescending { it.completionDate } }

                val result = IBResult(
                    completionsByYear = byYear,
                    undatedCount      = undated,
                    totalCompleted    = deduped.size
                )
                cache = result
                prefs.edit().putString(KEY_CACHE, serializeResult(result)).apply()
                if (forceRefresh) lastManualRefreshAt = System.currentTimeMillis()
                IBStatus.Success(result)
            } catch (e: Exception) {
                IBStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun serializeResult(result: IBResult): String {
        val obj    = JSONObject()
        obj.put("fetchedAt",     result.fetchedAt)
        obj.put("totalCompleted", result.totalCompleted)
        obj.put("undatedCount",  result.undatedCount)
        val byYear = JSONObject()
        result.completionsByYear.forEach { (year, games) ->
            val arr = JSONArray()
            games.forEach { c ->
                arr.put(JSONObject().apply {
                    put("gameName",         c.gameName)
                    put("completionDate",   c.completionDate ?: JSONObject.NULL)
                    put("isFullyCompleted", c.isFullyCompleted)
                })
            }
            byYear.put(year.toString(), arr)
        }
        obj.put("completionsByYear", byYear)
        return obj.toString()
    }

    private fun deserializeResult(json: String): IBResult? = try {
        val obj        = JSONObject(json)
        val byYearObj  = obj.getJSONObject("completionsByYear")
        val byYear     = mutableMapOf<Int, List<IBCompletion>>()
        byYearObj.keys().forEach { yearStr ->
            val year = yearStr.toIntOrNull() ?: return@forEach
            val arr  = byYearObj.getJSONArray(yearStr)
            byYear[year] = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                IBCompletion(
                    gameName         = c.getString("gameName"),
                    completionDate   = if (c.isNull("completionDate")) null else c.getString("completionDate"),
                    isFullyCompleted = c.getBoolean("isFullyCompleted")
                )
            }
        }
        IBResult(
            completionsByYear = byYear,
            undatedCount      = obj.getInt("undatedCount"),
            totalCompleted    = obj.getInt("totalCompleted"),
            fetchedAt         = obj.getLong("fetchedAt")
        )
    } catch (_: Exception) { null }

    private fun resolveUserId(username: String): Int? {
        val conn = URL("https://infinitebacklog.net/api/users/username/$username")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/json")
        return try {
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().readText()).optInt("id", -1).takeIf { it > 0 }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val KEY_CACHE               = "ib_cache_json_v2"
        private const val KEY_LAST_MANUAL_REFRESH = "ib_last_manual_refresh"
    }

    private fun fetchAllPages(userId: Int, completionType: String): List<IBCompletion> {
        val results = mutableListOf<IBCompletion>()
        var offset = 0
        val pageSize = 100
        while (true) {
            val url = "https://infinitebacklog.net/api/user_collections" +
                "?user_id=$userId&completion=$completionType" +
                "&sort_field=completion_date&sort_order=desc&limit=$pageSize&offset=$offset"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/json")
            val page = try {
                if (conn.responseCode != 200) return results
                val array = JSONArray(conn.inputStream.bufferedReader().readText())
                (0 until array.length()).mapNotNull { i ->
                    val obj  = array.getJSONObject(i)
                    val name = obj.optJSONObject("game")?.optString("name")
                        ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val date = obj.optString("completion_date")
                        .takeIf { it.isNotEmpty() && it != "null" }
                    IBCompletion(name, date, isFullyCompleted = completionType == "completed")
                }
            } finally {
                conn.disconnect()
            }
            results += page
            if (page.size < pageSize) break
            offset += pageSize
        }
        return results
    }
}
