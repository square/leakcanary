package shark.explorer.app

import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import shark.SharkLog
import shark.explorer.DeepLink
import shark.explorer.DeviceHeapDumps
import shark.explorer.agent.AgentHeapDump
import shark.explorer.agent.AgentRefusal
import shark.explorer.agent.ShownPlace

/**
 * The heap dumps of a run with no window, for an agent on a machine with no screen.
 *
 * Everything a window would hold, held here instead — the heap dump's own thread, the notes, the statuses set
 * by hand — and **the same files on disk**, so a dump investigated over ssh today reads back with all of it in
 * a window tomorrow. That is why this is in the app module rather than a program of its own: the notes and the
 * verdicts are the artefact, and a headless mode writing them somewhere else would be a second app.
 *
 * So what is left here is the two answers that differ from a run that has windows — which dumps are open, and
 * what opening one means — plus the one call a run with no window genuinely can't make: [AgentHeapDump.show]
 * has nowhere to put a tab and says so rather than answering that it did. See [NO_UI_OPTION].
 */
internal class HeadlessAgentHeapDumps(
  deviceHeapDumps: DeviceHeapDumps,
  /** Heap dumps named on the command line, opened as this starts. */
  heapDumpFiles: List<File> = emptyList(),
  /** The same notes a window keeps, in the same directory: a test passes its own. See [ExplorerNotes]. */
  private val notes: ExplorerNotes = ExplorerNotes(),
  private val leakStatuses: ExplorerLeakStatuses = ExplorerLeakStatuses()
) : RunAgentHeapDumps(deviceHeapDumps), Closeable {

  /**
   * One per heap dump being opened, keyed by file, whether it has finished or not.
   *
   * **What makes opening the same dump twice one open rather than two.** Which matters here in a way it
   * doesn't in a window: the command line's dumps start opening while the client's first message is still on
   * its way, so an agent calling `open_heap_dump` on the path it was pointed at is racing them — and losing
   * that race would mean a second index of the same gigabyte, on a second thread, with the notes of the first.
   */
  private val openings = mutableMapOf<File, Deferred<HeadlessHeapDump>>()

  /** The ones that finished opening, in the order they did, which is the order a window list is in. */
  private val opened = mutableListOf<HeadlessHeapDump>()

  private val lock = Any()

  /**
   * Off the connection's thread, so that opening a dump and answering about one already open can happen at
   * once. Its own scope rather than the caller's: an open outlives the call that asked for it.
   */
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  init {
    heapDumpFiles.forEach { file ->
      // Started rather than awaited, because a client is waiting on this process to answer `initialize` and
      // a large heap dump is minutes of indexing. So a quick dump is open by the time the first call comes,
      // a slow one is opened by whichever call asks for it, and either way it is opened once.
      opening(file)
    }
  }

  override fun openHeapDumps(): List<AgentHeapDump> = synchronized(lock) { opened.map { it.agent } }

  override suspend fun open(file: File): AgentHeapDump = opening(file).await().agent

  /** Releases the thread each open heap dump owns, and stops the ones still opening. */
  override fun close() {
    scope.cancel()
    synchronized(lock) {
      opened.forEach { it.open.session.close() }
      opened.clear()
      openings.clear()
    }
  }

  /** This dump's open, joining one already in flight rather than starting a second. */
  private fun opening(file: File): Deferred<HeadlessHeapDump> {
    val absolute = file.absoluteFile
    return synchronized(lock) {
      openings.getOrPut(absolute) { scope.async { openNow(absolute) } }
    }
  }

  private suspend fun openNow(file: File): HeadlessHeapDump {
    val session = try {
      HeapDumpSession.open(file) { step -> SharkLog.d { step } }
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not open $file" }
      // Off the map, so that a path given by mistake can be given again once the file is there — and so that
      // the refusal is this one rather than the same failure remembered for the rest of the session.
      synchronized(lock) { openings -= file }
      throw AgentRefusal("${file.absolutePath} could not be opened as a heap dump: $throwable")
    }
    val open = OpenHeapDump(
      session = session,
      notes = notes.of(file),
      leakStatuses = leakStatuses.of(file)
    )
    // What `HeapDumpExplorer` does as it comes up, and it has to happen somewhere: every verdict is refused
    // until the file of them has been read, since saving over an unread one would delete the conclusions in
    // it. A window reads it because it draws them, and a run with no window would otherwise never read it and
    // refuse every verdict an agent tried to record.
    open.leakStatuses.read()
    // The same kind of id a window has, because it is the same question: which of the heap dumps open. Called
    // `window` on the surface even here, rather than growing a second word for a run that has none — what an
    // agent does with it is name a dump, and a vocabulary that changes with whether there is a screen is one
    // nobody can carry between the two.
    val windowId = DeepLink.newWindowId()
    val dump = HeadlessHeapDump(
      open = open,
      agent = OpenAgentHeapDump(windowId = windowId, open = open) { place ->
        SharkLog.d { "Nowhere to show $place: this run was started with $NO_UI_OPTION" }
        // And no link either, deliberately: a link names a window, so one from here would be an address
        // nothing answers to, handed to somebody who would click it.
        ShownPlace.nowhere(
          "This Shark Explorer was started with $NO_UI_OPTION, so it has no window and nothing was shown. " +
            "Say what you found in your answer instead. Whoever opens ${file.name} in a window later will " +
            "find your notes and verdicts on it, since those are on disk rather than on screen."
        )
      }
    )
    synchronized(lock) { opened += dump }
    SharkLog.d { "${file.name} is open as $windowId, with no window" }
    return dump
  }
}

/** One heap dump this run has open, with nothing drawing it. */
private class HeadlessHeapDump(
  val open: OpenHeapDump,
  val agent: AgentHeapDump
)
