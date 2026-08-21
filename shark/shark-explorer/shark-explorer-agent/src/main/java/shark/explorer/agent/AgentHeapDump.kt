package shark.explorer.agent

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
 * **A window and not a heap dump file**, matching `shark.explorer.DeepLink`: the same dump is often open
 * twice — that is what comparing two of them is — so a path would be ambiguous exactly when it matters, and
 * a verdict set through one of two windows has to be the verdict the other one draws.
 */
interface AgentHeapDump {

  /** What a link names this window by, and what an agent addresses it by. See [AgentTools]. */
  val windowId: String

  /** Which heap dump is open here, absolute, so that an agent can check it is the one it was asked about. */
  val heapDumpPath: String

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
   * Opens [place] in a tab of this window and brings the window to the front, which is what makes an agent's
   * work something the person at the machine can watch rather than read about afterwards.
   *
   * Not suspending and not answered: this is the same hand-over a `shark://` link makes — a place put where
   * the tabs take it on the next frame — so there is nothing to wait for and nothing that can fail here.
   */
  fun show(place: Place)
}

/**
 * The open heap dumps of this run, which is what a connection asks before anything else.
 *
 * Windows come and go while an agent is connected, so this is asked per call rather than captured: a tool
 * naming a window that has since closed is an error message, not a stale answer.
 */
fun interface AgentHeapDumps {

  /** Every window with a heap dump open, in the order they were opened. */
  fun openHeapDumps(): List<AgentHeapDump>
}
