package com.ourspace.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * 近 7 天每日屏幕时长的自绘柱状图。
 *
 * 完全用 onDraw + Canvas / Paint 画，不引入任何第三方图表库。
 * 数据由外部通过 setData(dates, valuesMs) 传入，日期升序，最后一根是今天。
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var dates: List<String> = emptyList()
    private var values: List<Long> = emptyList()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barTodayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * resources.displayMetrics.scaledDensity
    }

    init {
        barPaint.color = context.getColor(R.color.chart_bar)
        barTodayPaint.color = context.getColor(R.color.chart_bar_today)
        axisPaint.color = context.getColor(R.color.divider)
        textPaint.color = context.getColor(R.color.text_secondary)
        valuePaint.color = context.getColor(R.color.text_primary)
    }

    /** 传入日期与对应的毫秒值（一一对应，日期升序） */
    fun setData(dates: List<String>, valuesMs: List<Long>) {
        this.dates = dates
        this.values = valuesMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dates.isEmpty() || values.isEmpty()) {
            drawEmpty(canvas)
            return
        }

        val n = minOf(dates.size, values.size)
        val w = width.toFloat()
        val h = height.toFloat()

        val paddingTop = 18f * resources.displayMetrics.density
        val paddingBottom = 30f * resources.displayMetrics.density
        val chartH = h - paddingTop - paddingBottom
        if (chartH <= 0f) return

        // 顶部留出数值文字的高度，底部留出日期文字
        val maxValue = max(1L, values.maxOrNull() ?: 1L)
        val slot = w / n
        val barW = minOf(slot * 0.55f, 40f * resources.displayMetrics.density)

        // 基线
        val baseY = paddingTop + chartH
        canvas.drawLine(0f, baseY, w, baseY, axisPaint)

        for (i in 0 until n) {
            val v = values[i]
            val ratio = (v.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
            val barH = ratio * (chartH - 6f)
            val left = i * slot + (slot - barW) / 2f
            val right = left + barW
            val top = baseY - barH
            val rect = RectF(left, top, right, baseY)

            val isToday = i == n - 1
            canvas.drawRoundRect(
                rect,
                3f * resources.displayMetrics.density,
                3f * resources.displayMetrics.density,
                if (isToday) barTodayPaint else barPaint
            )

            // 值文字（画在柱子上方）
            if (v > 0L) {
                canvas.drawText(shortDuration(v), left + barW / 2f, top - 3f, valuePaint)
            }
            // 日期文字
            canvas.drawText(TimeUtil.shortLabel(dates[i]), left + barW / 2f, baseY + 14f, textPaint)
        }
    }

    private fun drawEmpty(canvas: Canvas) {
        canvas.drawText(
            "暂无数据",
            width / 2f,
            height / 2f,
            textPaint
        )
    }

    /** 柱顶的短时长文案：1.5h / 45m / 3m */
    private fun shortDuration(ms: Long): String {
        val totalMin = ms / 60_000
        return when {
            totalMin >= 60 -> {
                val hours = totalMin / 60.0
                val rounded = Math.round(hours * 10.0) / 10.0
                "${rounded}h"
            }
            totalMin >= 1 -> "${totalMin}m"
            else -> "<1m"
        }
    }
}
