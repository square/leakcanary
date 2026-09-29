package shark.dive.agent

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import shark.dive.AndroidDevice
import shark.dive.DeviceProcess

/**
 * The whole way in: `--agent <tool> name=value …`, one call typed at a window that is already open.
 *
 * **Argument translation and nothing else.** It turns a command line into the one line [AgentWire] describes,
 * prints what came back and exits with what happened, so a refusal met here was thrown by the tool's own
 * handler in [AgentTools]. Everything that decides anything is on the other end of the socket, which is what
 * keeps this from being a second surface with rules of its own. See `notes/agent-surface.md`.
 *
 * **It talks to the window that is already open**, over the loopback socket every run publishes. So being a
 * process per call costs the connect and nothing else: the heap dump was parsed and indexed once, in the
 * window somebody is watching, and this queues on that window's own reading thread. What it does cost is that
 * a process cannot be what gathers an investigation, which is what [SESSION_OPTION] is for.
 *
 * Why a command line rather than something an agent's client is configured with: it is what an agent reaches
 * for without being configured at all, it costs nothing until it is run, it pipes into `jq`, and a person
 * watching can type the same call the agent just made.
 */
object AgentCommandLine {

  /**
   * Makes one call, prints the answer, and returns the exit code the process should end with.
   *
   * [words] is what was typed after `--agent`: the name of a tool, then its arguments as `name=value`.
   */
  fun run(
    /** Where the runs of the app publish themselves. See [AgentServer]. */
    directory: File,
    words: List<String>,
    /** Which run, by process id, or null for the one that started most recently. */
    pid: String? = null,
    /**
     * Which session this call belongs to on the *Agent logs* screen. See [defaultSessionName].
     *
     * Refused rather than sanitised when it is no name: it becomes part of a file name, and a caller that
     * got it wrong wants to hear so on the call it got wrong.
     */
    sessionName: String = defaultSessionName(),
    /** How long to wait for a run to appear, for a call made while the app is still starting. */
    waitMillis: Long = DEFAULT_RUN_WAIT_MILLIS,
    /** How to open a window when no run of the app is open, and null to only look for one. */
    openAWindow: (() -> Unit)? = null
  ): Int {
    val toolName = words.firstOrNull()
    if (toolName == null || isCallArgument(toolName)) {
      say("$AGENT_OPTION needs the name of a tool. $HELP_OPTION prints the ones there are.")
      return NOTHING_ANSWERED
    }
    if (!AgentSessionFile.isSessionName(sessionName)) {
      say(
        "\"$sessionName\" is no session name: it becomes part of a file name, so it is letters and digits, " +
          "up to ${AgentSessionFile.MAX_SESSION_NAME_LENGTH} of them."
      )
      return NOTHING_ANSWERED
    }
    val arguments = try {
      argumentsOf(toolName, words.drop(1))
    } catch (unreadable: IllegalArgumentException) {
      say(unreadable.message.orEmpty())
      return NOTHING_ANSWERED
    }
    val run = waitForRun(directory, pid, waitMillis, openAWindow) ?: return NOTHING_ANSWERED
    val socket = try {
      Socket().apply {
        connect(InetSocketAddress(InetAddress.getLoopbackAddress(), run.port), CONNECT_TIMEOUT_MILLIS)
      }
    } catch (throwable: Throwable) {
      // Which is a run that was killed: the file is still there and nothing is on the port.
      say("Shark Dive run ${run.pid} does not answer on port ${run.port}: $throwable")
      run.file.delete()
      return NOTHING_ANSWERED
    }
    return socket.use { call(it, run, toolName, arguments, sessionName) }
  }

  /**
   * Every tool of this build as text: what each is for, and the arguments it takes.
   *
   * Generated from [AgentTools.all], which is the same list every call is answered out of, so a tool cannot be
   * in this text and missing from the surface or the other way round. [toolName] narrows it to one, because a
   * surface this size is worth reading a piece at a time.
   *
   * Answered with no run of the app and no heap dump anywhere, since it describes a build rather than
   * anything open: an agent reads this *before* there is something to read. [NoHeapDumpToDescribe] is what
   * makes that literal.
   */
  fun help(
    /** What to type to run this app, which is what the examples are written with. */
    command: String,
    toolName: String? = null
  ): String {
    val tools = described()
    val asked = toolName?.let { name -> tools.filter { it.name == name } }
    if (asked != null && asked.isEmpty()) {
      return "There is no tool called \"$toolName\". This build has " +
        tools.joinToString(", ") { it.name } + "."
    }
    return buildString {
      if (asked == null) {
        appendLine(preamble(command))
      }
      (asked ?: tools).forEach { appendLine(it.helpText()) }
    }
  }

