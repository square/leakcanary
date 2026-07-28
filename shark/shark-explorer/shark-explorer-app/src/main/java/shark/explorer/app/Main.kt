package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
  Window(onCloseRequest = ::exitApplication, title = "Shark Explorer") {
    MaterialTheme {
      ExplorerApp()
    }
  }
}

@Composable
fun ExplorerApp() {
  Text("Shark Explorer")
}
