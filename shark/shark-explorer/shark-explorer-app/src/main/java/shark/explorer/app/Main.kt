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
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import kotlinx.coroutines.CancellationException
import shark.SharkLog
import shark.explorer.CommandLineAdb
import shark.explorer.DeviceHeapDumps
import shark.explorer.HeapSizes
import shark.explorer.NativeBitmapPixels
import shark.explorer.ReachabilityStrength
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount
import shark.explorer.jdwp.JdwpBitmaps

fun main(args: Array<String>) {
  // Launched from a terminal, so Shark's own diagnostics and any failure to open a heap dump belong on
  // stdout as well as in the window — and in a file, so that a session someone reports on can be read
  // back after it. See [installLogging].
  installLogging().use {
    SharkLog.d { "Started with ${if (args.isEmpty()) "no arguments" else args.joinToString(" ")}" }
    val arguments = try {
      ExplorerArguments.parse(args.toList())
    } catch (invalidArguments: IllegalArgumentException) {
      // Said on stdout and in the log rather than thrown, because a command line nobody can read is a
      // message to whoever typed it and not a crash to report.
      SharkLog.d { invalidArguments.message.orEmpty() }
      return@use
    }
    // What the arguments above were taken to mean, which is not obvious from them: a shell, Gradle's
    // `--args` and a run configuration each split a quoted title differently, and a title split in two
    // reads as one on the line above.
    SharkLog.d { "Read that as $arguments" }
    nameThisRun(arguments.titlePrefix ?: APP_NAME)
    // Heap dump paths on the command line open straight away, which is how this is usually run.
    explorerApplication(arguments)
  }
}

/**
 * Calls this process [name] wherever the OS names the process rather than one of its windows, which on
 * macOS is the menu bar and the app switcher.
 *
 * A run is one process and several windows, so the one name it gets is what the run was started for —
 * the `--title` its windows share — rather than which heap dump is open. Without this a run is called
 * after whatever launched it, which is the same for every explorer on screen.
 *
 * Called before the first window, which is not a matter of taste: AWT reads this property as it starts
 * and registers the process under whatever it says then, so setting it once a window is up changes
 * nothing. It is the same name `-Xdock:name` sets — that JVM argument only puts it in an environment
 * variable AWT reads at that same moment — which is why nothing that launches this has to pass one.
 *
 * The macOS dock is the one place this does not reach: it names a process after the bundle it was
 * launched from and takes no notice of either. Naming that is the `runNamed` Gradle task's job.
 */
private fun nameThisRun(name: String) {
  // Read on macOS only. Windows and Linux name a process after its window, which already says this.
  System.setProperty("apple.awt.application.name", name)
  SharkLog.d { "This run is called \"$name\" where the OS names the process rather than a window" }
}

/** One window per heap dump open, which is what [openHeapDump] keeps true as more are opened. */
private fun explorerApplication(arguments: ExplorerArguments) = application {
  val windows = remember { explorerWindows(arguments) }
  windows.forEach { window ->
    // Keyed on the window, so that closing one doesn't hand its size and position to the next one along.
    key(window) {
      Window(
        onCloseRequest = {
          windows -= window
          // The app is its windows, so there is nothing left to come back to.
          if (windows.isEmpty()) {
            exitApplication()
          }
        },
        title = window.title,
        // What Windows and Linux show in the title bar and the window list. macOS takes the dock icon
        // from the process instead, which the build script sets.
        icon = painterResource(APP_ICON),
        state = rememberWindowState(
          width = WINDOW_WIDTH,
          height = WINDOW_HEIGHT,
          position = cascadedPosition(window.cascade)
        )
      ) {
        MaterialTheme {
          ExplorerApp(
            heapDumpFile = window.heapDumpFile,
            bitmapPixels = window.bitmapPixels,
            onHeapDumpChosen = { file, fetchedPixels ->
              windows.openHeapDump(window, file, fetchedPixels)
            }
          )
        }
      }
    }
  }
}

