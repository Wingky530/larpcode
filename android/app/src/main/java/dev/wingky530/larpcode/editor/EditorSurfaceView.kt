package dev.wingky530.larpcode.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Parcel
import android.os.Parcelable
import android.text.InputType
import android.util.TypedValue
import android.view.AbsSavedState
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import dev.wingky530.larpcode.R
import kotlin.math.roundToInt

class EditorSurfaceView(context: Context) : View(context) {
    private val backgroundPaint = Paint().apply {
        color = Color.rgb(30, 30, 30)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 220, 220)
        textSize = sp(15f)
        typeface = Typeface.MONOSPACE
    }
    private val gutterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 120, 120)
        textSize = sp(12f)
        typeface = Typeface.MONOSPACE
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 150)
        textSize = sp(12f)
        typeface = Typeface.MONOSPACE
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(122, 193, 255)
        strokeWidth = dp(2f)
    }
    private val composingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(122, 193, 255)
        strokeWidth = dp(2f)
    }

    private val contentTop = dp(16f)
    private val textLeft = dp(56f)
    private val bottomPadding = dp(24f)
    private val lineHeight = textPaint.textSize * 1.4f
    private val charWidth = textPaint.measureText("M").coerceAtLeast(1f)
    private val scroller = OverScroller(context)

    private val buffer = StringBuilder()
    private var cursor = 0
    private var composingStart = -1
    private var composingEnd = -1
    private var scrollOffset = 0
    private var cursorVisible = true
    private var editorInputConnection: EditorInputConnection? = null

    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (!hasFocus()) return
            cursorVisible = !cursorVisible
            invalidate()
            postDelayed(this, 500L)
        }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                if (!scroller.isFinished) scroller.forceFinished(true)
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                requestEditorFocus()
                moveCursorToPoint(e.x, e.y)
                showKeyboard()
                performClick()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (!scroller.isFinished) scroller.forceFinished(true)
                scrollOffset = (scrollOffset + distanceY.roundToInt())
                    .coerceIn(0, maxScroll())
                invalidate()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val max = maxScroll()
                if (max <= 0) return false
                scroller.fling(
                    0,
                    scrollOffset,
                    0,
                    -velocityY.roundToInt(),
                    0,
                    0,
                    0,
                    max,
                )
                postInvalidateOnAnimation()
                return true
            }
        },
    )

    init {
        id = R.id.editor_surface
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
        isSaveEnabled = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)

        val lines = buffer.toString().split('\n')
        val starts = lineStarts(lines)

        if (buffer.isEmpty() && !hasFocus()) {
            canvas.drawText(
                "Tap to focus, then type with any keyboard",
                textLeft,
                contentTop + textPaint.textSize,
                hintPaint,
            )
        }

        drawBuffer(canvas, lines, starts)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrollOffset = scrollOffset.coerceIn(0, maxScroll())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = cursor
        outAttrs.initialSelEnd = cursor
        outAttrs.initialCapsMode = 0
        return EditorInputConnection(this).also { editorInputConnection = it }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return if (handleKeyEvent(event)) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return if (handleKeyEvent(event)) true else super.onKeyUp(keyCode, event)
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) resetBlink() else stopBlink()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (hasFocus()) resetBlink()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkRunnable)
        super.onDetachedFromWindow()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.coerceIn(0, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState() ?: AbsSavedState.EMPTY_STATE
        return SavedState(superState).apply {
            text = buffer.toString()
            cursor = this@EditorSurfaceView.cursor
            scrollOffset = this@EditorSurfaceView.scrollOffset
            composingStart = this@EditorSurfaceView.composingStart
            composingEnd = this@EditorSurfaceView.composingEnd
        }
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState ?: AbsSavedState.EMPTY_STATE)
        restoreState(
            state.text,
            state.cursor,
            state.scrollOffset,
            state.composingStart,
            state.composingEnd,
        )
    }

    fun snapshotText(): String = buffer.toString()

    fun snapshotCursor(): Int = cursor

    fun snapshotScrollOffset(): Int = scrollOffset

    fun snapshotComposingStart(): Int = composingStart

    fun snapshotComposingEnd(): Int = composingEnd

    fun restoreState(
        text: String,
        cursor: Int,
        scrollOffset: Int,
        composingStart: Int,
        composingEnd: Int,
    ) {
        buffer.clear()
        buffer.append(text)
        this.cursor = cursor.coerceIn(0, buffer.length)
        this.composingStart = composingStart
        this.composingEnd = composingEnd
        this.scrollOffset = scrollOffset.coerceIn(0, maxScroll())
        if (
            this.composingStart < 0 ||
            this.composingEnd <= this.composingStart ||
            this.composingEnd > buffer.length
        ) {
            clearComposing()
        }
        editorInputConnection = null
        resetBlink()
        invalidate()
    }

    private fun requestEditorFocus() {
        if (!isFocused) {
            if (!requestFocusFromTouch()) requestFocus()
        }
    }

    private fun showKeyboard() {
        post {
            val manager = context.getSystemService(InputMethodManager::class.java)
                ?: return@post
            if (!manager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)) {
                postDelayed({
                    context.getSystemService(InputMethodManager::class.java)
                        ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                }, 150L)
            }
        }
        postDelayed({
            val manager = context.getSystemService(InputMethodManager::class.java)
                ?: return@postDelayed
            if (!manager.isActive(this)) {
                manager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 300L)
    }

    private fun moveCursorToPoint(x: Float, y: Float) {
        val lines = buffer.toString().split('\n')
        val starts = lineStarts(lines)
        val contentY = y + scrollOffset - contentTop
        val lineIndex = (contentY / lineHeight).toInt()
            .coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val line = lines[lineIndex]
        val column = ((x - textLeft) / charWidth).roundToInt()
            .coerceIn(0, line.length)
        cursor = (starts[lineIndex] + column).coerceIn(0, buffer.length)
        clearComposing()
        afterEdit()
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.isCtrlPressed || event.isAltPressed) return false

        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                clearComposing()
                deleteBefore(1)
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                clearComposing()
                deleteAfter(1)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                clearComposing()
                insert("\n")
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                clearComposing()
                moveCursor(-1)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                clearComposing()
                moveCursor(1)
            }
            KeyEvent.KEYCODE_MOVE_HOME -> {
                clearComposing()
                cursor = 0
                afterEdit()
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                clearComposing()
                cursor = buffer.length
                afterEdit()
            }
            else -> {
                val unicode = event.unicodeChar
                if (unicode == 0) return false
                clearComposing()
                insert(unicode.toChar().toString())
            }
        }
        return true
    }

    private fun insert(value: String) {
        if (value.isEmpty()) return
        buffer.insert(cursor, value)
        cursor += value.length
        afterEdit()
    }

    private fun deleteBefore(count: Int) {
        if (count <= 0 || cursor <= 0) return
        val start = (cursor - count).coerceAtLeast(0)
        buffer.delete(start, cursor)
        cursor = start
        afterEdit()
    }

    private fun deleteAfter(count: Int) {
        if (count <= 0 || cursor >= buffer.length) return
        buffer.delete(cursor, (cursor + count).coerceAtMost(buffer.length))
        afterEdit()
    }

    private fun moveCursor(delta: Int) {
        cursor = (cursor + delta).coerceIn(0, buffer.length)
        afterEdit()
    }

    private fun afterEdit() {
        cursor = cursor.coerceIn(0, buffer.length)
        if (composingStart >= 0) {
            composingStart = composingStart.coerceIn(0, buffer.length)
            composingEnd = composingEnd.coerceIn(composingStart, buffer.length)
            if (composingStart == composingEnd) clearComposing()
        }
        ensureCursorVisible()
        resetBlink()
        invalidate()
    }

    private fun hasComposing(): Boolean {
        return composingStart >= 0 && composingEnd > composingStart
    }

    private fun clearComposing() {
        composingStart = -1
        composingEnd = -1
    }

    private fun resetBlink() {
        cursorVisible = true
        removeCallbacks(blinkRunnable)
        if (hasFocus()) postDelayed(blinkRunnable, 500L)
        invalidate()
    }

    private fun stopBlink() {
        removeCallbacks(blinkRunnable)
        cursorVisible = false
        invalidate()
    }

    private fun ensureCursorVisible() {
        val lineIndex = cursorLineIndex()
        val lineTop = contentTop + lineIndex * lineHeight - scrollOffset
        val lineBottom = lineTop + lineHeight
        val visibleBottom = height - bottomPadding
        when {
            lineTop < contentTop -> {
                scrollOffset = (scrollOffset - (contentTop - lineTop)).roundToInt()
            }
            lineBottom > visibleBottom -> {
                scrollOffset = (scrollOffset + (lineBottom - visibleBottom)).roundToInt()
            }
        }
        scrollOffset = scrollOffset.coerceIn(0, maxScroll())
    }

    private fun cursorLineIndex(): Int {
        return buffer.substring(0, cursor).count { it == '\n' }
    }

    private fun maxScroll(): Int {
        val lineCount = buffer.count { it == '\n' } + 1
        val contentHeight = contentTop + lineCount * lineHeight + bottomPadding
        return (contentHeight - height).roundToInt().coerceAtLeast(0)
    }

    private fun lineStarts(lines: List<String>): IntArray {
        val starts = IntArray(lines.size)
        var offset = 0
        for (index in lines.indices) {
            starts[index] = offset
            offset += lines[index].length + 1
        }
        return starts
    }

    private fun drawBuffer(canvas: Canvas, lines: List<String>, starts: IntArray) {
        if (lines.isEmpty()) return
        val first = ((scrollOffset - contentTop) / lineHeight).toInt().coerceAtLeast(0)
        val last = ((scrollOffset + height - contentTop) / lineHeight).toInt()
            .coerceAtMost(lines.lastIndex)

        if (first <= last) {
            for (index in first..last) {
                val lineTop = contentTop + index * lineHeight - scrollOffset
                val baseline = lineTop + textPaint.textSize
                val number = (index + 1).toString()
                val numberX = textLeft - dp(8f) - gutterPaint.measureText(number)
                canvas.drawText(number, numberX, baseline, gutterPaint)
                canvas.drawText(lines[index], textLeft, baseline, textPaint)
                drawComposingUnderline(canvas, lines, starts, index, lineTop)
            }
        }

        drawCursor(canvas, lines, starts)
    }

    private fun drawComposingUnderline(
        canvas: Canvas,
        lines: List<String>,
        starts: IntArray,
        index: Int,
        lineTop: Float,
    ) {
        if (!hasComposing()) return
        val lineStart = starts[index]
        val lineEnd = lineStart + lines[index].length
        val from = maxOf(composingStart, lineStart)
        val to = minOf(composingEnd, lineEnd)
        if (from >= to) return

        val startX = textLeft + textPaint.measureText(
            lines[index].substring(0, from - lineStart),
        )
        val endX = textLeft + textPaint.measureText(
            lines[index].substring(0, to - lineStart),
        )
        val y = lineTop + lineHeight - dp(3f)
        canvas.drawLine(startX, y, endX, y, composingPaint)
    }

    private fun drawCursor(canvas: Canvas, lines: List<String>, starts: IntArray) {
        if (!hasFocus() || !cursorVisible) return
        val lineIndex = cursorLineIndex()
        if (lineIndex !in lines.indices) return
        val line = lines[lineIndex]
        val column = (cursor - starts[lineIndex]).coerceIn(0, line.length)
        val lineTop = contentTop + lineIndex * lineHeight - scrollOffset
        if (lineTop + lineHeight < 0f || lineTop > height) return

        val x = textLeft + textPaint.measureText(line.substring(0, column))
        val top = lineTop + dp(2f)
        val bottom = lineTop + lineHeight - dp(2f)
        canvas.drawLine(x, top, x, bottom, cursorPaint)
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value,
            resources.displayMetrics,
        )
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics,
        )
    }

    class SavedState : View.BaseSavedState {
        var text: String = ""
        var cursor: Int = 0
        var scrollOffset: Int = 0
        var composingStart: Int = -1
        var composingEnd: Int = -1

        constructor(superState: Parcelable?) : super(superState)

        constructor(source: Parcel) : super(source) {
            text = source.readString().orEmpty()
            cursor = source.readInt()
            scrollOffset = source.readInt()
            composingStart = source.readInt()
            composingEnd = source.readInt()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeString(text)
            out.writeInt(cursor)
            out.writeInt(scrollOffset)
            out.writeInt(composingStart)
            out.writeInt(composingEnd)
        }

        companion object {
            @JvmField
            val CREATOR: Parcelable.Creator<SavedState> =
                object : Parcelable.Creator<SavedState> {
                    override fun createFromParcel(source: Parcel): SavedState {
                        return SavedState(source)
                    }

                    override fun newArray(size: Int): Array<SavedState?> {
                        return arrayOfNulls(size)
                    }
                }
        }
    }

    private inner class EditorInputConnection(target: View) :
        BaseInputConnection(target, false) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val value = text?.toString() ?: return false
            val start = if (hasComposing()) composingStart else cursor
            val end = if (hasComposing()) composingEnd else cursor
            buffer.replace(start, end, value)
            cursor = (start + value.length).coerceIn(0, buffer.length)
            clearComposing()
            afterEdit()
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val value = text?.toString() ?: return false
            val start = if (hasComposing()) composingStart else cursor
            val end = if (hasComposing()) composingEnd else cursor
            buffer.replace(start, end, value)
            cursor = (start + value.length).coerceIn(0, buffer.length)
            if (value.isEmpty()) {
                clearComposing()
            } else {
                composingStart = start
                composingEnd = start + value.length
            }
            afterEdit()
            return true
        }

        override fun finishComposingText(): Boolean {
            clearComposing()
            invalidate()
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            val from = minOf(start, end).coerceIn(0, buffer.length)
            val to = maxOf(start, end).coerceIn(0, buffer.length)
            if (from == to) {
                clearComposing()
            } else {
                composingStart = from
                composingEnd = to
            }
            invalidate()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            clearComposing()
            val before = beforeLength.coerceAtLeast(0)
            val after = afterLength.coerceAtLeast(0)
            if (before > 0) {
                val start = (cursor - before).coerceAtLeast(0)
                buffer.delete(start, cursor)
                cursor = start
            }
            if (after > 0) {
                val end = (cursor + after).coerceAtMost(buffer.length)
                buffer.delete(cursor, end)
            }
            afterEdit()
            return true
        }

        override fun deleteSurroundingTextInCodePoints(
            beforeLength: Int,
            afterLength: Int,
        ): Boolean {
            return deleteSurroundingText(beforeLength, afterLength)
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            if (n <= 0) return ""
            val start = (cursor - n).coerceAtLeast(0)
            return buffer.substring(start, cursor)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
            if (n <= 0) return ""
            val end = (cursor + n).coerceAtMost(buffer.length)
            return buffer.substring(cursor, end)
        }

        override fun getSelectedText(flags: Int): CharSequence? = null

        override fun setSelection(start: Int, end: Int): Boolean {
            cursor = start.coerceIn(0, buffer.length)
            clearComposing()
            afterEdit()
            return true
        }

        override fun getCursorCapsMode(reqModes: Int): Int = 0

        override fun beginBatchEdit(): Boolean = true

        override fun endBatchEdit(): Boolean = true

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            handleKeyEvent(event)
            return true
        }
    }
}
