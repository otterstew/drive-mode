package com.thewishingstream.drive

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast

// The drive screen: the orb and nothing else.
//   tap         talk (or send what it heard, or stop it talking)
//   long press  settings
// It opens Settings by itself until the key, the microphone and the other
// permissions are in place.
class MainActivity : Activity() {
    private lateinit var orb: Orb
    private val redraw = { orb.invalidate() }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        orb = Orb(this)
        orb.isHapticFeedbackEnabled = true
        orb.setOnClickListener { tap() }
        orb.setOnLongClickListener { startActivity(Intent(this, SettingsActivity::class.java)); true }
        orb.contentDescription = "Talk to drive mode"
        setContentView(orb)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }

    override fun onResume() {
        super.onResume()
        Live.listen(redraw); orb.invalidate()
        if (!SettingsActivity.ready(this)) startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onPause() { Live.unlisten(redraw); super.onPause() }

    private fun tap() {
        if (!SettingsActivity.ready(this)) { startActivity(Intent(this, SettingsActivity::class.java)); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        DriveService.tap(this)
    }

    // drivemode://key?k=...   from the QR code on the Mac
    // drivemode://callback    Spotify sign-in coming back
    private fun handle(i: Intent?) {
        // Debug builds only: pass a test recording to the service (emulator testing).
        i?.getStringExtra("testWav")?.let { wav ->
            if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                startForegroundService(Intent(this, DriveService::class.java).setAction("test").putExtra("wav", wav).putExtra("tapped", i.getBooleanExtra("tapped", true)))
            i.removeExtra("testWav")
        }
        val uri = i?.data ?: return
        if (uri.scheme != "drivemode") return
        i.data = null
        when (uri.host) {
            "key" -> uri.getQueryParameter("k")?.takeIf { it.isNotBlank() }?.let {
                Prefs(this).key = it
                Toast.makeText(this, "Drive key saved", Toast.LENGTH_SHORT).show()
            }
            "callback" -> {
                val code = uri.getQueryParameter("code")
                if (code == null) { Toast.makeText(this, "Spotify: " + (uri.getQueryParameter("error") ?: "not connected"), Toast.LENGTH_LONG).show(); return }
                Thread {
                    val msg = try {
                        Spotify.finishSignIn(this, code)
                        // Then let Spotify's own app approve remote control, once.
                        Spotify.connect(this, showAuth = true)
                        "Spotify connected"
                    } catch (e: Exception) { e.message ?: "Spotify didn't connect" }
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                }.start()
            }
        }
    }

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) DriveService.tap(this)
    }
}
