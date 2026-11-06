package dev.wingky530.larpcode.ime

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout

class LarpCodeImeService : InputMethodService() {
    private var shift = false
    private var shiftButton: Button? = null

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(24, 24, 24))
            setPadding(dp(6), dp(10), dp(6), dp(12))
        }

        addLetterRow(root, "qwertyuiop")
        addLetterRow(root, "asdfghjkl")
        addBottomLetterRow(root)
        addCommandRow(root)

        return root
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun addLetterRow(parent: LinearLayout, letters: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        letters.forEach { letter ->
            row.addView(key(letter.toString(), 1f) { type(letter.toString()) })
        }
        parent.addView(row)
    }

    private fun addBottomLetterRow(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val shiftKey = key(shiftLabel(), 1.7f) {
            shift = !shift
            shiftButton?.text = shiftLabel()
            shiftButton?.setTextColor(if (shift) Color.rgb(24, 24, 24) else Color.WHITE)
            shiftButton?.background = keyBackground(
                if (shift) Color.rgb(122, 193, 255) else Color.rgb(55, 55, 55),
            )
        }
        shiftButton = shiftKey
        row.addView(shiftKey)

        "zxcvbnm".forEach { letter ->
            row.addView(key(letter.toString(), 1f) { type(letter.toString()) })
        }
        row.addView(key("Del", 1.7f) { backspace() })
        parent.addView(row)
    }

    private fun addCommandRow(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(key("Space", 5f) { currentInputConnection?.commitText(" ", 1) })
        row.addView(key("Enter", 2f) { enter() })
        parent.addView(row)
    }

    private fun key(label: String, weight: Float, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = keyBackground(Color.rgb(55, 55, 55))
            layoutParams = LinearLayout.LayoutParams(0, dp(46), weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            }
            setOnClickListener { action() }
        }
    }

    private fun type(letter: String) {
        val text = if (shift) letter.uppercase() else letter
        currentInputConnection?.commitText(text, 1)
    }

    private fun backspace() {
        val connection = currentInputConnection ?: return
        if (!connection.deleteSurroundingText(1, 0)) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
    }

    private fun enter() {
        val connection = currentInputConnection ?: return
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    private fun shiftLabel(): String = if (shift) "SHIFT" else "Shift"

    private fun keyBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            setCornerRadius(dp(6).toFloat())
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }
}
