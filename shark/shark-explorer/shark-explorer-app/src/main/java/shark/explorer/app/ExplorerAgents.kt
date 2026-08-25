package shark.explorer.app

import androidx.compose.runtime.snapshotFlow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import shark.SharkLog
import shark.explorer.AndroidDevice
import shark.explorer.DeviceHeapDumps
import shark.explorer.DeviceProcess
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
import shark.explorer.placeOfNoteKeyOrNull

/**
 * Publishes this run so that agents can find it, or does nothing if it can't. See [AgentServer].
 *
 * Its own socket rather than the one `DeepLinkPeers` listens on, because the two have nothing in common but
 * being loopback: a link is one line answered in a millisecond, and this is a session held open for as long
 * as an investigation takes.
 */
internal fun listenForAgents(
  windows: ExplorerWindows,
  deviceHeapDumps: DeviceHeapDumps
) = AgentServer.listen(
  heapDumps = WindowAgentHeapDumps(windows, deviceHeapDumps),
  serverVersion = SharkExplorerVersion.current,
  directory = AGENT_RUNS_DIRECTORY
)

/**
 * How an agent reaches this run: the app's side of `shark-explorer-agent`.
 *
 * The one thing worth knowing here is **why an agent is given a window and not a heap dump file**. Every read
 * goes through that window's own [HeapDumpSession], so an agent's question queues on the thread the person at
 * the machine is already reading with, and the verdicts and notes it writes are the ones that window is
 * drawing. An agent that opened the dump itself would be answering about a heap dump nobody is looking at, and
 * its conclusions would land in a file the open window would then overwrite.
 *
 * Which is also why opening a dump and taking one off a device end in a window here rather than in a file
 * path: they are the two buttons above the map, and an agent pressing one has to end up somewhere its human
 * can follow it to.
 */
