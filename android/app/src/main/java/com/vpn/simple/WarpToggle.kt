package com.vpn.simple

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

class WarpToggle(context: Context) : View(context) {
    var checked = false
        set(value) {
            field = value
            animateKnob()
            invalidate()
        }
    var onToggle: ((Boolean) -> Unit)? = null

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private var knobPosition = 0f
    private var offShader: Shader? = null
    private var onShader: Shader? = null

    private val onColors = intArrayOf(0xFFFFA03C.toInt(), 0xFFFF2D55.toInt())
    private val offColors = intArrayOf(0xFF9E7BFF.toInt(), 0xFF6C4DF6.toInt())

    init {
        ring.style = Paint.Style.STROKE
        ring.color = 0x33000000
        ring.strokeWidth = dp(1.2f).toFloat()
        setOnClickListener { onToggle?.invoke(!checked) }
        contentDescription = "VPN switch"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onShader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), onColors[0], onColors[1], Shader.TileMode.CLAMP)
        offShader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), offColors[0], offColors[1], Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f
        track.shader = if (checked) onShader else offShader
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, track)
        val pad = h * 0.14f
        val knobD = h - pad * 2
        val cx = pad + knobPosition * (w - h) + knobD / 2
        knob.color = Color.WHITE
        canvas.drawCircle(cx, h / 2, knobD / 2, knob)
        canvas.drawCircle(cx, h / 2, knobD / 2 - ring.strokeWidth / 2, ring)
    }

    private fun animateKnob() {
        val start = knobPosition
        val target = if (checked) 1f else 0f
        val started = System.currentTimeMillis()
        post(object : Runnable {
            override fun run() {
                val t = ((System.currentTimeMillis() - started) / 190f).coerceIn(0f, 1f)
                knobPosition = start + (target - start) * t
                invalidate()
                if (t < 1f) postOnAnimation(this)
            }
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(220f), dp(110f))
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
