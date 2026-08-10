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
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import shark.SharkLog
import shark.explorer.CommandLineAdb
import shark.explorer.DeepLink
import shark.explorer.DeviceHeapDumps
import shark.explorer.HeapSizes
import shark.explorer.NativeBitmapPixels
import shark.explorer.Place
import shark.explorer.ReachabilityStrength
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount
import shark.explorer.jdwp.JdwpBitmaps
import shark.explorer.jdwp.JdwpGc

fun main(args: Array<String>) {
  // A command line that is nothing but links is a courier: Windows and Linux deliver a link by starting a
  // process with it, so this is most of the runs of this app on those two. Answered before anything else
  // and without opening a log file — one file per link would push out every run worth reading, and the
  // last 20 is what a bug report attaches. See [SessionLog].
  if (deliveredToAnotherRun(args)) {
    return
  }
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
    val windows = explorerWindows(arguments)
    // Both before the first window, so that a link arriving while the heap dumps are still opening is one
    // the window queues rather than one that lands on an app not listening yet.
    DeepLinkScheme.takeUrisFromTheOs(windows)
    DeepLinkScheme.registerWithTheOs()
    DeepLinkPeers.listen(windows).use {
      // Whatever no other run claimed, which for a link naming a window that has gone is an empty window
      // saying so. Ours to answer for now: nobody else is going to.
      DeepLinkPeers.deliver(arguments.deepLinks).forEach { windows.open(it) }
      // Heap dump paths on the command line open straight away, which is how this is usually run.
      explorerApplication(windows)
    }
  }
}

/**
 * Hands a command line of nothing but links to the runs that have those windows, and says whether every
 * one of them was taken.
 *
 * False for anything else on the command line, for a link nobody claimed and for a link that doesn't
 * parse — all three are a run that has something to show, and the full path below shows it, with a log
 * file and a window rather than from here.
 */
private fun deliveredToAnotherRun(args: Array<String>): Boolean {
  if (args.isEmpty() || !args.all { DeepLink.looksLikeOne(it) }) {
    return false
  }
  val links = args.map {
    try {
      DeepLink.parse(it)
    } catch (invalidLink: IllegalArgumentException) {
      // Left to the full path, which has somewhere to say this: a courier has no window and, on Windows,
      // no console either.
      return false
    }
  }
  return DeepLinkPeers.deliver(links).isEmpty()
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
private fun explorerApplication(windows: ExplorerWindows) = application {
  val updateNotice = remember { UpdateNotice() }
  // One notepad per place for the whole run, so that a heap dump open in two windows is one set of notes
  // rather than two that overwrite each other. See [ExplorerNotes].
  val notes = remember { ExplorerNotes() }
  // Once per run, not once per window, and off the UI thread: this is a network request, and a window that
  // waits for GitHub to answer before it draws is a window that hangs when GitHub is unreachable.
  LaunchedEffect(updateNotice) {
    withContext(Dispatchers.IO) { UpdateCheck().check() }?.let { updateNotice.offer(it) }
  }
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
        // The OS window this one came out as, which is the one thing following a link needs and Compose
        // keeps no state for: raising a window is AWT's, not the composition's. See [ExplorerWindow].
        DisposableEffect(this.window) {
          window.attachTo(this@Window.window)
          onDispose {}
        }
        MaterialTheme {
          ExplorerApp(
            heapDumpFile = window.heapDumpFile,
            bitmapPixels = window.bitmapPixels,
            onHeapDumpChosen = { file, fetchedPixels ->
              windows.openHeapDump(window, file, fetchedPixels)
            },
            updateNotice = updateNotice,
            notes = notes,
            deepLinkId = window.deepLinkId,
            // The same way a link arriving from the OS is followed, which is what makes a `shark://` link
            // written in a note work wherever it is read from.
            followDeepLink = { link -> DeepLinkPeers.follow(link, windows) },
            linkedPlaces = window.linkedPlaces,
            onLinkedPlaceOpened = { place -> window.linkedPlaceOpened(place) },
            deepLinkProblem = window.deepLinkProblem
          )
        }
      }
    }
  }
}

