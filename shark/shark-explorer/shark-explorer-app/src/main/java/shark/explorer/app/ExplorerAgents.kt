package shark.explorer.app

import java.io.File
import shark.SharkLog
import shark.explorer.HeapExplorer
import shark.explorer.LeakStatusOverride
import shark.explorer.LeakStatusOverrides
import shark.explorer.Place
import shark.explorer.agent.AgentHeapDump
import shark.explorer.agent.AgentHeapDumps
import shark.explorer.agent.AgentRefusal
import shark.explorer.agent.AgentServer
import shark.explorer.agent.AgentSession
import shark.explorer.agent.AgentSessionFile
import shark.explorer.agent.AgentStdioBridge

/**
 * How an agent reaches the windows of this run: the app's side of `shark-explorer-agent`.
 *
 * The one thing worth knowing here is **why an agent is given the window and not the heap dump file**. Every
 * read goes through that window's own [HeapDumpSession], so an agent's question queues on the thread the
 * person at the machine is already reading with, and the verdicts and notes it writes are the ones that
 * window is drawing. An agent that opened the dump itself would be answering about a heap dump nobody is
 * looking at, and its conclusions would land in a file the open window would then overwrite.
 */
internal fun explorerAgentHeapDumps(windows: ExplorerWindows): AgentHeapDumps = AgentHeapDumps {
  // Asked per call rather than captured, because windows come and go while an agent is connected: a tool
  // naming a window that has since closed has to be an error message and not a stale answer.
  windows.mapNotNull { window ->
    window.openHeapDump?.let { open -> WindowAgentHeapDump(window, open) }
  }
}

/**
 * Publishes this run so that agents can find it, or does nothing if it can't. See [AgentServer].
 *
 * Its own socket rather than the one `DeepLinkPeers` listens on, because the two have nothing in common but
 * being loopback: a link is one line answered in a millisecond, and this is a session held open for as long
 * as an investigation takes.
 */
internal fun listenForAgents(windows: ExplorerWindows) = AgentServer.listen(
  heapDumps = explorerAgentHeapDumps(windows),
  serverVersion = SharkExplorerVersion.current,
  directory = AGENT_RUNS_DIRECTORY
)

/**
 * Whether this process was started to be a pipe between an agent and another run of the app, and what to
 * exit with if it was. Null for every other command line.
 *
 * Answered before anything else in `main` and before any logging is installed, because the app's logger
 * writes to stdout and in this mode stdout is the protocol. See [AgentStdioBridge].
 */
internal fun agentBridgeExitCode(args: Array<String>): Int? {
  if (MCP_STDIO_OPTION !in args) {
    return null
  }
  val pid = args.firstOrNull { it.startsWith(AgentStdioBridge.PID_OPTION) }
    ?.removePrefix(AgentStdioBridge.PID_OPTION)
  return AgentStdioBridge.run(directory = AGENT_RUNS_DIRECTORY, pid = pid)
}

/**
 * What a window has open, for everything that isn't drawing it.
 *
 * The heap dump's session plus the two things an investigation writes into, gathered because they are all
 * per heap dump and are all reached the same way — through the window rather than through the file. See
 * [ExplorerWindow.openHeapDump].
 */
internal class WindowHeapDump(
  val session: HeapDumpSession,
  val notes: HeapDumpNotes,
  val leakStatuses: HeapDumpLeakStatuses
)

/** One window's heap dump, as the agent surface sees it. */
private class WindowAgentHeapDump(
  private val window: ExplorerWindow,
  private val open: WindowHeapDump
) : AgentHeapDump {

  override val windowId: String get() = window.deepLinkId

  override val heapDumpPath: String get() = open.session.heapDumpFile.absolutePath

  override suspend fun <T> read(
    description: String,
    block: (HeapExplorer) -> T
  ): T = open.session.read(description, block)

  override val verdicts: LeakStatusOverrides get() = open.leakStatuses.overrides

  override suspend fun setVerdict(
    verdict: LeakStatusOverride,
    solved: List<LeakStatusOverride>
  ) {
    requireStatusesRead()
    open.leakStatuses.set(verdict, solved)
  }

  override suspend fun clearVerdict(objectId: Long) {
    requireStatusesRead()
    open.leakStatuses.clear(objectId)
  }

  /**
   * Appends to what has been written about [place], leaving whatever was there.
   *
   * **Refuses while somebody is typing in that note**, which is the one case where writing would cost
   * something that exists nowhere else: a draft is unsaved text, and saving over it with the draft plus an
   * agent's paragraph would put half a sentence of theirs on disk under an answer of ours.
   */
  override suspend fun appendToNote(
    place: Place,
    text: String
  ) {
    val notepad = open.notes.of(place)
    notepad.read()
    if (!notepad.isRead) {
      throw AgentRefusal(
        "The notes of that place could not be read, so writing would overwrite whatever is in them: " +
          (notepad.problem ?: "reading ${notepad.file} did not finish.")
      )
    }
    if (notepad.draft != null) {
      throw AgentRefusal(
        "Somebody is writing in the notes of that place right now, so there is nothing to append to yet. " +
          "Say what you found in your answer instead, or try again once they have saved."
      )
    }
    notepad.edit()
    notepad.edited(listOf(notepad.text, text).filter { it.isNotBlank() }.joinToString(PARAGRAPH_BREAK))
    notepad.save()
    if (notepad.problem != null) {
      throw AgentRefusal("The notes could not be saved: ${notepad.problem}")
    }
  }

  override fun show(place: Place) {
    SharkLog.d { "An agent asked window ${window.deepLinkId} for $place" }
    // The same two steps following a link takes, which is what makes an agent showing something and a
    // person clicking a link land in the same place. See [ExplorerWindows.open].
    window.goToLinked(place)
    window.bringToFront()
  }

  /**
   * Refuses until the file of statuses set by hand has been read.
   *
   * [HeapDumpLeakStatuses.set] declines to write before then and says so in the log, which is right for the
   * button it was written for — it is disabled — and silent for an agent, which would read "no error" as
   * "recorded". Saving over an unread file would delete every conclusion in it.
   */
  private fun requireStatusesRead() {
    if (!open.leakStatuses.isRead) {
      throw AgentRefusal(
        "The verdicts already recorded about this heap dump have not been read yet, so recording one now " +
          "could delete them: " + (open.leakStatuses.problem ?: "reading ${open.leakStatuses.file} " +
          "has not finished. Try again in a moment.")
      )
    }
  }
}

/** Between what was already written about a place and what an agent has to add, which is markdown. */
private const val PARAGRAPH_BREAK = "\n\n"

/**
 * What every agent that has connected to this app did, newest session first.
 *
 * Read off disk rather than kept in memory, and not only this run's: the question the *Agent logs* screen
 * answers is "what has an agent done to this heap dump", and the answer to that outlives the run it happened
 * in. A directory of small files, so re-reading it is what keeps the screen live while an agent works.
 */
internal fun agentSessions(): List<AgentSession> =
  AgentSessionFile.sessionsIn(AgentServer.sessionsDirectory(AGENT_RUNS_DIRECTORY))

/** Beside the runs answering links, the notes, the statuses and the logs. See [AgentServer]. */
private val AGENT_RUNS_DIRECTORY = File(SHARK_EXPLORER_DIRECTORY, "agents")

/** What a command line says to be a pipe rather than a window. See [AgentStdioBridge]. */
internal const val MCP_STDIO_OPTION = "--mcp-stdio"
