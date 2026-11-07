package com.thewishingstream.drive

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

enum class State { IDLE, LISTENING, THINKING, SPEAKING }

// What the service is doing, for the screen to show. The service runs on
// even when the screen is off or another app is in front.
object Live {
    @Volatile var state = State.IDLE; private set
    // Listening hands-free, waiting for its name (shown differently).
    @Volatile var waitingForName = false; private set
    // How loud the microphone is right now, 0 to 1 (above the background), for the orb.
    @Volatile var level = 0f
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun set(s: State, waiting: Boolean = false) {
        state = s; waitingForName = waiting
        android.util.Log.d("DriveMode", "state $s" + if (waiting) " (waiting for name)" else "")
        main.post { listeners.forEach { it() } }
    }
    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
}