@Composable
fun ExplorerApp(
  /** The one heap dump this window shows, null until one has been chosen for it. */
  heapDumpFile: File?,
  /**
   * The pixels of [heapDumpFile]'s bitmaps, when they were fetched off the device along with it. Null for
   * every other way a heap dump gets here, which is most of them.
   */
  bitmapPixels: NativeBitmapPixels? = null,
  /** Where a heap dump chosen from the bar goes, which is a window: see [openHeapDump]. */
  onHeapDumpChosen: (File, NativeBitmapPixels?) -> Unit,
  /** Overridden by tests, which have no display to put a file dialog on. */
  chooseHeapDumpFile: () -> File? = ::showHeapDumpFileDialog,
  /** Overridden by tests, which have no device to go back to and no `adb` to ask. */
  deviceHeapDumps: DeviceHeapDumps = remember {
    val adb = CommandLineAdb()
    // The debugger is what gets the pixels of a bitmap off API 26 to 34, where no heap dump has them.
    DeviceHeapDumps(adb, JdwpBitmaps(adb))
  }
) {
  var state: HeapDumpState by remember { mutableStateOf(HeapDumpState.None) }
  var takesHeapDump by remember { mutableStateOf(false) }

  LaunchedEffect(heapDumpFile) {
    val file = heapDumpFile
    if (file == null) {
      state = HeapDumpState.None
      return@LaunchedEffect
    }
    state = HeapDumpState.Opening(file, "Opening ${file.name}")
    state = try {
      val session = HeapDumpSession.open(file) { step ->
        state = HeapDumpState.Opening(file, step)
      }
      val sizes = session.read("the sizes of ${file.name}") { it.sizes }
      HeapDumpState.Open(session, sizes, bitmapPixels).also {
        SharkLog.d { "${it.statusLine()} · ${sizes.strengthsText()}" }
      }
    } catch (cancellation: CancellationException) {
      // Not a failure to show: this window is closing, or is already opening another heap dump. Rethrown
      // rather than caught below, because the state of a window nobody is looking at must not become the
      // state of the window that replaced it.
      throw cancellation
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not open $file" }
      HeapDumpState.Failed(file, throwable.toString())
    }
  }

  val currentState = state
  // Closing the window is what ends the session: it's the only thing that takes this heap dump off
  // screen, since another one opens in a window of its own.
  DisposableEffect(currentState) {
    onDispose { (currentState as? HeapDumpState.Open)?.session?.close() }
  }

  if (takesHeapDump) {
    TakeHeapDumpDialog(
      deviceHeapDumps = deviceHeapDumps,
      // The dump lands in a window the same way a chosen file does: it is one, and one window per heap
      // dump is what keeps the windows on screen the dumps open. Its bitmaps' pixels travel with it,
      // since they were fetched for this dump and belong to no other.
      onDumped = { file, fetchedPixels ->
        onHeapDumpChosen(file, fetchedPixels)
        takesHeapDump = false
      },
      onDismiss = { takesHeapDump = false }
    )
  }

  Column(Modifier.fillMaxSize()) {
    HeapDumpBar(
      state = currentState,
      onOpenClick = {
        val chosenFile = chooseHeapDumpFile()
        if (chosenFile == null) {
          // Which is why nothing happened, and the only way to tell that from a dialog that failed to
          // open at all.
          SharkLog.d { "No heap dump chosen" }
        } else {
          // No pixels: a file chosen off the disk is whatever it has in it, and only taking a dump can
          // go and fetch what it hasn't.
          onHeapDumpChosen(chosenFile, null)
        }
      },
      onTakeClick = { takesHeapDump = true }
    )
    if (currentState is HeapDumpState.Open) {
      HeapDumpExplorer(
        session = currentState.session,
        sizes = currentState.sizes,
        deviceHeapDumps = deviceHeapDumps,
        fetchedBitmapPixels = currentState.bitmapPixels,
        modifier = Modifier.weight(1f)
      )
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
    val sizes: HeapSizes,
    /** Fetched off the device along with the dump, for a device whose dump can't carry them. */
    val bitmapPixels: NativeBitmapPixels?
  ) : HeapDumpState
}

/** Which heap dump is open, and how to open another. Everything else belongs to whatever is showing it. */
@Composable
private fun HeapDumpBar(
  state: HeapDumpState,
  onOpenClick: () -> Unit,
  onTakeClick: () -> Unit
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
      // Next to opening one, because taking one off a device ends in the same place: a file to open. And
      // a dump taken here arrives with its bitmaps in it, which one taken by hand only does with `-b`.
      Button(onClick = onTakeClick) {
        Text(TAKE_HEAP_DUMP)
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
 * Where a window opens: centred, then [cascade] steps down and to the right.
 *
 * Placing it is ours to do because macOS centres every window it is left to place itself, and a window
 * landing exactly over the one it was opened from is what "the heap dump was replaced" looks like.
 */
private fun cascadedPosition(cascade: Int): WindowPosition {
  // The screen minus whatever the OS keeps for itself, so a window doesn't open under the menu bar.
  val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
  val centredX = ((screen.width.dp - WINDOW_WIDTH) / 2).coerceAtLeast(0.dp)
  val centredY = ((screen.height.dp - WINDOW_HEIGHT) / 2).coerceAtLeast(0.dp)
  // As many steps as there is room for below and to the right of centred, then over from the centre
  // again: two windows sharing a spot beats one opening half off the screen.
  val stepCount = (minOf(centredX, centredY) / CASCADE_STEP).toInt().coerceAtLeast(1)
  val step = CASCADE_STEP * (cascade % stepCount)
  return WindowPosition(x = screen.x.dp + centredX + step, y = screen.y.dp + centredY + step)
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

/** What a window with no heap dump in it is called, since it has no better name to go by. */
internal const val APP_NAME = "Shark Explorer"

/**
 * How large a window opens, which is also the size the UI tests drive one at: what a test presses is only
 * where a user would find it if the window it presses is the window that opens.
 */
internal val WINDOW_WIDTH = 1440.dp
internal val WINDOW_HEIGHT = 900.dp

/** How far a window opens from the one before it, which is about the height of a title bar. */
private val CASCADE_STEP = 28.dp

internal const val OPEN_HEAP_DUMP = "Open heap dump…"
internal const val NO_HEAP_DUMP = "Open an Android heap dump to see what retains its memory."
