package com.joeyos.app.data

import android.content.Context
import android.content.SharedPreferences
import com.joeyos.app.AppLog
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RAAward(
    val title: String,
    val consoleName: String,
    val awardType: String,       // "Game Beaten", "Mastery/Completion"
    val awardDataExtra: Int,     // 1 = hardcore, 0 = softcore
    val awardedAt: Date,
    val imageIcon: String,
    val gameId: Int              // for linking to retroachievements.org/game/<id>
) {
    val gameUrl: String? get() = if (gameId > 0) "https://retroachievements.org/game/$gameId" else null
    val iconUrl: String? get() = imageIcon.takeIf { it.isNotBlank() }?.let { "https://media.retroachievements.org$it" }
}

data class RAAwardsResult(
    val awards: List<RAAward>,
    val totalAwardsCount: Int,
    val masteryAwardsCount: Int,
    val beatenHardcoreAwardsCount: Int,
    val beatenSoftcoreAwardsCount: Int,
    val fetchedAt: Long = System.currentTimeMillis()
)

sealed class RAResult {
    data class Success(val data: RAAwardsResult) : RAResult()
    data class Error(val message: String) : RAResult()
    object NotConfigured : RAResult()
}

data class RAProgressGame(
    val gameId: Int,
    val title: String,
    val consoleName: String,
    val numAwarded: Int,
    val maxPossible: Int,
    val highestAwardKind: String?,  // "beaten-softcore", "beaten-hardcore", "mastered", "completed", or null
    val imageIcon: String = ""
) {
    val percent: Float get() = if (maxPossible > 0) numAwarded.toFloat() / maxPossible else 0f
    val isBeaten: Boolean get() = highestAwardKind != null && highestAwardKind.isNotBlank() &&
        !highestAwardKind.equals("none", ignoreCase = true)
    val gameUrl: String get() = "https://retroachievements.org/game/$gameId"
    val iconUrl: String? get() = imageIcon.takeIf { it.isNotBlank() }?.let { "https://media.retroachievements.org$it" }
}

data class RARecentGame(
    val gameId: Int,
    val title: String,
    val consoleName: String,
    val imageIcon: String,
    val lastPlayedMs: Long,
    val numAchieved: Int,
    val numPossible: Int
) {
    val iconUrl: String? get() = imageIcon.takeIf { it.isNotBlank() }?.let { "https://media.retroachievements.org$it" }
    val gameUrl: String? get() = if (gameId > 0) "https://retroachievements.org/game/$gameId" else null
}

sealed class RAProgressResult {
    data class Success(val games: List<RAProgressGame>, val fetchedAt: Long) : RAProgressResult()
    data class Error(val message: String) : RAProgressResult()
    object NotConfigured : RAProgressResult()
}

/** One achievement in a game, with whether you've earned it. */
data class RAAchievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    val badgeName: String,
    val earned: Boolean,
    val earnedHardcore: Boolean,
    val dateEarned: Date?,
    val numAwarded: Int,
    val type: String?,           // "progression", "win_condition", "missable" or null
    val displayOrder: Int,
    val numAwardedHardcore: Int = 0,
    /** RetroPoints: RA's difficulty-weighted score for it. */
    val trueRatio: Int = 0
) {
    val badgeUrl: String get() = "https://media.retroachievements.org/Badge/$badgeName.png"
    val lockedBadgeUrl: String get() = "https://media.retroachievements.org/Badge/${badgeName}_lock.png"
    val isMissable: Boolean get() = type == "missable"
    /** Counts toward beating the game (progression or the win condition). */
    val isToBeat: Boolean get() = type == "progression" || type == "win_condition"
}

/** A comment left on an achievement (where players post tips). */
data class RAComment(val user: String, val text: String, val submitted: Date?)

/** What RetroAchievements says you're playing now, and its live status line. */
data class RANowPlaying(val gameId: Int, val richPresence: String)

/** A game's achievements and your progress through them. */
data class RAGameProgress(
    val gameId: Int,
    val title: String,
    val consoleName: String,
    val imageIcon: String,
    val numDistinctPlayers: Int,
    val consoleId: Int = 0,
    val achievements: List<RAAchievement>,
    val fetchedAt: Long = System.currentTimeMillis(),
    /** Your total time in the game, in seconds (0 when RA doesn't know). */
    val userPlaytimeSeconds: Int = 0,
    /** "beaten-softcore", "beaten-hardcore", "completed", "mastered", or null. */
    val highestAward: String? = null,
    val genre: String? = null,
    val developer: String? = null,
    val released: String? = null
) {
    val iconUrl: String? get() = imageIcon.takeIf { it.isNotBlank() }?.let { "https://media.retroachievements.org$it" }
    val gameUrl: String get() = "https://retroachievements.org/game/$gameId"
    val earnedCount: Int get() = achievements.count { it.earned }
    val totalPoints: Int get() = achievements.sumOf { it.points }
    val earnedPoints: Int get() = achievements.filter { it.earned }.sumOf { it.points }
    val earnedHardcoreCount: Int get() = achievements.count { it.earnedHardcore }
    val toBeat: List<RAAchievement> get() = achievements.filter { it.isToBeat }
}

