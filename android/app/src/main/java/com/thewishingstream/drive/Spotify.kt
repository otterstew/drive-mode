package com.thewishingstream.drive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Spotify, two ways:
//  - App Remote (Spotify's Android library) plays, pauses and skips. It wakes
//    Spotify in the background, so Spotify never jumps in front of the drive
//    screen: the problem the web page could not get round.
//  - The Web API, signed in once with PKCE (no secret in the app), only for
//    searching: App Remote can play a song but can't look one up.
// Both check this app's package name and signing fingerprint against the
// Spotify developer app, so those must be registered on its dashboard.
object Spotify {
    const val REDIRECT = "drivemode://callback"
    private const val SCOPES = "user-modify-playback-state user-read-playback-state app-remote-control"
    @Volatile private var remote: SpotifyAppRemote? = null

    // ---- Signing in (once, from the settings screen) ----

    fun signIn(ctx: Context) {
        val prefs = Prefs(ctx)
        val verifier = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
        prefs.spotifyVerifier = verifier
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val url = "https://accounts.spotify.com/authorize?" + form(
            "client_id" to prefs.spotifyClientId, "response_type" to "code", "redirect_uri" to REDIRECT,
            "scope" to SCOPES, "code_challenge_method" to "S256", "code_challenge" to challenge,
        )
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // Called with the code from drivemode://callback. Blocking.
    fun finishSignIn(ctx: Context, code: String) {
        val prefs = Prefs(ctx)
        token(prefs, "grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT, "code_verifier" to prefs.spotifyVerifier)
    }

    private fun token(prefs: Prefs, vararg params: Pair<String, String>) {
        val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(form("client_id" to prefs.spotifyClientId, *params).toByteArray()) }
        val ok = c.responseCode < 400
        val j = JSONObject((if (ok) c.inputStream else c.errorStream).bufferedReader().use { it.readText() })
        if (!ok) throw Exception("Spotify sign-in failed: " + j.optString("error_description", j.optString("error")))
        prefs.spotifyAccess = j.getString("access_token")
        if (j.has("refresh_token")) prefs.spotifyRefresh = j.getString("refresh_token")
        prefs.spotifyUntil = System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 60) * 1000
    }

    fun signedIn(ctx: Context) = Prefs(ctx).spotifyRefresh.isNotEmpty()

    // ---- Searching (Web API). Blocking. ----

    fun search(ctx: Context, query: String, kind: String): Pair<String, String>? {
        val prefs = Prefs(ctx)
        if (prefs.spotifyRefresh.isEmpty()) throw Exception("Spotify isn't signed in on this phone")
        if (System.currentTimeMillis() > prefs.spotifyUntil) token(prefs, "grant_type" to "refresh_token", "refresh_token" to prefs.spotifyRefresh)
        val q = query.replace(Regex("\\s+by\\s+", RegexOption.IGNORE_CASE), " ")
        val c = URL("https://api.spotify.com/v1/search?" + form("q" to q, "type" to kind, "limit" to "5")).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer " + prefs.spotifyAccess)
        if (c.responseCode >= 400) throw Exception("Spotify search ${c.responseCode}")
        val items = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONObject(kind + "s")?.optJSONArray("items") ?: return null
        // Spotify's own editorial playlists can't be started by apps like this, so prefer others.
        var fallback: Pair<String, String>? = null
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val pick = it.getString("uri") to it.optString("name")
            if (it.optJSONObject("owner")?.optString("id") == "spotify") { if (fallback == null) fallback = pick; continue }
            return pick
        }
        return fallback
    }

    // ---- Playing (App Remote). Blocking: waits for Spotify to answer. ----

    fun connect(ctx: Context, showAuth: Boolean = false): SpotifyAppRemote {
        remote?.takeIf { it.isConnected && !showAuth }?.let { return it }
        val prefs = Prefs(ctx)
        val params = ConnectionParams.Builder(prefs.spotifyClientId).setRedirectUri(REDIRECT).showAuthView(showAuth).build()
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            SpotifyAppRemote.connect(if (showAuth) ctx else ctx.applicationContext, params, object : Connector.ConnectionListener {
                override fun onConnected(r: SpotifyAppRemote) { remote = r; done.countDown() }
                override fun onFailure(t: Throwable) { failure = t; done.countDown() }
            })
        }
        if (!done.await(20, TimeUnit.SECONDS)) throw Exception("Spotify didn't answer")
        failure?.let { throw Exception("Spotify: " + (it.message ?: it.javaClass.simpleName), it) }
        return remote!!
    }

    fun command(ctx: Context, command: String, uri: String? = null) {
        val player = connect(ctx).playerApi
        val result = when (command) {
            "play" -> player.play(uri)
            "pause" -> player.pause()
            "resume" -> player.resume()
            "next" -> player.skipNext()
            "previous" -> player.skipPrevious()
            else -> return
        }
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        result.setResultCallback { done.countDown() }.setErrorCallback { failure = it; done.countDown() }
        done.await(10, TimeUnit.SECONDS)
        failure?.let { throw Exception("Spotify: " + (it.message ?: it.javaClass.simpleName)) }
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun form(vararg kv: Pair<String, String>) = kv.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
}
