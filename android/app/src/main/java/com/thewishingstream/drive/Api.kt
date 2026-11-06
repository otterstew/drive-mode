package com.thewishingstream.drive

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// The drive-mode server (a Supabase function shared with the web page).
// Blocking calls: always run them off the main thread.
object Api {
    private const val BASE = "https://uftlxeahciewiitclkbu.functions.supabase.co/drive-mode"

    class Locked : Exception("Wrong drive key")

    fun call(key: String, path: String, body: JSONObject? = null, timeoutMs: Int = 45000): JSONObject {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = timeoutMs
        c.setRequestProperty("x-drive-key", key)
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = c.responseCode
        if (code == 401) throw Locked()
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
        if (code >= 400) throw Exception("Server $code: ${text.take(200)}")
        return JSONObject(text)
    }

    // Fire and forget: what happened on the phone, for the function's logs.
    fun log(key: String, event: JSONObject) {
        Thread { runCatching { call(key, "/log", event.put("app", "android"), 10000) } }.start()
    }
}
