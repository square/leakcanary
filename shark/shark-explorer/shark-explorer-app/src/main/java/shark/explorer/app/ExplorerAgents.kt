package shark.explorer.app

import androidx.compose.runtime.snapshotFlow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import shark.SharkLog
import shark.explorer.AndroidDevice
import shark.explorer.DeepLink
import shark.explorer.DeviceHeapDumps
import shark.explorer.DeviceProcess
import shark.explorer.HeapExplorer
import shark.explorer.LeakStatusOverride
import shark.explorer.LeakStatusOverrides
import shark.explorer.Place
import shark.explorer.agent.AgentCommandLine
import shark.explorer.agent.AgentHeapDump
import shark.explorer.agent.AgentHeapDumps
import shark.explorer.agent.AgentRefusal
import shark.explorer.agent.AgentServer
import shark.explorer.agent.AgentSession
import shark.explorer.agent.AgentSessionFile
import shark.explorer.agent.AgentStdioBridge
import shark.explorer.agent.AgentStdioServer
import shark.explorer.agent.ShownPlace
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
 * Everything an agent can ask this run that isn't a question about one open heap dump: which dumps are open,
 * opening another, and the two `adb` questions behind taking one off a device.
 *
 * Whether this run has windows shows up in exactly two places — which dumps are open, and what opening one
 * means — so those are what a subclass answers and the rest is here. A device is a device either way, and a
 * heap dump pulled off one is a file that then has to be opened, which is [open] again.
 */
