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
   * The heap dump this window has open, once it is open, for everything that isn't drawing the window.
   *
   * Which today is one thing: an agent reaching in from outside the app. Here for the same reason
   * [linkedPlaces] is — a socket thread has to find a window, and what the window has open is a
   * composable's state — and set by [ExplorerApp] as the session opens and closes. Null while a heap dump is
   * being opened, for a window that has none, and for one whose dump failed to open.
   */
  var openHeapDump: OpenHeapDump? by mutableStateOf(null)

  /**
   * Why the heap dump this window was given could not be opened, and null while nothing has gone wrong.
   *
   * The other half of [openHeapDump] for a reader outside the composition, and not its opposite: a window
   * whose dump is still being indexed has neither, which is what tells something waiting for that dump that
   * waiting is still the right thing to do. Set by [ExplorerApp] the same way, and cleared as another dump
   * opens here.
   */
  var openProblem: String? by mutableStateOf(null)

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
  val titlePrefix: String? = null,
  /** One Compose window is drawn per entry, so a window opening or closing is an edit of this. */
  private val windows: SnapshotStateList<ExplorerWindow> = mutableStateListOf()
) : MutableList<ExplorerWindow> by windows {

  /**
   * The window of this run [link] leads to, or null for one no window here can answer.
   *
   * Which is what a run asks before handing a link on to the others, so it is deliberately *only* about
   * windows that exist: a run that answered "I could open that file" would claim every link on the machine.
   * Opening the dump is what whoever ends up answering does, in [open].
   *
   * In order, because each step is right about something the next one isn't:
   *
   * - **The window the link was made from**, while it is still open. Two windows on one dump are two
   *   readings of it, and this is the only thing that tells them apart.
   * - **A window of that heap dump**, by path, when the link says where the file is — which is one handed
   *   over by another run, since that run looked it up. Exact, so nothing is tried after it: two dumps of one
   *   name off two devices are two investigations.
   * - **A window of a dump with that file name**, which is what a link says about the dump and all it says.
   * - **A window with that id**, for a link whose whole authority is a window: `shark://<window>/…` is in
   *   notes on disk and in agent sessions, and one of those pasted back into the run it came from still goes
   *   where it went. Last, so that a heap dump called like a window id — which is a file called `abcd2345` —
   *   is read as the heap dump.
   */
  fun windowFor(link: DeepLink): ExplorerWindow? {
    val ofWindowId = link.windowId?.let { id -> firstOrNull { it.deepLinkId == id } }
    if (ofWindowId != null) {
      return ofWindowId
    }
    val path = link.heapDumpPath
    if (path != null) {
      return firstOrNull { it.heapDumpFile?.absoluteFile?.normalize() == path }
    }
    return firstOrNull { it.heapDumpFile?.name == link.heapDumpName }
      ?: firstOrNull { it.deepLinkId == link.heapDumpName }
  }

  /**
   * Follows [link]: the place opens as a new tab in a window of that heap dump, which comes to the front.
   *
   * **A link outlives the window it was made from**, so one whose window has gone opens the heap dump it
   * names — that is the whole point of naming the dump — and only a link naming a file that isn't there any
   * more has nowhere to go. That gets an empty window saying so rather than silence: silence is the one
   * answer that can't be told from the app having failed to start, and a link is usually followed from
   * somewhere that cannot see whether this app did anything at all.
   */
  fun open(link: DeepLink) {
    val window = windowFor(link)
    if (window != null) {
      SharkLog.d { "A link asked window ${window.deepLinkId} for ${link.place} of ${link.heapDumpName}" }
      window.goToLinked(link.place)
      window.bringToFront()
      return
    }
    val heapDumpFile = link.heapDumpPath?.takeIf { it.isFile }
    if (heapDumpFile == null) {
      SharkLog.d { "No window of this run has ${link.heapDumpName} open: opening one to say so" }
      add(
        ExplorerWindow(
          cascade = freeCascade(),
          titlePrefix = titlePrefix,
          deepLinkProblem = noSuchHeapDump(link)
        )
      )
      return
    }
    // The file rather than what the link called it, which for a link named by a window id is that id.
    SharkLog.d { "A link asked for ${link.place} of ${heapDumpFile.name}, which is not open yet" }
    goToHeapDump(heapDumpFile, link.place)
  }

  /** The first step of the cascade no window is at, which is where the next window goes. */
  fun freeCascade(): Int = generateSequence(0, Int::inc).first { step -> none { it.cascade == step } }

  companion object {
    /**
     * What an empty window opened by a link with nowhere to go says in the middle of it.
     *
     * Two ways to get here, and they need different things done about them: a heap dump that has been moved
     * or deleted since the link was made, and one this machine has no record of ever opening — which is a
     * link from somebody else's machine, or one about a dump opened so long ago that where it was has been
     * forgotten. Both are answered by opening the file, so both messages end by saying so.
     */
    fun noSuchHeapDump(link: DeepLink): String {
      val path = link.heapDumpPath
      return if (path == null) {
        "No heap dump called ${link.heapDumpName} is open here, and this machine has no record of opening " +
          "one by that name. Open that file and follow the link again, or say where it is in the link: " +
          "&${DeepLink.DUMP_PARAMETER}=/path/to/${link.heapDumpName}"
      } else {
        "${link.heapDumpName} is not open and there is no file at $path to open, so this link has nowhere " +
          "to go. A link outlives the window it was copied from, but not the heap dump it is about."
      }
    }
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
    // Which window is which, for reading a link out of a log afterwards: a link carries the window it was
    // copied from, and nothing else in the file says what that id stands for.
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
    // Whatever a link, or a dump that failed to open, said about this window is answered now that it has one.
    window.deepLinkProblem = null
    window.openProblem = null
  }
  // One run's log covers every window of that run, and what tells the lines apart afterwards is the
  // thread each was written from, so which window a heap dump went to is worth a line of its own.
  SharkLog.d {
    val where = if (isWindowOfItsOwn) "a window of its own" else "the window that had no heap dump"
    "Opening ${heapDumpFile.name} in $where"
  }
}

