package shark.explorer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import shark.SharkLog
import shark.explorer.HeapSizes
import shark.explorer.ReachabilityStrength
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount

fun main(args: Array<String>) {
  // Launched from a terminal, so Shark's own diagnostics and any failure to open a heap dump belong
  // on stdout as well as in the window.
  SharkLog.logger = object : SharkLog.Logger {
    override fun d(message: String) = println(message)

    override fun d(
      throwable: Throwable,
      message: String
    ) {
      println(message)
      throwable.printStackTrace()
    }
  }
  explorerApplication(args)
}

private fun explorerApplication(args: Array<String>) = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "Shark Explorer",
    // What Windows and Linux show in the title bar and the window list. macOS takes the dock icon
    // from the process instead, which the build script sets.
    icon = painterResource(APP_ICON),
    state = rememberWindowState(width = 1440.dp, height = 900.dp)
  ) {
    MaterialTheme {
      // A heap dump path on the command line opens straight away, which is how this is usually run.
      ExplorerApp(initialHeapDumpFile = args.firstOrNull()?.let(::File))
    }
  }
}

@Composable
fun ExplorerApp(
  initialHeapDumpFile: File? = null,
  /** Overridden by tests, which have no display to put a file dialog on. */
  chooseHeapDumpFile: () -> File? = ::showHeapDumpFileDialog
) {
  var requestedFile: File? by remember { mutableStateOf(initialHeapDumpFile) }
  var state: HeapDumpState by remember { mutableStateOf(HeapDumpState.None) }

  LaunchedEffect(requestedFile) {
    val file = requestedFile
    if (file == null) {
      state = HeapDumpState.None
      return@LaunchedEffect
    }
    state = HeapDumpState.Opening(file, "Opening ${file.name}")
    state = try {
      val session = HeapDumpSession.open(file) { step ->
        state = HeapDumpState.Opening(file, step)
      }
      val sizes = session.read { it.sizes }
      HeapDumpState.Open(session, sizes).also {
        SharkLog.d { "${it.statusLine()} · ${sizes.strengthsText()}" }
      }
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not open $file" }
      HeapDumpState.Failed(file, throwable.toString())
    }
  }

  val currentState = state
  // Opening another heap dump replaces this state, which is when the previous one has to be closed.
  DisposableEffect(currentState) {
    onDispose { (currentState as? HeapDumpState.Open)?.session?.close() }
  }

  Column(Modifier.fillMaxSize()) {
    HeapDumpBar(
      state = currentState,
      onOpenClick = { chooseHeapDumpFile()?.let { requestedFile = it } }
    )
    if (currentState is HeapDumpState.Open) {
      // Keyed on the session, so that opening another heap dump starts from the whole of it rather than
      // from wherever the previous one was being read.
      key(currentState.session) {
        HeapDumpExplorer(currentState.session, currentState.sizes, Modifier.weight(1f))
      }
    } else {
      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          if (currentState is HeapDumpState.Opening) {
            CircularProgressIndicator()
          }
          Text(currentState.centerMessage(), style = MaterialTheme.typography.bodyLarge)
        }
      }
    }
  }
}

/** Which heap dump the app has open, if any. */
private sealed interface HeapDumpState {

  object None : HeapDumpState

  /** [step] describes what opening the heap dump is currently doing, which takes a while. */
  data class Opening(
    val file: File,
    val step: String
  ) : HeapDumpState

  data class Failed(
    val file: File,
    val message: String
  ) : HeapDumpState

  data class Open(
    val session: HeapDumpSession,
    val sizes: HeapSizes
  ) : HeapDumpState
}

/** Which heap dump is open, and how to open another. Everything else belongs to whatever is showing it. */
@Composable
private fun HeapDumpBar(
  state: HeapDumpState,
  onOpenClick: () -> Unit
) {
  Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
    Row(
      Modifier.fillMaxWidth().padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Button(onClick = onOpenClick) {
        Text(OPEN_HEAP_DUMP)
      }
      Text(state.statusLine(), style = MaterialTheme.typography.bodyMedium)
    }
  }
}

private fun HeapDumpState.statusLine(): String = when (this) {
  HeapDumpState.None -> ""
  is HeapDumpState.Opening -> step
  is HeapDumpState.Failed -> "Could not open ${file.name}"
  // The split between reachable and uncollected is the legend's job now, and it says it per strength.
  is HeapDumpState.Open -> "${session.heapDumpFile.name} · " +
    "${formatByteSize(sizes.totalByteCount)} in ${formatObjectCount(sizes.totalObjectCount)}"
}

/**
 * What the legend says, for the log: how a dump splits up by strength is the least predictable thing
 * about it, so a terminal run should say it rather than only the window.
 */
private fun HeapSizes.strengthsText(): String = ReachabilityStrength.values()
  .filter { byteCountByStrength.getValue(it) > 0L }
  .joinToString(", ") { strength ->
    "${formatByteSize(byteCountByStrength.getValue(strength))} ${strength.displayName.lowercase()}"
  }

private fun HeapDumpState.centerMessage(): String = when (this) {
  HeapDumpState.None -> NO_HEAP_DUMP
  is HeapDumpState.Opening -> step
  is HeapDumpState.Failed -> "${file.name} could not be opened.\n$message"
  is HeapDumpState.Open -> ""
}

/**
 * The platform file picker, through AWT: Compose Multiplatform has none of its own, and this is the
 * native dialog on macOS and Windows.
 */
private fun showHeapDumpFileDialog(): File? {
  val dialog = FileDialog(null as Frame?, "Open heap dump", FileDialog.LOAD)
  dialog.setFilenameFilter { _, name -> name.endsWith(".hprof") }
  dialog.isVisible = true
  val directory = dialog.directory
  val fileName = dialog.file
  return if (directory == null || fileName == null) null else File(directory, fileName)
}

/** Rendered from `icons/shark-explorer-icon.svg`, and the same PNG a Linux package is built with. */
private const val APP_ICON = "shark-explorer-icon.png"

internal const val OPEN_HEAP_DUMP = "Open heap dump…"
internal const val NO_HEAP_DUMP = "Open an Android heap dump to see what retains its memory."
