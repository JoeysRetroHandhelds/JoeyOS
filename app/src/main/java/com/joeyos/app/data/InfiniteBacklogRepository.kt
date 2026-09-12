package com.joeyos.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

data class IBCompletion(
    val gameName: String,
    val completionDate: String?,   // "2026-06-17" or null
    val isFullyCompleted: Boolean,
    val slug: String?,             // for linking to infinitebacklog.net/games/<slug>
    val coverId: String? = null,   // IGDB cover image id, e.g. "co9hb4.jpg"
    val releaseDate: Long? = null, // epoch seconds
    val rating: Int? = null,       // aggregated rating out of 100
    val playtimeMinutes: Int = 0   // the user's tracked playtime for this game
) {
    val gameUrl: String? get() = slug?.takeIf { it.isNotBlank() }?.let { "https://infinitebacklog.net/games/$it" }
    val coverUrl: String? get() = coverId?.takeIf { it.isNotBlank() }
        ?.let { "https://images.igdb.com/igdb/image/upload/t_cover_big/$it" }
    val releaseYear: Int? get() = releaseDate?.takeIf { it > 0 }
        ?.let { java.util.Calendar.getInstance().apply { timeInMillis = it * 1000 }.get(java.util.Calendar.YEAR) }
    val playtimeLabel: String? get() = playtimeMinutes.takeIf { it > 0 }?.let {
        if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m"
    }
}

data class IBResult(
    val completionsByYear: Map<Int, List<IBCompletion>>,
    val undated: List<IBCompletion>,
    val totalCompleted: Int,
    val fetchedAt: Long = System.currentTimeMillis()
) {
    val undatedCount: Int get() = undated.size
}

sealed class IBStatus {
    object NotConfigured                   : IBStatus()
    data class Success(val data: IBResult) : IBStatus()
    data class Error(val message: String)  : IBStatus()
}

data class IBProgressGame(
    val gameName: String,
    val slug: String?,
    val coverId: String?,
    val progress: Int,          // 0-100
    val playtimeMinutes: Int,
    val updatedAt: String?      // ISO timestamp of the last collection update ("last played")
) {
    val gameUrl: String? get() = slug?.takeIf { it.isNotBlank() }?.let { "https://infinitebacklog.net/games/$it" }
    val coverUrl: String? get() = coverId?.takeIf { it.isNotBlank() }
        ?.let { "https://images.igdb.com/igdb/image/upload/t_cover_big/$it" }
    val playtimeLabel: String? get() = playtimeMinutes.takeIf { it > 0 }?.let {
        if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m"
    }
    val lastPlayedDate: String? get() = updatedAt?.takeIf { it.length >= 10 }?.substring(0, 10)  // yyyy-MM-dd
}

sealed class IBProgressStatus {
    object NotConfigured                          : IBProgressStatus()
    data class Success(val games: List<IBProgressGame>, val fetchedAt: Long) : IBProgressStatus()
    data class Error(val message: String)         : IBProgressStatus()
}

class InfiniteBacklogRepository(context: Context) {

    private val appContext = context.applicationContext

    private val prefs by lazy {
        appContext.getSharedPreferences("infinitebacklog", Context.MODE_PRIVATE)
    }

    var username: String
        get() = prefs.getString("username", "") ?: ""
        set(v) = prefs.edit().putString("username", v).apply()

    private var cachedUserId: Int
        get() = prefs.getInt("user_id", -1)
        set(v) = prefs.edit().putInt("user_id", v).apply()

    val isConfigured: Boolean get() = username.isNotBlank()

    private val cacheTtlMs = 4 * 60 * 60 * 1000L

    val manualRefreshCooldownMs = 30 * 60 * 1000L // 30 minutes
    var lastManualRefreshAt: Long
        get() = prefs.getLong(KEY_LAST_MANUAL_REFRESH, 0L)
        private set(v) = prefs.edit().putLong(KEY_LAST_MANUAL_REFRESH, v).apply()

    @Volatile private var cacheLoaded = false
    private var cacheBacking: IBResult? = null