  /**
   * Whether a word of a command line is one argument of a call, `name=value`.
   *
   * The one rule both ends of `--agent` read a command line by: whatever this says is an argument is sent to
   * the tool, and whatever it doesn't is the command line of the window. Two definitions of that would be a
   * heap dump path quietly sent as an argument, or an argument quietly opened as a heap dump.
   *
   * A name of letters and digits, which is what every argument on this surface is called, so that a path is
   * still a path — `/tmp/a=b.hprof` has a slash in its name and is therefore no argument. The one it gets
   * wrong is a relative path with an `=` in it and no directory, which is a file nobody has.
   */
  fun isCallArgument(word: String): Boolean {
    val name = word.substringBefore('=', missingDelimiterValue = "")
    return name.isNotEmpty() && name.first().isAsciiLetter() && name.all { it.isAsciiLetterOrDigit() }
  }

  /**
   * What a call joins when nothing said: the nearest process above this one that outlives a single call, which
   * is the shell an investigation is typed in, or the agent driving that shell.
   *
   * A connection held open for a whole investigation would gather its own calls, so a process per call has to
   * find something that plays that part, and the obvious candidate is the parent — a person's shell lives as
   * long as their conversation does. **It is the wrong answer for the agent this exists for.** Claude Code runs
   * every command it issues in a `zsh -c` of its own, so the parent's id is a session per call again: measured,
   * one investigation of nine calls wrote nine session files and drew nine rows of the *Agent logs* screen.
   *
   * So a shell that was handed one command to run is walked past — it ends with that command, which is the
   * definition of what cannot gather calls — and its parent is the session instead. For an agent that is the
   * agent's own process, whose life is the conversation; for a person it is still their own shell, which has no
   * command on its command line, and a shell per terminal tab is what they would expect. Falls back to this
   * process, which is a session per call: an ancestry that cannot be read is calls that cannot be gathered, and
   * a row each is better than landing in somebody else's session.
   */
  fun defaultSessionName(): String = sessionName(ProcessHandle.current())

  /**
   * [defaultSessionName] from anywhere in a process tree, which is how it is tested: a test can start a shell
   * and find what it ran, and cannot be the thing the shell ran.
   */
  internal fun sessionName(start: ProcessHandle): String {
    var session = start
    // Bounded because this walks a tree read one handle at a time, and a cycle in it would be a hang in
    // something every call goes through.
    repeat(MAX_COMMAND_SHELLS_WALKED_PAST + 1) {
      val parent = session.parent().orElse(null) ?: return "$SESSION_NAME_PREFIX${session.pid()}"
      session = parent
      val information = parent.info()
      val ranOneCommand = ranOneCommand(
        command = information.command().orElse(null),
        arguments = information.arguments().orElse(emptyArray()).asList()
      )
      if (!ranOneCommand) return "$SESSION_NAME_PREFIX${parent.pid()}"
    }
    return "$SESSION_NAME_PREFIX${session.pid()}"
  }

  /**
   * Whether a process is a shell that was given one command, `sh -c "…"`, and therefore ends when that
   * command does. See [defaultSessionName].
   *
   * Both halves are needed. Without the name, anything carrying a `-c` is walked past — `claude -c` resumes a
   * conversation — and the session would land on whatever launched *that*, gathering separate conversations
   * into one. Without the `-c`, an interactive shell is walked past too, and a person's session would be
   * their terminal, which merges the tabs and panes they opened precisely to keep work apart.
   *
   * A name this doesn't know is a shell whose calls go back to being a session each, which is what makes the
   * list safe to be incomplete. The option is read a letter at a time because a short option carries
   * others — `-lc` is a login shell running one command — and `--` begins something else entirely.
   */
  internal fun ranOneCommand(
    /** As [ProcessHandle.Info.command] has it, a path, and null for a process this one may not read. */
    command: String?,
    arguments: List<String>
  ): Boolean {
    val name = command?.substringAfterLast('/') ?: return false
    if (name !in COMMAND_SHELLS) {
      return false
    }
    return arguments.any { argument ->
      argument.length > 1 && argument.startsWith("-") && !argument.startsWith("--") && 'c' in argument
    }
  }

