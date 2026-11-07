package com.thewishingstream.drive

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.io.File

// Settings: setup steps first (each says whether it is done), then how it
// listens and talks. Built in code: one plain column, Otter colours, words in
// sentence case. Not for use while driving: the drive screen has no words.
class SettingsActivity : Activity() {
    companion object {
        fun ready(ctx: Context) = Prefs(ctx).key.isNotBlank() &&
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private val ink by lazy { getColor(R.color.ink) }
    private val paper by lazy { getColor(R.color.paper) }
    private val ochre by lazy { getColor(R.color.ochre) }
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        prefs = Prefs(this)
    }

    override fun onResume() { super.onResume(); build() }   // permission results come back here

    private fun build() {
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(40)) }
        setContentView(ScrollView(this).apply { addView(col) })

        heading("Drive mode", 30)
        if (intent.getBooleanExtra("locked", false) && prefs.key.isNotBlank()) note("The server didn't accept the drive key. Scan the QR code on the Mac again.")

        heading("Set up", 22)
        step("Drive key", prefs.key.isNotBlank(), "Scan the QR code on the Mac, or paste the key here.")
        val key = field(prefs.key, password = true) { prefs.key = it.trim() }
        step("Microphone", granted(Manifest.permission.RECORD_AUDIO), "So it can hear you.") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) step("Notifications", granted(Manifest.permission.POST_NOTIFICATIONS), "Android shows one while it listens, with a Stop button.") {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        step("Open Maps from the background", Settings.canDrawOverlays(this), "Android calls this “display over other apps”. It lets it start directions while Spotify is in front.") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        step("Spotify", Spotify.signedIn(this), "Paste the client ID from the Spotify developer dashboard, then connect.")
        field(prefs.spotifyClientId, hint = "Spotify client ID") { prefs.spotifyClientId = it }
        button(if (Spotify.signedIn(this)) "Connect Spotify again" else "Connect Spotify") {
            if (prefs.spotifyClientId.isBlank()) note("Paste the client ID first.") else Spotify.signIn(this)
        }

        heading("Listening", 22)
        label("Its name")
        field(prefs.wakeName) { prefs.wakeName = it }
        label("After an answer")
        choice(listOf("name" to "Listen for its name", "keep" to "Keep listening (it guesses)", "tap" to "Wait for a tap"), prefs.afterAnswer) { prefs.afterAnswer = it }
        label("Listen for its name for")
        choice(listOf("5" to "5 minutes", "30" to "30 minutes", "0" to "Until I stop it"), prefs.listenMins.toString()) { prefs.listenMins = it.toLong() }
        label("Pause before it answers")
        choice(listOf("1000" to "Short", "1500" to "Normal", "3000" to "Long"), prefs.pauseMs.toString()) { prefs.pauseMs = it.toLong() }

        heading("Voice", 22)
        val voices = RadioGroup(this); col.addView(voices)
        listOf("eleven" to "ElevenLabs, male (most lifelike)", "gemini" to "Gemini, male", "gemini_f" to "Gemini, female").forEach { (id, l) -> radio(voices, id, l, prefs.voice) { prefs.voice = id } }
        loadVoices(voices)
        label("Volume")
        col.addView(SeekBar(this).apply {
            max = 100; progress = (prefs.volume * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) prefs.volume = p / 100f }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        button("Play a sample") { sample() }

        space(24)
        button("Done", primary = true) { prefs.key = key.text.toString().trim(); finish() }
    }

    // ---------- Pieces ----------

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun heading(text: String, size: Int) {
        space(if (size > 24) 0 else 20)
        col.addView(TextView(this).apply { this.text = text; textSize = size.toFloat(); typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL); setTextColor(ink) })
        space(8)
    }
    private fun label(text: String) { space(12); col.addView(TextView(this).apply { this.text = text; textSize = 15f; setTextColor(ink); alpha = 0.75f }) }
    private fun note(text: String) { col.addView(TextView(this).apply { this.text = text; textSize = 15f; setTextColor(ochre); setPadding(0, dp(6), 0, dp(6)) }, 1) }
    private fun space(h: Int) = col.addView(View(this), LinearLayout.LayoutParams(MATCH_PARENT, dp(h)))

    // A setup step: what it is, whether it's done, why; tap to fix.
    private fun step(title: String, done: Boolean, why: String, fix: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { setColor(paper); cornerRadius = dp(12).toFloat() }
            if (fix != null && !done) setOnClickListener { fix() }
        }
        row.addView(TextView(this).apply {
            text = (if (done) "✓  " else "○  ") + title + if (fix != null && !done) "  →" else ""
            textSize = 17f; setTextColor(if (done) ink else ochre)
        })
        row.addView(TextView(this).apply { text = why; textSize = 14f; setTextColor(ink); alpha = 0.7f })
        col.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun field(value: String, hint: String = "", password: Boolean = false, save: (String) -> Unit): EditText {
        val e = EditText(this).apply {
            setText(value); this.hint = hint; textSize = 16f; setTextColor(ink); setSingleLine()
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setOnFocusChangeListener { _, has -> if (!has) save(text.toString()) }
        }
        col.addView(e, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        fields.add(e to save)
        return e
    }
    private val fields = mutableListOf<Pair<EditText, (String) -> Unit>>()
    override fun onPause() { fields.forEach { (e, save) -> save(e.text.toString()) }; super.onPause() }

    private fun choice(options: List<Pair<String, String>>, current: String, pick: (String) -> Unit) {
        val g = RadioGroup(this); col.addView(g)
        options.forEach { (id, l) -> radio(g, id, l, current) { pick(id) } }
    }
    private fun radio(g: RadioGroup, id: String, label: String, current: String, pick: () -> Unit) {
        // Each button needs its own view id, or the group can't untick the others.
        val b = RadioButton(this).apply {
            this.id = View.generateViewId()
            text = label; textSize = 16f; setTextColor(ink); buttonTintList = android.content.res.ColorStateList.valueOf(ochre)
            tag = id; minHeight = dp(44)
            setOnClickListener { pick() }
        }
        g.addView(b)
        if (id == current) g.check(b.id)
    }

    private fun button(text: String, primary: Boolean = false, go: () -> Unit) {
        col.addView(Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(if (primary) paper else ink)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                if (primary) setColor(ink) else { setColor(0); setStroke(dp(1), ink) }
            }
            setOnClickListener { go() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })
    }

    // The server's list of voices, in case it has more than the built-in three.
    private fun loadVoices(group: RadioGroup) {
        if (prefs.key.isBlank()) return
        Thread {
            val list = runCatching { Api.call(prefs.key, "/ping", timeoutMs = 10000).optJSONArray("voices") }.getOrNull() ?: return@Thread
            runOnUiThread {
                group.removeAllViews()
                for (i in 0 until list.length()) {
                    val v = list.getJSONObject(i); val id = v.getString("id")
                    radio(group, id, v.getString("label"), prefs.voice) { prefs.voice = id }
                }
            }
        }.start()
    }

    private fun sample() {
        if (prefs.key.isBlank()) return
        Thread {
            val r = runCatching { Api.call(prefs.key, "/speak", JSONObject().put("text", "Hello Stewart. This is how I'll sound in the car.").put("voice", prefs.voice), 20000) }.getOrNull()
            val url = r?.optString("audio").orEmpty()
            if (!url.startsWith("data:")) { runOnUiThread { note(r?.optString("error")?.ifBlank { null } ?: "The sample didn't play.") }; return@Thread }
            val f = File(cacheDir, if (url.startsWith("data:audio/wav")) "sample.wav" else "sample.mp3").apply { writeBytes(Base64.decode(url.substringAfter(","), Base64.DEFAULT)) }
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(f.path); setVolume(prefs.volume, prefs.volume); setOnCompletionListener { it.release() }; prepare(); start()
            }
        }.start()
    }
}