class RetroAchievementsRepository(context: Context) {

    private val appContext = context.applicationContext

    // Lazy: creating an EncryptedSharedPreferences instance hits the Android keystore, which
    // is slow enough to notice. This repository is constructed in the ViewModel factory on
    // the main thread at every app start, so defer the cost until credentials are actually
    // read — i.e. until the user opens the Achievements tab.
    //
    // Opening the encrypted store throws (AEADBadTagException / KeyStoreException etc.) when
    // the keystore key no longer matches the file — e.g. credentials restored from a backup
    // onto a new device, or a corrupted keystore entry. That used to crash the app on opening
    // the Achievements tab. Recover by wiping the unreadable store (the user just re-enters
    // their API key); if the keystore is unusable altogether, fall back to plain private prefs.
    private val prefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            AppLog.w("RetroAchievements", "Saved login unreadable, resetting it", e)
            try {
                appContext.deleteSharedPreferences(CREDENTIALS_FILE)
                createEncryptedPrefs()
            } catch (e2: Exception) {
                AppLog.e("RetroAchievements", "Encrypted storage unusable, using private prefs", e2)
                appContext.getSharedPreferences("${CREDENTIALS_FILE}_plain", Context.MODE_PRIVATE)
            }
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            CREDENTIALS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val cachePrefs by lazy {
        appContext.getSharedPreferences("ra_cache", Context.MODE_PRIVATE)
    }

    private val cacheTtlMs = 24 * 60 * 60 * 1000L // 24 hours

    val manualRefreshCooldownMs = 30 * 60 * 1000L // 30 minutes
    var lastManualRefreshAt: Long
        get() = cachePrefs.getLong(KEY_LAST_MANUAL_REFRESH, 0L)
        private set(v) = cachePrefs.edit().putLong(KEY_LAST_MANUAL_REFRESH, v).apply()

    @Volatile private var cacheLoaded = false
    private var cachedResultBacking: RAAwardsResult? = null

    private var cachedResult: RAAwardsResult?
        get() {
            if (!cacheLoaded) synchronized(this) {
                if (!cacheLoaded) {
                    cachedResultBacking = cachePrefs.getString(KEY_CACHE, null)
                        ?.let { deserializeResult(it) }
                    cacheLoaded = true
                }
            }
            return cachedResultBacking
        }
        set(value) = synchronized(this) {
            cachedResultBacking = value
            cacheLoaded = true
        }

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    val isConfigured: Boolean
        get() = username.isNotBlank() && apiKey.isNotBlank()

    fun clearCache() {
        cachedResult = null
        progressBacking = null
        progressLoaded = false
        playtimes.clear(); playtimesLoaded = false; playtimesFetchedAt = 0L
        gameInfoCache.clear()
        cachePrefs.edit().remove(KEY_CACHE).remove(KEY_PROGRESS).remove(KEY_PLAYTIMES).apply()
    }

