package shark.dive.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.awt.EventQueue
import java.awt.Frame
import java.io.File
import kotlin.random.Random
import shark.SharkLog
import shark.dive.DeepLink
import shark.dive.HeapDumpPaths
import shark.dive.NativeBitmapPixels
import shark.dive.Place

/**
 * One window of the app, which is one heap dump.
 *
 * Plain state rather than a composable's, so that how many windows there are and which heap dump each
 * one shows can be tested without a display: `diveApplication` only draws a window per entry.
 */
internal class DiveWindow(
  heapDumpFile: File? = null,
  bitmapPixels: NativeBitmapPixels? = null,
  /**
   * How many steps down and to the right of centre this window opens: no two windows on screen are at
   * the same one, so a window opened from another lands beside it rather than exactly over it. Where
   * that is, is `cascadedPosition`.
   */
  val cascade: Int = 0,
  /** What this run calls its windows, in front of the heap dump. See [DiveArguments.titlePrefix]. */
  val titlePrefix: String? = null,
  /**
   * What an agent calls this window, since a heap dump open in two of them is two places to be told about.
   * Never in a link — see [DeepLink] — and it is not what makes one work.
   */
  val windowId: String = newWindowId(),
  /** Why this window is empty, for one a link opened having nowhere to go. See [DiveWindows.open]. */
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
   * Said in the middle of a window with nothing in it. See [DiveWindows.open].
   */
  var deepLinkProblem: String? by mutableStateOf(deepLinkProblem)

  /**
   * What a link is waiting on an answer about, and null whenever nothing is being asked.
   *
   * The other way a link ends up here: two heap dumps of the name it says, or none this machine can find.
   * Set from whatever thread the link arrived on, for the reason [linkedPlaces] is, and taken by the window
   * on the next frame as a dialog. See [DiveWindows.open] and [DiveWindows.chooseLinkedHeapDump].
   */
  var linkedHeapDump: LinkedHeapDump? by mutableStateOf(null)

  /**
   * The heap dump this window has open, once it is open, for everything that isn't drawing the window.
   *
   * Which today is one thing: an agent reaching in from outside the app. Here for the same reason
   * [linkedPlaces] is — a socket thread has to find a window, and what the window has open is a
   * composable's state — and set by [DiveApp] as the session opens and closes. Null while a heap dump is
   * being opened, for a window that has none, and for one whose dump failed to open.
   */
  var openHeapDump: OpenHeapDump? by mutableStateOf(null)

  /**
   * Why the heap dump this window was given could not be opened, and null while nothing has gone wrong.
   *
   * The other half of [openHeapDump] for a reader outside the composition, and not its opposite: a window
   * whose dump is still being indexed has neither, which is what tells something waiting for that dump that
   * waiting is still the right thing to do. Set by [DiveApp] the same way, and cleared as another dump
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
      SharkLog.d { "Window $windowId has no frame yet, so nothing was brought to the front" }
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
 * A new name for a window: eight characters of an alphabet nothing can be misread in.
 *
 * Read out of a log and typed back into an agent's call — see [DiveWindow.windowId] — so the alphabet
 * leaves out `l`, `1`, `o` and `0`, and eight characters is short enough to retype. Random rather than
 * counted, because two runs of this app count from the same place and an agent talking to both would be told
 * about two windows called `1`.
 */
internal fun newWindowId(random: Random = Random.Default): String =
  (1..WINDOW_ID_LENGTH).map { WINDOW_ID_ALPHABET.random(random) }.joinToString("")

/**
 * A heap dump a link named that this run could not pin down on its own, and what is being asked about it.
 *
 * Two ways one link gets here — see [DiveWindows.open] — and one question answers both, because both
 * answers are a path: which of the heap dumps called that, or where the one called that is.
 */
internal class LinkedHeapDump(
  /** What the link called the heap dump, which is a file name and all a link says. */
  val heapDumpName: String,
  /** Why this is being asked, said in the dialog and in the middle of a window opened to ask it. */
  val question: String,
  /** The paths of that name this machine knows of, which is what there is to pick from. Can be empty. */
  val choices: List<File>,
  /** Where the link was going, which is where whichever heap dump is picked opens. */
  val place: Place
)

/**
 * Every window of this run, and the way in for anything that isn't drawing one.
 *
 * Hoisted out of the composable that draws the windows because a link arrives from outside the app — the
 * OS, another run of it, this run's own command line — and has to find a window from a thread that is
 * composing nothing. See [DiveWindow.linkedPlaces].
 */
internal class DiveWindows(
  /**
   * Where the heap dumps this machine has opened are, which is how a link about one that no window of this
   * run has open finds the file. See [open].
   */
  val heapDumpPaths: HeapDumpPaths,
  /** Put in front of every window title of this run. See [DiveArguments.titlePrefix]. */
  val titlePrefix: String? = null,
  /** One Compose window is drawn per entry, so a window opening or closing is an edit of this. */
  private val windows: SnapshotStateList<DiveWindow> = mutableStateListOf()
) : MutableList<DiveWindow> by windows {

  /**
   * The windows of this run [link] leads to, which is every window showing the heap dump it names.
   *
   * Which is what a run asks before handing a link on to the others, so it is deliberately *only* about
   * windows that exist: a run that answered "I could open that file" would claim every link on the machine.
   * Opening the file, and asking where it is, is what whoever ends up answering does. See [open].
   *
   * By file name, which is the whole of what a link says about the heap dump — and by path for the rare link
   * that says where the file is, since that one is exact: two dumps called `com.squareup.hprof` off two
   * devices are two investigations, and a link carrying a path has already said which.
   *
   * A window whose heap dump **failed to open** is not a window that has it. The file it was given may well
   * be openable from where this machine last saw it — a path typed wrong, a dump on a volume that wasn't
   * mounted then — and landing a link on the window that says so is the one outcome that helps nobody.
   */
  fun windowsFor(link: DeepLink): List<DiveWindow> {
    val path = link.heapDumpPath?.normalizedPath()
    return filter { window ->
      val heapDumpFile = window.heapDumpFile ?: return@filter false
      if (window.openProblem != null) return@filter false
      if (path == null) heapDumpFile.name == link.heapDumpName else heapDumpFile.normalizedPath() == path
    }
  }

  /**
   * Follows [link]: the place opens as a new tab in a window of the heap dump it names, which comes to the
   * front.
   *
   * **A link outlives the window it was copied from**, and naming the heap dump rather than the window is
   * what makes that work. Four ways it goes, and the first is nearly always the one:
   *
   * - **A window of this run has that heap dump open.** That window is where the link goes, and nothing else
   *   is looked at.
   * - **None has, but this machine has had it open.** The file opens in a window of its own, wherever it was
   *   last seen. See [HeapDumpPaths].
   * - **There are two heap dumps of that name.** Two windows on two files, or two paths on record: a link
   *   says nothing that tells them apart, so the reader is asked which, by path.
   * - **Nothing here knows that name.** Which is a link from somebody else's machine, or about a heap dump
   *   opened too long ago to still be on record, or one that has been deleted: the reader is asked for the
   *   file. Asked, rather than left with silence — silence is the one answer that can't be told from the app
   *   having failed to start, and a link is usually followed from somewhere that cannot see either way.
   */
  fun open(link: DeepLink) {
    val openWindows = windowsFor(link)
    val openPaths = openWindows.mapNotNull { it.heapDumpFile?.normalizedPath() }.distinct()
    if (openPaths.size > 1) {
      // Which of them is a question only the reader can answer: nothing in the link tells the two apart, and
      // guessing would be picking somebody's investigation for them.
      ask(link, whichHeapDump(link, openPaths, areOpen = true), openPaths, host = openWindows.first())
      return
    }
    val window = openWindows.firstOrNull()
    if (window != null) {
      // The first of them when one heap dump is open in two windows, which is two readings of one file: a
      // link says nothing that tells those apart either, but they show the same dump, so there is nothing
      // worth asking.
      SharkLog.d { "A link asked window ${window.windowId} for ${link.place} of ${link.heapDumpName}" }
      window.goToLinked(link.place)
      window.bringToFront()
      return
    }
    // Nothing here has it open, so where the file is comes off the link when it says, and off what this
    // machine remembers opening when it doesn't.
    val remembered = link.heapDumpPath?.let { listOf(it.normalizedPath()) }
      ?: heapDumpPaths.pathsNamed(link.heapDumpName).map { it.normalizedPath() }.distinct()
    val found = remembered.filter { it.isFile }
    when {
      found.size == 1 -> {
        SharkLog.d { "A link asked for ${link.place} of ${found.single()}, which is not open yet" }
        goToHeapDump(found.single(), link.place)
      }
      found.size > 1 -> ask(link, whichHeapDump(link, found, areOpen = false), found, host = null)
      // Nowhere to go on its own, so the question is the whole of what this link gets: a window of its own,
      // saying what is missing, over a dialog asking for the file.
      else -> ask(link, noSuchHeapDump(link, remembered), choices = emptyList(), host = null)
    }
  }

  /**
   * Puts a question about [link]'s heap dump to whoever followed it, in [host] or in a window of its own.
   *
   * A window already showing one of the heap dumps in question when there is one, so that asking which of
   * them costs no window: the dialog is over what its reader was looking at either way. Failing that the
   * question needs a window, which is also where the heap dump picked will open — and that window says the
   * question in the middle of it as well, so that a question dismissed leaves the reason on screen rather
   * than a window with nothing in it and nothing to explain it.
   *
   * Brought to the front either way, for the same reason following a link raises a window: a dialog drawn
   * inside a window that is behind another one is a link that did nothing, as far as its reader can tell.
   */
  private fun ask(
    link: DeepLink,
    question: String,
    choices: List<File>,
    host: DiveWindow?
  ) {
    SharkLog.d { "A link to ${link.place} of ${link.heapDumpName} is asking: $question" }
    val window = host ?: emptyWindow().also { it.deepLinkProblem = question }
    window.linkedHeapDump = LinkedHeapDump(
      heapDumpName = link.heapDumpName,
      question = question,
      choices = choices,
      place = link.place
    )
    window.bringToFront()
  }

  /**
   * What an answer to [DiveWindow.linkedHeapDump] does: the link goes where it was going, in the heap
   * dump that was picked.
   *
   * [chosen] is null for a question dismissed, which is a link not followed — the window keeps the reason it
   * was asked, and there is nothing else to do about it.
   */
  fun chooseLinkedHeapDump(
    window: DiveWindow,
    chosen: File?
  ) {
    val asked = window.linkedHeapDump ?: return
    window.linkedHeapDump = null
    if (chosen == null) {
      SharkLog.d { "Nothing was picked for ${asked.heapDumpName}, so its link goes nowhere" }
      return
    }
    SharkLog.d { "$chosen was picked for ${asked.heapDumpName}, so its link goes to ${asked.place}" }
    goToHeapDump(chosen, asked.place)
  }

  /** The first step of the cascade no window is at, which is where the next window goes. */
  fun freeCascade(): Int = generateSequence(0, Int::inc).first { step -> none { it.cascade == step } }

  /**
   * A window with nothing in it for a link to say something in: the one this run started with when it was
   * started with no heap dump, and a new one otherwise.
   *
   * Never one that is already asking about another link. Two links that both need an answer are two
   * questions, and the second one taking this window would take the first one's question with it.
   */
  private fun emptyWindow(): DiveWindow =
    firstOrNull { it.heapDumpFile == null && it.linkedHeapDump == null }
      ?: DiveWindow(cascade = freeCascade(), titlePrefix = titlePrefix).also { add(it) }

  companion object {
    /**
     * What a link about a heap dump with more than one place to be asks, which is: which of these?
     *
     * Rare, and worth wording rather than guessing at. Every heap dump this app takes off a device is named
     * after the process, its pid and a random number, and LeakCanary names its own after the time of the
     * dump — so two files of one name are a dump named by hand, or one app dumped on two devices, which are
     * exactly the two cases where picking one for the reader would be picking wrong.
     */
    fun whichHeapDump(
      link: DeepLink,
      choices: List<File>,
      areOpen: Boolean
    ): String = if (areOpen) {
      "${choices.size} heap dumps called ${link.heapDumpName} are open."
    } else {
      "${choices.size} heap dumps called ${link.heapDumpName} have been opened here, and none is open now."
    }

    /**
     * What a link about a heap dump this run cannot find says, which ends in the two ways to say where it is.
     *
     * Two ways to get here, and they mean different things to whoever reads it: [gone] is where this machine
     * remembers the heap dump being, so an empty one is a name nothing here has ever opened — a link from
     * somebody else's machine, or about a dump opened so long ago that where it was has been forgotten — and
     * a full one is a heap dump moved or deleted since the link was written. See [HeapDumpPaths].
     */
    fun noSuchHeapDump(
      link: DeepLink,
      gone: List<File>
    ): String = if (gone.isEmpty()) {
      "No heap dump called ${link.heapDumpName} is open here, and this machine has no record of opening one " +
        "by that name. Choose the file, or say where it is in the link: " +
        "&${DeepLink.DUMP_PARAMETER}=/path/to/${link.heapDumpName}"
    } else {
      "${link.heapDumpName} is not open, and there is no file at ${gone.joinToString(" or ")} any more. A " +
        "link outlives the window it was copied from, but not the heap dump it is about. Choose the file if " +
        "it has moved."
    }
  }
}

/**
 * A window per heap dump named on the command line, or one window with none — something has to carry
 * the button that opens the first one.
 */
internal fun diveWindows(
  arguments: DiveArguments,
  /** Handed in rather than made here, because a run records the heap dumps it opens into the same one. */
  heapDumpPaths: HeapDumpPaths
): DiveWindows =
  DiveWindows(heapDumpPaths, arguments.titlePrefix).apply {
    val titlePrefix = arguments.titlePrefix
    if (arguments.heapDumpFiles.isEmpty()) {
      add(DiveWindow(null, titlePrefix = titlePrefix))
    } else {
      arguments.heapDumpFiles.forEachIndexed { index, file ->
        add(DiveWindow(file, cascade = index, titlePrefix = titlePrefix))
      }
    }
    // Which window is which, for reading an agent's session out of a log afterwards: a call names the window
    // it was answered by, and nothing else in the file says what that id stands for.
    SharkLog.d { "Windows of this run: ${joinToString { "${it.windowId} ${it.title}" }}" }
  }

/**
 * Shows [heapDumpFile] in [window] if it has none yet, and in a window of its own if it has.
 *
 * A window showing a heap dump keeps it, so the windows on screen are the heap dumps open: comparing
 * two dumps is looking at both, and closing a window closes one of them rather than the trail through
 * several. The window that shows nothing has nothing to keep, so the first heap dump opens in it.
 */
internal fun DiveWindows.openHeapDump(
  window: DiveWindow,
  heapDumpFile: File,
  /** Fetched with the dump, for a device whose dump can't carry the pixels of its bitmaps. */
  bitmapPixels: NativeBitmapPixels? = null
) {
  val isWindowOfItsOwn = window.heapDumpFile != null
  if (isWindowOfItsOwn) {
    // Named after the same run as the window it was opened from: every window of one Shark Dive window belongs to
    // whatever that Shark Dive window was started for, however many heap dumps end up open in it.
    add(
      DiveWindow(
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
internal fun DiveWindows.openHeapDump(
  heapDumpFile: File,
  /** Fetched with the dump, for a device whose dump can't carry the pixels of its bitmaps. */
  bitmapPixels: NativeBitmapPixels? = null
): DiveWindow {
  val window = firstOrNull { it.heapDumpFile == null }
    ?: DiveWindow(cascade = freeCascade(), titlePrefix = titlePrefix).also { add(it) }
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
 * Which is also where [DiveWindows.open] ends up for a link about a heap dump no window has open, and
 * where an answer to [DiveWindows.chooseLinkedHeapDump] goes: a link is about a heap dump for the same
 * reason a row of that screen is.
 */
internal fun DiveWindows.goToHeapDump(
  heapDumpFile: File,
  place: Place
) {
  val showing = firstOrNull { it.heapDumpFile?.normalizedPath() == heapDumpFile.normalizedPath() }
  SharkLog.d {
    val where = if (showing == null) "a window it is not open in yet" else "window ${showing.windowId}"
    "Something outside a window asked $where for $place of ${heapDumpFile.name}"
  }
  val window = showing ?: openHeapDump(heapDumpFile)
  window.goToLinked(place)
  showing?.bringToFront()
}

/**
 * One spelling of a heap dump's path, so that two names for one file are one heap dump: a window opened from
 * a command line holds the relative path it was given, while a link, a session and [HeapDumpPaths] carry the
 * absolute one.
 *
 * The same spelling `normalizedHeapDumpPath` gives what shark-dive-core writes about a heap dump, copied
 * rather than shared: a line of this belongs in whichever module needs it, not in a published API.
 */
private fun File.normalizedPath(): File = absoluteFile.normalize()

/** Between what a run is called and which heap dump a window shows, as elsewhere in this window. */
private const val TITLE_SEPARATOR = " · "

private const val WINDOW_ID_LENGTH = 8

/** Lowercase and digits without `l`, `1`, `o` and `0`, which is what nothing can be misread in. */
private const val WINDOW_ID_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
