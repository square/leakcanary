package shark.explorer.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.io.File
import shark.SharkLog
import shark.explorer.NativeBitmapPixels

/**
 * One window of the app, which is one heap dump.
 *
 * Plain state rather than a composable's, so that how many windows there are and which heap dump each
 * one shows can be tested without a display: `explorerApplication` only draws a window per entry.
 */
internal class ExplorerWindow(
  heapDumpFile: File?,
  bitmapPixels: NativeBitmapPixels? = null,
  /**
   * How many steps down and to the right of centre this window opens: no two windows on screen are at
   * the same one, so a window opened from another lands beside it rather than exactly over it. Where
   * that is, is `cascadedPosition`.
   */
  val cascade: Int = 0,
  /** What this run calls its windows, in front of the heap dump. See [ExplorerArguments.titlePrefix]. */
  val titlePrefix: String? = null
) {

  /** Null in the window the app starts with when it was given no heap dump to open. */
  var heapDumpFile: File? by mutableStateOf(heapDumpFile)

  /**
   * The pixels of [heapDumpFile]'s bitmaps when they were fetched off the device along with it, which is
   * a dump taken with the box ticked and nothing else. Set only with [heapDumpFile], by [openHeapDump]:
   * pixels of one dump shown for another would be pictures of the wrong app.
   */
  var bitmapPixels: NativeBitmapPixels? by mutableStateOf(bitmapPixels)

  /**
   * Which heap dump this window is, and which run it belongs to, for the window list of the OS: several
   * windows all called after the app tell you nothing about which one to switch to.
   */
  val title: String
    get() = listOfNotNull(titlePrefix, heapDumpFile?.name ?: APP_NAME).joinToString(TITLE_SEPARATOR)
}

/**
 * A window per heap dump named on the command line, or one window with none — something has to carry
 * the button that opens the first one.
 */
internal fun explorerWindows(arguments: ExplorerArguments): SnapshotStateList<ExplorerWindow> =
  mutableStateListOf<ExplorerWindow>().apply {
    val titlePrefix = arguments.titlePrefix
    if (arguments.heapDumpFiles.isEmpty()) {
      add(ExplorerWindow(null, titlePrefix = titlePrefix))
    } else {
      addAll(
        arguments.heapDumpFiles.mapIndexed { index, file ->
          ExplorerWindow(file, cascade = index, titlePrefix = titlePrefix)
        }
      )
    }
  }

/**
 * Shows [heapDumpFile] in [window] if it has none yet, and in a window of its own if it has.
 *
 * A window showing a heap dump keeps it, so the windows on screen are the heap dumps open: comparing
 * two dumps is looking at both, and closing a window closes one of them rather than the trail through
 * several. The window that shows nothing has nothing to keep, so the first heap dump opens in it.
 */
internal fun MutableList<ExplorerWindow>.openHeapDump(
  window: ExplorerWindow,
  heapDumpFile: File,
  /** Fetched with the dump, for a device whose dump can't carry the pixels of its bitmaps. */
  bitmapPixels: NativeBitmapPixels? = null
) {
  val isWindowOfItsOwn = window.heapDumpFile != null
  if (isWindowOfItsOwn) {
    // Named after the same run as the window it was opened from: every window of one explorer belongs to
    // whatever that explorer was started for, however many heap dumps end up open in it.
    add(
      ExplorerWindow(
        heapDumpFile,
        bitmapPixels,
        cascade = freeCascade(),
        titlePrefix = window.titlePrefix
      )
    )
  } else {
    window.heapDumpFile = heapDumpFile
    window.bitmapPixels = bitmapPixels
  }
  // One run's log covers every window of that run, and what tells the lines apart afterwards is the
  // thread each was written from, so which window a heap dump went to is worth a line of its own.
  SharkLog.d {
    val where = if (isWindowOfItsOwn) "a window of its own" else "the window that had no heap dump"
    "Opening ${heapDumpFile.name} in $where"
  }
}

/** Between what a run is called and which heap dump a window shows, as elsewhere in this window. */
private const val TITLE_SEPARATOR = " · "

/** The first step of the cascade no window is at, which is where the next window goes. */
private fun List<ExplorerWindow>.freeCascade(): Int =
  generateSequence(0, Int::inc).first { step -> none { it.cascade == step } }
