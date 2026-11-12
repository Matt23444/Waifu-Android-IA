package com.waifu.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Avatar 2D simple dibujado con Canvas: parpadea, respira, cambia de expresión y mueve la boca con la voz. */
class AvatarView(context: Context) : View(context) {

    enum class Emotion { NEUTRAL, HAPPY, SAD, SURPRISED }

    var emotion: Emotion = Emotion.NEUTRAL
        set(value) {
            field = value
            invalidate()
        }

    private var mouthTarget = 0f
    private var mouth = 0f
    private var blinkStart = 0L
    private var nextBlink = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    /** level: volumen de la voz (aprox. 0..0.3). */
    fun setMouth(level: Float) {
        mouthTarget = (level * 7f).coerceIn(0f, 1f)
    }

    private fun oval(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) {
        fill.color = color
        rect.set(l, t, r, b)
        c.drawOval(rect, fill)
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        mouth += (mouthTarget - mouth) * 0.4f

        if (now > nextBlink) {
            blinkStart = now
            nextBlink = now + 2500 + Random.nextLong(3000)
        }
        val bt = now - blinkStart
        val blink = if (bt in 0..160) sin(bt / 160f * PI).toFloat() else 0f

        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(w, h) * 0.32f
        val cx = w / 2f + sin(now / 1800f) * r * 0.03f
        val cy = h * 0.5f + sin(now / 1200f) * r * 0.02f

        c.drawColor(0xFF1E1B2E.toInt())
        oval(c, cx - r * 1.6f, cy - r * 1.6f, cx + r * 1.6f, cy + r * 1.6f, 0xFF2A2640.toInt())

        // Pelo de atrás y cara
        oval(c, cx - r * 1.25f, cy - r * 1.3f, cx + r * 1.25f, cy + r * 1.7f, 0xFF5B3A8E.toInt())
        oval(c, cx - r, cy - r * 1.1f, cx + r, cy + r * 1.1f, 0xFFFFE0CC.toInt())

        // Flequillo
        fill.color = 0xFF6D46A8.toInt()
        path.reset()
        path.moveTo(cx - r * 1.1f, cy - r * 0.1f)
        path.quadTo(cx - r * 1.0f, cy - r * 1.5f, cx, cy - r * 1.35f)
        path.quadTo(cx + r * 1.0f, cy - r * 1.5f, cx + r * 1.1f, cy - r * 0.1f)
        path.quadTo(cx + r * 0.6f, cy - r * 0.75f, cx + r * 0.3f, cy - r * 0.45f)
        path.quadTo(cx, cy - r * 0.8f, cx - r * 0.3f, cy - r * 0.45f)
        path.quadTo(cx - r * 0.6f, cy - r * 0.75f, cx - r * 1.1f, cy - r * 0.1f)
        path.close()
        c.drawPath(path, fill)

        // Ojos y cejas
        val surprised = emotion == Emotion.SURPRISED
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * r * 0.42f
            val ey = cy + r * 0.05f
            val eyeW = r * 0.26f
            val eyeH = r * (if (surprised) 0.34f else 0.28f) * (1f - blink * 0.92f)
            oval(c, ex - eyeW, ey - eyeH, ex + eyeW, ey + eyeH, 0xFFFFFFFF.toInt())
            val iris = r * 0.17f * (1f - blink * 0.5f)
            oval(c, ex - iris, ey - iris, ex + iris, ey + iris, 0xFF7A4FD6.toInt())
            oval(c, ex - iris * 0.5f, ey - iris * 0.5f, ex + iris * 0.5f, ey + iris * 0.5f, 0xFF1E1B2E.toInt())
            oval(c, ex - iris * 0.1f, ey - iris * 0.7f, ex + iris * 0.35f, ey - iris * 0.25f, 0xFFFFFFFF.toInt())

            var by = ey - r * 0.32f
            var innerY = by
            var outerY = by
            when (emotion) {
                Emotion.SAD -> { innerY -= r * 0.09f; outerY += r * 0.05f }
                Emotion.HAPPY -> { by -= r * 0.03f; innerY = by; outerY = by }
                Emotion.SURPRISED -> { by -= r * 0.12f; innerY = by; outerY = by }
                else -> {}
            }
            stroke.color = 0xFF4A2F78.toInt()
            stroke.strokeWidth = r * 0.045f
            c.drawLine(ex - side * r * 0.2f, innerY, ex + side * r * 0.2f, outerY, stroke)

            if (emotion == Emotion.HAPPY) {
                fill.color = 0x55FF7F9E
                c.drawCircle(ex, cy + r * 0.38f, r * 0.14f, fill)
            }
        }

        // Boca
        val my = cy + r * 0.58f
        val mw = r * 0.28f
        if (mouth > 0.08f) {
            val mh = r * (0.05f + 0.3f * mouth)
            oval(c, cx - mw, my - mh * 0.5f, cx + mw, my + mh * 0.5f, 0xFF8B2C3C.toInt())
        } else {
            stroke.color = 0xFF8B2C3C.toInt()
            stroke.strokeWidth = r * 0.04f
            when (emotion) {
                Emotion.HAPPY -> {
                    rect.set(cx - mw, my - mw * 0.6f, cx + mw, my + mw * 0.6f)
                    c.drawArc(rect, 20f, 140f, false, stroke)
                }
                Emotion.SAD -> {
                    rect.set(cx - mw, my, cx + mw, my + mw * 1.2f)
                    c.drawArc(rect, 200f, 140f, false, stroke)
                }
                else -> c.drawLine(cx - mw * 0.6f, my, cx + mw * 0.6f, my, stroke)
            }
        }

        postInvalidateOnAnimation()
    }
}