internal abstract class RunAgentHeapDumps(
  private val deviceHeapDumps: DeviceHeapDumps
) : AgentHeapDumps {

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
    // minutes of it, and an agent reads a bitmap's size rather than looking at it. Whoever ends up at the
    // window can still fetch them from the panel afterwards.
    return open(heapDumpFile)
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
   * Which is not the heap dump's thread either: a dump takes minutes of shelling out, and whatever heap dump
   * is already open is being read while it does.
   */
  private suspend fun <T> onAdbThread(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}

/**
 * How an agent reaches the windows of this run: the app's side of `shark-explorer-agent`.
 *
 * The one thing worth knowing here is **why an agent is given a window and not a heap dump file**. Every read
 * goes through that window's own [HeapDumpSession], so an agent's question queues on the thread the person at
 * the machine is already reading with, and the verdicts and notes it writes are the ones that window is
 * drawing. An agent that opened the dump itself would be answering about a heap dump nobody is looking at, and
 * its conclusions would land in a file the open window would then overwrite.
 *
 * Which is also why opening a dump and taking one off a device end in a window here rather than in a file
 * path: they are the two buttons above the map, and an agent pressing one has to end up somewhere its human
 * can follow it to. [HeadlessAgentHeapDumps] is the same surface for a run that has no window at all.
 */
internal class WindowAgentHeapDumps(
  private val windows: ExplorerWindows,
  deviceHeapDumps: DeviceHeapDumps
) : RunAgentHeapDumps(deviceHeapDumps) {

  override fun openHeapDumps(): List<AgentHeapDump> =
    // Asked per call rather than captured, because windows come and go while an agent is connected: a tool
    // naming a window that has since closed has to be an error message and not a stale answer.
    windows.mapNotNull { window ->
      window.openHeapDump?.let { open -> window.agentHeapDump(open) }
    }

  override fun openingHeapDumpPaths(): List<String> = windows.mapNotNull { window ->
    // A window opened on a file it is still indexing, which is what a run started on a path looks like for as
    // long as the indexing takes — and what an agent connecting in that window is otherwise told nothing about.
    window.heapDumpFile?.takeIf { window.openHeapDump == null }?.absolutePath
  }

  override suspend fun open(file: File): AgentHeapDump {
    // A window already on this file rather than a second window on it, which is the opposite of what the
    // button does: a person clicking `Open heap dump…` twice on one dump is comparing two readings of it, and
    // an agent naming a path is naming a heap dump. Which matters most in the case this tool was written for —
    // a window opened for an agent publishes this run before its dump is readable, so the agent's first move
    // is to open the path it was pointed at, and a second window on it would be a second index of the same
    // gigabyte and a window nobody asked for.
    val already = windows.firstOrNull { it.heapDumpFile?.absoluteFile == file.absoluteFile }
    // Edited from whichever thread the agent's connection is on, exactly as a link arriving from another run
    // of this app edits it: a window is snapshot state, and the composition takes the change on the next
    // frame. See [ExplorerWindow.linkedPlaces].
    val window = already ?: windows.openHeapDump(file)
    SharkLog.d {
      if (already == null) {
        "An agent opened ${file.absolutePath} in window ${window.deepLinkId}"
      } else {
        "An agent asked for ${file.absolutePath}, which window ${window.deepLinkId} already has"
      }
    }
    // Three ways this ends and only one of them is an answer — the dump opens, it fails to open, or the
    // window is closed under it — because a window id handed over before its dump is open is one that refuses
    // every call made with it, and either of the other two would otherwise be a call that never comes back.
    // Which is what [ExplorerWindow.openProblem] exists for.
    snapshotFlow {
      window.openHeapDump != null || window.openProblem != null || window !in windows
    }.first { it }
    val open = window.openHeapDump
    if (open != null) {
      return window.agentHeapDump(open)
    }
    throw AgentRefusal(
      window.openProblem?.let { "${file.name} could not be opened as a heap dump: $it" }
        ?: "Window ${window.deepLinkId} was closed before ${file.name} had finished opening, so there is " +
        "nothing to read. Opening it again is a call away."
    )
  }
}

/**
 * Whether this process was started to talk MCP over stdio, and what to exit with if it was. Null for every
 * other command line, which is the app opening windows.
 *
 * Answered before anything else in `main` and before any logging is installed, because the app's logger
 * writes to stdout and in this mode **stdout is the protocol**. Which of the two shapes it is depends only on
 * whether there is a screen to investigate on: [AgentStdioBridge] pipes to a window, and [NO_UI_OPTION]
 * serves the tools from this process.
 */
internal fun agentBridgeExitCode(args: Array<String>): Int? {
  if (MCP_STDIO_OPTION !in args) {
    return null
  }
  val arguments = try {
    windowArguments(args)
  } catch (invalidArguments: IllegalArgumentException) {
    // On stderr, where an MCP client collects a server's log, since there is no window and no console to
    // print a usage message to.
    saidToTheClient(invalidArguments.message.orEmpty())
    return UNREADABLE_COMMAND_LINE
  }
  if (NO_UI_OPTION in args) {
    return serveAgentsWithNoWindow(arguments)
  }
  val pid = args.firstOrNull { it.startsWith(AgentStdioBridge.PID_OPTION) }
    ?.removePrefix(AgentStdioBridge.PID_OPTION)
  return AgentStdioBridge.run(
    directory = AGENT_RUNS_DIRECTORY,
    pid = pid,
    // So that an agent pointed at a machine where nothing is open gets a heap dump to investigate and its
    // human gets a window to watch it in, rather than being told to go and launch something. Declined when
    // this run has no way of knowing what started it — see [relaunchCommand].
    openAWindow = relaunchCommand()?.let { command -> { openAnotherRun(command, arguments) } }
  )
}

/**
 * Whether this process was started to make one tool call from a shell, and what to exit with if it was.
 *
 * The other adapter over the same tools: `--mcp-stdio` is a client holding a session open, and this is an
 * agent — or a person — typing one command at a window that is already up. See [AgentCommandLine].
 *
 * Answered before any logging is installed for the reason the pipe is: **stdout carries the answer**, and a
 * log line in the middle of it is JSON that whatever ran this cannot parse.
 */
internal fun agentCommandExitCode(args: Array<String>): Int? {
  val helpIndex = args.indexOf(AgentCommandLine.HELP_OPTION)
  val callIndex = args.indexOf(AgentCommandLine.AGENT_OPTION)
  if (helpIndex < 0 && callIndex < 0) {
    return null
  }
  if (helpIndex >= 0) {
    // On stdout, since this is the whole of what the command was run for. No window, no heap dump and no
    // waiting: the tools are text this build carries.
    println(AgentCommandLine.help(command = commandToRunThis(), toolName = args.toolNameAt(helpIndex)))
    return 0
  }
  val toolName = args.toolNameAt(callIndex)
  val arguments = try {
    // Everything that isn't the call is the command line of the window this may have to open.
    windowArguments(args, toolName)
  } catch (invalidArguments: IllegalArgumentException) {
    saidToTheClient(invalidArguments.message.orEmpty())
    return UNREADABLE_COMMAND_LINE
  }
  return AgentCommandLine.run(
    directory = AGENT_RUNS_DIRECTORY,
    words = listOfNotNull(toolName) + args.filter { AgentCommandLine.isCallArgument(it) },
    pid = args.optionValue(AgentStdioBridge.PID_OPTION),
    sessionName = args.optionValue(AgentCommandLine.SESSION_OPTION)
      ?: AgentCommandLine.defaultSessionName(),
    // The same window a client that found nothing open gets, and here it is worth more: the next call from
    // this shell finds that run published and talks to it, so one command line opening a window is what
    // makes every command after it cheap.
    openAWindow = relaunchCommand()?.let { command -> { openAnotherRun(command, arguments) } }
  )
}

/**
 * The rest of the command line, once the options that make this a server or a call are off it.
 *
 * Because what is left is an ordinary command line — heap dumps to open, a title to call their windows — and
 * it means the same thing: a client's configuration says which dump to investigate the way a terminal does.
 * Taken off here rather than taught to the parser, so that a window is the only thing that ever sees them.
 *
 * Throws [IllegalArgumentException] for a command line that doesn't read, like the parser it wraps.
 */
internal fun windowArguments(
  args: Array<String>,
  /** The one word of a call that isn't `name=value`, and null for a command line that is no call. */
  toolName: String? = null
): ExplorerArguments = ExplorerArguments.parse(
  args.filterNot { word ->
    word.isAgentOption() || AgentCommandLine.isCallArgument(word) || (toolName != null && word == toolName)
  }
)

/**
 * The word naming a tool at [index] of the command line, which is the one after the option.
 *
 * Positional because a call reads as a command — `--agent describe_object object=0x7205` — and null for an
 * option that was given nothing, which is `--agent-help` on its own.
 */
private fun Array<String>.toolNameAt(index: Int): String? =
  getOrNull(index + 1)?.takeIf { !it.startsWith("-") && !AgentCommandLine.isCallArgument(it) }

private fun Array<String>.optionValue(option: String): String? =
  firstOrNull { it.startsWith(option) }?.removePrefix(option)

/** What a word of the command line has to be to reach an agent rather than a window. */
private fun String.isAgentOption(): Boolean = this == MCP_STDIO_OPTION || this == NO_UI_OPTION ||
  this == AgentCommandLine.AGENT_OPTION || this == AgentCommandLine.HELP_OPTION ||
  startsWith(AgentStdioBridge.PID_OPTION) || startsWith(AgentCommandLine.SESSION_OPTION)

/**
 * What to type to run this app, for the examples in the help.
 *
 * The launcher of a packaged install, which is a path somebody can copy — and the generic name for a run
 * from source, where the real command line is a JVM and a classpath nobody wants printed at them.
 */
private fun commandToRunThis(): String {
  val launcher = launcherPathOrNull() ?: return "shark-explorer"
  return if (' ' in launcher) "\"$launcher\"" else launcher
}

/**
 * Answers an agent's calls from this process, with no window anywhere.
 *
 * For a machine with no screen — a build server, or a heap dump on the far end of an ssh session — and for
 * anything that drives an agent without a person watching, which is what the eval is. Everything an
 * investigation leaves behind is on disk either way, so a dump worked on here opens in a window later with
 * the notes and the verdicts on it. See [HeadlessAgentHeapDumps].
 */
private fun serveAgentsWithNoWindow(arguments: ExplorerArguments): Int {
  // On stderr, because stdout is the protocol and the tools run in this process: unlike the bridge, the heap
  // dump's own diagnostics are in this stream too, and every one of them would be a broken JSON-RPC message.
  // The log file is written as usual, which is what makes a headless session as readable as a windowed one.
  return installLogging(System.err).use {
    SharkLog.d { "Answering an agent over stdio, with no window" }
    HeadlessAgentHeapDumps(
      deviceHeapDumps = commandLineDeviceHeapDumps(),
      heapDumpFiles = arguments.heapDumpFiles
    ).use { heapDumps ->
      AgentStdioServer.run(
        heapDumps = heapDumps,
        serverVersion = SharkExplorerVersion.current,
        sessions = AgentServer.sessionsDirectory(AGENT_RUNS_DIRECTORY)
      )
    }
  }
}

/**
 * Starts another Shark Explorer, with a window, and leaves it running.
 *
 * **Deliberately outliving this process.** The bridge ends when the agent's client closes the pipe, and the
 * window it opened is the whole point: whoever is at the machine reads the notes and the verdicts afterwards,
 * on the tabs the agent left open.
 *
 * With the same command line this run was given, so that a client configured to investigate one heap dump
 * opens a window on that dump rather than an empty one the agent then has to fill.
 */
private fun openAnotherRun(
  command: List<String>,
  arguments: ExplorerArguments
) {
  val titled = command + arguments.heapDumpFiles.map { it.absolutePath } +
    "$TITLE_OPTION=${arguments.titlePrefix ?: AGENT_WINDOW_TITLE}"
  try {
    ProcessBuilder(titled)
      // Its stdout is where its own diagnostics go, and they are in its log file too; its stderr is worth
      // inheriting, because a window that dies before it can open a log file says why only there.
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.INHERIT)
      .start()
  } catch (throwable: Throwable) {
    saidToTheClient("Could not start a window with ${titled.joinToString(" ")}: $throwable")
  }
}

