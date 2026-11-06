package dev.wingky530.larpcode.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Parcel
import android.os.Parcelable
import android.text.InputType
import android.util.TypedValue
import android.view.AbsSavedState
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.PopupWindow
import android.widget.TextView
import dev.wingky530.larpcode.R
import kotlin.math.roundToInt

class EditorSurfaceView(context: Context) : View(context) {
    private enum class DragHandle { START, END }

    private data class HandlePosition(val x: Float, val y: Float)

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
    private val selectionPaint = Paint().apply {
        color = Color.argb(86, 122, 193, 255)
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(122, 193, 255)
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }

    private val contentTop = dp(16f)
    private val textLeft = dp(56f)
    private val bottomPadding = dp(24f)
    private val contentRightPadding = dp(16f)
    private val lineHeight = textPaint.textSize * 1.4f
    private val charWidth = textPaint.measureText("M").coerceAtLeast(1f)
    private val handleRadius = dp(7f)
    private val handleTouchRadius = dp(26f)
    private val toolbarMargin = dp(8f)
    private val scroller = OverScroller(context)

    private val buffer = StringBuilder()
    private var cursor = 0
    private var composingStart = -1
    private var composingEnd = -1
    private var scrollOffset = 0
    private var scrollXOffset = 0
    private var selectionStart = -1
    private var selectionEnd = -1
    private var cursorVisible = true
    private var dragHandle: DragHandle? = null
    private var editorInputConnection: EditorInputConnection? = null
    private var selectionToolbar: PopupWindow? = null

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
                dismissSelectionToolbar()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                dismissSelectionToolbar()
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
                clearSelection()
                dismissSelectionToolbar()
                scrollOffset = (scrollOffset + distanceY.roundToInt())
                    .coerceIn(0, maxScroll())
                scrollXOffset = (scrollXOffset + distanceX.roundToInt())
                    .coerceIn(0, maxHorizontalScroll())
                invalidate()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                clearSelection()
                dismissSelectionToolbar()
                val maxX = maxHorizontalScroll()
                val maxY = maxScroll()
                if (maxX <= 0 && maxY <= 0) return false
                scroller.fling(
                    scrollXOffset,
                    scrollOffset,
                    -velocityX.roundToInt(),
                    -velocityY.roundToInt(),
                    0,
                    maxX,
                    0,
                    maxY,
                )
                postInvalidateOnAnimation()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                dismissSelectionToolbar()
                requestEditorFocus()
                if (buffer.isEmpty()) return
                val range = wordRangeAt(offsetForPoint(e.x, e.y))
                selectionStart = range.first
                selectionEnd = range.second
                cursor = selectionEnd
                clearComposing()
                resetBlink()
                invalidate()
                showSelectionToolbar()
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