  private fun call(
    socket: Socket,
    run: AgentServer.PublishedRun,
    toolName: String,
    arguments: JsonObject,
    sessionName: String
  ): Int {
    val toApp = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
    val fromApp = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    // The token and then which session this call is one of, on one line, because a process per call would
    // otherwise be a session per call and a session is what somebody reads afterwards. See [AgentServer].
    toApp.println("${run.token} $sessionName")
    if (fromApp.readLine() != AgentServer.ACCEPTED) {
      say("Shark Dive run ${run.pid} refused the token in ${run.file}, so it is not the run that wrote it")
      return NOTHING_ANSWERED
    }
    toApp.println(AgentWire.encode(AgentWire.call(toolName, arguments)))
    val line = fromApp.readLine()
    if (line == null) {
      say("Shark Dive stopped answering during $toolName, so the window it was in has gone")
      return NOTHING_ANSWERED
    }
    val response = AgentWire.decodeOrNull(line)
    if (response == null) {
      say("Shark Dive answered $toolName with something that is no JSON object: $line")
      return NOTHING_ANSWERED
    }
    return printed(toolName, response)
  }

  /**
   * Puts the answer on stdout and anything else on stderr, and says which happened in the exit code.
   *
   * Two streams and three codes because **a refusal is not a failure of the command**: it is what the surface
   * answered, and its message is the next thing to do. So a shell keeping stdout for the JSON still shows the
   * sentence, and a script can tell "it said no" from "nothing answered", which is the difference between
   * going and reading something and going and looking at the app's log.
   */
  private fun printed(
    toolName: String,
    response: JsonObject
  ): Int {
    AgentWire.refusalOf(response)?.let { refusal ->
      say(refusal)
      return REFUSED
    }
    AgentWire.failureOf(response)?.let { failure ->
      say("Shark Dive could not answer $toolName: $failure")
      return NOTHING_ANSWERED
    }
    val answer = AgentWire.answerOf(response)
    if (answer == null) {
      say("Shark Dive answered $toolName with no answer, no refusal and no failure: $response")
      return NOTHING_ANSWERED
    }
    val out = PrintWriter(OutputStreamWriter(System.out, Charsets.UTF_8), true)
    out.println(AgentWire.pretty(answer))
    out.flush()
    return ANSWERED
  }

  /**
   * The arguments as JSON: every value as it was typed, except the ones the schema says are lists.
   *
   * Which works because [AgentArguments] reads a number and a boolean out of text — the tools were written
   * for a model, and a model sends `limit=30` as a string as often as not. So a command line spells
   * everything the way a person types it, and the one shape with no spelling of its own is a list: those are
   * comma separated, a shell having no brackets.
   *
   * The schema is this build's own, so an argument of a tool this build has never heard of goes as text and
   * is refused at the other end, by a message that lists the tools there are.
   */
  private fun argumentsOf(
    toolName: String,
    words: List<String>
  ): JsonObject {
    val lists = described().firstOrNull { it.name == toolName }?.listArguments().orEmpty()
    return buildJsonObject {
      words.forEach { word ->
        require(isCallArgument(word)) {
          "\"$word\" is no argument of $toolName. An argument is `name=value`, and a value with spaces in " +
            "it is quoted: reason=\"why I am asking\"."
        }
        val name = word.substringBefore('=')
        val value = word.substringAfter('=')
        if (name in lists) {
          putJsonArray(name) { value.split(LIST_SEPARATOR).forEach { add(it.trim()) } }
        } else {
          put(name, value)
        }
      }
    }
  }

  /** The tools of this build, described. Built per call, so nothing here is shared between threads. */
  private fun described(): List<AgentTool> = AgentTools(NoHeapDumpToDescribe) { nothingToDescribeWith() }.all