    private var cache: IBResult?
        get() {
            if (!cacheLoaded) synchronized(this) {
                if (!cacheLoaded) {
                    cacheBacking = prefs.getString(KEY_CACHE, null)?.let { deserializeResult(it) }
                    cacheLoaded = true
                }
            }
            return cacheBacking
        }
        set(value) = synchronized(this) {
            cacheBacking = value
            cacheLoaded = true
        }

    fun clearCache() {
        cache = null
        cachedUserId = -1
        ipBacking = null; ipLoaded = false
        prefs.edit().remove(KEY_CACHE).remove(KEY_INPROGRESS).apply()
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

                val withDate = deduped.filter { it.completionDate != null }
                val undated  = deduped.filter { it.completionDate == null }
                    .sortedBy { it.gameName.lowercase() }

                val byYear = withDate
                    .groupBy { it.completionDate!!.take(4).toIntOrNull() ?: 0 }
                    .mapValues { (_, v) -> v.sortedByDescending { it.completionDate } }

                val result = IBResult(
                    completionsByYear = byYear,
                    undated           = undated,
                    totalCompleted    = deduped.size
                )
                cache = result
                prefs.edit().putString(KEY_CACHE, serializeResult(result)).apply()
                if (forceRefresh) lastManualRefreshAt = System.currentTimeMillis()
                IBStatus.Success(result)
            } catch (e: UnknownHostException) {
                IBStatus.Error("No internet connection")
            } catch (e: SocketTimeoutException) {
                IBStatus.Error("Connection timed out — Infinite Backlog may be down")
            } catch (e: IOException) {
                IBStatus.Error("Network error: ${e.message ?: "connection failed"}")
            } catch (e: Exception) {
                IBStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ── In-progress games ("Almost there") ────────────────────────────────────
    @Volatile private var ipLoaded = false
    private var ipBacking: IBProgressStatus.Success? = null

    suspend fun fetchInProgress(forceRefresh: Boolean = false): IBProgressStatus {
        if (!isConfigured) return IBProgressStatus.NotConfigured
        if (!ipLoaded) synchronized(this) {
            if (!ipLoaded) { ipBacking = prefs.getString(KEY_INPROGRESS, null)?.let { deserializeProgress(it) }; ipLoaded = true }
        }
        ipBacking?.let { if (!forceRefresh && System.currentTimeMillis() - it.fetchedAt < cacheTtlMs) return it }

        return withContext(Dispatchers.IO) {
            try {
                var uid = cachedUserId
                if (uid < 0) { uid = resolveUserId(username) ?: return@withContext IBProgressStatus.Error("User \"$username\" not found"); cachedUserId = uid }
                val items = mutableListOf<IBProgressGame>()
                for (type in listOf("unfinished", "continuous")) items += fetchProgressParallel(uid, type)
                val seen = mutableSetOf<String>()
                val deduped = items.filter { seen.add(it.gameName) }.sortedByDescending { it.progress }
                val result = IBProgressStatus.Success(deduped, System.currentTimeMillis())
                ipBacking = result; ipLoaded = true
                prefs.edit().putString(KEY_INPROGRESS, serializeProgress(result)).apply()
                result
            } catch (e: UnknownHostException) {
                IBProgressStatus.Error("No internet connection")
            } catch (e: SocketTimeoutException) {
                IBProgressStatus.Error("Connection timed out — Infinite Backlog may be down")
            } catch (e: IOException) {
                IBProgressStatus.Error("Network error: ${e.message ?: "connection failed"}")
            } catch (e: Exception) {
                IBProgressStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    // The "unfinished" list can run to hundreds of games across many pages. Fetching them
    // one page at a time is the slow part, so fetch in parallel waves and stop at the first
    // short page (the end).
    private val pageSize = 100
    private val waveSize = 6

    private suspend fun fetchProgressParallel(userId: Int, completionType: String): List<IBProgressGame> = coroutineScope {
        val out = mutableListOf<IBProgressGame>()
        var wave = 0
        while (wave * waveSize < MAX_PAGES) {
            val pages = (0 until waveSize).map { k ->
                val offset = (wave * waveSize + k) * pageSize
                async(Dispatchers.IO) { fetchOneProgressPage(userId, completionType, offset) }
            }.awaitAll()
            pages.forEach { out += it }
            if (pages.any { it.size < pageSize }) break   // reached the end
            wave++
        }
        out
    }

    private fun fetchOneProgressPage(userId: Int, completionType: String, offset: Int): List<IBProgressGame> {
        val url = "https://infinitebacklog.net/api/user_collections" +
            "?user_id=$userId&completion=${enc(completionType)}&limit=$pageSize&offset=$offset"
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.applyTimeouts(); conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return emptyList()
            val array = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val game = obj.optJSONObject("game")
                val name = game?.optString("name")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                IBProgressGame(
                    gameName = name,
                    slug = game.optString("slug").takeIf { it.isNotEmpty() && it != "null" },
                    coverId = game.optJSONObject("cover")?.optString("url")?.takeIf { it.isNotEmpty() && it != "null" },
                    progress = obj.optInt("progress", 0),
                    playtimeMinutes = obj.optInt("playtime", 0),
                    updatedAt = obj.optString("updated_at").takeIf { it.isNotEmpty() && it != "null" }
                )
            }
        } finally { conn.disconnect() }
    }

    private fun serializeProgress(r: IBProgressStatus.Success): String {
        val obj = JSONObject().put("fetchedAt", r.fetchedAt)
        val arr = JSONArray()
        r.games.forEach { g ->
            arr.put(JSONObject().apply {
                put("gameName", g.gameName); put("slug", g.slug ?: JSONObject.NULL)
                put("coverId", g.coverId ?: JSONObject.NULL); put("progress", g.progress); put("playtime", g.playtimeMinutes)
                put("updatedAt", g.updatedAt ?: JSONObject.NULL)
            })
        }
        return obj.put("games", arr).toString()
    }

    private fun deserializeProgress(json: String): IBProgressStatus.Success? = try {
        val obj = JSONObject(json); val arr = obj.getJSONArray("games")
        val games = (0 until arr.length()).map { i ->
            val g = arr.getJSONObject(i)
            IBProgressGame(
                gameName = g.getString("gameName"),
                slug = if (g.isNull("slug")) null else g.getString("slug"),
                coverId = if (g.isNull("coverId")) null else g.getString("coverId"),
                progress = g.getInt("progress"), playtimeMinutes = g.optInt("playtime", 0),
                updatedAt = if (g.isNull("updatedAt")) null else g.getString("updatedAt")
            )
        }
        IBProgressStatus.Success(games, obj.getLong("fetchedAt"))
    } catch (_: Exception) { null }

    private fun completionToJson(c: IBCompletion) = JSONObject().apply {
        put("gameName",         c.gameName)
        put("completionDate",   c.completionDate ?: JSONObject.NULL)
        put("isFullyCompleted", c.isFullyCompleted)
        put("slug",             c.slug ?: JSONObject.NULL)
        put("coverId",          c.coverId ?: JSONObject.NULL)
        put("releaseDate",      c.releaseDate ?: JSONObject.NULL)
        put("rating",           c.rating ?: JSONObject.NULL)
        put("playtime",         c.playtimeMinutes)
    }

    private fun jsonToCompletion(c: JSONObject) = IBCompletion(
        gameName         = c.getString("gameName"),
        completionDate   = if (c.isNull("completionDate")) null else c.getString("completionDate"),
        isFullyCompleted = c.getBoolean("isFullyCompleted"),
        slug             = if (c.isNull("slug")) null else c.getString("slug"),
        coverId          = if (c.isNull("coverId")) null else c.getString("coverId"),
        releaseDate      = if (c.isNull("releaseDate")) null else c.getLong("releaseDate"),
        rating           = if (c.isNull("rating")) null else c.getInt("rating"),
        playtimeMinutes  = c.optInt("playtime", 0)
    )

    private fun serializeResult(result: IBResult): String {
        val obj = JSONObject()
        obj.put("fetchedAt",      result.fetchedAt)
        obj.put("totalCompleted", result.totalCompleted)
        val byYear = JSONObject()
        result.completionsByYear.forEach { (year, games) ->
            byYear.put(year.toString(), JSONArray().apply { games.forEach { put(completionToJson(it)) } })
        }
        obj.put("completionsByYear", byYear)
        obj.put("undated", JSONArray().apply { result.undated.forEach { put(completionToJson(it)) } })
        return obj.toString()
    }

    private fun deserializeResult(json: String): IBResult? = try {
        val obj       = JSONObject(json)
        val byYearObj = obj.getJSONObject("completionsByYear")
        val byYear    = mutableMapOf<Int, List<IBCompletion>>()
        byYearObj.keys().forEach { yearStr ->
            val year = yearStr.toIntOrNull() ?: return@forEach
            val arr  = byYearObj.getJSONArray(yearStr)
            byYear[year] = (0 until arr.length()).map { jsonToCompletion(arr.getJSONObject(it)) }
        }
        val undatedArr = obj.optJSONArray("undated") ?: JSONArray()
        IBResult(
            completionsByYear = byYear,
            undated           = (0 until undatedArr.length()).map { jsonToCompletion(undatedArr.getJSONObject(it)) },
            totalCompleted    = obj.getInt("totalCompleted"),
            fetchedAt         = obj.getLong("fetchedAt")
        )
    } catch (_: Exception) { null }

    private fun resolveUserId(username: String): Int? {
        val conn = URL("https://infinitebacklog.net/api/users/username/${enc(username)}")
            .openConnection() as HttpURLConnection
        return try {
            conn.applyTimeouts()
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
                .let { JSONObject(it).optInt("id", -1) }
                .takeIf { it > 0 }
        } finally {
            conn.disconnect()
        }
    }

    private fun HttpURLConnection.applyTimeouts() {
        connectTimeout = 10_000
        readTimeout    = 10_000
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val KEY_CACHE               = "ib_cache_json_v6"
        private const val KEY_INPROGRESS          = "ib_inprogress_json_v2"
        private const val KEY_LAST_MANUAL_REFRESH = "ib_last_manual_refresh"
        private const val MAX_PAGES = 100
    }

    private fun fetchAllPages(userId: Int, completionType: String): List<IBCompletion> {
        val results = mutableListOf<IBCompletion>()
        var offset = 0
        val pageSize = 100
        var pagesFetched = 0
        while (pagesFetched < MAX_PAGES) {
            val url = "https://infinitebacklog.net/api/user_collections" +
                "?user_id=$userId&completion=${enc(completionType)}" +
                "&sort_field=completion_date&sort_order=desc&limit=$pageSize&offset=$offset"
            val conn = URL(url).openConnection() as HttpURLConnection
            val page = try {
                conn.applyTimeouts()
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) return results
                val body  = conn.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(body)
                (0 until array.length()).mapNotNull { i ->
                    val obj  = array.getJSONObject(i)
                    val game = obj.optJSONObject("game")
                    val name = game?.optString("name")
                        ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val slug = game.optString("slug").takeIf { it.isNotEmpty() && it != "null" }
                    val cover = game.optJSONObject("cover")?.optString("url")
                        ?.takeIf { it.isNotEmpty() && it != "null" }
                    val releaseDate = game.optLong("first_release_date", 0).takeIf { it > 0 }
                    val rating = game.optDouble("aggregated_rating", 0.0).takeIf { it > 0 }?.let { Math.round(it).toInt() }
                    val date = obj.optString("completion_date")
                        .takeIf { it.isNotEmpty() && it != "null" }
                    IBCompletion(name, date, isFullyCompleted = completionType == "completed", slug = slug,
                        coverId = cover, releaseDate = releaseDate, rating = rating,
                        playtimeMinutes = obj.optInt("playtime", 0))
                }
            } finally {
                conn.disconnect()
            }
            results += page
            pagesFetched++
            if (page.size < pageSize) break
            offset += pageSize
        }
        return results
    }
}
