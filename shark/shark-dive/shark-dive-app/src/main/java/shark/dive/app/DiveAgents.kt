package shark.dive.app

import androidx.compose.runtime.snapshotFlow
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import shark.SharkLog
import shark.dive.AndroidDevice
import shark.dive.DeepLink
import shark.dive.DeviceHeapDumps
import shark.dive.DeviceProcess
import shark.dive.HeapDive
import shark.dive.HeapSizes
import shark.dive.LeakStatusOverride
import shark.dive.LeakStatusOverrides
import shark.dive.Place
import shark.dive.agent.AgentCommandLine
import shark.dive.agent.AgentHeapDump
import shark.dive.agent.AgentHeapDumps
import shark.dive.agent.AgentRefusal
import shark.dive.agent.AgentServer
import shark.dive.agent.AgentSession
import shark.dive.agent.AgentSessionFile
import shark.dive.agent.ShownPlace
import shark.dive.placeOfNoteKeyOrNull

/**
 * Publishes this run so that agents can find it, or does nothing if it can't. See [AgentServer].
 *
 * Its own socket rather than the one `DeepLinkPeers` listens on, because the two have nothing in common but
 * being loopback: a link is one line answered in a millisecond, and this is a session held open for as long
 * as an investigation takes.
 */
internal fun listenForAgents(
  windows: DiveWindows,
  deviceHeapDumps: DeviceHeapDumps
) = listenForAgents(WindowAgentHeapDumps(windows, deviceHeapDumps), hasWindow = true)

/**
 * How this app publishes itself to agents, whatever it has open.
 *
 * One place, because **a run with no window is published exactly like a run with windows** — same directory,
 * same handshake, same sessions — so that a command finds either without being told which it is talking to.
 * What the published file says about the difference is [hasWindow], and it is there for the one decision that
 * has to be made before connecting: which kind of run a heap dump is opened in. See [HeadlessAgentHeapDumps].
 */
internal fun listenForAgents(
  heapDumps: AgentHeapDumps,
  hasWindow: Boolean
) = AgentServer.listen(
  heapDumps = heapDumps,
  serverVersion = SharkDiveVersion.current,
  // So that a command only ever talks to a run of its own build, which is what makes this surface workable
  // while it is being worked on: the run from the last branch is still up. See [SharkDiveVersion.buildSha].
  buildSha = SharkDiveVersion.buildSha,
  hasWindow = hasWindow,
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
 * How an agent reaches the windows of this run: the app's side of `shark-dive-agent`.
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
internal class WindowAgentHeapDumps(
  private val windows: DiveWindows,
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
    // frame. See [DiveWindow.linkedPlaces].
    val window = already ?: windows.openHeapDump(file)
    SharkLog.d {
      if (already == null) {
        "An agent opened ${file.absolutePath} in window ${window.windowId}"
      } else {
        "An agent asked for ${file.absolutePath}, which window ${window.windowId} already has"
      }
    }
    // Three ways this ends and only one of them is an answer — the dump opens, it fails to open, or the
    // window is closed under it — because a heap dump named back before it is open is one that refuses every
    // call made about it, and either of the other two would otherwise be a call that never comes back. Which
    // is what [DiveWindow.openProblem] exists for.
    snapshotFlow {
      window.openHeapDump != null || window.openProblem != null || window !in windows
    }.first { it }
    val open = window.openHeapDump
    if (open != null) {
      return window.agentHeapDump(open)
    }
    throw AgentRefusal(
      window.openProblem?.let { "${file.name} could not be opened as a heap dump: $it" }
        ?: "The window opening ${file.name} was closed before it had finished, so there is nothing to " +
        "read. Opening it again is one command away."
    )
  }

  /**
   * Closes the window drawing [dump], which is what closes the heap dump: the session belongs to the window,
   * and disposing the composition that drew it is what releases the thread it was read on.
   *
   * The run itself ends when the last window goes, and that rule is in one place — `diveApplication` — rather
   * than here, because a person clicking the last window's close button means exactly the same thing.
   */
  override suspend fun close(dump: AgentHeapDump) {
    val window = windows.firstOrNull { window ->
      window.openHeapDump?.session?.heapDumpFile?.absolutePath == dump.heapDumpPath
    } ?: throw AgentRefusal(
      "${File(dump.heapDumpPath).name} is not open here any more, so there is nothing to close: somebody " +
        "closed its window while you were reading it."
    )
    SharkLog.d { "An agent closed ${dump.heapDumpPath}, which window ${window.windowId} had open" }
    // Edited from the connection's thread, like every other change to a window here: the composition takes it
    // on the next frame, and `onDispose` closes the session.
    windows -= window
  }
}