  private fun preamble(command: String): String = """
    |Shark Dive's heap dump tools, from a shell. One call per command, answered by the window that has
    |the heap dump open — or by a window this opens when none is.
    |
    |  $command $AGENT_OPTION <tool> name=value …
    |  $command $AGENT_OPTION open_heap_dump path=/path/to/dump.hprof reason="Starting on the dump I was given"
    |  $command $AGENT_OPTION describe_object object=0x7205 reason="Reading the holder's fields"
    |
    |Start with open_heap_dump on the heap dump you were given: it opens that file, or hands back the window
    |that already has it, and its answer carries the name every other tool names that dump by and whatever
    |verdicts somebody has already recorded about it. If you were given no heap dump, open_heap_dumps lists
    |the ones open. Then, for anything about a leak, list_leaks: its answer carries the method to follow —
    |what a leak is, and the order that finds the faulty reference — and no other call on this surface does.
    |
    |Every tool takes `reason`, which is why you are making the call. It is logged beside the reads it causes
    |and read afterwards on the *Agent logs* screen of the window, so write the sentence you would say to the
    |person watching. Addresses are `0x…`, exactly as this surface writes them, and never decimal.
    |
    |${options()}
    |
    |Exit code $ANSWERED when the answer is on stdout, $REFUSED when the call was refused and the refusal is
    |on stderr, $NOTHING_ANSWERED when there was nothing to answer it.
    |
    |TOOLS
  """.trimMargin()

  private fun options(): String = listOf(
    "$PID_OPTION<pid>" to "Which run of the app to call, when more than one is open.",
    "$SESSION_OPTION<name>" to
      "Which session these calls are one of, letters and digits. One per shell by default, so that an " +
      "investigation is one row of the *Agent logs* screen rather than a row per call.",
    "$HELP_OPTION <tool>" to "Just that tool."
  ).joinToString("\n") { (option, what) -> "  ${option.padEnd(OPTION_WIDTH)}$what" }

  /** Answered: the tool's own JSON is on stdout. */
  const val ANSWERED = 0

  /**
   * Nothing answered: no run to talk to, one that has gone, a command line this could not read, or a call the
   * app could not answer at all.
   *
   * A command that did nothing has to fail, or whatever ran it carries on as though it had an answer. The
   * last of the four is why this is not the same code as a refusal: a read that threw is the app falling over
   * and the next thing to do about it is in `~/.shark-dive/logs`, not in the message. See [REFUSED].
   */
  const val NOTHING_ANSWERED = 1

  /** Refused: the tool said no, and stderr says what to do about it. */
  const val REFUSED = 2

  /** What a command line says to make one call. See `shark.dive.app.DiveArguments`. */
  const val AGENT_OPTION = "--agent"

  /** And to read what the calls are, which needs no window and no heap dump. See [help]. */
  const val HELP_OPTION = "--agent-help"

  /**
   * What a command line says to put its calls in one session, rather than one session per call.
   *
   * For an agent whose calls do not all come out of one shell — a harness that starts one per command — and
   * for a person following an investigation of their own. See [defaultSessionName].
   */
  const val SESSION_OPTION = "--agent-session="

  /** Which run to call, for a machine with several windows open. See `shark.dive.app.DiveArguments`. */
  const val PID_OPTION = "--agent-run="

  /** How the session of a call from here is named, so that a file says what made it. */
  private const val SESSION_NAME_PREFIX = "cli"

  /**
   * The names [ranOneCommand] will walk past, when one of them was given a command. Every shell an agent or a
   * terminal on a developer machine is likely to start a command with; a name missing from it costs that
   * shell's calls being a session each, which is what they were for every shell before this list existed.
   */
  private val COMMAND_SHELLS = setOf(
    "sh", "bash", "zsh", "dash", "ksh", "mksh", "ash", "fish", "csh", "tcsh", "nu", "pwsh"
  )

  /** See [defaultSessionName]. A shell inside a shell inside a shell, and then some. */
  private const val MAX_COMMAND_SHELLS_WALKED_PAST = 4

  /** Wide enough for the longest option above, since the descriptions read as a column or as nothing. */
  private const val OPTION_WIDTH = 24

  private const val LIST_SEPARATOR = ','

