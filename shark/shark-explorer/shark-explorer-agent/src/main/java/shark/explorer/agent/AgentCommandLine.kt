package shark.explorer.agent

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import shark.explorer.AndroidDevice
import shark.explorer.DeviceProcess

/**
 * One tool call typed rather than spoken over a session: `--agent <tool> name=value …`.
 *
 * The second adapter over the registry in [AgentTools], and deliberately not a second surface: all it does is
 * turn a command line into a `tools/call` and print what came back, so a refusal met here is the same refusal
 * thrown by the same handler. See `notes/agent-surface.md`.
 *
 * **It talks to the window that is already open**, over the loopback socket every run publishes — the same
 * one [AgentStdioBridge] pipes to. So a call from here is as cheap as one made over MCP: the heap dump was
 * parsed and indexed once, in the window somebody is watching, and this queues on that window's own reading
 * thread. Being a process per call costs exactly one thing, which is that a connection can no longer be what
 * gathers an investigation. See [SESSION_OPTION].
 *
 * Why have it at all, given the pipe: it is what an agent reaches for without being configured, it costs
 * nothing until it is run, it pipes into `grep`, and it is the only one of the two that an agent whose client
 * speaks no MCP can use.
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
      say("Shark Explorer run ${run.pid} does not answer on port ${run.port}: $throwable")
      run.file.delete()
      return NOTHING_ANSWERED
    }
    return socket.use { call(it, run, toolName, arguments, sessionName) }
  }

  /**
   * Every tool of this build as text: what each is for, and the arguments it takes.
   *
   * Generated from the same registry `tools/list` answers from, so a tool cannot be on one and missing from
   * the other — which is the rule this adapter is under. [toolName] narrows it to one, because a surface of
   * sixteen tools is worth reading a piece at a time.
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
   * What a call joins when nothing said: the process that ran it, which for an agent is its shell.
   *
   * A shell lives as long as the conversation does and an agent's calls are commands in it, so its process
   * id gathers an investigation the way one held-open connection gathers an MCP one. Falls back to this
   * process, which is a session per call — a shell that cannot be named is one whose calls cannot be
   * gathered, and a row each is better than landing in somebody else's session.
   */
  fun defaultSessionName(): String {
    val current = ProcessHandle.current()
    val pid = current.parent().map { it.pid() }.orElse(current.pid())
    return "$SESSION_NAME_PREFIX$pid"
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
    // The token, and then which session this call is one of: one line, because the alternative is a
    // handshake that has to be answered before the protocol can start. See [AgentServer].
    toApp.println("${run.token} $sessionName")
    if (fromApp.readLine() != AgentServer.ACCEPTED) {
      say("Shark Explorer run ${run.pid} refused the token in ${run.file}, so it is not the run that wrote it")
      return NOTHING_ANSWERED
    }
    // Says who is calling, which is what puts a client name in a session's first line. Nothing else here
    // needs it: the tools are the same whether or not anybody introduced themselves.
    if (ask(toApp, fromApp, INITIALIZE_ID, "initialize", initializeParameters()) == null) {
      return NOTHING_ANSWERED
    }
    val result = ask(
      toApp,
      fromApp,
      CALL_ID,
      "tools/call",
      buildJsonObject {
        put("name", toolName)
        put("arguments", arguments)
      }
    ) ?: return NOTHING_ANSWERED
    return printed(result)
  }

  /** One JSON-RPC call and its answer, or null having said on stderr why there wasn't one. */
  private fun ask(
    toApp: PrintWriter,
    fromApp: BufferedReader,
    id: Int,
    method: String,
    parameters: JsonObject
  ): JsonObject? {
    val message = buildJsonObject {
      put("jsonrpc", JSONRPC_VERSION)
      put("id", id)
      put("method", method)
      put("params", parameters)
    }
    toApp.println(JSON.encodeToString(JsonElement.serializer(), message))
    val line = fromApp.readLine()
    if (line == null) {
      say("Shark Explorer stopped answering during $method, so the window it was in has gone")
      return null
    }
    val answer = try {
      JSON.parseToJsonElement(line) as? JsonObject
    } catch (notJson: Exception) {
      say("Shark Explorer answered $method with something that is no JSON-RPC message: $notJson")
      return null
    }
    val error = answer?.get("error") as? JsonObject
    if (error != null) {
      // Not a refusal: a refusal is an answer a tool gave. This is the app failing to answer at all.
      say("Shark Explorer could not answer $method: ${(error["message"] as? JsonPrimitive)?.content}")
      return null
    }
    return answer?.get("result") as? JsonObject
  }

  /**
   * Puts the answer on stdout, or the refusal on stderr, and says which happened in the exit code.
   *
   * Two streams and two codes because **a refusal is not a failure of the command**: it is what the surface
   * answered, and its message is the next thing to do. So a shell keeping stdout for the JSON still shows
   * the sentence, and a script can tell "it said no" from "there was nothing to ask".
   */
  private fun printed(result: JsonObject): Int {
    val text = ((result["content"] as? JsonArray)?.firstOrNull() as? JsonObject)
      ?.let { (it["text"] as? JsonPrimitive)?.content }
    if ((result["isError"] as? JsonPrimitive)?.content == "true") {
      say(text ?: "The call was refused, and the refusal said nothing.")
      return REFUSED
    }
    val structured = result["structuredContent"] as? JsonObject
    val out = PrintWriter(OutputStreamWriter(System.out, Charsets.UTF_8), true)
    out.println(
      structured?.let { PRETTY_JSON.encodeToString(JsonElement.serializer(), it) } ?: text.orEmpty()
    )
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

  private fun initializeParameters(): JsonObject = buildJsonObject {
    put("protocolVersion", PROTOCOL_VERSION)
    putJsonObject("clientInfo") {
      put("name", CLIENT_NAME)
    }
  }

  private fun preamble(command: String): String = """
    |Shark Explorer's heap dump tools, from a shell. One call per command, answered by the window that has
    |the heap dump open — or by a window this opens when none is.
    |
    |  $command $AGENT_OPTION <tool> name=value …
    |  $command $AGENT_OPTION open_heap_dumps reason="Finding out which heap dump is open"
    |  $command $AGENT_OPTION describe_object object=0x7205 reason="Reading the holder's fields"
    |
    |Start with open_heap_dumps: its answer carries the method to follow, the file names every other tool
    |names a heap dump by, and whatever verdicts somebody has already recorded about that dump.
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
   * Nothing answered: no run to talk to, one that has gone, or a command line this could not read.
   *
   * The same code [AgentStdioBridge] ends with for the same case and for the same reason: a command that did
   * nothing has to fail, or whatever ran it carries on as though it had an answer.
   */
  const val NOTHING_ANSWERED = 1

  /** Refused: the tool said no, and stderr says what to do about it. */
  const val REFUSED = 2

  /** What a command line says to make one call. See `shark.explorer.app.ExplorerArguments`. */
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

  /** Which run to call, spelled the way the pipe spells it. See [AgentStdioBridge.PID_OPTION]. */
  const val PID_OPTION = AgentStdioBridge.PID_OPTION

  /** How the session of a call from here is named, so that a file says what made it. */
  private const val SESSION_NAME_PREFIX = "cli"

  /** What the window's *Agent logs* screen says connected, for a session started from a shell. */
  private const val CLIENT_NAME = "shark-explorer-cli"

  /** Wide enough for the longest option above, since the descriptions read as a column or as nothing. */
  private const val OPTION_WIDTH = 24

  private const val LIST_SEPARATOR = ','

  private const val JSONRPC_VERSION = "2.0"

  /** The revision this was written against, which the app echoes back. See [McpSession]. */
  private const val PROTOCOL_VERSION = "2025-06-18"

  private const val INITIALIZE_ID = 1
  private const val CALL_ID = 2

  private const val CONNECT_TIMEOUT_MILLIS = 1_000

  private val JSON = Json { ignoreUnknownKeys = true }

  /**
   * Indented, because the reader is either a person or a model reading a chain of twenty steps.
   *
   * The same shape the text of an MCP answer is in, so that what an agent reads is the same either way.
   */
  private val PRETTY_JSON = Json(JSON) { prettyPrint = true }
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
  // `reason` is on every tool and is said once, in the preamble: sixteen copies of the same paragraph is a
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