// Internal, like the rest of this app: nothing outside the module composes it, the module is published
// nowhere, and it takes internal types.
@Composable
internal fun ExplorerApp(
  /** The one heap dump this window shows, null until one has been chosen for it. */
  heapDumpFile: File?,
  /**
   * The pixels of [heapDumpFile]'s bitmaps, when they were fetched off the device along with it. Null for
   * every other way a heap dump gets here, which is most of them.
   */
  bitmapPixels: NativeBitmapPixels? = null,
  /** Where a heap dump chosen from the bar goes, which is a window: see [openHeapDump]. */
  onHeapDumpChosen: (File, NativeBitmapPixels?) -> Unit,
  /**
   * Whether a newer release has been found, shared with every other window of this run. Empty by default so
   * that a test only gets the bar when it is what the test is about.
   */
  updateNotice: UpdateNotice = remember { UpdateNotice() },
  /**
   * The notes of every heap dump this run has open, shared with every other window. Its own by default, so
   * that a test writes into a directory it was given rather than into the notes of whoever is running it.
   */
  notes: ExplorerNotes = remember { ExplorerNotes() },
  /** What a link to a place in this window names it by. See [shark.explorer.DeepLink]. */
  deepLinkId: String = remember { DeepLink.newWindowId() },
  /** Places a link has asked this window for, which its tabs open. See [ExplorerWindow.linkedPlaces]. */
  linkedPlaces: List<Place> = emptyList(),
  onLinkedPlaceOpened: (Place) -> Unit = {},
  /**
   * Where a `shark://` link written in the notes goes. Only the application knows, since routing one is a
   * question about every window of the run, so a window composed without it says so in the log.
   */
  followDeepLink: (DeepLink) -> Unit = { link -> SharkLog.d { "Nothing here to follow $link with" } },
  /**
   * Why this window has no heap dump, for one a link opened because the window it named had gone.
   *
   * Shown where "open an Android heap dump" would be, rather than as a dialog: the window is empty either
   * way, and the two buttons above are what to do about it in both cases.
   */
  deepLinkProblem: String? = null,
  /** Overridden by tests, which have no system clipboard and want to read what would have been copied. */
  copyToClipboard: (String) -> Unit = ::copyTextToClipboard,
  /** Overridden by tests, which have no browser to open a link written in the notes in. */
  openUrl: (String) -> Unit = ::openInBrowser,
  /** Overridden by tests, which have no display to put a file dialog on. */
  chooseHeapDumpFile: () -> File? = ::showHeapDumpFileDialog,
  /** Overridden by tests, which have no device to go back to and no `adb` to ask. */
  deviceHeapDumps: DeviceHeapDumps = remember {
    val adb = CommandLineAdb()
    // A debugger is what reaches into a process for the two things `am dumpheap` can't ask it for on an
    // old enough device: the pixels of a bitmap below API 35, and a collection below API 27.
    DeviceHeapDumps(adb, JdwpBitmaps(adb), JdwpGc(adb))
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
    // Above the heap dump bar, because it is about the app rather than about what is open in it, and
    // because a bar that pushes the map down is one nobody can miss and nobody has to act on.
    UpdateBar(updateNotice)
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
        notes = notes.of(currentState.session.heapDumpFile),
        deepLinkId = deepLinkId,
        linkedPlaces = linkedPlaces,
        onLinkedPlaceOpened = onLinkedPlaceOpened,
        followDeepLink = followDeepLink,
        openUrl = openUrl,
        copyToClipboard = copyToClipboard,
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
          // A window a link opened to say the window it named has gone says that instead of the invitation
          // to open a heap dump: it was opened to carry a message, and the invitation is under it anyway.
          Text(
            deepLinkProblem?.takeIf { currentState is HeapDumpState.None }
              ?: currentState.centerMessage(),
            style = MaterialTheme.typography.bodyLarge
          )
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

/**
 * Says that a newer release exists, and nothing more: a link to it and a way to stop being told.
 *
 * Deliberately not a dialog. Someone opens this app to look at a heap dump, and a modal in front of that
 * to announce a version number would be in the way of the reason they launched it.
 */
@Composable
private fun UpdateBar(updateNotice: UpdateNotice) {
  val update = updateNotice.availableUpdate ?: return
  Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        updateAvailableText(update.version, SharkExplorerVersion.current),
        style = MaterialTheme.typography.bodyMedium
      )
      TextButton(onClick = { openInBrowser(update.releaseUrl) }) {
        Text(DOWNLOAD_UPDATE)
      }
      TextButton(onClick = { updateNotice.dismiss() }) {
        Text(DISMISS_UPDATE)
      }
    }
  }
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

/** A function rather than a constant because the versions are in the middle of it, and a test wants all of it. */
internal fun updateAvailableText(
  version: String,
  currentVersion: String
) = "Shark Explorer $version is available. This run is $currentVersion."

internal const val DOWNLOAD_UPDATE = "Download"
internal const val DISMISS_UPDATE = "Not now"
