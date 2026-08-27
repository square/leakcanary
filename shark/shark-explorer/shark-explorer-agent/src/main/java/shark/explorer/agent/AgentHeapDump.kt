package shark.explorer.agent

import java.io.File
import shark.explorer.AndroidDevice
import shark.explorer.DeviceProcess
import shark.explorer.HeapExplorer
import shark.explorer.LeakStatusOverride
import shark.explorer.LeakStatusOverrides
import shark.explorer.Place

/**
 * One open heap dump an agent can ask about, which is one window of the app.
 *
 * An interface rather than the window itself so that every tool in [AgentTools] is testable against a heap
 * dump and nothing else: the app's implementation carries a `HeapDumpSession`, the statuses set by hand and
 * the tabs, none of which a test of what a tool answers needs.
 *
 * **One of these is a window and not a heap dump file**, even though an agent names it by the file: the same
 * dump is open twice whenever two readings of it are being compared, and a verdict set through one of those
 * windows has to be the verdict the other one draws. See [AgentTools.HEAP_DUMP] for how one is asked for, and
 * `shark.explorer.DeepLink` for the same split in a link.
 */
interface AgentHeapDump {

  /**
   * Which window this is, for the one thing the file name can't say: which of two windows on one dump.
   *
   * What a link names as its window and what this run's log calls it, so it is also how a person watching
   * finds the window an agent was in. See [AgentTools.HEAP_DUMP].
   */
  val windowId: String

  /** Which heap dump is open here, absolute, so that an agent can check it is the one it was asked about. */
  val heapDumpPath: String

  /** How an agent names this dump, which is the file name. See [AgentTools.HEAP_DUMP]. */
  val heapDumpName: String get() = File(heapDumpPath).name

  /**
   * Runs [block] against the open heap dump, wherever the implementation reads one.
   *
   * Suspending because the app owns one thread per heap dump and reads queue on it, so a tool call waits its
   * turn behind whatever the person at the window is doing — which is the point rather than a cost: an agent
   * reading the dump the human is reading must not be able to read it from a second thread and get an answer
   * the window never showed.
   *
   * [description] names what is being read, the way `HeapDumpSession.read` takes one, so that an agent's
   * reads appear in this run's log beside the window's own.
   */
  suspend fun <T> read(
    description: String,
    block: (HeapExplorer) -> T
  ): T

  /** Every verdict set by hand on this dump so far, which every read is made through. */
  val verdicts: LeakStatusOverrides

  /**
   * Sets [verdict], along with the [solved] verdicts that had to flip for it to hold, and puts the lot on
   * disk. See `shark.explorer.LeakStatusConflict`.
   */
  suspend fun setVerdict(
    verdict: LeakStatusOverride,
    solved: List<LeakStatusOverride>
  )

  /** Takes the verdict off [objectId], so the dump says what it says about it again. */
  suspend fun clearVerdict(objectId: Long)

  /** Appends [text] to the note of [place], which is the notepad the window shows on that tab. */
  suspend fun appendToNote(
    place: Place,
    text: String
  )

  /**
   * Puts [text] in the note of [place] instead of what is there, which is what editing one is.
   *
   * Beside appending rather than instead of it, because the two are different acts: an agent adding what it
   * found is appending, and an agent that has been told its own paragraph was wrong is editing. A surface
   * that could only append would leave a wrong conclusion in the notes of the object it is wrong about,
   * under a correction — and the next reader finds both.
   */
  suspend fun replaceNote(
    place: Place,
    text: String
  )

  /** What is in the note of [place], and empty for a place nobody has written about. */
  suspend fun readNote(place: Place): String

  /**
   * Every place of this heap dump with a note, which is what the window marks a tab for.
   *
   * The listing rather than the notes, like the tab strip: what it answers is where somebody has been, and
   * reading what they wrote is a call per place. Places from a newer version of the app, or from a screen
   * a caller cannot be sent to, are left out — see `shark.explorer.placeOfNoteKeyOrNull`.
   */
  suspend fun notedPlaces(): List<Place>

  /**
   * Opens [place] in a tab of this window and brings the window to the front, which is what makes an agent's
   * work something the person at the machine can watch rather than read about afterwards.
   *
   * Not suspending: this is the same hand-over a `shark://` link makes — a place put where the tabs take it
   * on the next frame — so there is nothing to wait for.
   */
  fun show(place: Place): ShownPlace
}