  private const val CONNECT_TIMEOUT_MILLIS = 1_000

  /** How long a call waits for a run that ought to be there already, before saying there is none. */
  private const val DEFAULT_RUN_WAIT_MILLIS = 10_000L

  /**
   * How long a window opened for an agent is given to publish itself.
   *
   * A lot longer than a wait for one that should already be there, because it covers a cold JVM, Compose
   * starting and jlink's runtime being paged in — and because the alternative to waiting is telling an agent
   * there is no window while one is in the middle of appearing.
   */
  private const val OPENING_WAIT_MILLIS = 60_000L

  private const val POLL_MILLIS = 250L

  /**
   * The run of the app to talk to, opening one if there is none, and null for a machine where there was never
   * going to be one.
   */
  private fun waitForRun(
    directory: File,
    pid: String?,
    waitMillis: Long,
    /**
     * How to open a window to investigate in when no run of the app is open, and null to wait for one.
     *
     * Because the alternative is an agent whose only answer is "ask somebody to launch Shark Dive", and a
     * window opened here is a window the person at the machine can then watch — which is the whole reason
     * this surface is a window rather than a library. Not called when a run was asked for by [pid]: that
     * names a window, and opening a different one would be answering about the wrong heap dump.
     */
    openAWindow: (() -> Unit)?
  ): AgentServer.PublishedRun? {
    var waited = 0L
    var deadline = waitMillis
    var opened = false
    // Naming a run names a window and therefore a heap dump, so opening a different one would be answering
    // about the wrong dump: for that command line there is nothing to open, only something to wait for.
    val opensAWindow = openAWindow != null && pid == null
    while (true) {
      val runs = AgentServer.publishedRuns(directory)
      val run = if (pid == null) runs.firstOrNull() else runs.firstOrNull { it.pid == pid }
      if (run == null && !opened && opensAWindow) {
        say("No Shark Dive is running, so one is being opened to investigate in.")
        requireNotNull(openAWindow).invoke()
        opened = true
        // From here rather than from the start, because what is being waited for changed: a JVM starting,
        // Compose coming up and a window appearing, rather than a file that may already be there.
        deadline = waited + OPENING_WAIT_MILLIS
      }
      if (run != null) {
        if (pid == null && runs.size > 1) {
          // Which run a call ends up in is worth saying rather than leaving to be worked out from what heap
          // dump it finds open: several Shark Dive windows at once is the normal way this app is used.
          say(
            "${runs.size} Shark Dive runs are open; talking to ${run.pid}, the one that started most " +
              "recently. Pass $PID_OPTION<pid> to pick another: " + runs.joinToString(", ") { it.pid }
          )
        }
        return run
      }
      if (waited >= deadline) {
        say(nothingToTalkTo(directory, pid, opened))
        return null
      }
      Thread.sleep(POLL_MILLIS)
      waited += POLL_MILLIS
    }
  }

  private fun nothingToTalkTo(
    directory: File,
    pid: String?,
    opened: Boolean
  ): String = if (pid == null && opened) {
    "A Shark Dive run was started and has not published itself in ${OPENING_WAIT_MILLIS / 1000} seconds, " +
      "so something went wrong opening it. Its log is in the newest file under ~/.shark-dive/logs."
  } else if (pid == null) {
    "No Shark Dive is running, so there is no heap dump to investigate. Open one — every run of the app " +
      "publishes itself in $directory — and start this again."
  } else {
    "No Shark Dive run is $pid. Open runs: " +
      AgentServer.publishedRuns(directory).joinToString(", ") { it.pid }.ifEmpty { "none" }
  }
}

/**
 * On stderr, always: where a shell shows what a command is doing, and where whatever ran it collects that.
 *
 * Not through `SharkLog`: this process installs none of the app's logging, since that writes to stdout and
 * stdout is where the answer goes.
 */
internal fun say(message: String) {
  System.err.println("[shark-dive] $message")
}

internal fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

internal fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || this in '0'..'9'

/** One argument of a tool, as the help prints it. */
private class HelpArgument(
  val name: String,
  val schema: JsonObject,
  val isRequired: Boolean
)