    suspend fun fetchAwards(forceRefresh: Boolean = false): RAResult {
        if (!isConfigured) return RAResult.NotConfigured

        cachedResult?.let { cached ->
            if (!forceRefresh && System.currentTimeMillis() - cached.fetchedAt < cacheTtlMs)
                return RAResult.Success(cached)
        }

        return withContext(Dispatchers.IO) {
            try {
                val url = URL(
                    "https://retroachievements.org/API/API_GetUserAwards.php" +
                    "?u=${enc(username)}&y=${enc(apiKey)}"
                )
                val conn = url.openConnection() as HttpURLConnection
                val body = try {
                    conn.connectTimeout = 10_000
                    conn.readTimeout    = 10_000
                    conn.setRequestProperty("User-Agent", "JoeyOS/1.0")

                    val code = conn.responseCode
                    if (code != 200) {
                        return@withContext RAResult.Error(
                            when (code) {
                                401, 403 -> "Invalid username or API key"
                                404      -> "User \"$username\" not found"
                                429      -> "Too many requests — try again in a few minutes"
                                in 500..599 -> "RetroAchievements server error — try again later"
                                else     -> "RetroAchievements returned an unexpected error (HTTP $code)"
                            }
                        )
                    }
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    conn.disconnect()
                }

                val json = JSONObject(body)

                json.optString("Error", "").takeIf { it.isNotBlank() }?.let { apiError ->
                    return@withContext RAResult.Error(
                        if (apiError.contains("credentials", ignoreCase = true) ||
                            apiError.contains("key", ignoreCase = true))
                            "Invalid username or API key"
                        else apiError
                    )
                }

                val awardsArray = json.getJSONArray("VisibleUserAwards")
                val dateFormat  = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                val awards = mutableListOf<RAAward>()

                for (i in 0 until awardsArray.length()) {
                    val obj = awardsArray.getJSONObject(i)
                    val type = obj.optString("AwardType", "")
                    if (type != "Game Beaten" && type != "Mastery/Completion") continue
                    val dateStr = obj.optString("AwardedAt", "")
                    val date = runCatching { dateFormat.parse(dateStr) }.getOrNull() ?: continue
                    awards += RAAward(
                        title            = obj.optString("Title", "Unknown"),
                        consoleName      = obj.optString("ConsoleName", ""),
                        awardType        = type,
                        awardDataExtra   = obj.optInt("AwardDataExtra", 0),
                        awardedAt        = date,
                        imageIcon        = obj.optString("ImageIcon", ""),
                        // For game awards, AwardData holds the game ID.
                        gameId           = obj.optString("AwardData", "").toIntOrNull() ?: 0
                    )
                }

                val result = RAAwardsResult(
                    awards                    = awards.sortedByDescending { it.awardedAt },
                    totalAwardsCount          = json.optInt("TotalAwardsCount", 0),
                    masteryAwardsCount        = json.optInt("MasteryAwardsCount", 0),
                    beatenHardcoreAwardsCount = json.optInt("BeatenHardcoreAwardsCount", 0),
                    beatenSoftcoreAwardsCount = json.optInt("BeatenSoftcoreAwardsCount", 0)
                )
                cachedResult = result
                cachePrefs.edit().putString(KEY_CACHE, serializeResult(result)).apply()
                if (forceRefresh) lastManualRefreshAt = System.currentTimeMillis()
                RAResult.Success(result)
            } catch (e: UnknownHostException) {
                RAResult.Error("No internet connection")
            } catch (e: SocketTimeoutException) {
                RAResult.Error("Connection timed out — RetroAchievements may be down")
            } catch (e: IOException) {
                RAResult.Error("Network error: ${e.message ?: "connection failed"}")
            } catch (e: Exception) {
                RAResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ── Completion progress (for "almost there" games) ────────────────────────
    @Volatile private var progressLoaded = false
    private var progressBacking: RAProgressResult.Success? = null

    suspend fun fetchProgress(forceRefresh: Boolean = false): RAProgressResult {
        if (!isConfigured) return RAProgressResult.NotConfigured

        if (!progressLoaded) synchronized(this) {
            if (!progressLoaded) {
                progressBacking = cachePrefs.getString(KEY_PROGRESS, null)?.let { deserializeProgress(it) }
                progressLoaded = true
            }
        }
        progressBacking?.let {
            if (!forceRefresh && System.currentTimeMillis() - it.fetchedAt < cacheTtlMs) return it
        }

        return withContext(Dispatchers.IO) {
            try {
                val all = mutableListOf<RAProgressGame>()
                var offset = 0
                val pageSize = 500
                while (true) {
                    val url = URL(
                        "https://retroachievements.org/API/API_GetUserCompletionProgress.php" +
                        "?u=${enc(username)}&y=${enc(apiKey)}&c=$pageSize&o=$offset"
                    )
                    val conn = url.openConnection() as HttpURLConnection
                    val body = try {
                        conn.connectTimeout = 10_000
                        conn.readTimeout    = 15_000
                        conn.setRequestProperty("User-Agent", "JoeyOS/1.0")
                        val code = conn.responseCode
                        if (code != 200) return@withContext RAProgressResult.Error(
                            when (code) {
                                401, 403 -> "Invalid username or API key"
                                429      -> "Too many requests — try again in a few minutes"
                                in 500..599 -> "RetroAchievements server error — try again later"
                                else     -> "RetroAchievements error (HTTP $code)"
                            }
                        )
                        conn.inputStream.bufferedReader().use { it.readText() }
                    } finally { conn.disconnect() }

                    val json = JSONObject(body)
                    val results = json.optJSONArray("Results") ?: break
                    for (i in 0 until results.length()) {
                        val g = results.getJSONObject(i)
                        all += RAProgressGame(
                            gameId           = g.optInt("GameID", 0),
                            title            = g.optString("Title", "Unknown"),
                            consoleName      = g.optString("ConsoleName", ""),
                            numAwarded       = g.optInt("NumAwarded", 0),
                            maxPossible      = g.optInt("MaxPossible", 0),
                            highestAwardKind = g.optString("HighestAwardKind", "").takeIf { it.isNotBlank() && it != "null" },
                            imageIcon        = g.optString("ImageIcon", "")
                        )
                    }
                    val total = json.optInt("Total", all.size)
                    offset += pageSize
                    if (results.length() < pageSize || offset >= total) break
                }

                val result = RAProgressResult.Success(all, System.currentTimeMillis())
                progressBacking = result
                progressLoaded = true
                cachePrefs.edit().putString(KEY_PROGRESS, serializeProgress(result)).apply()
                result
            } catch (e: UnknownHostException) {
                RAProgressResult.Error("No internet connection")
            } catch (e: SocketTimeoutException) {
                RAProgressResult.Error("Connection timed out — RetroAchievements may be down")
            } catch (e: IOException) {
                RAProgressResult.Error("Network error: ${e.message ?: "connection failed"}")
            } catch (e: Exception) {
                RAProgressResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun serializeProgress(r: RAProgressResult.Success): String {
        val obj = JSONObject()
        obj.put("fetchedAt", r.fetchedAt)
        val arr = JSONArray()
        r.games.forEach { g ->
            arr.put(JSONObject().apply {
                put("gameId", g.gameId); put("title", g.title); put("consoleName", g.consoleName)
                put("numAwarded", g.numAwarded); put("maxPossible", g.maxPossible)
                put("highestAwardKind", g.highestAwardKind ?: JSONObject.NULL)
                put("imageIcon", g.imageIcon)
            })
        }
        obj.put("games", arr)
        return obj.toString()
    }

    private fun deserializeProgress(json: String): RAProgressResult.Success? = try {
        val obj = JSONObject(json)
        val arr = obj.getJSONArray("games")
        val games = (0 until arr.length()).map { i ->
            val g = arr.getJSONObject(i)
            RAProgressGame(
                gameId = g.getInt("gameId"), title = g.getString("title"),
                consoleName = g.getString("consoleName"), numAwarded = g.getInt("numAwarded"),
                maxPossible = g.getInt("maxPossible"),
                highestAwardKind = if (g.isNull("highestAwardKind")) null else g.getString("highestAwardKind"),
                imageIcon = g.optString("imageIcon", "")
            )
        }
        RAProgressResult.Success(games, obj.getLong("fetchedAt"))
    } catch (_: Exception) { null }

    // Per-game extra info (genre/developer/publisher/released), fetched lazily when a game's
    // detail sheet opens. Cached in-memory for the session.
    private val gameInfoCache = java.util.concurrent.ConcurrentHashMap<Int, List<Pair<String, String>>>()

    suspend fun fetchGameInfo(gameId: Int): List<Pair<String, String>> {
        if (gameId <= 0 || !isConfigured) return emptyList()
        gameInfoCache[gameId]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                // GetGameInfoAndUserProgress carries the metadata AND this user's total playtime.
                val conn = URL("https://retroachievements.org/API/API_GetGameInfoAndUserProgress.php" +
                    "?u=${enc(username)}&g=$gameId&y=${enc(apiKey)}").openConnection() as HttpURLConnection
                val body = try {
                    conn.connectTimeout = 10_000; conn.readTimeout = 10_000
                    conn.setRequestProperty("User-Agent", "JoeyOS/1.0")
                    if (conn.responseCode != 200) return@withContext emptyList()
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally { conn.disconnect() }
                val j = JSONObject(body)
                val list = buildList {
                    formatPlaytime(j.optInt("UserTotalPlaytime", 0))?.let { add("Playtime" to it) }
                    j.optString("Genre").takeIf { it.isNotBlank() && it != "null" }?.let { add("Genre" to it) }
                    j.optString("Developer").takeIf { it.isNotBlank() && it != "null" }?.let { add("Developer" to it) }
                    j.optString("Publisher").takeIf { it.isNotBlank() && it != "null" }?.let { add("Publisher" to it) }
                    j.optString("Released").takeIf { it.isNotBlank() && it != "null" }?.let { add("Released" to it) }
                }
                gameInfoCache[gameId] = list
                list
            } catch (_: Exception) { emptyList() }
        }
    }

    /** Seconds → "Xh Ym" / "Ym", or null when zero. */
    private fun formatPlaytime(seconds: Int): String? = seconds.takeIf { it > 0 }?.let {
        val m = it / 60
        if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    // ── Profile points + recently played ─────────────────────────────────────
    @Volatile private var pointsCache: Int? = null
    suspend fun fetchPoints(): Int? {
        if (!isConfigured) return null
        pointsCache?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL("https://retroachievements.org/API/API_GetUserProfile.php?u=${enc(username)}&y=${enc(apiKey)}")
                    .openConnection() as HttpURLConnection
                val body = try {
                    conn.connectTimeout = 10_000; conn.readTimeout = 10_000
                    conn.setRequestProperty("User-Agent", "JoeyOS/1.0")
                    if (conn.responseCode != 200) return@withContext null
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally { conn.disconnect() }
                val j = JSONObject(body)
                // Softcore-only accounts have 0 hardcore points; softcore points are the meaningful total.
                val pts = j.optInt("TotalPoints", 0).takeIf { it > 0 } ?: j.optInt("TotalSoftcorePoints", 0)
                pointsCache = pts; pts
            } catch (_: Exception) { null }
        }
    }

    private var recentCache: List<RARecentGame>? = null
    private var recentAt = 0L
    /** Your recently played games, newest first: RA's maximum of 50, so "See all" has them all. */
    suspend fun fetchRecentlyPlayed(count: Int = 50, forceRefresh: Boolean = false): List<RARecentGame> {
        if (!isConfigured) return emptyList()
        recentCache?.let { if (!forceRefresh && System.currentTimeMillis() - recentAt < 30 * 60_000L) return it }
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL("https://retroachievements.org/API/API_GetUserRecentlyPlayedGames.php?u=${enc(username)}&y=${enc(apiKey)}&c=$count")
                    .openConnection() as HttpURLConnection
                val body = try {
                    conn.connectTimeout = 10_000; conn.readTimeout = 10_000
                    conn.setRequestProperty("User-Agent", "JoeyOS/1.0")
                    if (conn.responseCode != 200) return@withContext (recentCache ?: emptyList())
                    conn.inputStream.bufferedReader().use { it.readText() }
                } finally { conn.disconnect() }
                val arr = JSONArray(body)
                // RA times are UTC: read as local they were off by the time-zone offset.
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                val list = (0 until arr.length()).map { i ->
                    val g = arr.getJSONObject(i)
                    RARecentGame(
                        gameId = g.optInt("GameID", 0), title = g.optString("Title", "Unknown"),
                        consoleName = g.optString("ConsoleName", ""), imageIcon = g.optString("ImageIcon", ""),
                        lastPlayedMs = runCatching { fmt.parse(g.optString("LastPlayed"))?.time ?: 0L }.getOrDefault(0L),
                        numAchieved = g.optInt("NumAchieved", 0), numPossible = g.optInt("NumPossibleAchievements", 0)
                    )
                }
                recentCache = list; recentAt = System.currentTimeMillis(); list
            } catch (_: Exception) { recentCache ?: emptyList() }
        }
    }

    // Per-game playtime (seconds), for the Stats hours aggregates. One API call per game, so
    // fetched in parallel, cached to disk, and only missing ids are fetched on later runs.
    @Volatile private var playtimesLoaded = false
    private var playtimes: MutableMap<Int, Int> = mutableMapOf()
    private var playtimesFetchedAt = 0L

    suspend fun fetchGamePlaytimes(ids: List<Int>, forceRefresh: Boolean = false): Map<Int, Int> {
        if (!isConfigured) return emptyMap()
        if (!playtimesLoaded) synchronized(this) {
            if (!playtimesLoaded) {
                cachePrefs.getString(KEY_PLAYTIMES, null)?.let { s ->
                    runCatching {
                        val o = JSONObject(s); playtimesFetchedAt = o.optLong("fetchedAt")
                        val m = o.getJSONObject("map"); m.keys().forEach { playtimes[it.toInt()] = m.getInt(it) }
                    }
                }
                playtimesLoaded = true
            }
        }
        val wanted = ids.filter { it > 0 }.distinct()
        val fresh = System.currentTimeMillis() - playtimesFetchedAt < cacheTtlMs
        val missing = if (forceRefresh || !fresh) wanted else wanted.filter { it !in playtimes }
        if (missing.isEmpty()) return playtimes.toMap()

        return withContext(Dispatchers.IO) {
            var anyStored = false
            coroutineScope {
                // Modest concurrency + a pause between waves: RA rate-limits bursts, and a
                // throttled request used to come back "0" and get cached as real 0 playtime.
                for (chunk in missing.chunked(4)) {
                    chunk.map { id -> async { id to fetchOnePlaytime(id) } }.awaitAll()
                        .forEach { (id, sec) -> if (sec != null) { playtimes[id] = sec; anyStored = true } }  // null = failed, retry later
                    Thread.sleep(250)
                }
            }
            if (anyStored) {
                playtimesFetchedAt = System.currentTimeMillis()
                val out = JSONObject().put("fetchedAt", playtimesFetchedAt)
                val m = JSONObject(); playtimes.forEach { (k, v) -> m.put(k.toString(), v) }
                cachePrefs.edit().putString(KEY_PLAYTIMES, out.put("map", m).toString()).apply()
            }
            playtimes.toMap()
        }
    }

    /** Seconds of playtime, or null when the request failed / was throttled (so it isn't cached as 0). */
    private fun fetchOnePlaytime(gameId: Int, retry: Boolean = true): Int? {
        val conn = URL("https://retroachievements.org/API/API_GetGameInfoAndUserProgress.php" +
            "?u=${enc(username)}&g=$gameId&y=${enc(apiKey)}").openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "JoeyOS/1.0")
            when (val code = conn.responseCode) {
                200 -> JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optInt("UserTotalPlaytime", 0)
                429 -> { if (retry) { Thread.sleep(1500); fetchOnePlaytime(gameId, retry = false) } else null }
                else -> null
            }
        } catch (_: Exception) { null } finally { conn.disconnect() }
    }

    // ── The game being played, and a game's achievement list (the second screen) ──────────

    /** RA's dates are UTC ("yyyy-MM-dd HH:mm:ss"). */
    private fun parseRaUtc(s: String?): Date? = s?.takeIf { it.isNotBlank() && it != "null" }?.let {
        runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(it)
        }.getOrNull()
    }