/**
 * Shows [heapDumpFile] somewhere, and says where, for a dump that arrives from **outside any window**.
 *
 * Which is an agent opening a file, or taking one off a device: there is no window it was asked from, so the
 * rule above is asked of every window instead of one — the window showing nothing is the one with nothing to
 * lose, and failing that a heap dump is a window.
 */
internal fun ExplorerWindows.openHeapDump(
  heapDumpFile: File,
  /** Fetched with the dump, for a device whose dump can't carry the pixels of its bitmaps. */
  bitmapPixels: NativeBitmapPixels? = null
): ExplorerWindow {
  val window = firstOrNull { it.heapDumpFile == null }
    ?: ExplorerWindow(cascade = freeCascade(), titlePrefix = titlePrefix).also { add(it) }
  openHeapDump(window, heapDumpFile, bitmapPixels)
  return window
}

/**
 * Goes to [place] of [heapDumpFile], opening that dump if no window of this run has it.
 *
 * Which is what a row of the *Agent logs* screen about another heap dump leads to: a session is one agent's
 * connection and can read whichever dumps were open, so the object a call was about is often not in the
 * window the log is being read in. A window already showing that dump is the one to raise rather than a
 * second one on the same file — the same rule [openHeapDump] follows, one window per heap dump — and the
 * window that has just been opened for it is in front already, so only an existing one is brought forward.
 *
 * Which is also where [ExplorerWindows.open] ends up for a link whose window has gone, a link being about a
 * heap dump for the same reason a row of that screen is.
 */
internal fun ExplorerWindows.goToHeapDump(
  heapDumpFile: File,
  place: Place
) {
  // By absolute path, because a window opened from a command line holds the relative path it was given while
  // a session recorded the absolute one, and those are the same heap dump.
  val showing = firstOrNull { it.heapDumpFile?.absoluteFile == heapDumpFile.absoluteFile }
  SharkLog.d {
    val where = if (showing == null) "a window it is not open in yet" else "window ${showing.deepLinkId}"
    "A row of an agent's session asked $where for $place of ${heapDumpFile.name}"
  }
  val window = showing ?: openHeapDump(heapDumpFile)
  window.goToLinked(place)
  showing?.bringToFront()
}

/** Between what a run is called and which heap dump a window shows, as elsewhere in this window. */
private const val TITLE_SEPARATOR = " · "