/**
 * Whether this process was started to run one command from a shell, and what to exit with if it was. Null for
 * every other command line, which is the app opening windows.
 *
 * The whole way in: an agent — or a person — typing one command at a run that already has the heap dump open.
 * See [AgentCommandLine].
 *
 * Answered before anything else in `main` bar the help, and before any logging is installed, because **stdout
 * carries the answer** and a log line in the middle of it is JSON that whatever ran this cannot parse.
 */
internal fun cliExitCode(args: Array<String>): Int? {
  val cliIndex = args.indexOf(AgentCommandLine.CLI_OPTION)
  if (cliIndex < 0) {
    return null
  }
  val commandName = args.commandNameAt(cliIndex)
  val arguments = try {
    // Everything that isn't the command is the command line of the run this may have to start.
    windowArguments(args, commandName)
  } catch (invalidArguments: IllegalArgumentException) {
    saidToTheCaller(invalidArguments.message.orEmpty())
    return UNREADABLE_COMMAND_LINE
  }
  return AgentCommandLine.run(
    directory = AGENT_RUNS_DIRECTORY,
    command = commandToRunThis(),
    words = listOfNotNull(commandName) + args.filter { AgentCommandLine.isCallArgument(it) },
    buildSha = SharkDiveVersion.buildSha,
    pid = args.optionValue(AgentCommandLine.RUN_OPTION),
    noWindow = AgentCommandLine.NO_UI_OPTION in args,
    sessionName = args.optionValue(AgentCommandLine.SESSION_OPTION)
      ?: AgentCommandLine.defaultSessionName(),
    // So that opening a heap dump where nothing is open gets one open, and its human a window to watch it in,
    // rather than being told to go and launch something. Worth more than it looks: every command after this
    // one finds that run published and talks to it, so one command line starting a run is what makes the rest
    // of them cheap. Declined when this run has no way of knowing what started it — see [relaunchCommand].
    openARun = relaunchCommand()?.let { command ->
      { noWindow -> openAnotherRun(command, arguments, noWindow) }
    }
  )
}

/**
 * Whether this process was started to answer agents with no window, and what to exit with if it was. Null for
 * every other command line.
 *
 * **A run rather than a way in.** `--no-ui` opens no window and publishes the same socket every run publishes,
 * so a command reaches it exactly as it reaches a window and nothing on the calling side knows the difference
 * — which is what makes a machine with no screen, a build agent or a box over ssh, a machine this surface
 * works on. A headless mode with a transport of its own is what the MCP server had, and
 * `shark/shark-dive/notes/agent-surface.md` has why that is the half of it that went.
 *
 * What it costs is one call: `show` has nowhere to put a tab and hands back a link instead of claiming
 * somebody saw it. See [HeadlessAgentHeapDumps].
 *
 * Answered in `main` after [cliExitCode] and before any window, since a run with no window has no reason to
 * start Compose — and on a machine with no display, starting it is how this would die. Which is also why the
 * option reaches here at all: `--cli open_heap_dump --no-ui` is a *command*, answered above, and the run it
 * starts is this, carrying the option with no `--cli` in front of it.
 */
internal fun headlessAgentExitCode(args: Array<String>): Int? {
  if (AgentCommandLine.NO_UI_OPTION !in args) {
    return null
  }
  val arguments = try {
    // The heap dumps to open as this comes up, which is the whole of what is left once the option is off.
    windowArguments(args)
  } catch (invalidArguments: IllegalArgumentException) {
    saidToTheCaller(invalidArguments.message.orEmpty())
    return UNREADABLE_COMMAND_LINE
  }
  return serveAgentsWithNoWindow(arguments)
}

/**
 * Publishes this run, answers agents, and blocks until the last heap dump open is closed.
 *
 * Logging as usual — stdout and a file — because nothing here is a protocol: whoever started this reads every
 * call and the reads it caused in the terminal it is running in, which is the closest thing to watching a
 * window that a machine with no screen has. The MCP server had to put this on stderr, stdout being the pipe.
 *
 * **What ends it is the same rule a run with windows has**: a run is its heap dumps, and one with none left
 * has nothing to come back to. Nothing is left behind either way — the file naming this run is deleted as
 * [AgentServer.listen] is closed, and by the shutdown hook it installs.
 */
private fun serveAgentsWithNoWindow(arguments: DiveArguments): Int {
  installLogging().use {
    SharkLog.d {
      "Started with ${AgentCommandLine.NO_UI_OPTION}, so this run has no window and every call it answers " +
        "is here"
    }
    // Counted down by closing the last heap dump open. See [HeadlessAgentHeapDumps.close].
    val over = CountDownLatch(1)
    HeadlessAgentHeapDumps(
      deviceHeapDumps = commandLineDeviceHeapDumps(),
      heapDumpFiles = arguments.heapDumpFiles,
      endTheRun = over::countDown
    ).use { heapDumps ->
      listenForAgents(heapDumps, hasWindow = false).use {
        // Calls are answered on the socket's own threads, so what this thread has left to do is keep the
        // process they are in alive.
        over.await()
      }
    }
  }
  return 0
}

