package dev.wingky530.larpcode.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

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
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 150)
        textSize = 30f
        typeface = Typeface.MONOSPACE
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(122, 193, 255)
        strokeWidth = 3f
    }

    private val buffer = StringBuilder()
    private var cursor = 0
    private var editorInputConnection: EditorInputConnection? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        if (buffer.isEmpty()) {
            canvas.drawText("Tap to focus, then type with any keyboard", 72f, 64f, hintPaint)
        }

        drawBuffer(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            requestFocus()
            context.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            invalidate()
        }
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return EditorInputConnection(this).also { editorInputConnection = it }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return editorInputConnection?.sendKeyEvent(event) ?: super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return editorInputConnection?.sendKeyEvent(event) ?: super.onKeyUp(keyCode, event)
    }

    private fun drawBuffer(canvas: Canvas) {
        val lines = buffer.toString().split('\n')
        val lineHeight = textPaint.textSize * 1.4f
        var baseline = 64f

        lines.take(MAX_VISIBLE_LINES).forEachIndexed { index, line ->
            canvas.drawText((index + 1).toString(), 16f, baseline, gutterPaint)
            canvas.drawText(line, 72f, baseline, textPaint)
            baseline += lineHeight
        }

        val beforeCursor = buffer.substring(0, cursor)
        val lineIndex = beforeCursor.count { it == '\n' }
        val column = beforeCursor.length - beforeCursor.lastIndexOf('\n') - 1
        val line = lines.getOrNull(lineIndex).orEmpty()
        val prefix = line.substring(0, column.coerceAtMost(line.length))
        val x = 72f + textPaint.measureText(prefix)
        val y = 64f + lineIndex * lineHeight
        canvas.drawLine(x, y - textPaint.textSize, x, y + 4f, cursorPaint)
    }

    private fun insert(value: String) {
        if (value.isEmpty()) return
        buffer.insert(cursor, value)
        cursor += value.length
        invalidate()
    }

    private fun deleteBefore(count: Int) {
        if (count <= 0 || cursor <= 0) return
        val start = (cursor - count).coerceAtLeast(0)
        buffer.delete(start, cursor)
        cursor = start
        invalidate()
    }

    private fun deleteAfter(count: Int) {
        if (count <= 0 || cursor >= buffer.length) return
        buffer.delete(cursor, (cursor + count).coerceAtMost(buffer.length))
        invalidate()
    }

    private fun moveCursor(delta: Int) {
        cursor = (cursor + delta).coerceIn(0, buffer.length)
        invalidate()
    }

    private inner class EditorInputConnection(target: View) :
        BaseInputConnection(target, false) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val value = text?.toString() ?: return false
            insert(value)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            return commitText(text, newCursorPosition)
        }

        override fun finishComposingText(): Boolean = true

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deleteBefore(beforeLength)
            deleteAfter(afterLength)
            return true
        }

        override fun deleteSurroundingTextInCodePoints(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            return deleteSurroundingText(beforeLength, afterLength)
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            val start = (cursor - n).coerceAtLeast(0)
            return buffer.substring(start, cursor)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
            val end = (cursor + n).coerceAtMost(buffer.length)
            return buffer.substring(cursor, end)
        }

        override fun getSelectedText(flags: Int): CharSequence? = null

        override fun setSelection(start: Int, end: Int): Boolean {
            cursor = start.coerceIn(0, buffer.length)
            invalidate()
            return true
        }

        override fun getCursorCapsMode(reqModes: Int): Int = 0

        override fun beginBatchEdit(): Boolean = true

        override fun endBatchEdit(): Boolean = true

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when {
                    event.isCtrlPressed || event.isAltPressed -> Unit
                    event.keyCode == KeyEvent.KEYCODE_DEL -> deleteBefore(1)
                    event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> deleteAfter(1)
                    event.keyCode == KeyEvent.KEYCODE_ENTER ||
                        event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> insert("\n")
                    event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> moveCursor(-1)
                    event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursor(1)
                    event.keyCode == KeyEvent.KEYCODE_MOVE_HOME -> {
                        cursor = 0
                        invalidate()
                    }
                    event.keyCode == KeyEvent.KEYCODE_MOVE_END -> {
                        cursor = buffer.length
                        invalidate()
                    }
                    else -> {
                        val unicode = event.unicodeChar
                        if (unicode != 0) insert(unicode.toChar().toString())
                    }
                }
            }
            return true
        }
    }

    companion object {
        private const val MAX_VISIBLE_LINES = 40
    }
}