    private fun getJson(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 10_000; conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "JoeyOS/1.0")
            if (conn.responseCode != 200) { AppLog.w("RetroAchievements", "HTTP ${conn.responseCode} from ${url.substringBefore('?')}"); null }
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            AppLog.w("RetroAchievements", "Request failed: ${url.substringBefore('?')}", e); null
        } finally { conn.disconnect() }
    }

    /**
     * The game you're playing right now, as RetroAchievements sees it: your last game, provided
     * RA saw you playing it since [sinceMs]. Null until an emulator signed in to RetroAchievements
     * has started the game. Never cached: it's the live answer.
     *
     * Only ten seconds' grace for clocks, and none for [previousGameId] (the last session's game):
     * with a minute's grace, switching from Aladdin to another game showed Aladdin for 15 seconds,
     * because RA had seen it within the minute (found on device).
     */
    suspend fun fetchNowPlaying(sinceMs: Long, previousGameId: Int? = null): RANowPlaying? {
        if (!isConfigured) return null
        return withContext(Dispatchers.IO) {
            val body = getJson("https://retroachievements.org/API/API_GetUserSummary.php" +
                "?u=${enc(username)}&y=${enc(apiKey)}&g=1&a=0") ?: return@withContext null
            runCatching {
                val j = JSONObject(body)
                val last = j.optJSONArray("RecentlyPlayed")?.optJSONObject(0) ?: return@runCatching null
                val id = last.optInt("GameID", 0).takeIf { it > 0 } ?: return@runCatching null
                val at = parseRaUtc(last.optString("LastPlayed"))?.time ?: return@runCatching null
                val grace = if (id == previousGameId) 0L else 10_000L
                if (at < sinceMs - grace) return@runCatching null
                val rp = j.optString("RichPresenceMsg").takeIf { it.isNotBlank() && it != "null" && j.optInt("LastGameID") == id }
                RANowPlaying(id, rp.orEmpty())
            }.getOrNull()
        }
    }

    /** An achievement's comments, newest first. Empty when there are none or it can't be read. */
    suspend fun fetchAchievementComments(achievementId: Int): List<RAComment> {
        if (!isConfigured) return emptyList()
        return withContext(Dispatchers.IO) {
            val body = getJson("https://retroachievements.org/API/API_GetComments.php" +
                "?y=${enc(apiKey)}&t=2&i=$achievementId&c=100&sort=-submitted") ?: return@withContext emptyList()
            runCatching {
                val arr = JSONObject(body).optJSONArray("Results") ?: return@runCatching emptyList()
                (0 until arr.length()).map { i ->
                    val c = arr.getJSONObject(i)
                    RAComment(c.optString("User"), c.optString("CommentText"), parseIsoOrRa(c.optString("Submitted")))
                }.sortedByDescending { it.submitted }
            }.getOrElse { AppLog.w("RetroAchievements", "Couldn't read comments for $achievementId", it); emptyList() }
        }
    }

    /** Comment dates come as ISO 8601 ("2024-01-02T03:04:05.000000Z") or RA's own form. */
    private fun parseIsoOrRa(s: String?): Date? = s?.takeIf { it.isNotBlank() && it != "null" }?.let {
        runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(it.take(19))
        }.getOrNull() ?: parseRaUtc(it)
    }

    /**
     * RetroAchievements' own game titles for a console (id → title), used to name romhacks: a
     * RAPatches archive is filed under its hack's RA game id. One request per console, kept a
     * week on disk. Empty when not signed in or offline.
     */
    suspend fun fetchGameTitles(consoleIds: List<Int>): Map<Int, String> {
        if (!isConfigured) return emptyMap()
        return withContext(Dispatchers.IO) {
            val out = mutableMapOf<Int, String>()
            for (cid in consoleIds) {
                val key = "ra_titles_$cid"
                val cached = cachePrefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                val fresh = cached != null && System.currentTimeMillis() - cached.optLong("fetchedAt") < 7 * 24 * 3_600_000L
                val map = if (fresh) cached!!.getJSONObject("map") else {
                    val body = getJson("https://retroachievements.org/API/API_GetGameList.php" +
                        "?y=${enc(apiKey)}&i=$cid&f=1&h=0")
                    val parsed = body?.let { b -> runCatching {
                        val arr = JSONArray(b); val m = JSONObject()
                        for (i in 0 until arr.length()) arr.getJSONObject(i).let { m.put(it.optInt("ID").toString(), it.optString("Title")) }
                        m
                    }.getOrNull() }
                    if (parsed != null) {
                        cachePrefs.edit().putString(key, JSONObject().put("fetchedAt", System.currentTimeMillis()).put("map", parsed).toString()).apply()
                        parsed
                    } else cached?.optJSONObject("map") ?: JSONObject()
                }
                map.keys().forEach { k -> k.toIntOrNull()?.let { out[it] = map.getString(k) } }
            }
            out
        }
    }

    /** RetroAchievements' game consoles (id, name), A to Z, for searching the whole catalogue. Kept a week. */
    suspend fun fetchConsoles(): List<Pair<Int, String>> {
        if (!isConfigured) return emptyList()
        return withContext(Dispatchers.IO) {
            val key = "ra_consoles"
            val cached = cachePrefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val fresh = cached != null && System.currentTimeMillis() - cached.optLong("fetchedAt") < 7 * 24 * 3_600_000L
            val arr = if (fresh) cached!!.getJSONArray("list") else {
                getJson("https://retroachievements.org/API/API_GetConsoleIDs.php?y=${enc(apiKey)}&a=1&g=1")
                    ?.let { runCatching { JSONArray(it) }.getOrNull() }
                    ?.also { cachePrefs.edit().putString(key, JSONObject().put("fetchedAt", System.currentTimeMillis()).put("list", it).toString()).apply() }
                    ?: cached?.optJSONArray("list") ?: JSONArray()
            }
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> o.optInt("ID").takeIf { it > 0 }?.let { it to o.optString("Name") } }
            }.sortedBy { it.second.lowercase() }
        }
    }

    private val gameProgressCache = java.util.concurrent.ConcurrentHashMap<Int, RAGameProgress>()

    /** A game's achievements with what you've earned. Reused for [maxAgeMs], so a live view can poll. */
    suspend fun fetchGameProgress(gameId: Int, maxAgeMs: Long = 60_000): RAGameProgress? {
        if (gameId <= 0 || !isConfigured) return null
        gameProgressCache[gameId]?.let { if (System.currentTimeMillis() - it.fetchedAt < maxAgeMs) return it }
        return withContext(Dispatchers.IO) {
            val body = getJson("https://retroachievements.org/API/API_GetGameInfoAndUserProgress.php" +
                "?u=${enc(username)}&g=$gameId&y=${enc(apiKey)}&a=1") ?: return@withContext gameProgressCache[gameId]
            runCatching {
                val j = JSONObject(body)
                val list = mutableListOf<RAAchievement>()
                j.optJSONObject("Achievements")?.let { map ->
                    map.keys().forEach { k ->
                        val a = map.getJSONObject(k)
                        val date = parseRaUtc(a.optString("DateEarnedHardcore")) ?: parseRaUtc(a.optString("DateEarned"))
                        list += RAAchievement(
                            id = a.optInt("ID"), title = a.optString("Title"), description = a.optString("Description"),
                            points = a.optInt("Points"), badgeName = a.optString("BadgeName"),
                            earned = date != null, earnedHardcore = parseRaUtc(a.optString("DateEarnedHardcore")) != null,
                            dateEarned = date, numAwarded = a.optInt("NumAwarded"),
                            // RA's live API sends "Type" (its docs show "type"): reading only the docs'
                            // spelling found no progression/win/missable markings at all (found on device).
                            type = (a.optString("Type").ifBlank { a.optString("type") })
                                .takeIf { it.isNotBlank() && it != "null" },
                            displayOrder = a.optInt("DisplayOrder"),
                            numAwardedHardcore = a.optInt("NumAwardedHardcore"),
                            trueRatio = a.optInt("TrueRatio")
                        )
                    }
                }
                RAGameProgress(
                    gameId = gameId, title = j.optString("Title"), consoleName = j.optString("ConsoleName"),
                    imageIcon = j.optString("ImageIcon"), numDistinctPlayers = j.optInt("NumDistinctPlayers"),
                    consoleId = j.optInt("ConsoleID"),
                    achievements = list.sortedWith(compareBy({ it.displayOrder }, { it.id })),
                    userPlaytimeSeconds = j.optInt("UserTotalPlaytime"),
                    highestAward = j.optString("HighestAwardKind").takeIf { it.isNotBlank() && it != "null" },
                    genre = j.optString("Genre").takeIf { it.isNotBlank() && it != "null" },
                    developer = j.optString("Developer").takeIf { it.isNotBlank() && it != "null" },
                    released = j.optString("Released").takeIf { it.isNotBlank() && it != "null" }
                ).also {
                    if (gameProgressCache[gameId] == null) AppLog.i("RetroAchievements",
                        "Game $gameId '${it.title}': ${it.achievements.size} achievements, ${it.toBeat.size} to beat, " +
                            "${it.achievements.count { a -> a.isMissable }} missable, ${it.earnedCount} earned")
                    gameProgressCache[gameId] = it
                }
            }.getOrElse { AppLog.w("RetroAchievements", "Couldn't read game $gameId", it); gameProgressCache[gameId] }
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun serializeResult(result: RAAwardsResult): String {
        val obj = JSONObject()
        obj.put("fetchedAt",                 result.fetchedAt)
        obj.put("totalAwardsCount",          result.totalAwardsCount)
        obj.put("masteryAwardsCount",        result.masteryAwardsCount)
        obj.put("beatenHardcoreAwardsCount", result.beatenHardcoreAwardsCount)
        obj.put("beatenSoftcoreAwardsCount", result.beatenSoftcoreAwardsCount)
        val arr = JSONArray()
        result.awards.forEach { award ->
            arr.put(JSONObject().apply {
                put("title",          award.title)
                put("consoleName",    award.consoleName)
                put("awardType",      award.awardType)
                put("awardDataExtra", award.awardDataExtra)
                put("awardedAt",      award.awardedAt.time)
                put("imageIcon",      award.imageIcon)
                put("gameId",         award.gameId)
            })
        }
        obj.put("awards", arr)
        return obj.toString()
    }

    private fun deserializeResult(json: String): RAAwardsResult? = try {
        val obj    = JSONObject(json)
        val arr    = obj.getJSONArray("awards")
        val awards = (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            RAAward(
                title          = a.getString("title"),
                consoleName    = a.getString("consoleName"),
                awardType      = a.getString("awardType"),
                awardDataExtra = a.getInt("awardDataExtra"),
                awardedAt      = Date(a.getLong("awardedAt")),
                imageIcon      = a.getString("imageIcon"),
                gameId         = a.optInt("gameId", 0)
            )
        }
        RAAwardsResult(
            awards                    = awards,
            totalAwardsCount          = obj.getInt("totalAwardsCount"),
            masteryAwardsCount        = obj.getInt("masteryAwardsCount"),
            beatenHardcoreAwardsCount = obj.getInt("beatenHardcoreAwardsCount"),
            beatenSoftcoreAwardsCount = obj.getInt("beatenSoftcoreAwardsCount"),
            fetchedAt                 = obj.getLong("fetchedAt")
        )
    } catch (_: Exception) { null }

    companion object {
        private const val CREDENTIALS_FILE        = "ra_credentials"
        private const val KEY_USERNAME            = "ra_username"
        private const val KEY_API_KEY             = "ra_api_key"
        private const val KEY_CACHE               = "ra_cache_json"
        private const val KEY_PROGRESS            = "ra_progress_json"
        private const val KEY_PLAYTIMES           = "ra_playtimes_json_v2"
        private const val KEY_LAST_MANUAL_REFRESH = "ra_last_manual_refresh"
    }
}
