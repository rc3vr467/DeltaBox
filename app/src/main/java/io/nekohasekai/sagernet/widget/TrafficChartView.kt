package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import io.nekohasekai.sagernet.R

/**
 * Live Upload / Download chart fed ONLY with real samples from the existing traffic pipeline
 * (TrafficLooper -> SpeedDisplayData -> MainActivity.cbSpeedUpdate -> StatsBar.updateSpeed).
 *
 * Behaviour:
 * - IDLE (disconnected / stopping): flat neutral baseline, no lines, buffer cleared.
 * - WAITING (connecting): dashed baseline, nothing else.
 * - ACTIVE (connected): lines appear with the first real sample; zero traffic is drawn as a real
 *   zero. Nothing is animated independently of data: the view only redraws when a sample arrives.
 *
 * Drawing reuses Paint / Path / gradients (no allocation in onDraw). The Y scale is shared by both
 * lines and only affects the drawing, never the numeric values shown elsewhere.
 */
class TrafficChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode { IDLE, WAITING, ACTIVE }

    private val buffer = TrafficRingBuffer()
    private var mode = Mode.IDLE

    /**
     * Interval the service uses to send samples (DataStore.speedInterval). It only decides how much
     * time the chart shows (about 60 samples) and when a missing stretch of data is drawn as a gap
     * instead of an invented line (the service may not send samples while the app is in background).
     */
    var sampleIntervalMs: Long = 1000L
        set(value) {
            field = value.coerceAtLeast(100L)
            invalidate()
        }
    private val windowMs: Long get() = (sampleIntervalMs * 60).coerceIn(30_000L, 600_000L)
    private val gapMs: Long get() = sampleIntervalMs * 3 + 1000L

    private val downColor = ContextCompat.getColor(context, R.color.delta_chart_download)
    private val upColor = ContextCompat.getColor(context, R.color.delta_chart_upload)

    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val areaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.delta_chart_baseline)
    }
    private val dashEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)

    private val linePath = Path()
    private val areaPath = Path()
    private var downGradient: LinearGradient? = null
    private var upGradient: LinearGradient? = null

    private val padTop = 4f * density
    private val padBottom = 3f * density

    /** Switches the chart state. Every state change starts a new session (buffer cleared). */
    fun setMode(newMode: Mode) {
        if (newMode == mode) return
        buffer.clear()
        mode = newMode
        invalidate()
    }

    /** Adds one real sample (bytes per second) and redraws. Ignored unless ACTIVE. */
    fun addSample(txRate: Long, rxRate: Long) {
        if (mode != Mode.ACTIVE) return
        buffer.add(SystemClock.elapsedRealtime(), txRate, rxRate)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val top = padTop
        val bottom = h - padBottom
        downGradient = LinearGradient(
            0f, top, 0f, bottom, ColorUtils.setAlphaComponent(downColor, 0x33),
            ColorUtils.setAlphaComponent(downColor, 0x00), Shader.TileMode.CLAMP
        )
        upGradient = LinearGradient(
            0f, top, 0f, bottom, ColorUtils.setAlphaComponent(upColor, 0x26),
            ColorUtils.setAlphaComponent(upColor, 0x00), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val baseY = h - padBottom

        // baseline (dashed while waiting, solid otherwise)
        basePaint.pathEffect = if (mode == Mode.WAITING) dashEffect else null
        canvas.drawLine(0f, baseY, w, baseY, basePaint)

        if (mode != Mode.ACTIVE || buffer.size == 0) return

        val newest = buffer.timeAt(buffer.size - 1)
        val window = windowMs
        val since = newest - window
        // floor keeps tiny rates from filling the whole height; it only changes drawing scale
        val maxRate = maxOf(buffer.maxRateSince(since), MIN_SCALE_BYTES_PER_S).toFloat() * 1.1f

        drawSeries(canvas, true, newest, since, window, maxRate, baseY, w, upGradient, upColor)
        drawSeries(canvas, false, newest, since, window, maxRate, baseY, w, downGradient, downColor)
    }

    private fun drawSeries(
        canvas: Canvas, upload: Boolean, newest: Long, since: Long, window: Long, maxRate: Float,
        baseY: Float, w: Float, gradient: LinearGradient?, color: Int,
    ) {
        linePath.reset()
        areaPath.reset()
        val plotH = baseY - padTop
        var count = 0
        var prevX = 0f
        var prevY = 0f
        var prevT = 0L
        var firstX = 0f
        val gap = gapMs

        for (i in 0 until buffer.size) {
            val t = buffer.timeAt(i)
            if (t < since) continue
            val x = w - (newest - t).toFloat() / window * w
            val rate = if (upload) buffer.txAt(i) else buffer.rxAt(i)
            val y = baseY - (rate.toFloat() / maxRate) * plotH
            if (count == 0) {
                linePath.moveTo(x, y)
                areaPath.moveTo(x, baseY)
                areaPath.lineTo(x, y)
                firstX = x
            } else if (t - prevT > gap) {
                // no samples for a while: show the gap, do not connect it with an invented line
                linePath.moveTo(x, y)
                areaPath.lineTo(prevX, baseY)
                areaPath.lineTo(x, baseY)
                areaPath.lineTo(x, y)
            } else {
                // control points share the Y of their end points: smooth, no overshoot below 0
                val mid = (prevX + x) / 2f
                linePath.cubicTo(mid, prevY, mid, y, x, y)
                areaPath.cubicTo(mid, prevY, mid, y, x, y)
            }
            prevX = x
            prevY = y
            prevT = t
            count++
        }
        if (count == 0) return

        if (count == 1) {
            // a single real sample: draw it as a short flat segment instead of nothing
            linePaint.color = color
            canvas.drawLine(maxOf(prevX - 12f * density, 0f), prevY, prevX, prevY, linePaint)
            return
        }

        areaPath.lineTo(prevX, baseY)
        areaPath.lineTo(firstX, baseY)
        areaPath.close()
        areaPaint.shader = gradient
        canvas.drawPath(areaPath, areaPaint)
        linePaint.color = color
        canvas.drawPath(linePath, linePaint)
    }

    companion object {
        private const val MIN_SCALE_BYTES_PER_S = 4L * 1024L
    }
}