/**
 * The rest of the command line, once the options that make this a command are off it.
 *
 * Because what is left is an ordinary command line — heap dumps to open, a title to call their windows — and
 * it means the same thing: a client's configuration says which dump to investigate the way a terminal does.
 * Taken off here rather than taught to the parser, so that a window is the only thing that ever sees them.
 *
 * Throws [IllegalArgumentException] for a command line that doesn't read, like the parser it wraps.
 */
internal fun windowArguments(
  args: Array<String>,
  /** The one word of a command that isn't `name=value`, and null for a command line that is no command. */
  commandName: String? = null
): DiveArguments = DiveArguments.parse(
  args.filterNot { word ->
    word.isCliOption() || AgentCommandLine.isCallArgument(word) ||
      (commandName != null && word == commandName)
  }
)

/**
 * The word naming a command at [index] of the command line, which is the one after the option.
 *
 * Positional, because a command reads as a command — `--cli describe_object object=0x7205` — and null for an
 * option given nothing, which is the command line [helpExitCode] answers.
 */
internal fun Array<String>.commandNameAt(index: Int): String? =
  getOrNull(index + 1)?.takeIf { !it.startsWith("-") && !AgentCommandLine.isCallArgument(it) }

private fun Array<String>.optionValue(option: String): String? =
  firstOrNull { it.startsWith(option) }?.removePrefix(option)

/** What a word of the command line has to be to reach a command rather than a window. */
internal fun String.isCliOption(): Boolean =
  this == AgentCommandLine.CLI_OPTION || this == AgentCommandLine.HELP_OPTION ||
    this == AgentCommandLine.LEAK_METHOD_OPTION || this == AgentCommandLine.NO_UI_OPTION ||
    startsWith(AgentCommandLine.RUN_OPTION) || startsWith(AgentCommandLine.SESSION_OPTION)

/**
 * What to type to run this app, for the examples in the help.
 *
 * The launcher of a packaged install, which is a path somebody can copy — and the generic name for a run
 * from source, where the real command line is a JVM and a classpath nobody wants printed at them.
 */
internal fun commandToRunThis(): String {
  val launcher = launcherPathOrNull() ?: return "shark-dive"
  return if (' ' in launcher) "\"$launcher\"" else launcher
}

/**
 * Starts another Shark Dive and leaves it running, with a window unless [noWindow].
 *
 * **Deliberately outliving this process.** A command ends with its one answer, and the run it started is the
 * whole point: every command after it reaches that run, and whoever is at the machine reads the notes and the
 * verdicts afterwards, on the tabs the agent left open.
 *
 * With the rest of the command line this process was given, so that `--title` and any heap dump named outside
 * the command mean here what they mean to a run somebody launched by hand.
 */
private fun openAnotherRun(
  command: List<String>,
  arguments: DiveArguments,
  noWindow: Boolean
) {
  val started = command + arguments.heapDumpFiles.map { it.absolutePath } + listOfNotNull(
    "$TITLE_OPTION=${arguments.titlePrefix ?: CLI_RUN_TITLE}",
    AgentCommandLine.NO_UI_OPTION.takeIf { noWindow }
  )
  val detached = detached(started)
  try {
    ProcessBuilder(detached)
      // Both discarded, because **the caller's streams are somebody's pipe**: a run holding the stderr this
      // process inherited is a command that appears never to finish, for whatever reads until end of file.
      // Which is the mirror image of the problem [detached] solves, and worse — a window that dies before it
      // can open a log file says why nowhere, against a command that hangs for as long as one is open. So
      // that diagnostic goes where every other one goes, `~/.shark-dive/logs`, and the one case it can't
      // cover — dying before there is a log file — is a run that never published itself, which is what the
      // caller is told.
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
  } catch (throwable: Throwable) {
    saidToTheCaller("Could not start Shark Dive with ${detached.joinToString(" ")}: $throwable")
  }
}

