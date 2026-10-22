package com.example.answerhelper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 启动动画：
 * 1) 图标淡入 + 从 0.9 放大到 1.0（0 ~ 45%）
 * 2) 电路线条以图标为基准逐段绘出，末端圆点亮起（30% ~ 100%）
 */
class SplashLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val logo: Bitmap? = BitmapFactory.decodeResource(resources, R.drawable.splash_logo)

    /** 跟随系统明暗：浅色主题黑标，深色主题白标 */
    private val ink: Int = androidx.core.content.ContextCompat.getColor(context, R.color.splash_ink)

    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = ink
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ink
    }

    /** 点坐标是相对图标矩形的比例，(0,0)=左上，(1,1)=右下，可为负或大于 1 */
    private class Trace(val points: List<Pair<Float, Float>>, val dotAtEnd: Boolean)

    private val traces = listOf(
        Trace(listOf(-0.24f to 0.03f, 0.02f to 0.03f), true),
        Trace(listOf(-0.15f to 0.10f, -0.04f to 0.10f, -0.04f to 0.22f), false),
        Trace(listOf(-0.24f to 0.03f, -0.24f to 0.28f, -0.11f to 0.28f), false),
        Trace(listOf(-0.28f to 0.54f, -0.11f to 0.54f), true),
        Trace(listOf(1.20f to 0.54f, 1.03f to 0.54f), true),
        Trace(listOf(1.13f to 0.62f, 1.13f to 0.88f, 0.97f to 0.88f), false),
        Trace(listOf(1.07f to 0.68f, 1.07f to 0.80f, 0.99f to 0.80f), false),
        Trace(listOf(0.12f to 1.06f, 0.12f to 1.16f, 0.32f to 1.16f), true)
    )

    private val paths = ArrayList<Path>(traces.size)
    private val measures = ArrayList<PathMeasure>(traces.size)
    private val lengths = ArrayList<Float>(traces.size)
    private val dots = ArrayList<Pair<Float, Float>?>(traces.size)

    private var progress = 0f
    private val logoRect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutLogo(w.toFloat(), h.toFloat(), 1f)
        buildTraces()
    }

    private fun layoutLogo(w: Float, h: Float, scale: Float) {
        val bmp = logo ?: return
        val targetW = min(w, h) * 0.52f * scale
        val ratio = targetW / bmp.width
        val drawW = bmp.width * ratio
        val drawH = bmp.height * ratio
        logoRect.set((w - drawW) / 2f, (h - drawH) / 2f, (w + drawW) / 2f, (h + drawH) / 2f)
    }

    private fun buildTraces() {
        paths.clear(); measures.clear(); lengths.clear(); dots.clear()
        traces.forEach { trace ->
            val path = Path()
            trace.points.forEachIndexed { index, (fx, fy) ->
                val px = logoRect.left + fx * logoRect.width()
                val py = logoRect.top + fy * logoRect.height()
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            val measure = PathMeasure(path, false)
            paths.add(path)
            measures.add(measure)
            lengths.add(measure.length)
            val last = trace.points.last()
            dots.add(
                if (trace.dotAtEnd) logoRect.left + last.first * logoRect.width() to
                    logoRect.top + last.second * logoRect.height() else null
            )
        }
    }

    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 1f)
        layoutLogo(width.toFloat(), height.toFloat(), 0.90f + 0.10f * easeOut((progress / 0.45f).coerceIn(0f, 1f)))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val logoPhase = (progress / 0.45f).coerceIn(0f, 1f)
        val logoAlpha = (logoPhase * 255).toInt()

        logo?.let { bmp ->
            logoPaint.alpha = logoAlpha
            canvas.drawBitmap(bmp, null, logoRect, logoPaint)
        }

        val linePhase = ((progress - 0.30f) / 0.70f).coerceIn(0f, 1f)
        val count = paths.size
        val step = 0.05f

        paths.forEachIndexed { index, _ ->
            val start = index * step
            val local = ((linePhase - start) / (1f - start)).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed

            val dst = Path()
            measures[index].getSegment(0f, lengths[index] * local, dst, true)
            linePaint.alpha = (logoAlpha * 0.9f).toInt().coerceIn(0, 255)
            canvas.drawPath(dst, linePaint)

            dots[index]?.let { (dx, dy) ->
                if (local > 0.9f) {
                    dotPaint.alpha = (((local - 0.9f) / 0.1f) * 255).toInt().coerceIn(0, 255)
                    canvas.drawCircle(dx, dy, dp(4.4f), dotPaint)
                }
            }
        }
    }

    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
