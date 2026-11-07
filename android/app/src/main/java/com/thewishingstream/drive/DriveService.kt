package com.thewishingstream.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

// Drive mode itself. A foreground service, so it keeps listening with the
// screen off and with Maps or Spotify in front: the thing the web page
// couldn't do. It records with the phone's own microphone in normal audio
// mode (VOICE_RECOGNITION, no call mode), so the car never thinks a phone
// call has started.
//
// One worker thread runs the loop: listen -> send to the server -> chime or
// speak -> do what was asked -> listen again (for its name) or wait for a tap.
class DriveService : Service() {
    companion object {
        const val TAP = "tap"     // the orb was tapped
        const val STOP = "stop"   // stop listening and write up the notes

        fun tap(ctx: Context) = ctx.startForegroundService(Intent(ctx, DriveService::class.java).setAction(TAP))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, DriveService::class.java).setAction(STOP))

        private const val RATE = 16000
        private const val FRAME = 1024                    // 64 ms
        private const val WAIT_MS = 9000L                 // tapped: give up if nothing is said
        private const val MAX_MS = 45000L                 // longest single turn (music can hide the end of speech)
        private const val HANDS_FREE_MAX_MS = 15000L      // hands-free: send at least this often, even over music
        private const val IDLE_FINISH_MS = 10 * 60 * 1000L // write the notes up after 10 quiet minutes
        private const val STALE_MS = 30 * 60 * 1000L       // a conversation older than this is over
    }

    private lateinit var prefs: Prefs
    private lateinit var audio: AudioManager
    private var wake: PowerManager.WakeLock? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private val taps = LinkedBlockingQueue<Boolean>()

    // Set from a tap while listening or speaking; read by the worker.
    @Volatile private var sendNow = false
    @Volatile private var switchToTapped = false
    @Volatile private var hush = false
    @Volatile private var interrupted = false   // tapped while it was talking: listen to him next
    private var addressedAt = 0L
    @Volatile private var testWav: Pair<ByteArray, Boolean>? = null
    // The background noise level: the quietest moment of the last two
    // seconds (the gaps between words fall to it), kept from one listen to
    // the next. The first version measured the first 0.4 s of each listen;
    // when he was already talking, or its own voice was still echoing, his
    // voice became "background" and it stopped hearing him.
    private val recentLevels = ArrayDeque<Double>()
    private var noise = 0.0
    private var peak = 0.0   // the last time something was said to it

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        audio = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> { shutDown(); return START_NOT_STICKY }
            // Debug builds only: hear a WAV file instead of the microphone (for testing on the emulator).
            "test" -> if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                testWav = File(filesDir, intent.getStringExtra("wav") ?: "test.wav").readBytes() to intent.getBooleanExtra("tapped", true)
                if (!running) begin() else if (Live.state == State.IDLE) taps.offer(true)
            }
            else -> {
                if (!running) begin() else onTap()
            }
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 30) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "drive:listening").apply { acquire(6 * 60 * 60 * 1000L) }
        running = true
        addressedAt = System.currentTimeMillis()
        worker = Thread({ runCatching { retryPending() }; loop(tapped = true) }, "drive").also { it.start() }
    }

    // A tap means "I want to talk", whatever it was doing.
    private fun onTap() {
        when (Live.state) {
            State.IDLE -> taps.offer(true)
            State.LISTENING -> if (Live.waitingForName) switchToTapped = true else sendNow = true
            State.SPEAKING -> { hush = true; interrupted = true }
            State.THINKING -> {}
        }
    }

    private fun shutDown() {
        val wasRunning = running
        running = false
        hush = true; taps.offer(false)
        Live.set(State.IDLE)
        Thread {
            worker?.join(3000)
            if (wasRunning) runCatching { finishSession() }
            wake?.takeIf { it.isHeld }?.release()
        }.start()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { running = false; taps.offer(false); super.onDestroy() }

    // ---------------- The loop ----------------

    private fun loop(tapped: Boolean) {
        var nextTapped = tapped
        while (running) {
            if (!nextTapped && !listeningForName()) {
                Live.set(State.IDLE)
                if (!waitForTap()) break
                nextTapped = true
                continue
            }
            val heardTapped = nextTapped
            val wav = record(heardTapped)
            if (!running) break
            if (wav == null) { nextTapped = false; continue }
            val tappedTurn = heardTapped || wav.second
            if (tappedTurn) Chime.heard()
            nextTapped = false
            interrupted = false
            try { turn(wav.first, tappedTurn); nextTapped = interrupted }
            catch (e: Api.Locked) { locked(); break }
            catch (e: Exception) { android.util.Log.w("DriveMode", "turn failed", e); Live.set(State.IDLE); Chime.problem(); Api.log(prefs.key, JSONObject().put("event", "error").put("error", e.message)) }
        }
        Live.set(State.IDLE)
    }

    // Waits for a tap. Writes up the notes if the conversation goes quiet.
    private fun waitForTap(): Boolean {
        while (running) {
            val t = taps.poll(30, TimeUnit.SECONDS)
            if (t != null) return t && running
            finishIfQuiet()
        }
        return false
    }

    private fun listeningForName(): Boolean {
        if (prefs.afterAnswer == "tap") return false
        val mins = prefs.listenMins
        return mins == 0L || System.currentTimeMillis() - addressedAt < mins * 60_000
    }

    private fun finishIfQuiet() {
        if (prefs.history.length() > 0 && System.currentTimeMillis() - prefs.lastAt > IDLE_FINISH_MS) runCatching { finishSession() }
    }

    // ---------------- Listening ----------------

    // Returns the WAV (base64) and whether he tapped part-way through, or null
    // if nothing was said.
    private fun record(tapped: Boolean): Pair<String, Boolean>? {
        testWav?.let { (bytes, t) -> testWav = null; Live.set(State.LISTENING); Thread.sleep(500); return Base64.encodeToString(bytes, Base64.NO_WRAP) to (t || tapped) }
        val handsFree = !tapped
        if (tapped) Chime.listening()
        Live.set(State.LISTENING, waiting = handsFree && prefs.afterAnswer == "name")
        sendNow = false; switchToTapped = false
        val size = max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), FRAME * 4)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        } catch (e: SecurityException) { Chime.problem(); running = false; return null }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); Chime.problem(); Thread.sleep(2000); return null }
        val lead = ArrayDeque<ShortArray>()   // a moment before he started, so the first word isn't clipped
        val kept = ArrayList<ShortArray>()
        val began = System.currentTimeMillis()
        peak = 0.0
        var floor = 0.0; var spoke = false; var lastLoud = 0L; var tappedMidway = false; var restart = false
        val pause = prefs.pauseMs
        try {
            rec.startRecording()
            while (running) {
                val buf = ShortArray(FRAME)
                val n = rec.read(buf, 0, FRAME)
                if (n <= 0) continue
                val now = System.currentTimeMillis()
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                val level = sqrt(sum / n)
                peak = max(peak, level)
                recentLevels.addLast(level); while (recentLevels.size > 32) recentLevels.removeFirst()
                noise = recentLevels.min()
                floor = noise
                val loud = recentLevels.size >= 4 && level > max(noise * 2, 0.006)
                Live.level = ((level - noise) / 0.04).coerceIn(0.0, 1.0).toFloat()
                if (loud) { lastLoud = now; if (!spoke) { spoke = true; kept.addAll(lead); lead.clear() } }
                if (spoke) kept.add(buf) else { lead.addLast(buf); while (lead.size > 16) lead.removeFirst() }

                if (switchToTapped) {
                    // Tapped while it was waiting for its name: listen to him properly now.
                    switchToTapped = false
                    if (spoke) { tappedMidway = true; Chime.listening() } else { restart = true; break }
                    Live.set(State.LISTENING)
                }
                // A tap sends what it has; with nothing heard yet it keeps listening.
                if (sendNow) { sendNow = false; if (spoke) return wav(kept) to true }
                val limit = if (handsFree && !tappedMidway) HANDS_FREE_MAX_MS else MAX_MS
                if (spoke && now - lastLoud > pause) return wav(kept) to tappedMidway
                if (!spoke && now - began > WAIT_MS) return null
                if (now - began > limit) return if (spoke) wav(kept) to tappedMidway else null
            }
        } finally {
            android.util.Log.d("DriveMode", "listened ${System.currentTimeMillis() - began} ms: noise %.4f peak %.4f spoke $spoke".format(floor, peak))
            // He tapped but nothing counted as speech: report the levels, for tuning.
            if (tapped && !spoke && running) Api.log(prefs.key, JSONObject().put("event", "unheard").put("noise", floor).put("peak", peak))
            runCatching { rec.stop() }; rec.release()
            Live.level = 0f
        }
        return if (restart) record(true) else null
    }

    private fun wav(frames: List<ShortArray>): String {
        val samples = frames.sumOf { it.size }
        val b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(samples * 2)
        for (f in frames) for (s in f) b.putShort(s)
        return Base64.encodeToString(b.array(), Base64.NO_WRAP)
    }

    // ---------------- One turn ----------------

    private fun turn(wav: String, tapped: Boolean) {
        val now = System.currentTimeMillis()
        if (prefs.history.length() > 0 && now - prefs.lastAt > STALE_MS) finishSession()
        if (prefs.history.length() == 0) prefs.sessionStarted = iso(now)
        Live.set(State.THINKING)
        val nameRule = prefs.afterAnswer == "name"
        val body = JSONObject()
            .put("mode", "drive").put("history", prefs.history).put("audio", wav)
            .put("voice", prefs.voice).put("recent", prefs.recent).put("handsFree", !tapped)
        if (nameRule) body.put("wakeName", prefs.wakeName)
        val sent = System.currentTimeMillis()
        val r = Api.call(prefs.key, "/turn", body)
        val heard = r.optString("heard")
        android.util.Log.d("DriveMode", "heard \"$heard\" quiet=${r.optBoolean("quiet")} ignored=${r.optBoolean("ignored")} say \"${r.optString("say")}\" actions=${r.optJSONArray("actions")}")
        if (heard.isBlank()) return
        if (r.optBoolean("ignored")) { Api.log(prefs.key, JSONObject().put("event", "ignored").put("heard", heard)); return }

        addressedAt = System.currentTimeMillis()
        val say = r.optString("say")
        val history = prefs.history
        history.put(JSONObject().put("role", "user").put("text", heard))
        if (say.isNotBlank()) history.put(JSONObject().put("role", "assistant").put("text", say))
        prefs.history = history
        prefs.lastAt = System.currentTimeMillis()
        val actions = r.optJSONArray("actions") ?: JSONArray()
        Api.log(prefs.key, JSONObject().put("event", "turn").put("heard", heard).put("said", say).put("actions", actions)
            .put("server", r.opt("timing")).put("roundTripMs", System.currentTimeMillis() - sent).put("kb", wav.length / 1024).put("noise", noise).put("peak", peak))

        // A plain command: no words, just a chime, and do it (Stewart: "it
        // should just put the song on"). It only talks for conversation.
        if (r.optBoolean("quiet")) Chime.done()
        else speak(r.optString("audio"))
        for (i in 0 until actions.length()) act(actions.getJSONObject(i))

        if (r.optBoolean("end")) { addressedAt = 0; Live.set(State.IDLE); finishSession() }
    }

    // ---------------- Speaking ----------------

    private fun speak(dataUrl: String) {
        if (!dataUrl.startsWith("data:")) return
        val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
        val ext = if (dataUrl.startsWith("data:audio/wav")) "wav" else "mp3"
        val file = File(cacheDir, "say.$ext").apply { writeBytes(bytes) }
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        // Music dips while it talks, then comes back.
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        audio.requestAudioFocus(focus)
        hush = false
        Live.set(State.SPEAKING)
        val done = CountDownLatch(1)
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(attrs)
            player.setDataSource(file.path)
            player.setVolume(prefs.volume, prefs.volume)
            player.setOnCompletionListener { done.countDown() }
            player.setOnErrorListener { _, _, _ -> done.countDown(); true }
            player.prepare()
            player.start()
            while (!done.await(100, TimeUnit.MILLISECONDS)) if (hush || !running) break
        } finally {
            runCatching { player.stop() }; player.release()
            audio.abandonAudioFocusRequest(focus)
        }
    }

    // ---------------- Doing things on the phone ----------------

    private fun act(a: JSONObject) {
        when (a.optString("type")) {
            "directions" -> directions(a.optString("destination"))
            "music" -> music(a)
        }
    }

    private fun directions(dest: String) {
        if (dest.isBlank()) return
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + URLEncoder.encode(dest, "UTF-8") + "&mode=d"))
            .setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // From the background, Android only lets it open Maps with the
        // "display over other apps" permission (asked for in Settings).
        if (!Settings.canDrawOverlays(this)) {
            outcome("Maps: could not open directions to $dest, because the app isn't allowed to open other apps from the background. Tell him to allow it in Drive's settings.")
            Chime.problem(); return
        }
        try { startActivity(i); outcome("Maps: opened directions to $dest.") }
        catch (e: Exception) { outcome("Maps: could not open directions to $dest (${e.message})."); Chime.problem() }
    }

    private fun music(a: JSONObject) {
        val command = a.optString("command")
        val query = a.optString("query")
        try {
            if (command == "play" && query.isNotBlank()) {
                val (uri, name) = Spotify.search(this, query, a.optString("kind").ifBlank { "track" })
                    ?: run { outcome("Spotify: found nothing for \"$query\"."); Chime.problem(); return }
                Spotify.command(this, "play", uri)
                outcome("Spotify: playing \"$name\". Confirmed by Spotify.")
                Api.log(prefs.key, JSONObject().put("event", "spotify-playing").put("uri", uri).put("name", name))
            } else {
                Spotify.command(this, if (command == "play") "resume" else command)
                outcome("Spotify: $command done. Confirmed by Spotify.")
            }
        } catch (e: Exception) {
            outcome("Spotify: $command${if (query.isNotBlank()) " \"$query\"" else ""} failed (${e.message}).")
            Api.log(prefs.key, JSONObject().put("event", "spotify-error").put("command", command).put("error", e.message))
            Chime.problem()
        }
    }

    // What really happened, so the AI never claims something that didn't.
    private fun outcome(text: String) {
        android.util.Log.d("DriveMode", "outcome: $text")
        val t = SimpleDateFormat("HH:mm", Locale.UK).format(Date())
        val recent = prefs.recent
        recent.put("$t $text")
        val keep = JSONArray(); for (i in max(0, recent.length() - 6) until recent.length()) keep.put(recent.get(i))
        prefs.recent = keep
    }

    // ---------------- Notes ----------------

    private fun finishSession() {
        val history = prefs.history
        val started = prefs.sessionStarted
        prefs.history = JSONArray(); prefs.recent = JSONArray()
        if (history.length() == 0) return
        val body = JSONObject().put("mode", "drive").put("history", history).put("startedAt", started)
        try { Api.call(prefs.key, "/finish", body, 90000) }
        catch (e: Exception) { prefs.pending = prefs.pending.put(body) }   // no signal: try again next time
    }

    private fun retryPending() {
        val waiting = prefs.pending
        if (waiting.length() == 0) return
        prefs.pending = JSONArray()
        for (i in 0 until waiting.length()) {
            val body = waiting.getJSONObject(i)
            try { Api.call(prefs.key, "/finish", body, 90000) } catch (e: Exception) { prefs.pending = prefs.pending.put(body) }
        }
    }

    private fun locked() {
        Chime.problem()
        running = false
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("locked", true))
        Thread { shutDown() }.start()
    }

    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.UK).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    // ---------------- Notification (needed to listen in the background) ----------------

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("drive", "Listening", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DriveService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "drive")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Drive mode is listening")
            .setContentText("Say “${prefs.wakeName}”, or tap to open")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat), "Stop", stop).build())
            .build()
    }
}