/**
 * [started], wrapped in whatever this OS takes to leave the new run **out of this process's process group**.
 *
 * A child of this process already survives it exiting — it is reparented to `launchd` or `init` — and that is
 * not the case that kills it. A signal aimed at a *group* is: closing a terminal sends `SIGHUP` to its
 * foreground process group, and a JVM dies on `SIGHUP`, so the run an agent opened goes with the shell the
 * command was typed in. Anything that kills a command by its group — which is what a coding agent's own
 * harness does — takes it too.
 *
 * Measured three ways, and recorded in `shark/shark-dive/notes/agent-surface.md`:
 *
 * - A plain child keeps this process's group and session, so both of the above reach it.
 * - `open -n -a <bundle>` hands the launch to LaunchServices: the run comes out a child of `launchd`, in a
 *   session of its own, with no terminal. Nothing aimed at this process's group or session can reach it.
 * - `sh -c 'set -m; "$@" &'` turns job control on for one command, which puts the backgrounded JVM in a group
 *   of its own. Still this process's session, so it is the weaker of the two and the one for a run from a
 *   classpath, where there is no bundle to name.
 *
 * `open` gives the run the root directory to work in, so every path handed over has to be absolute — which
 * they are, `DiveArguments` holding files and this spelling them `absolutePath`. And it hands back no pid,
 * which costs nothing here: what waits for the run is [AgentCommandLine] watching the directory runs publish
 * themselves in, exactly as it would for a run somebody else started.
 */
private fun detached(started: List<String>): List<String> {
  val bundle = appBundlePathOrNull()
  if (bundle != null && File(OPEN_COMMAND).canExecute()) {
    // The launcher is what the bundle names, so what is left of the command line is the arguments for it.
    return listOf(OPEN_COMMAND, "-n", "-a", bundle, "--args") + started.drop(1)
  }
  if (File(POSIX_SHELL).canExecute()) {
    // `$0` for the script, so that the command line arrives as `$@` however it is spelled.
    return listOf(POSIX_SHELL, "-c", BACKGROUND_SCRIPT, POSIX_SHELL) + started
  }
  return started
}

/**
 * On stderr, always, which is the stream a caller keeping stdout for the JSON still sees.
 *
 * Not through `SharkLog`, and not only because stdout carries the answer: this is said before any logging has
 * been installed, by the path that ends before there is anything to install it for.
 */
internal fun saidToTheCaller(message: String) {
  System.err.println("[shark-dive] $message")
}

/**
 * One heap dump open: the thread every read of it queues on, and the two things an investigation writes into.
 *
 * Gathered because they are all per heap dump and all wanted together by everything that isn't drawing the
 * window — which is an agent, whether or not there is a window. See [DiveWindow.openHeapDump].
 */
internal class OpenHeapDump(
  val session: HeapDumpSession,
  val notes: HeapDumpNotes,
  val leakStatuses: HeapDumpLeakStatuses
)

/** This window's heap dump as an agent sees it: shown by going to a tab, the way a link does. */
private fun DiveWindow.agentHeapDump(open: OpenHeapDump): AgentHeapDump =
  OpenAgentHeapDump(open = open) { place ->
    SharkLog.d { "An agent asked window $windowId for $place" }
    // The same two steps following a link takes, which is what makes an agent showing something and a
    // person clicking a link land in the same place. See [DiveWindows.open].
    goToLinked(place)
    bringToFront()
    // And the link itself, which is the same one the right click menu copies: an agent's answer can then
    // point at this place rather than describe how to get to it.
    ShownPlace.at(DeepLink(open.session.heapDumpFile, place).toUri())
  }

/**
 * One open heap dump, as the agent surface sees it.
 *
 * Where a place goes is a parameter rather than a method to override, because it is the one thing about an open
 * dump that is the window's rather than the dump's. Everything else — the reads, the verdicts, the notes and
 * every refusal about them — is about the heap dump and the files beside it.
 */
internal class OpenAgentHeapDump(
  private val open: OpenHeapDump,
  /** Where a place goes, and the link to it. See [AgentHeapDump.show]. */
  private val showPlace: (Place) -> ShownPlace
) : AgentHeapDump {

  override val heapDumpPath: String get() = open.session.heapDumpFile.absolutePath

  override val sizes: HeapSizes get() = open.session.sizes

  override suspend fun <T> read(
    description: String,
    block: (HeapDive) -> T
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
internal val AGENT_RUNS_DIRECTORY = File(SHARK_DIVE_DIRECTORY, "agents")

/** What a run started by a command that found none is called, since nobody typed a title for it. */
private const val CLI_RUN_TITLE = "Command line"

/** How a launch is handed to LaunchServices on macOS, so that the run is none of this process's business. */
private const val OPEN_COMMAND = "/usr/bin/open"

private const val POSIX_SHELL = "/bin/sh"

/**
 * One command in the background with job control on, which is what puts it in a process group of its own.
 *
 * `$@` rather than the command spelled into it: a path with a space in it is one word to this and would be
 * several to anything that interpolated. See [detached].
 */
private const val BACKGROUND_SCRIPT = "set -m; \"\$@\" &"

/**
 * What this process ends with when the command line it was given doesn't read.
 *
 * A failure rather than a message and a window, because a call is one command: whatever ran it reads an exit
 * code and the message on stderr, and a window it did not ask for is not an answer. See
 * [AgentCommandLine.NOTHING_ANSWERED].
 */
internal const val UNREADABLE_COMMAND_LINE = 1
