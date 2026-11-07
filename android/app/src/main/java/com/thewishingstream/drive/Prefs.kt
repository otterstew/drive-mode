package com.thewishingstream.drive

import android.content.Context
import org.json.JSONArray

// Everything the app remembers, in one place.
class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("drive", Context.MODE_PRIVATE)
    private fun str(k: String, d: String) = p.getString(k, d) ?: d
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()

    var key: String get() = str("key", ""); set(v) = put("key", v)
    var wakeName: String get() = str("wakeName", "Jarvis"); set(v) = put("wakeName", v.ifBlank { "Jarvis" })
    // name: hands-free only when he says the name; keep: the AI guesses; tap: only after a tap
    var afterAnswer: String get() = str("afterAnswer", "name"); set(v) = put("afterAnswer", v)
    var voice: String get() = str("voice", "eleven"); set(v) = put("voice", v)
    var pauseMs: Long get() = p.getLong("pauseMs", 1500); set(v) = p.edit().putLong("pauseMs", v).apply()
    // How long it keeps listening for its name after the last thing said to it. 0: until stopped.
    var listenMins: Long get() = p.getLong("listenMins", 30); set(v) = p.edit().putLong("listenMins", v).apply()
    var volume: Float get() = p.getFloat("volume", 0.6f); set(v) = p.edit().putFloat("volume", v).apply()

    var spotifyClientId: String get() = str("spClient", ""); set(v) = put("spClient", v.trim())
    var spotifyAccess: String get() = str("spAccess", ""); set(v) = put("spAccess", v)
    var spotifyRefresh: String get() = str("spRefresh", ""); set(v) = put("spRefresh", v)
    var spotifyUntil: Long get() = p.getLong("spUntil", 0); set(v) = p.edit().putLong("spUntil", v).apply()
    var spotifyVerifier: String get() = str("spVerifier", ""); set(v) = put("spVerifier", v)

    // The conversation so far, and what really happened on the phone.
    var history: JSONArray
        get() = runCatching { JSONArray(str("history", "[]")) }.getOrDefault(JSONArray())
        set(v) = put("history", v.toString())
    var sessionStarted: String get() = str("started", ""); set(v) = put("started", v)
    var lastAt: Long get() = p.getLong("lastAt", 0); set(v) = p.edit().putLong("lastAt", v).apply()
    // Conversations whose notes couldn't be saved yet (no signal); retried later.
    var pending: JSONArray
        get() = runCatching { JSONArray(str("pending", "[]")) }.getOrDefault(JSONArray())
        set(v) = put("pending", v.toString())
    var recent: JSONArray
        get() = runCatching { JSONArray(str("recent", "[]")) }.getOrDefault(JSONArray())
        set(v) = put("recent", v.toString())
}