private fun AgentTool.helpText(): String = buildString {
  appendLine(name)
  appendLine("  $description")
  // `reason` is on every tool and is said once, in the preamble: a copy of the same paragraph per tool is a
  // sixth of the help, and the one argument nobody needs reminding of per tool is the mandatory one.
  arguments().filter { it.name != REASON_ARGUMENT }.forEach { appendLine("  ${it.helpLine()}") }
}

/** Which of this tool's arguments are lists, which is the one thing a command line has to spell specially. */
private fun AgentTool.listArguments(): Set<String> =
  arguments().filter { it.schema.type() == ARRAY_TYPE }.map { it.name }.toSet()

private fun AgentTool.arguments(): List<HelpArgument> {
  val properties = schema[PROPERTIES_KEY] as? JsonObject ?: return emptyList()
  val required = (schema[REQUIRED_KEY] as? JsonArray).orEmpty()
    .mapNotNull { (it as? JsonPrimitive)?.content }
  return properties.mapNotNull { (name, element) ->
    (element as? JsonObject)?.let { HelpArgument(name, it, name in required) }
  }
}

private fun HelpArgument.helpLine(): String {
  val kind = listOfNotNull(schema.kind(), "optional".takeIf { !isRequired }).joinToString(", ")
  return "$name ($kind) — ${schema.description()}"
}

/**
 * How a value of this argument is written on a command line, which is not its JSON type.
 *
 * Every value is typed as text and read as whatever the tool asks for — see `AgentCommandLine.argumentsOf` —
 * so what a reader needs here is what to type, rather than that JSON has numbers in it.
 */
private fun JsonObject.kind(): String {
  val values = (this[ENUM_KEY] as? JsonArray)?.contents()
  val itemValues = ((this[ITEMS_KEY] as? JsonObject)?.get(ENUM_KEY) as? JsonArray)?.contents()
  return when {
    values != null -> values.joinToString(" or ")
    type() == ARRAY_TYPE ->
      "comma separated" + itemValues?.let { ", from ${it.joinToString(", ")}" }.orEmpty()
    type() == INTEGER_TYPE -> "a whole number"
    type() == BOOLEAN_TYPE -> "true or false"
    else -> "text"
  }
}

private fun JsonArray.contents(): List<String> = mapNotNull { (it as? JsonPrimitive)?.content }

private fun JsonObject.type(): String? = (this["type"] as? JsonPrimitive)?.content

private fun JsonObject.description(): String = (this["description"] as? JsonPrimitive)?.content.orEmpty()

/**
 * The heap dumps of a run that is answering nobody, which is every method throwing.
 *
 * [AgentTools] holds these so that its handlers can read a heap dump, and **describing a tool never calls
 * its handler** — so a registry built on this can be printed and cannot be used. Throwing rather than
 * answering with nothing, because "no heap dump is open" is an answer an agent would act on and this is not
 * that: it is a description of a build, with nowhere for a call to go.
 */
private object NoHeapDumpToDescribe : AgentHeapDumps {

  override fun openHeapDumps(): List<AgentHeapDump> = nothing()

  override fun openingHeapDumpPaths(): List<String> = nothing()

  override suspend fun open(file: File): AgentHeapDump = nothing()

  override suspend fun devices(): List<AndroidDevice> = nothing()

  override suspend fun processesOf(serialNumber: String): List<DeviceProcess> = nothing()

  override suspend fun dumpHeap(
    serialNumber: String,
    processName: String
  ): AgentHeapDump = nothing()

  private fun nothing(): Nothing = nothingToDescribeWith()
}

/** The same for the sessions the log tool reads, which a build being described has no directory for. */
private fun nothingToDescribeWith(): Nothing = throw IllegalStateException(
  "These tools are only being described, so there is no heap dump here and nothing to call: a call goes to " +
    "the run of the app that has one open. See AgentCommandLine."
)

/** Said once in the preamble rather than under every tool. See [AgentTool]. */
private const val REASON_ARGUMENT = "reason"

private const val PROPERTIES_KEY = "properties"
private const val REQUIRED_KEY = "required"
private const val ENUM_KEY = "enum"
private const val ITEMS_KEY = "items"
private const val ARRAY_TYPE = "array"
private const val INTEGER_TYPE = "integer"
private const val BOOLEAN_TYPE = "boolean"