/**
 * On stderr, always, which is where an MCP client collects what a server has to say.
 *
 * Not through `SharkLog`, and not only because stdout is the protocol: this is said before any logging has
 * been installed, by the two paths that end before there is anything to install it for.
 */
private fun saidToTheClient(message: String) {
  System.err.println("[shark-explorer] $message")
}

/**
 * One heap dump open: the thread every read of it queues on, and the two things an investigation writes into.
 *
 * Gathered because they are all per heap dump and all wanted together by everything that isn't drawing the
 * window — which is an agent, whether or not there is a window. See [ExplorerWindow.openHeapDump].
 */
internal class OpenHeapDump(
  val session: HeapDumpSession,
  val notes: HeapDumpNotes,
  val leakStatuses: HeapDumpLeakStatuses
)

/** This window's heap dump as an agent sees it: shown by going to a tab, the way a link does. */
private fun ExplorerWindow.agentHeapDump(open: OpenHeapDump): AgentHeapDump =
  OpenAgentHeapDump(windowId = deepLinkId, open = open) { place ->
    SharkLog.d { "An agent asked window $deepLinkId for $place" }
    // The same two steps following a link takes, which is what makes an agent showing something and a
    // person clicking a link land in the same place. See [ExplorerWindows.open].
    goToLinked(place)
    bringToFront()
    // And the link itself, which is the same one the right click menu copies: an agent's answer can then
    // point at this place rather than describe how to get to it.
    ShownPlace.at(DeepLink(deepLinkId, place).toUri())
  }

