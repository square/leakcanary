package shark.explorer.agent

import java.io.Closeable
import shark.SharkLog
import shark.explorer.HeapExplorer
import shark.explorer.LeakStatusOverride
import shark.explorer.LeakStatusOverrides
import shark.explorer.Place

/**
 * A heap dump open the way a window has one, without a window.
 *
 * Which is the whole reason [AgentHeapDump] is an interface: every tool is a read of a heap dump and a write
 * of a verdict or a note, so a test of what a tool answers needs a dump and three fields, and none of
 * Compose, the session or the tabs.
 */
internal class FakeAgentHeapDump(
  private val explorer: HeapExplorer,
  override val windowId: String = "testwindow"
) : AgentHeapDump, Closeable {

  override val heapDumpPath: String get() = explorer.heapDumpFile.absolutePath

  override var verdicts: LeakStatusOverrides = LeakStatusOverrides.NONE
    private set

  /** What was written about each place, in the order it was written, so a test can read it back. */
  val notes = mutableMapOf<Place, MutableList<String>>()

  /** The places an agent asked the window to show, in order. */
  val shown = mutableListOf<Place>()

  /** What each read was described as, which is what a session log would have said. */
  val reads = mutableListOf<String>()

  override suspend fun <T> read(
    description: String,
    block: (HeapExplorer) -> T
  ): T {
    reads += description
    // Logged as well as recorded, because the window's own `HeapDumpSession.read` logs every read: what a
    // session log has to show is the reason for a call and then the reads it caused, in that order, and a
    // fake that logged nothing would leave that assertion with only half of what it is about.
    SharkLog.d { description }
    return block(explorer)
  }

  override suspend fun setVerdict(
    verdict: LeakStatusOverride,
    solved: List<LeakStatusOverride>
  ) {
    verdicts = verdicts.with(listOf(verdict) + solved)
  }

  override suspend fun clearVerdict(objectId: Long) {
    verdicts = verdicts.without(objectId)
  }

  override suspend fun appendToNote(
    place: Place,
    text: String
  ) {
    notes.getOrPut(place) { mutableListOf() } += text
  }

  override fun show(place: Place) {
    shown += place
  }

  override fun close() {
    explorer.close()
  }
}