private class WindowAgentHeapDumps(
  private val windows: ExplorerWindows,
  private val deviceHeapDumps: DeviceHeapDumps
) : AgentHeapDumps {

  override fun openHeapDumps(): List<AgentHeapDump> =
    // Asked per call rather than captured, because windows come and go while an agent is connected: a tool
    // naming a window that has since closed has to be an error message and not a stale answer.
    windows.mapNotNull { window ->
      window.openHeapDump?.let { open -> WindowAgentHeapDump(window, open) }
    }

  override suspend fun open(file: File): AgentHeapDump {
    // Edited from whichever thread the agent's connection is on, exactly as a link arriving from another run
    // of this app edits it: a window is snapshot state, and the composition takes the change on the next
    // frame. See [ExplorerWindow.linkedPlaces].
    val window = windows.openHeapDump(file)
    SharkLog.d { "An agent opened ${file.absolutePath} in window ${window.deepLinkId}" }
    return awaitHeapDump(window)
  }

  override suspend fun devices(): List<AndroidDevice> = onAdbThread {
    deviceHeapDumps.connectedDevices()
  }

  override suspend fun processesOf(serialNumber: String): List<DeviceProcess> = onAdbThread {
    deviceHeapDumps.appProcesses(device(serialNumber))
  }

  override suspend fun dumpHeap(
    serialNumber: String,
    processName: String
  ): AgentHeapDump {
    val heapDumpFile = onAdbThread {
      val device = device(serialNumber)
      val process = deviceHeapDumps.appProcesses(device).firstOrNull { it.name == processName }
        ?: throw AgentRefusal(
          "No process called \"$processName\" is running on ${device.description}. A process is dumped by " +
            "name because a pid changes every time the app restarts, so ask list_devices again: what it " +
            "answers with is what is running now."
        )
      // Every step of it in this run's log, which is the only place a dump that is taking minutes says how
      // far it has got — the agent is waiting for one answer and there is nothing to stream it through.
      deviceHeapDumps.dumpHeap(device, process) { step -> SharkLog.d { "For an agent: $step" } }
    }
    // No pixels fetched to go with it, unlike the dialog's tick box: that is a second suspension of the app,
    // minutes of it, and an agent reads a bitmap's size rather than looking at it. Whoever is at the window
    // can still fetch them from the panel afterwards.
    val window = windows.openHeapDump(heapDumpFile)
    SharkLog.d { "An agent dumped $processName into window ${window.deepLinkId}" }
    return awaitHeapDump(window)
  }

  /**
   * Waits for [window]'s heap dump to be readable, and refuses when it never will be.
   *
   * Three ways it ends and only one of them is an answer — the dump opens, it fails to open, or the window is
   * closed under it — because a window id handed over before its dump is open is one that refuses every call
   * made with it, and either of the other two would otherwise be a call that never comes back. Which is what
   * [ExplorerWindow.openProblem] exists for.
   */
  private suspend fun awaitHeapDump(window: ExplorerWindow): AgentHeapDump {
    snapshotFlow {
      window.openHeapDump != null || window.openProblem != null || window !in windows
    }.first { it }
    val open = window.openHeapDump
    if (open != null) {
      return WindowAgentHeapDump(window, open)
    }
    val name = window.heapDumpFile?.name
    throw AgentRefusal(
      window.openProblem?.let { "$name could not be opened as a heap dump: $it" }
        ?: "Window ${window.deepLinkId} was closed before $name had finished opening, so there is nothing " +
        "to read. Opening it again is a call away."
    )
  }

  /** The device with this serial number, or a refusal listing the ones there are. */
  private fun device(serialNumber: String): AndroidDevice {
    val devices = deviceHeapDumps.connectedDevices()
    return devices.firstOrNull { it.serialNumber == serialNumber }
      ?: throw AgentRefusal(
        "`adb` is connected to no device called \"$serialNumber\". " + if (devices.isEmpty()) {
          "It is connected to nothing at all."
        } else {
          "It is connected to " + devices.joinToString(", ") { "${it.serialNumber} (${it.description})" } +
            "."
        }
      )
  }

  /**
   * Everything `adb` blocks on, off the connection's thread.
   *
   * Which is not the heap dump's thread either: a dump takes minutes of shelling out, and the window it will
   * open in is being read by whoever is at the machine while it does.
   */
  private suspend fun <T> onAdbThread(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}

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

  override suspend fun appendToNote(
    place: Place,
    text: String
  ) = write(place) { existing ->
    listOf(existing, text).filter { it.isNotBlank() }.joinToString(PARAGRAPH_BREAK)
  }

  override suspend fun replaceNote(
    place: Place,
    text: String
  ) = write(place) { text }

  override suspend fun readNote(place: Place): String = readable(place).text

  override suspend fun notedPlaces(): List<Place> {
    // The same listing the tab strip is marked from, read once per run of the app either way.
    open.notes.list()
    return open.notes.writtenAbout.mapNotNull { key -> placeOfNoteKeyOrNull(key) }
  }

  /**
   * Puts what [newText] makes of the saved note on disk, whether that is the note plus a paragraph or
   * something else entirely.
   *
   * **Refuses while somebody is typing in that note**, which is the one case where writing would cost
   * something that exists nowhere else: a draft is unsaved text, and saving over it would put half a sentence
   * of theirs on disk under an answer of ours.
   */
  private suspend fun write(
    place: Place,
    newText: (String) -> String
  ) {
    val notepad = readable(place)
    if (notepad.draft != null) {
      throw AgentRefusal(
        "Somebody is writing in the notes of that place right now, so writing there would take their " +
          "unsaved words with it. Say what you found in your answer instead, or try again once they have " +
          "saved."
      )
    }
    notepad.edit()
    notepad.edited(newText(notepad.text))
    notepad.save()
    if (notepad.problem != null) {
      throw AgentRefusal("The notes could not be saved: ${notepad.problem}")
    }
  }

  /**
   * The notepad of [place] with its file read, refusing when it couldn't be.
   *
   * Before reading it as much as before writing it: an unread notepad's text is empty because nothing has
   * been read rather than because nothing was written, so answering with it would be telling an agent that
   * a note it is about to replace does not exist.
   */
  private suspend fun readable(place: Place): PlaceNotes {
    val notepad = open.notes.of(place)
    notepad.read()
    if (!notepad.isRead) {
      throw AgentRefusal(
        "The notes of that place could not be read, so what is in them is unknown: " +
          (notepad.problem ?: "reading ${notepad.file} did not finish.")
      )
    }
    return notepad
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
