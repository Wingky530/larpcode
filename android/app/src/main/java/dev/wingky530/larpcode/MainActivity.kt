package dev.wingky530.larpcode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.wingky530.larpcode.editor.EditorSurfaceView
import dev.wingky530.larpcode.ffi.coreVersion

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LarpCodeHome()
                }
            }
        }
    }
}

@Composable
private fun LarpCodeHome() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Text(
            text = "LarpCode ${coreVersion()}",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        AndroidView(
            factory = { context -> EditorSurfaceView(context) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}
