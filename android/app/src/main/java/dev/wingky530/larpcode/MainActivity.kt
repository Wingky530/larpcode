package dev.wingky530.larpcode

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.wingky530.larpcode.editor.EditorSurfaceView
import dev.wingky530.larpcode.ffi.coreVersion

class MainActivity : ComponentActivity() {
    private var editorView: EditorSurfaceView? = null
    private var initialEditorText: String = ""
    private var initialCursor: Int = 0
    private var initialScrollOffset: Int = 0
    private var initialComposingStart: Int = -1
    private var initialComposingEnd: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initialEditorText = savedInstanceState?.getString(KEY_EDITOR_TEXT)
            ?: sampleEditorText()
        initialCursor = savedInstanceState?.getInt(KEY_EDITOR_CURSOR, 0) ?: 0
        initialScrollOffset = savedInstanceState?.getInt(KEY_EDITOR_SCROLL_OFFSET, 0) ?: 0
        initialComposingStart = savedInstanceState?.getInt(KEY_EDITOR_COMPOSING_START, -1) ?: -1
        initialComposingEnd = savedInstanceState?.getInt(KEY_EDITOR_COMPOSING_END, -1) ?: -1

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LarpCodeHome(
                        initialText = initialEditorText,
                        initialCursor = initialCursor,
                        initialScrollOffset = initialScrollOffset,
                        initialComposingStart = initialComposingStart,
                        initialComposingEnd = initialComposingEnd,
                        onEditorReady = { editorView = it },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        editorView?.let { view ->
            outState.putString(KEY_EDITOR_TEXT, view.snapshotText())
            outState.putInt(KEY_EDITOR_CURSOR, view.snapshotCursor())
            outState.putInt(KEY_EDITOR_SCROLL_OFFSET, view.snapshotScrollOffset())
            outState.putInt(KEY_EDITOR_COMPOSING_START, view.snapshotComposingStart())
            outState.putInt(KEY_EDITOR_COMPOSING_END, view.snapshotComposingEnd())
        }
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val KEY_EDITOR_TEXT = "editor_text"
        private const val KEY_EDITOR_CURSOR = "editor_cursor"
        private const val KEY_EDITOR_SCROLL_OFFSET = "editor_scroll_offset"
        private const val KEY_EDITOR_COMPOSING_START = "editor_composing_start"
        private const val KEY_EDITOR_COMPOSING_END = "editor_composing_end"

        private fun sampleEditorText(): String = buildString {
            appendLine("LarpCode editor spike")
            appendLine()
            repeat(40) { index ->
                val number = (index + 1).toString().padStart(2, '0')
                appendLine("$number  The quick brown fox jumps over the lazy dog.")
            }
        }
    }
}

@Composable
private fun LarpCodeHome(
    initialText: String,
    initialCursor: Int,
    initialScrollOffset: Int,
    initialComposingStart: Int,
    initialComposingEnd: Int,
    onEditorReady: (EditorSurfaceView) -> Unit,
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Text(
            text = "LarpCode ${coreVersion()} / IME spike",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
            ) {
                Text("Enable keyboard")
            }
            Button(
                onClick = {
                    context.getSystemService(InputMethodManager::class.java)
                        ?.showInputMethodPicker()
                },
            ) {
                Text("Choose keyboard")
            }
        }
        Text(
            text = "Enable LarpCode Keyboard, select it, then tap the editor.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        AndroidView(
            factory = { viewContext ->
                EditorSurfaceView(viewContext).apply {
                    restoreState(
                        text = initialText,
                        cursor = initialCursor,
                        scrollOffset = initialScrollOffset,
                        composingStart = initialComposingStart,
                        composingEnd = initialComposingEnd,
                    )
                    onEditorReady(this)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}