        if (buffer.isEmpty() && !hasFocus() && !hasSelection()) {
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
        scrollXOffset = scrollXOffset.coerceIn(0, maxHorizontalScroll())
        dismissSelectionToolbar()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val handle = findHandleAt(event.x, event.y)
            if (handle != null) {
                dragHandle = handle
                dismissSelectionToolbar()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        }

        val handle = dragHandle
        if (handle != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    autoScrollForHandleDrag(event.x, event.y)
                    updateSelectionHandle(handle, offsetForPoint(event.x, event.y))
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    autoScrollForHandleDrag(event.x, event.y)
                    updateSelectionHandle(handle, offsetForPoint(event.x, event.y))
                    dragHandle = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    showSelectionToolbar()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragHandle = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    showSelectionToolbar()
                    return true
                }
            }
        }

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
        outAttrs.initialSelStart = if (hasSelection()) selectionStart else cursor
        outAttrs.initialSelEnd = if (hasSelection()) selectionEnd else cursor
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
        if (gainFocus) {
            resetBlink()
        } else {
            stopBlink()
            dismissSelectionToolbar()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (hasFocus()) resetBlink()
    }

    override fun onDetachedFromWindow() {
        dismissSelectionToolbar()
        removeCallbacks(blinkRunnable)
        super.onDetachedFromWindow()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXOffset = scroller.currX.coerceIn(0, maxHorizontalScroll())
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
            scrollXOffset = this@EditorSurfaceView.scrollXOffset
            selectionStart = this@EditorSurfaceView.selectionStart
            selectionEnd = this@EditorSurfaceView.selectionEnd
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
            state.scrollXOffset,
            state.selectionStart,
            state.selectionEnd,
            state.composingStart,
            state.composingEnd,
        )
    }

    fun snapshotText(): String = buffer.toString()

    fun snapshotCursor(): Int = cursor

    fun snapshotScrollOffset(): Int = scrollOffset

    fun snapshotScrollXOffset(): Int = scrollXOffset

    fun snapshotSelectionStart(): Int = selectionStart

    fun snapshotSelectionEnd(): Int = selectionEnd

    fun snapshotComposingStart(): Int = composingStart

    fun snapshotComposingEnd(): Int = composingEnd

    fun restoreState(
        text: String,
        cursor: Int,
        scrollOffset: Int,
        scrollXOffset: Int,
        selectionStart: Int,
        selectionEnd: Int,
        composingStart: Int,
        composingEnd: Int,
    ) {
        buffer.clear()
        buffer.append(text)
        this.cursor = cursor.coerceIn(0, buffer.length)
        this.composingStart = composingStart
        this.composingEnd = composingEnd
        this.scrollOffset = scrollOffset.coerceIn(0, maxScroll())
        this.scrollXOffset = scrollXOffset.coerceIn(0, maxHorizontalScroll())
        this.selectionStart = selectionStart.coerceIn(-1, buffer.length)
        this.selectionEnd = selectionEnd.coerceIn(-1, buffer.length)
        if (this.selectionStart < 0 || this.selectionEnd <= this.selectionStart) {
            clearSelection()
        } else {
            this.cursor = this.selectionEnd
        }
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

    private fun offsetForPoint(x: Float, y: Float): Int {
        val lines = buffer.toString().split('\n')
        val starts = lineStarts(lines)
        val contentY = y + scrollOffset - contentTop
        val lineIndex = (contentY / lineHeight).toInt()
            .coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        val line = lines[lineIndex]
        val contentX = x + scrollXOffset - textLeft
        val column = (contentX / charWidth).roundToInt()
            .coerceIn(0, line.length)
        return (starts[lineIndex] + column).coerceIn(0, buffer.length)
    }

    private fun moveCursorToPoint(x: Float, y: Float) {
        cursor = offsetForPoint(x, y)
        clearSelection()
        clearComposing()
        afterEdit()
    }

    private fun wordRangeAt(offset: Int): Pair<Int, Int> {
        if (buffer.isEmpty()) return 0 to 0
        var index = offset.coerceIn(0, buffer.length - 1)

        if (!isWordChar(buffer[index])) {
            var left = index
            while (left >= 0 && !isWordChar(buffer[left])) left--
            if (left >= 0) {
                index = left
            } else {
                var right = index
                while (right < buffer.length && !isWordChar(buffer[right])) right++
                if (right >= buffer.length) {
                    val start = offset.coerceIn(0, buffer.length - 1)
                    return start to (start + 1)
                }
                index = right
            }
        }

        var start = index
        var end = index + 1
        while (start > 0 && isWordChar(buffer[start - 1])) start--
        while (end < buffer.length && isWordChar(buffer[end])) end++
        return start to end
    }

    private fun isWordChar(value: Char): Boolean {
        return value.isLetterOrDigit() || value == '_'
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
                clearSelection()
                dismissSelectionToolbar()
                cursor = 0
                afterEdit()
            }
            KeyEvent.KEYCODE_MOVE_END -> {
                clearComposing()
                clearSelection()
                dismissSelectionToolbar()
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
        if (hasSelection()) {
            buffer.delete(selectionStart, selectionEnd)
            cursor = selectionStart
            clearSelection()
        }
        buffer.insert(cursor, value)
        cursor += value.length
        clearComposing()
        dismissSelectionToolbar()
        afterEdit()
    }

    private fun deleteBefore(count: Int) {
        if (hasSelection()) {
            buffer.delete(selectionStart, selectionEnd)
            cursor = selectionStart
            clearSelection()
            clearComposing()
            dismissSelectionToolbar()
            afterEdit()
            return
        }
        if (count <= 0 || cursor <= 0) return
        val start = (cursor - count).coerceAtLeast(0)
        buffer.delete(start, cursor)
        cursor = start
        afterEdit()
    }

    private fun deleteAfter(count: Int) {
        if (hasSelection()) {
            buffer.delete(selectionStart, selectionEnd)
            cursor = selectionStart
            clearSelection()
            clearComposing()
            dismissSelectionToolbar()
            afterEdit()
            return
        }
        if (count <= 0 || cursor >= buffer.length) return
        buffer.delete(cursor, (cursor + count).coerceAtMost(buffer.length))
        afterEdit()
    }

    private fun moveCursor(delta: Int) {
        if (hasSelection()) {
            cursor = if (delta < 0) selectionStart else selectionEnd
            clearSelection()
            clearComposing()
            dismissSelectionToolbar()
            afterEdit()
            return
        }
        cursor = (cursor + delta).coerceIn(0, buffer.length)
        afterEdit()
    }

    private fun afterEdit() {
        cursor = cursor.coerceIn(0, buffer.length)
        if (selectionStart >= 0) {
            val start = selectionStart.coerceIn(0, buffer.length)
            val end = selectionEnd.coerceIn(start, buffer.length)
            if (start == end) {
                clearSelection()
            } else {
                selectionStart = start
                selectionEnd = end
                cursor = end
            }
        }
        if (composingStart >= 0) {
            composingStart = composingStart.coerceIn(0, buffer.length)
            composingEnd = composingEnd.coerceIn(composingStart, buffer.length)
            if (composingStart == composingEnd) clearComposing()
        }
        ensureCursorVisible()
        resetBlink()
        invalidate()
    }

    private fun hasSelection(): Boolean {
        return selectionStart >= 0 && selectionEnd > selectionStart
    }

    private fun clearSelection() {
        selectionStart = -1
        selectionEnd = -1
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
        val lines = buffer.toString().split('\n')
        val starts = lineStarts(lines)
        val lineIndex = cursorLineIndex()
        val line = lines[lineIndex]
        val column = (cursor - starts[lineIndex]).coerceIn(0, line.length)
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

        val cursorX = textLeft + textPaint.measureText(line.substring(0, column)) - scrollXOffset
        val visibleRight = width - contentRightPadding
        when {
            cursorX < textLeft -> {
                scrollXOffset = (scrollXOffset - (textLeft - cursorX)).roundToInt()
            }
            cursorX > visibleRight -> {
                scrollXOffset = (scrollXOffset + (cursorX - visibleRight)).roundToInt()
            }
        }

        scrollOffset = scrollOffset.coerceIn(0, maxScroll())
        scrollXOffset = scrollXOffset.coerceIn(0, maxHorizontalScroll())
    }

    private fun cursorLineIndex(): Int {
        return buffer.substring(0, cursor).count { it == '\n' }
    }

    private fun lineIndexForOffset(offset: Int): Int {
        val limit = offset.coerceIn(0, buffer.length)
        return buffer.substring(0, limit).count { it == '\n' }
    }

    private fun maxScroll(): Int {
        val lineCount = buffer.count { it == '\n' } + 1
        val contentHeight = contentTop + lineCount * lineHeight + bottomPadding
        return (contentHeight - height).roundToInt().coerceAtLeast(0)
    }

    private fun maxHorizontalScroll(): Int {
        if (buffer.isEmpty()) return 0
        var maxWidth = 0f
        var start = 0
        for (index in 0..buffer.length) {
            if (index == buffer.length || buffer[index] == '\n') {
                val lineWidth = textPaint.measureText(buffer.substring(start, index))
                if (lineWidth > maxWidth) maxWidth = lineWidth
                start = index + 1
            }
        }
        val contentWidth = textLeft + maxWidth + contentRightPadding
        return (contentWidth - width).roundToInt().coerceAtLeast(0)
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

    private fun copySelection() {
        if (!hasSelection()) return
        clipboard()?.setPrimaryClip(
            ClipData.newPlainText("LarpCode", buffer.substring(selectionStart, selectionEnd)),
        )
        dismissSelectionToolbar()
        invalidate()
    }

    private fun cutSelection() {
        if (!hasSelection()) return
        clipboard()?.setPrimaryClip(
            ClipData.newPlainText("LarpCode", buffer.substring(selectionStart, selectionEnd)),
        )
        val start = selectionStart
        buffer.delete(selectionStart, selectionEnd)
        cursor = start
        clearSelection()
        clearComposing()
        dismissSelectionToolbar()
        afterEdit()
    }

    private fun pasteClipboard() {
        val text = clipboard()
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            .orEmpty()
        if (text.isEmpty()) {
            dismissSelectionToolbar()
            return
        }
        clearComposing()
        insert(text)
        dismissSelectionToolbar()
    }

    private fun selectAll() {
        if (buffer.isEmpty()) {
            clearSelection()
            dismissSelectionToolbar()
            return
        }
        selectionStart = 0
        selectionEnd = buffer.length
        cursor = buffer.length
        clearComposing()
        resetBlink()
        invalidate()
        showSelectionToolbar()
    }

    private fun clipboard(): ClipboardManager? {
        return context.getSystemService(ClipboardManager::class.java)
    }

    private fun showSelectionToolbar() {
        if (!hasSelection()) {
            dismissSelectionToolbar()
            return
        }
        if (!isAttachedToWindow || width <= 0 || height <= 0) return

        dismissSelectionToolbar()

        val content = buildSelectionToolbar()
        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        )
        popup.isOutsideTouchable = true
        popup.isFocusable = false
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        popup.elevation = dp(8f)
        popup.setOnDismissListener {
            if (selectionToolbar === popup) selectionToolbar = null
        }

        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )

        val lines = buffer.toString().split('\n')
        val starts = lineStarts(lines)
        val startPoint = handlePosition(selectionStart, false, lines, starts)
        val endPoint = handlePosition(selectionEnd, true, lines, starts)
        val anchorX = when {
            startPoint.x in textLeft..width.toFloat() -> startPoint.x
            endPoint.x in textLeft..width.toFloat() -> endPoint.x
            else -> width / 2f
        }
        val anchorY = minOf(startPoint.y, endPoint.y)
        val location = IntArray(2)
        getLocationOnScreen(location)
        val metrics = resources.displayMetrics
        val margin = toolbarMargin.roundToInt()
        val popupWidth = content.measuredWidth
        val popupHeight = content.measuredHeight
        val maxX = (metrics.widthPixels - popupWidth - margin).coerceAtLeast(margin)
        val screenX = (location[0] + anchorX - popupWidth / 2f)
            .roundToInt()
            .coerceIn(margin, maxX)
        val above = location[1] + anchorY - popupHeight - margin
        val below = location[1] + anchorY + lineHeight.roundToInt() + margin
        val maxY = (metrics.heightPixels - popupHeight - margin).coerceAtLeast(margin)
        val screenY = if (above >= margin.toFloat()) above else below.coerceAtMost(maxY.toFloat())

        popup.showAtLocation(this, Gravity.NO_GRAVITY, screenX, screenY.roundToInt())
        selectionToolbar = popup
    }

    private fun buildSelectionToolbar(): LinearLayout {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                dp(4f).roundToInt(),
                dp(4f).roundToInt(),
                dp(4f).roundToInt(),
                dp(4f).roundToInt(),
            )
            background = GradientDrawable().apply {
                setColor(Color.rgb(48, 48, 48))
                cornerRadius = dp(12f)
            }
            elevation = dp(6f)
        }

        val actions = listOf<Pair<String, () -> Unit>>(
            "Copy" to { copySelection() },
            "Cut" to { cutSelection() },
            "Paste" to { pasteClipboard() },
            "Select All" to { selectAll() },
        )

        actions.forEach { (label, action) ->
            val item = TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(
                    dp(12f).roundToInt(),
                    dp(8f).roundToInt(),
                    dp(12f).roundToInt(),
                    dp(8f).roundToInt(),
                )
                isClickable = true
                isFocusable = false
                setOnClickListener { action() }
            }
            layout.addView(item)
        }

        return layout
    }

    private fun dismissSelectionToolbar() {
        selectionToolbar?.dismiss()
        selectionToolbar = null
    }

    private fun handlePosition(
        offset: Int,
        isEnd: Boolean,
        lines: List<String>,
        starts: IntArray,
    ): HandlePosition {
        val safeOffset = offset.coerceIn(0, buffer.length)
        val lineIndex = lineIndexForOffset(safeOffset).coerceIn(0, lines.lastIndex)
        val line = lines[lineIndex]
        val column = (safeOffset - starts[lineIndex]).coerceIn(0, line.length)
        val x = textLeft + textPaint.measureText(line.substring(0, column)) - scrollXOffset
        val lineTop = contentTop + lineIndex * lineHeight - scrollOffset
        val atLineStart = column == 0 && lineIndex > 0
        val y = when {
            !isEnd -> lineTop + dp(1f)
            atLineStart -> lineTop + dp(1f)
            else -> lineTop + lineHeight - dp(1f)
        }
        return HandlePosition(x, y)
    }

    private fun findHandleAt(x: Float, y: Float): DragHandle? {
        if (!hasSelection()) return null
        val lines = buffer.toString().split('\n')
        val starts = lineStarts(lines)
        val start = handlePosition(selectionStart, false, lines, starts)
        val end = handlePosition(selectionEnd, true, lines, starts)
        val startDistance = distanceSquared(x, y, start)
        val endDistance = distanceSquared(x, y, end)
        val radiusSquared = handleTouchRadius * handleTouchRadius
        return when {
            startDistance <= radiusSquared && startDistance <= endDistance -> DragHandle.START
            endDistance <= radiusSquared -> DragHandle.END
            else -> null
        }
    }

    private fun updateSelectionHandle(handle: DragHandle, offset: Int) {
        when (handle) {
            DragHandle.START -> {
                if (offset > selectionEnd) {
                    selectionStart = selectionEnd
                    selectionEnd = offset
                    dragHandle = DragHandle.END
                } else {
                    selectionStart = offset
                }
            }
            DragHandle.END -> {
                if (offset < selectionStart) {
                    selectionEnd = selectionStart
                    selectionStart = offset
                    dragHandle = DragHandle.START
                } else {
                    selectionEnd = offset
                }
            }
        }
        if (hasSelection()) {
            cursor = selectionEnd
        } else {
            clearSelection()
        }
        clearComposing()
        invalidate()
    }

    private fun autoScrollForHandleDrag(x: Float, y: Float) {
        val margin = dp(24f)
        val step = dp(10f)
        var changed = false

        if (y < margin) {
            scrollOffset = (scrollOffset - step).roundToInt().coerceAtLeast(0)
            changed = true
        } else if (y > height - margin) {
            scrollOffset = (scrollOffset + step).roundToInt().coerceAtMost(maxScroll())
            changed = true
        }

        if (x < textLeft + margin) {
            scrollXOffset = (scrollXOffset - step).roundToInt().coerceAtLeast(0)
            changed = true
        } else if (x > width - margin) {
            scrollXOffset = (scrollXOffset + step).roundToInt().coerceAtMost(maxHorizontalScroll())
            changed = true
        }

        if (changed) invalidate()
    }

    private fun distanceSquared(x: Float, y: Float, point: HandlePosition): Float {
        val dx = x - point.x
        val dy = y - point.y
        return dx * dx + dy * dy
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
            }
        }

        canvas.save()
        canvas.clipRect(textLeft, 0f, width.toFloat(), height.toFloat())

        if (first <= last) {
            for (index in first..last) {
                val lineTop = contentTop + index * lineHeight - scrollOffset
                val baseline = lineTop + textPaint.textSize
                drawSelectionForLine(canvas, lines, starts, index, lineTop)
                canvas.drawText(
                    lines[index],
                    textLeft - scrollXOffset,
                    baseline,
                    textPaint,
                )
                drawComposingUnderline(canvas, lines, starts, index, lineTop)
            }
        }

        drawCursor(canvas, lines, starts)
        canvas.restore()

        drawSelectionHandles(canvas, lines, starts)
    }

    private fun drawSelectionForLine(
        canvas: Canvas,
        lines: List<String>,
        starts: IntArray,
        index: Int,
        lineTop: Float,
    ) {
        if (!hasSelection()) return
        val lineStart = starts[index]
        val lineEnd = lineStart + lines[index].length
        val from = maxOf(selectionStart, lineStart)
        val to = minOf(selectionEnd, lineEnd)
        if (from > to) return

        val startX = textLeft + textPaint.measureText(
            lines[index].substring(0, from - lineStart),
        ) - scrollXOffset
        var endX = textLeft + textPaint.measureText(
            lines[index].substring(0, to - lineStart),
        ) - scrollXOffset
        if (to == lineEnd && selectionEnd > lineEnd) endX += charWidth * 0.5f
        if (endX > startX) {
            canvas.drawRect(startX, lineTop, endX, lineTop + lineHeight, selectionPaint)
        }
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

        val contentX = textLeft - scrollXOffset
        val startX = contentX + textPaint.measureText(
            lines[index].substring(0, from - lineStart),
        )
        val endX = contentX + textPaint.measureText(
            lines[index].substring(0, to - lineStart),
        )
        val y = lineTop + lineHeight - dp(3f)
        canvas.drawLine(startX, y, endX, y, composingPaint)
    }

    private fun drawCursor(canvas: Canvas, lines: List<String>, starts: IntArray) {
        if (!hasFocus() || !cursorVisible || hasSelection()) return
        val lineIndex = cursorLineIndex()
        if (lineIndex !in lines.indices) return
        val line = lines[lineIndex]
        val column = (cursor - starts[lineIndex]).coerceIn(0, line.length)
        val lineTop = contentTop + lineIndex * lineHeight - scrollOffset
        if (lineTop + lineHeight < 0f || lineTop > height) return

        val x = textLeft + textPaint.measureText(line.substring(0, column)) - scrollXOffset
        val top = lineTop + dp(2f)
        val bottom = lineTop + lineHeight - dp(2f)
        canvas.drawLine(x, top, x, bottom, cursorPaint)
    }

    private fun drawSelectionHandles(canvas: Canvas, lines: List<String>, starts: IntArray) {
        if (!hasSelection()) return
        drawHandle(canvas, handlePosition(selectionStart, false, lines, starts))
        drawHandle(canvas, handlePosition(selectionEnd, true, lines, starts))
    }

    private fun drawHandle(canvas: Canvas, point: HandlePosition) {
        if (point.y < -handleRadius || point.y > height + handleRadius) return
        canvas.drawCircle(point.x, point.y, handleRadius, handleFillPaint)
        canvas.drawCircle(point.x, point.y, handleRadius, handleStrokePaint)
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
        var scrollXOffset: Int = 0
        var selectionStart: Int = -1
        var selectionEnd: Int = -1
        var composingStart: Int = -1
        var composingEnd: Int = -1

        constructor(superState: Parcelable?) : super(superState)

        constructor(source: Parcel) : super(source) {
            text = source.readString().orEmpty()
            cursor = source.readInt()
            scrollOffset = source.readInt()
            scrollXOffset = source.readInt()
            selectionStart = source.readInt()
            selectionEnd = source.readInt()
            composingStart = source.readInt()
            composingEnd = source.readInt()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeString(text)
            out.writeInt(cursor)
            out.writeInt(scrollOffset)
            out.writeInt(scrollXOffset)
            out.writeInt(selectionStart)
            out.writeInt(selectionEnd)
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
            val start: Int
            val end: Int
            when {
                hasSelection() -> {
                    start = selectionStart
                    end = selectionEnd
                }
                hasComposing() -> {
                    start = composingStart
                    end = composingEnd
                }
                else -> {
                    start = cursor
                    end = cursor
                }
            }
            buffer.replace(start, end, value)
            cursor = (start + value.length).coerceIn(0, buffer.length)
            clearSelection()
            clearComposing()
            dismissSelectionToolbar()
            afterEdit()
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val value = text?.toString() ?: return false
            val start: Int
            val end: Int
            when {
                hasSelection() -> {
                    start = selectionStart
                    end = selectionEnd
                }
                hasComposing() -> {
                    start = composingStart
                    end = composingEnd
                }
                else -> {
                    start = cursor
                    end = cursor
                }
            }
            buffer.replace(start, end, value)
            cursor = (start + value.length).coerceIn(0, buffer.length)
            clearSelection()
            if (value.isEmpty()) {
                clearComposing()
            } else {
                composingStart = start
                composingEnd = start + value.length
            }
            dismissSelectionToolbar()
            afterEdit()
            return true
        }

        override fun finishComposingText(): Boolean {
            clearComposing()
            invalidate()
            return true
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            clearSelection()
            dismissSelectionToolbar()
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
            if (hasSelection()) {
                val start = selectionStart
                buffer.delete(selectionStart, selectionEnd)
                cursor = start
                clearSelection()
                clearComposing()
                dismissSelectionToolbar()
                afterEdit()
                return true
            }

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

        override fun getSelectedText(flags: Int): CharSequence? {
            if (!hasSelection()) return null
            return buffer.substring(selectionStart, selectionEnd)
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            val from = start.coerceIn(0, buffer.length)
            val to = end.coerceIn(0, buffer.length)
            if (from == to) {
                cursor = from
                clearSelection()
            } else {
                selectionStart = minOf(from, to)
                selectionEnd = maxOf(from, to)
                cursor = selectionEnd
            }
            clearComposing()
            dismissSelectionToolbar()
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
