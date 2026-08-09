package shark.explorer.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.awt.EventQueue
import java.awt.Frame
import java.io.File
import shark.SharkLog
import shark.explorer.DeepLink
import shark.explorer.NativeBitmapPixels
import shark.explorer.Place

/**
 * One window of the app, which is one heap dump.
 *
 * Plain state rather than a composable's, so that how many windows there are and which heap dump each
 * one shows can be tested without a display: `explorerApplication` only draws a window per entry.
 */
internal class ExplorerWindow(
  heapDumpFile: File? = null,
  bitmapPixels: NativeBitmapPixels? = null,
  /**
   * How many steps down and to the right of centre this window opens: no two windows on screen are at
   * the same one, so a window opened from another lands beside it rather than exactly over it. Where
   * that is, is `cascadedPosition`.
   */
  val cascade: Int = 0,
  /** What this run calls its windows, in front of the heap dump. See [ExplorerArguments.titlePrefix]. */
  val titlePrefix: String? = null,
  /**
   * What a link to a place in this window names it by, and the whole of what makes deep linking work: a
   * link is answered by the window it was copied from and by no other, however many windows have the same
   * heap dump open. See [DeepLink].
   */
  val deepLinkId: String = DeepLink.newWindowId(),
  /** Why this window is empty, for one opened by a link naming a window that had already gone. */
  deepLinkProblem: String? = null
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
   * Said in the middle of a window with nothing in it. See [ExplorerWindows.open].
   */
  var deepLinkProblem: String? by mutableStateOf(deepLinkProblem)

  /**
   * Places a link has asked this window for and whose tabs are not open yet, oldest first.
   *
   * A list handed over rather than a call into the tabs, because a link arrives on whichever thread the OS
   * or another run of this app delivered it on, and the tabs of a window are a composable's state. So this
   * is the one point where the two meet: a link puts a place here, and the window takes it on the next
   * frame. Which also settles what a link that arrives while the heap dump is still opening does — it
   * waits, rather than being dropped.
   */
  var linkedPlaces: List<Place> by mutableStateOf(emptyList())
    private set

  /** Which heap dump this window is, and which run it belongs to, for the window list of the OS. */
  val title: String
    get() = listOfNotNull(titlePrefix, heapDumpFile?.name ?: APP_NAME).joinToString(TITLE_SEPARATOR)

  /**
   * The OS window this one is drawn as, for the one thing a link needs that Compose keeps no state for:
   * putting the window in front of whatever is covering it.
   */
  private var frame: Frame? = null

  /** Asks this window for [place], which its tabs open on the next frame. See [linkedPlaces]. */
  fun goToLinked(place: Place) {
    linkedPlaces = linkedPlaces + place
  }

  /** Taken by the tabs, which is what makes the window stop asking for it. */
  fun linkedPlaceOpened(place: Place) {
    linkedPlaces = linkedPlaces - place
  }

  fun attachTo(frame: Frame) {
    this.frame = frame
  }

  /**
   * Puts this window in front, which is half of what following a link means.
   *
   * On the event thread because it touches AWT and a link arrives on whatever thread delivered it. Nothing
   * here asks to be permanently above other windows: a link is a request to look at something once, and a
   * window that stays on top afterwards is one the reader has to put back.
   */
  fun bringToFront() {
    val frame = frame
    if (frame == null) {
      // Which is what a link that raised nothing at all looks like from here.
      SharkLog.d { "Window $deepLinkId has no frame yet, so nothing was brought to the front" }
      return
    }
    EventQueue.invokeLater {
      // A minimised window is raised by restoring it; toFront alone leaves it in the dock or the taskbar.
      if (frame.extendedState and Frame.ICONIFIED != 0) {
        frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
      }
      // Which application is in front is the process's to ask for, and toFront only orders this one's
      // windows: without this the right window is raised behind whatever the reader was looking at.
      DeepLinkScheme.bringProcessToFront()
      frame.toFront()
      frame.requestFocus()
    }
  }
}

/**
 * Every window of this run, and the way in for anything that isn't drawing one.
 *
 * Hoisted out of the composable that draws the windows because a link arrives from outside the app — the
 * OS, another run of it, this run's own command line — and has to find a window from a thread that is
 * composing nothing. See [ExplorerWindow.linkedPlaces].
 */
internal class ExplorerWindows(
  /** Put in front of every window title of this run. See [ExplorerArguments.titlePrefix]. */
  private val titlePrefix: String? = null,
  /** One Compose window is drawn per entry, so a window opening or closing is an edit of this. */
  private val windows: SnapshotStateList<ExplorerWindow> = mutableStateListOf()
) : MutableList<ExplorerWindow> by windows {

  /** Whether a window of this run answers to [windowId], which is what a link asks before it is handed over. */
  fun holds(windowId: String): Boolean = any { it.deepLinkId == windowId }

  /**
   * Follows [link]: the window it names opens the place in a new tab and comes to the front.
   *
   * A link whose window has gone gets an empty window saying so rather than silence. Silence is the one
   * answer that can't be told from the app having failed to start, and a link is usually followed from
   * somewhere that cannot see whether this app did anything at all.
   */
  fun open(link: DeepLink) {
    val window = firstOrNull { it.deepLinkId == link.windowId }
    if (window == null) {
      SharkLog.d { "No window of this run is ${link.windowId}: opening one to say so" }
      add(
        ExplorerWindow(
          cascade = freeCascade(),
          titlePrefix = titlePrefix,
          deepLinkProblem = noSuchWindow(link.windowId)
        )
      )
      return
    }
    SharkLog.d { "A link asked window ${link.windowId} for ${link.place}" }
    window.goToLinked(link.place)
    window.bringToFront()
  }

  /** The first step of the cascade no window is at, which is where the next window goes. */
  fun freeCascade(): Int = generateSequence(0, Int::inc).first { step -> none { it.cascade == step } }

  companion object {
    /** What an empty window opened by a link naming a window that has gone says in the middle of it. */
    fun noSuchWindow(windowId: String): String =
      "No window called $windowId is open. A link leads to one window of one run of this app, so it " +
        "stops working when that window is closed or the app is restarted."
  }
}

/**
 * A window per heap dump named on the command line, or one window with none — something has to carry
 * the button that opens the first one.
 */
internal fun explorerWindows(arguments: ExplorerArguments): ExplorerWindows =
  ExplorerWindows(arguments.titlePrefix).apply {
    val titlePrefix = arguments.titlePrefix
    if (arguments.heapDumpFiles.isEmpty()) {
      add(ExplorerWindow(null, titlePrefix = titlePrefix))
    } else {
      arguments.heapDumpFiles.forEachIndexed { index, file ->
        add(ExplorerWindow(file, cascade = index, titlePrefix = titlePrefix))
      }
    }
    // Which window is which, for reading a link out of a log afterwards: a link names one of these and
    // nothing else in the file says what the name stands for.
    SharkLog.d { "Windows of this run: ${joinToString { "${it.deepLinkId} ${it.title}" }}" }
  }

/**
 * Shows [heapDumpFile] in [window] if it has none yet, and in a window of its own if it has.
 *
 * A window showing a heap dump keeps it, so the windows on screen are the heap dumps open: comparing
 * two dumps is looking at both, and closing a window closes one of them rather than the trail through
 * several. The window that shows nothing has nothing to keep, so the first heap dump opens in it.
 */
internal fun ExplorerWindows.openHeapDump(
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
    // Whatever a link said about this window being empty is answered now that it isn't.
    window.deepLinkProblem = null
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
