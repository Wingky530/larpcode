package dev.wingky530.larpcode.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

class EditorSurfaceView(context: Context) : View(context) {
    private val backgroundPaint = Paint().apply {
        color = Color.rgb(30, 30, 30)
    }
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 120, 120)
        textSize = 36f
        typeface = Typeface.MONOSPACE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 220, 220)
        textSize = 36f
        typeface = Typeface.MONOSPACE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val lineHeight = textPaint.textSize * 1.4f
        val gutterWidth = 72f
        var baseline = 64f
        repeat(40) { index ->
            canvas.drawText((index + 1).toString(), 16f, baseline, gutterPaint)
            canvas.drawText("LarpCode editor surface", gutterWidth, baseline, textPaint)
            baseline += lineHeight
        }
    }
}
