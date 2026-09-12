package com.joeyos.app.data

import android.content.Context
import android.content.SharedPreferences
import com.joeyos.app.CrashLogger
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
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
    val awardType: String,       // "Game Beaten", "Mastery/Completion", etc.
    val awardDataExtra: Int,     // 1 = hardcore, 0 = softcore
    val awardedAt: Date,
    val imageIcon: String
)

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
            CrashLogger.logNonFatal(appContext, "RA encrypted prefs open", e)
            try {
                appContext.deleteSharedPreferences(CREDENTIALS_FILE)
                createEncryptedPrefs()
            } catch (e2: Exception) {
                CrashLogger.logNonFatal(appContext, "RA encrypted prefs recreate", e2)
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

    // Plain (non-encrypted) prefs for non-sensitive cache data
    private val cachePrefs by lazy {
        appContext.getSharedPreferences("ra_cache", Context.MODE_PRIVATE)
    }

    private val cacheTtlMs = 24 * 60 * 60 * 1000L // 24 hours

    val manualRefreshCooldownMs = 30 * 60 * 1000L // 30 minutes
    var lastManualRefreshAt: Long
        get() = cachePrefs.getLong(KEY_LAST_MANUAL_REFRESH, 0L)
        private set(v) = cachePrefs.edit().putLong(KEY_LAST_MANUAL_REFRESH, v).apply()

    // Disk cache is parsed on first access rather than in init, for the same reason as above.
    // Volatile + double-checked lock: fetchAwards can be invoked from multiple coroutines.
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
        cachePrefs.edit().remove(KEY_CACHE).apply()
    }

    suspend fun fetchAwards(forceRefresh: Boolean = false): RAResult {
        if (!isConfigured) return RAResult.NotConfigured

        cachedResult?.let { cached ->
            if (!forceRefresh && System.currentTimeMillis() - cached.fetchedAt < cacheTtlMs)
                return RAResult.Success(cached)
        }

        return withContext(Dispatchers.IO) {
            try {
                // Credentials must be URL-encoded — an unencoded username containing a space,
                // '&', or '#' would otherwise corrupt the query string.
                val url = URL(
                    "https://retroachievements.org/API/API_GetUserAwards.php" +
                    "?u=${enc(username)}&y=${enc(apiKey)}"
                )
                val conn = url.openConnection() as HttpURLConnection
                // Always release the connection, including on the non-200 and exception paths.
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

                // The API can return HTTP 200 with an error body for bad credentials rather
                // than a 4xx status.
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
                    // Skip non-game awards
                    if (type != "Game Beaten" && type != "Mastery/Completion") continue
                    val dateStr = obj.optString("AwardedAt", "")
                    val date = runCatching { dateFormat.parse(dateStr) }.getOrNull() ?: continue
                    awards += RAAward(
                        title            = obj.optString("Title", "Unknown"),
                        consoleName      = obj.optString("ConsoleName", ""),
                        awardType        = type,
                        awardDataExtra   = obj.optInt("AwardDataExtra", 0),
                        awardedAt        = date,
                        imageIcon        = obj.optString("ImageIcon", "")
                    )
                }

                val result = RAAwardsResult(
                    awards                   = awards.sortedByDescending { it.awardedAt },
                    totalAwardsCount         = json.optInt("TotalAwardsCount", 0),
                    masteryAwardsCount       = json.optInt("MasteryAwardsCount", 0),
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

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun serializeResult(result: RAAwardsResult): String {
        val obj = JSONObject()
        obj.put("fetchedAt",                  result.fetchedAt)
        obj.put("totalAwardsCount",           result.totalAwardsCount)
        obj.put("masteryAwardsCount",         result.masteryAwardsCount)
        obj.put("beatenHardcoreAwardsCount",  result.beatenHardcoreAwardsCount)
        obj.put("beatenSoftcoreAwardsCount",  result.beatenSoftcoreAwardsCount)
        val arr = JSONArray()
        result.awards.forEach { award ->
            arr.put(JSONObject().apply {
                put("title",          award.title)
                put("consoleName",    award.consoleName)
                put("awardType",      award.awardType)
                put("awardDataExtra", award.awardDataExtra)
                put("awardedAt",      award.awardedAt.time)
                put("imageIcon",      award.imageIcon)
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
                imageIcon      = a.getString("imageIcon")
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
        private const val CREDENTIALS_FILE = "ra_credentials"
        private const val KEY_USERNAME = "ra_username"
        private const val KEY_API_KEY  = "ra_api_key"
        private const val KEY_CACHE               = "ra_cache_json"
        private const val KEY_LAST_MANUAL_REFRESH = "ra_last_manual_refresh"
    }
}