/**
 * One open heap dump, as the agent surface sees it, however this run came by it.
 *
 * One class rather than one per kind of run, because what differs between a window and a machine with no
 * screen is a single call: where [show] puts a place. Everything else — the reads, the verdicts, the notes and
 * every refusal about them — is about the heap dump and the files beside it, which are the same either way.
 */
internal class OpenAgentHeapDump(
  override val windowId: String,
  private val open: OpenHeapDump,
  /** Where a place goes, and the link to it. See [AgentHeapDump.show]. */
  private val showPlace: (Place) -> ShownPlace
) : AgentHeapDump {

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

  override fun show(place: Place): ShownPlace = showPlace(place)

  /**
   * Puts what [newText] makes of the saved note on disk, whether that is the note plus a paragraph or
   * something else entirely.
   *
   * **Refuses while somebody is typing in that note**, which is the one case where writing would cost
   * something that exists nowhere else: a draft is unsaved text, and saving over it would put half a sentence
   * of theirs on disk under an answer of ours. A run with no window has no drafts, so there it never fires.
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
internal val AGENT_RUNS_DIRECTORY = File(SHARK_EXPLORER_DIRECTORY, "agents")

/** What a command says to talk MCP over stdio rather than open a window. See [AgentStdioBridge]. */
internal const val MCP_STDIO_OPTION = "--mcp-stdio"

/**
 * What a command says to answer an agent from this process rather than pipe it to a window.
 *
 * Only meaningful with [MCP_STDIO_OPTION], and deliberately not a way to run the app without a UI: the app
 * *is* its windows, so a run that opened none and served nobody would sit there doing nothing. A command line
 * with this and no `--mcp-stdio` is a window, which is the one thing it can't have meant.
 */
internal const val NO_UI_OPTION = "--no-ui"

/** What a window opened for an agent that found none is called, since nobody typed a title for it. */
private const val AGENT_WINDOW_TITLE = "Opened for an agent"

/**
 * What this process ends with when the command line it was given doesn't read.
 *
 * A failure rather than a message and a window, because a client that launched this has nowhere to show one:
 * an MCP server that starts and lists no tools reads as a server with no tools.
 */
private const val UNREADABLE_COMMAND_LINE = 1
