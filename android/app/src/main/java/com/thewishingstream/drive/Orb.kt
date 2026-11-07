package com.thewishingstream.drive

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min
import kotlin.math.sin

// The whole drive screen: one shape, no words (Stewart: "there shouldn't be
// words on the screen, it's a driving app"). Each state has its own shape
// and movement, so a glance tells them apart (his second ask: "it's not
// clear when it's listening"):
//   off                a still charcoal ring
//   waiting for name   an ochre ring that ripples with sound; the screen is tinted ochre
//   listening to him   a solid ochre disc that grows with his voice; tinted ochre
//   thinking           a charcoal arc going round
//   talking            a charcoal disc with rings spreading out
// The tint means "the microphone is on", seen from the corner of an eye.
class Orb(ctx: Context) : View(ctx) {
    private val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val ground = Color.parseColor(if (dark) "#24221F" else "#EDEBE7")
    private val tint = Color.parseColor(if (dark) "#3A3020" else "#F1E3C8")   // ground warmed with ochre
    private val ink = Color.parseColor(if (dark) "#E9E6E0" else "#2B2925")
    private val ochre = Color.parseColor(if (dark) "#C99A4A" else "#A8741C")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = RectF()
    private val start = System.currentTimeMillis()
    private var shown = 0f   // the mic level, smoothed so the shape moves calmly

    override fun onDraw(c: Canvas) {
        val t = (System.currentTimeMillis() - start) / 1000f
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) * 0.30f
        val state = Live.state
        shown += (Live.level - shown) * if (Live.level > shown) 0.5f else 0.12f

        c.drawColor(if (state == State.LISTENING) tint else ground)
        line.alpha = 255; fill.alpha = 255
        when (state) {
            State.IDLE -> {
                line.color = ink; line.strokeWidth = r * 0.05f
                c.drawCircle(cx, cy, r, line)
            }
            State.LISTENING -> if (Live.waitingForName) {
                // A clear ring, thickening and swelling with whatever it hears.
                line.color = ochre; line.strokeWidth = r * (0.07f + 0.12f * shown)
                c.drawCircle(cx, cy, r * (1f + 0.04f * sin(t * 1.5f) + 0.06f * shown), line)
            } else {
                fill.color = ochre
                c.drawCircle(cx, cy, r * (0.82f + 0.30f * shown + 0.03f * sin(t * 2.5f)), fill)
            }
            State.THINKING -> {
                line.color = ink; line.strokeWidth = r * 0.07f
                line.alpha = 50; c.drawCircle(cx, cy, r, line)
                line.alpha = 255; arc.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(arc, (t * 260f) % 360f, 80f, false, line)
            }
            State.SPEAKING -> {
                fill.color = ink; c.drawCircle(cx, cy, r * 0.78f, fill)
                // Rings spreading out, like sound.
                line.color = ink; line.strokeWidth = r * 0.04f
                for (k in 0 until 3) {
                    val p = ((t * 0.9f) + k / 3f) % 1f
                    line.alpha = ((1f - p) * 160).toInt()
                    c.drawCircle(cx, cy, r * (0.85f + 0.55f * p), line)
                }
            }
        }
        if (state != State.IDLE) postInvalidateOnAnimation()
    }
}