/**
 * What came of putting a place in front of the person watching: the link to it, and why there was nowhere.
 *
 * **One answer rather than two calls**, because a call to `show` raises two questions with one cause and an
 * agent needs both answers: whether anybody saw it, and what to write down so that somebody can. They are not
 * the same fact, since a `shark://` link names the *heap dump* — so a run with no window has nothing on screen
 * to point at and a link worth passing on all the same.
 *
 * The link matters as much as the showing does: it is what an agent puts in its *reply* so that whoever asked
 * can open the place themselves, later, from wherever the conversation is. Showing raises a window over
 * whatever they were doing, which is right once and wrong five times; a link in a sentence is right every
 * time. See [AgentTools] `show`.
 */
class ShownPlace private constructor(
  /** The `shark://` link a person can click to open it, and null when there is no heap dump to link to. */
  val link: String?,
  /** Why it wasn't shown, and null when it was. */
  val problem: String?
) {

  companion object {

    fun at(link: String) = ShownPlace(link = link, problem = null)

    /**
     * Nothing was shown and [problem] says why, with [link] to the place it would have been.
     *
     * Both, because either alone is misleading: an agent that told its human to look at something they cannot
     * see has said the one thing worse than nothing, and one that dropped the link would leave them with a
     * description of a place they could have been taken to.
     */
    fun onlyAsALink(
      link: String,
      problem: String
    ) = ShownPlace(link = link, problem = problem)

    /** Nothing was shown and there is nowhere to link to either, which [problem] has to account for. */
    fun nowhere(problem: String) = ShownPlace(link = null, problem = problem)
  }
}

/**
 * The heap dumps of this run, open and openable, which is what a connection asks before anything else.
 *
 * Windows come and go while an agent is connected, so [openHeapDumps] is asked per call rather than
 * captured: a tool naming a window that has since closed is an error message, not a stale answer.
 *
 * The rest of it is everything the app can be asked for that isn't about a dump it already has open —
 * opening another one, and taking one off a device — which is the same thing as **everything the buttons
 * above the map can do**. An agent that can read a heap dump but not open one is an agent that has to ask
 * its human to click something, which is the opposite of what this surface is for.
 */
interface AgentHeapDumps {

  /** Every window with a heap dump open, in the order they were opened. */
  fun openHeapDumps(): List<AgentHeapDump>

  /**
   * The heap dumps this run was pointed at and cannot read yet, absolute, because indexing one takes as long
   * as it takes.
   *
   * Beside [openHeapDumps] rather than in it, since a window id is a promise that every tool given it answers
   * and a dump nothing can read yet cannot keep that promise. But leaving the path out of the answer
   * altogether is worse than either: an agent that asks what is open, is told nothing is, and is not told the
   * path this run was started on has one move left, which is to guess a path. One did — it guessed a heap dump
   * belonging to another run of the same eval, investigated that instead, and answered confidently about a
   * dump nobody had asked it about. See `notes/agent-eval.md`.
   */
  fun openingHeapDumpPaths(): List<String>

  /**
   * Opens [file] in a window of this app and answers once it can be read.
   *
   * Once it can be *read*, rather than once the window exists: everything else here is a read, so an answer
   * handed over before the dump is open would be a window id that refuses every call made with it. Which
   * makes this the one call that takes as long as opening a heap dump takes.
   */
  suspend fun open(file: File): AgentHeapDump

  /** Every device `adb` is connected to, whether or not a heap dump could be taken off it. */
  suspend fun devices(): List<AndroidDevice>

  /** The processes of one device that belong to an installed app, which are the ones worth dumping. */
  suspend fun processesOf(serialNumber: String): List<DeviceProcess>

  /**
   * Takes a heap dump of a process, opens it in a window of this app, and answers once it can be read.
   *
   * Minutes, on a large app: a dump is written on the device, waited for, pulled, and then opened. The
   * steps land in this run's log as they happen, which is where to look while this hasn't come back.
   */
  suspend fun dumpHeap(
    serialNumber: String,
    processName: String
  ): AgentHeapDump
}
