package app.musicplayer.desktop

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.launch

fun main() = application {
    val owner = rememberCoroutineScope()
    var startupError by remember { mutableStateOf(false) }
    val graph = remember {
        runCatching { DesktopGraph(owner) }
            .getOrElse {
                startupError = true
                null
            }
    }
    var closing by remember { mutableStateOf(false) }
    Window(
        onCloseRequest = {
            if (!closing) {
                closing = true
                owner.launch {
                    try {
                        graph?.close()
                    } finally {
                        exitApplication()
                    }
                }
            }
        },
        title = "Molpy",
        state = rememberWindowState(width = 1100.dp, height = 760.dp),
    ) {
        if (graph != null) DesktopScreen(graph)
        else if (startupError) Text("Не удалось открыть локальное хранилище Molpy.")
    }
}
