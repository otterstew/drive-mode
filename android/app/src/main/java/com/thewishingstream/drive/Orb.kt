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
// words on the screen, it's a driving app"). Otter colours: greige ground,
// charcoal ink, ochre as the light accent.
//   ready            a still charcoal ring
//   listening        a breathing ochre disc
//   waiting for name a slow, faint ochre ring
//   thinking         a charcoal ring with a turning arc
//   speaking         an ochre disc pulsing quickly
class Orb(ctx: Context) : View(ctx) {
    private val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val ground = Color.parseColor(if (dark) "#24221F" else "#EDEBE7")
    private val ink = Color.parseColor(if (dark) "#E9E6E0" else "#2B2925")
    private val ochre = Color.parseColor(if (dark) "#C99A4A" else "#A8741C")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = RectF()
    private val start = System.currentTimeMillis()

    init { setBackgroundColor(ground) }

    override fun onDraw(c: Canvas) {
        val t = (System.currentTimeMillis() - start) / 1000f
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) * 0.30f
        line.strokeWidth = r * 0.06f
        when (Live.state) {
            State.IDLE -> { line.color = ink; line.alpha = 255; c.drawCircle(cx, cy, r, line) }
            State.LISTENING -> if (Live.waitingForName) {
                line.color = ochre; line.alpha = (110 + 50 * sin(t * 1.6f)).toInt()
                c.drawCircle(cx, cy, r * (0.96f + 0.03f * sin(t * 1.6f)), line)
            } else {
                fill.color = ochre; c.drawCircle(cx, cy, r * (0.88f + 0.07f * sin(t * 3f)), fill)
            }
            State.THINKING -> {
                line.color = ink; line.alpha = 60; c.drawCircle(cx, cy, r, line)
                line.alpha = 255; arc.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(arc, (t * 220f) % 360f, 70f, false, line)
            }
            State.SPEAKING -> { fill.color = ochre; c.drawCircle(cx, cy, r * (0.9f + 0.08f * sin(t * 9f)), fill) }
        }
        if (Live.state != State.IDLE) postInvalidateOnAnimation()
    }
}
