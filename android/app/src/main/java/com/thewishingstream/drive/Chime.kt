package com.thewishingstream.drive

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

// Soft notes. "Listening": he tapped and it is now listening.
// "Heard": he tapped and it has stopped listening.
// "Done": a command (music, directions) was carried out without words.
// "Problem": falling notes; something didn't work (no signal, Spotify refused).
object Chime {
    fun listening() = play(floatArrayOf(587f), 0.2f)    // one soft note: "go ahead"
    fun heard() = play(floatArrayOf(660f, 880f), 0.25f)
    fun done() = play(floatArrayOf(784f, 1175f), 0.35f)
    fun problem() = play(floatArrayOf(523f, 392f), 0.35f)

    private fun play(freqs: FloatArray, volume: Float) {
        Thread {
            val rate = 22050
            val note = (rate * 0.13).toInt()
            val pcm = ShortArray(note * freqs.size)
            freqs.forEachIndexed { n, f ->
                for (i in 0 until note) {
                    val env = min(1f, i / (rate * 0.01f)) * (1f - i.toFloat() / note)   // quick rise, gentle fall
                    pcm[n * note + i] = (sin(2 * PI * f * i / rate) * env * volume * Short.MAX_VALUE).toInt().toShort()
                }
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.size * 2).build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep((pcm.size * 1000L / rate) + 200)
            track.release()
        }.start()
    }
}
