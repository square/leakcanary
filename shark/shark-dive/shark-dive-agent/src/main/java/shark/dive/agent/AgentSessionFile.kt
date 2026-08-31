package shark.dive.agent

import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import shark.SharkLog
import shark.dive.DeepLink
import shark.dive.Place

/**
 * What one agent did, written down as it does it: the client that connected, then a line per call.
 *
 * **One artefact, two readers.** The window draws this as the *Agent logs* screen, so that the person at the
 * machine can follow an investigation they didn't watch — every call as a verb, the address it was about, and
 * the reason the agent gave for making it. And the eval reads the same file to score a run, because the
 * numbers worth having about this surface are counts of what happened: whether it concluded, on which
 * reference, in how many calls, how many of them refused. See `notes/agent-eval.md`.
 *
 * Which is why it is machine readable and appended to rather than the run log reworded: the run log is prose
 * about everything the app did, and this is the one agent's calls with nothing else in the file.
 *
 * **And a call carries the exchange itself, not only this app's reading of it.** Every derived field here —
 * the verb, the subject, the place a row leads to — is what Shark Dive made of a call, and a reading is the
 * one thing that is no use when the question is why an investigation went wrong. So [AgentSessionCall.input]
 * and [AgentSessionCall.output] are what the agent sent and what it read back, verbatim, which is what makes
 * a session something to debug and follow along with rather than only a summary to skim.
 *
 * **Every message, not only the ones that reached a tool.** A line goes down for the handshake, for
 * `tools/list`, for a ping, for a notification nothing was sent back for, for a method this app has never
 * heard of, and for a line that was not JSON at all. Which is the whole point of keeping traffic: the
 * messages worth reading are exactly the ones that went wrong, and a log that keeps what worked and drops
 * what didn't answers every question except the one it was opened for. [AgentSessionCall.tool] is null for
 * all of those and [AgentSessionCall.method] says what arrived instead; [AgentSession.toolCalls] is the
 * subset that reached a tool, for the readers that are counting an investigation rather than reading it.
 *
 * JSON, one object per line, flushed per line, because the session worth reading is often the one that ended
 * by the agent giving up or the app being killed. A header line naming the session, then a line per call.
 * Beside the notes and the verdicts under `~/.shark-dive`, since it is the same kind of thing: what
 * somebody concluded about a heap dump, kept where the next reader will find it.
 */
class AgentSessionFile private constructor(
  /** The file itself, shown in the window so that a session can be read without this app. */
  val file: File,
  /** What this session is called, in the window and in the file. See [newSessionId]. */
  val sessionId: String,
  private val startedAt: Instant,
  private val serverVersion: String,
  /** Whether the file already says whose session it is, which a call joining one finds true. */
  private var isHeaderWritten: Boolean
) {

  /**
   * Says who connected, which is the handshake and therefore the first thing to land in the file.
   *
   * Written here rather than at construction because the client only says its name in `initialize`, and a
   * session file that exists before anyone has spoken would be a session nobody had.
   */
  fun opened(
    client: String?,
    protocolVersion: String?
  ) {
    writeHeader(client, protocolVersion)
  }

  /**
   * Adds one call, whether it was answered or refused.
   *
   * A refusal is a line like any other and not an error to leave out: what a session is read for is what the
   * agent tried, and the refusals are where it was made to go back and look again.
   */
  fun called(call: AgentSessionCall) {
    // A client that calls a tool before the handshake is one this has not met, and its calls still belong in
    // a file that says which session they were.
    writeHeader(client = null, protocolVersion = null)
    append(call.asJson())
  }

  private fun writeHeader(
    client: String?,
    protocolVersion: String?
  ) {
    if (isHeaderWritten) {
      return
    }
    isHeaderWritten = true
    append(
      buildJsonObject {
        put(SESSION_KEY, sessionId)
        put(STARTED_AT_KEY, startedAt.toString())
        client?.let { put(CLIENT_KEY, it) }
        protocolVersion?.let { put(PROTOCOL_KEY, it) }
        put(SERVER_KEY, serverVersion)
      }
    )
  }

  /**
   * One line on disk, or a line in the run log saying why not.
   *
   * Never thrown: an agent's session must not end because the disk it was being written to filled up, and
   * this is the app's side of the connection, so the run log is where anyone would look.
   */
  private fun append(line: JsonObject) {
    try {
      file.appendText(JSON.encodeToString(JsonElement.serializer(), line) + "\n")
    } catch (throwable: Throwable) {
      SharkLog.d(throwable) { "Could not write to the agent session log $file" }
    }
  }

  companion object {

    /**
     * Starts a session's file in [directory], and deletes all but the newest [keepSessionCount] of them.
     *
     * The same housekeeping `shark.dive.SessionLog` does for the run logs, for the same reason: a
     * directory that grows for ever is one nobody opens. A session is a few kilobytes, so this keeps more of
     * them than there are runs — the question "what did that agent do last week" is one people ask.
     */
    fun starting(
      directory: File,
      serverVersion: String,
      startedAt: Instant = Instant.now(),
      sessionId: String = newSessionId(),
      keepSessionCount: Int = KEEP_SESSION_COUNT
    ): AgentSessionFile {
      directory.mkdirs()
      val name = FILE_NAME_PREFIX + FILE_NAME_TIME.format(startedAt) + "-$sessionId$FILE_NAME_SUFFIX"
      deleteOlderSessions(directory, keepSessionCount - 1)
      return AgentSessionFile(
        file = File(directory, name),
        sessionId = sessionId,
        startedAt = startedAt,
        serverVersion = serverVersion,
        isHeaderWritten = false
      )
    }

    /**
     * The session called [sessionId] to add to, which is the newest file of that name or a new one.
     *
     * What a command line needs and a connection doesn't. An MCP client holds one connection open for a
     * whole investigation, so a connection is a session; `--agent` is a process per call, so without this a
     * morning's work would be thirty files and the *Agent logs* screen would list thirty agents where there
     * was one. See [AgentCommandLine].
     *
     * The header is not written again, since a file with two of them is two sessions to whoever reads it —
     * so the client, the protocol and the build in it are the ones from the call that started the session.
     */
    fun continuing(
      directory: File,
      serverVersion: String,
      sessionId: String,
      startedAt: Instant = Instant.now(),
      keepSessionCount: Int = KEEP_SESSION_COUNT
    ): AgentSessionFile {
      val existing = directory.listFiles { file: File -> file.name.isSessionFile() }.orEmpty()
        .filter { it.name.sessionIdOfName() == sessionId }
        // Named after when it started, so the newest of them is a sort by name — the same one the window
        // lists first. Several only happen for a session named again after the older one aged out.
        .maxByOrNull { it.name }
        ?: return starting(directory, serverVersion, startedAt, sessionId, keepSessionCount)
      return AgentSessionFile(
        file = existing,
        sessionId = sessionId,
        startedAt = startedAt,
        serverVersion = serverVersion,
        isHeaderWritten = true
      )
    }

    /**
     * Whether [name] can name a session, which is strict because it becomes part of a file name.
     *
     * Letters and digits, and no '-' in particular: a file is `agent-<when>-<id>.jsonl` and the id is read
     * back out of it by splitting on the last one. Checked on both sides of the socket — the command line
     * refuses a name it cannot use, and this end never names a file after something it was told.
     */
    fun isSessionName(name: String): Boolean = name.length in 1..MAX_SESSION_NAME_LENGTH &&
      name.all { it.isAsciiLetterOrDigit() }

    /**
     * Every session written in [directory], newest first, with the calls of each in the order they were
     * made.
     *
     * A line that can't be read is skipped and says so in the run log, like the file of verdicts beside it:
     * this is evidence, and one truncated line — a session whose app was killed mid-write — must not be a
     * session that reads as empty.
     */
    fun sessionsIn(directory: File): List<AgentSession> {
      val files = directory.listFiles { file: File -> file.name.isSessionFile() }.orEmpty()
      // Named after the time the session started, so newest first is a sort by name.
      return files.sortedByDescending { it.name }.map { file -> sessionIn(file) }
    }

    private fun sessionIn(file: File): AgentSession {
      val lines = try {
        file.readLines()
      } catch (throwable: Throwable) {
        SharkLog.d(throwable) { "Could not read the agent session log $file" }
        emptyList()
      }
      var header: JsonObject? = null
      val calls = mutableListOf<AgentSessionCall>()
      lines.forEachIndexed { index, line ->
        val read = line.asJsonOrNull(file, index + 1)
        when {
          read == null -> Unit
          // The header first, since it is the one line that is about the session rather than one message of
          // it — and every message line is stamped with when it arrived, whether or not it named a tool.
          read[SESSION_KEY] != null -> header = header ?: read
          read[AT_KEY] != null -> read.asCallOrNull(file, index + 1)?.let { calls += it }
          else -> SharkLog.d { "Skipping line ${index + 1} of $file: it is neither a session nor a message" }
        }
      }
      return AgentSession(
        // From the file name for a session whose header never landed, so that a row still has a name to be
        // opened by: the id is in the name, which is what makes that recoverable.
        sessionId = header?.text(SESSION_KEY) ?: file.name.sessionIdOfName(),
        startedAt = header?.instant(STARTED_AT_KEY) ?: calls.firstOrNull()?.at,
        client = header?.text(CLIENT_KEY),
        serverVersion = header?.text(SERVER_KEY),
        file = file,
        calls = calls
      )
    }

    /**
     * Eight hexadecimal characters, from [SecureRandom] like the token beside it.
     *
     * Random rather than counted, for the reason a window's id is: ids handed out in order repeat
     * across runs of the app, and a session log named the same as one from yesterday is two investigations
     * that read as one.
     */
    fun newSessionId(): String {
      val bytes = ByteArray(SESSION_ID_BYTES)
      SecureRandom().nextBytes(bytes)
      return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun deleteOlderSessions(
      directory: File,
      keepCount: Int
    ) {
      val files = directory.listFiles { file: File -> file.name.isSessionFile() }.orEmpty()
      files.sortedBy { it.name }.dropLast(keepCount).forEach { older ->
        if (!older.delete()) {
          SharkLog.d { "Could not delete the log of an older agent session, $older" }
        }
      }
    }

    private fun AgentSessionCall.asJson(): JsonObject = buildJsonObject {
      put(AT_KEY, at.toString())
      over?.let { put(OVER_KEY, it.recorded) }
      method?.let { put(METHOD_KEY, it) }
      tool?.let { put(TOOL_KEY, it) }
      reason?.let { put(REASON_KEY, it) }
      windowId?.let { put(WINDOW_KEY, it) }
      heapDumpPath?.let { put(HEAP_DUMP_KEY, it) }
      // As the link the window hands out for that place, which is the whole of what a row has to be
      // clickable: the place to go to, and a line the agent's human can paste anywhere. See [DeepLink].
      link()?.let { put(LINK_KEY, it) }
      refusal?.let { put(REFUSAL_KEY, it) }
      error?.let { put(ERROR_KEY, it) }
      outcome?.let { put(OUTCOME_KEY, it) }
      if (openHeapDumps.isNotEmpty()) {
        putJsonArray(OPEN_HEAP_DUMPS_KEY) { openHeapDumps.forEach { add(it) } }
      }
      put(MILLIS_KEY, millis)
      if (arguments.isNotEmpty()) {
        putJsonObject(ARGUMENTS_KEY) {
          arguments.forEach { (name, value) -> put(name, value) }
        }
      }
      // Last, because they are the long ones: a line stays readable to whoever is looking at it with `head
      // -c` or a text editor's first screenful, and what is up there is what says which call this is.
      input?.let { put(INPUT_KEY, it) }
      output?.let { put(OUTPUT_KEY, it) }
    }

    private fun JsonObject.asCallOrNull(
      file: File,
      lineNumber: Int
    ): AgentSessionCall? {
      val at = instant(AT_KEY)
      if (at == null) {
        SharkLog.d { "Skipping line $lineNumber of $file: it says no time it arrived" }
        return null
      }
      val tool = text(TOOL_KEY)
      val link = text(LINK_KEY)
      val arguments = this[ARGUMENTS_KEY]?.asStringMap().orEmpty()
      return AgentSessionCall(
        at = at,
        over = text(OVER_KEY)?.let { AgentTransport.ofRecorded(it, file, lineNumber) },
        // From the tool for a session written before the method was kept: every line there was a tool call,
        // which is the one method a tool name can have arrived under.
        method = text(METHOD_KEY) ?: tool?.let { TOOLS_CALL_METHOD },
        tool = tool,
        reason = text(REASON_KEY),
        windowId = text(WINDOW_KEY),
        heapDumpPath = text(HEAP_DUMP_KEY),
        // From the tool for a line with no link, which is a session written by a build that recorded no
        // place for a call that named nothing — and it went to the same screen then as it would now.
        place = link?.let { placeOfLinkOrNull(it, file, lineNumber) }
          ?: tool?.let { screenOfTool(it, arguments)?.place },
        arguments = arguments,
        input = text(INPUT_KEY),
        output = text(OUTPUT_KEY),
        refusal = text(REFUSAL_KEY),
        error = text(ERROR_KEY),
        outcome = text(OUTCOME_KEY),
        openHeapDumps = this[OPEN_HEAP_DUMPS_KEY].asStrings(),
        millis = text(MILLIS_KEY)?.toLongOrNull() ?: 0L
      )
    }

    private fun placeOfLinkOrNull(
      link: String,
      file: File,
      lineNumber: Int
    ): Place? = try {
      DeepLink.parse(link).place
    } catch (noSuchPlace: IllegalArgumentException) {
      // A link written by a build that spelled a place differently, which is a row that leads nowhere
      // rather than a session that fails to open.
      SharkLog.d(noSuchPlace) { "Line $lineNumber of $file links to no place of a heap dump" }
      null
    }

    private fun String.asJsonOrNull(
      file: File,
      lineNumber: Int
    ): JsonObject? {
      if (isBlank()) {
        return null
      }
      return try {
        JSON.parseToJsonElement(this).jsonObject
      } catch (notJson: Exception) {
        // Which is what the last line of a session whose app was killed mid-write looks like.
        SharkLog.d(notJson) { "Skipping line $lineNumber of $file: it is not one JSON object" }
        null
      }
    }

    private fun JsonElement?.asStrings(): List<String> =
      (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    private fun JsonElement.asStringMap(): Map<String, String> =
      (this as? JsonObject)?.mapValues { (_, value) ->
        (value as? JsonPrimitive)?.content ?: value.toString()
      }.orEmpty()

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

    private fun JsonObject.instant(name: String): Instant? = text(name)?.let { text ->
      try {
        Instant.parse(text)
      } catch (notATime: Exception) {
        SharkLog.d(notATime) { "\"$text\" is no time an agent session was written at" }
        null
      }
    }

    private fun String.isSessionFile(): Boolean =
      startsWith(FILE_NAME_PREFIX) && endsWith(FILE_NAME_SUFFIX)

    private fun String.sessionIdOfName(): String =
      removeSuffix(FILE_NAME_SUFFIX).substringAfterLast('-')

    private val JSON = Json { ignoreUnknownKeys = true }

    /**
     * How many sessions are kept. More than the run logs beside them, because "what did that agent do" is
     * asked about an investigation rather than about a run.
     *
     * A session is as big as the answers it read — tens of kilobytes for a short one, more where a chain or a
     * tree came back — since [AgentSessionCall.output] keeps them. Which is the trade: a hundred sessions of
     * summaries would fit in a fraction of that and would be a hundred sessions nobody can check.
     */
    const val KEEP_SESSION_COUNT = 100

    /**
     * How long a name a caller can give a session, which is enough for a word and a process id.
     *
     * A bound at all because it is part of a file name: the ids this hands out are eight characters, and a
     * name nobody can read on the *Agent logs* screen is no better than one of those.
     */
    const val MAX_SESSION_NAME_LENGTH = 16

    private const val SESSION_ID_BYTES = 4

    private const val FILE_NAME_PREFIX = "agent-"
    private const val FILE_NAME_SUFFIX = ".jsonl"

    /** Sorts oldest first as text, which is what makes ordering these a sort by name. */
    private val FILE_NAME_TIME: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS").withZone(ZoneId.systemDefault())

    private const val SESSION_KEY = "agentSession"
    private const val STARTED_AT_KEY = "startedAt"
    private const val CLIENT_KEY = "client"
    private const val PROTOCOL_KEY = "protocol"
    private const val SERVER_KEY = "sharkDive"

    private const val AT_KEY = "at"

    /** How the message arrived, and what it asked for. See [AgentSessionCall.over]. */
    private const val OVER_KEY = "over"
    private const val METHOD_KEY = "method"

    /**
     * What a tool call arrives as, which is the method every line of an older session was one of.
     *
     * Spelled here rather than beside the protocol it belongs to because [McpSession]'s companion is private
     * and this one is read from both sides: one string, so that a session read back cannot disagree with the
     * one written.
     */
    internal const val TOOLS_CALL_METHOD = "tools/call"

    private const val TOOL_KEY = "tool"
    private const val REASON_KEY = "reason"
    private const val WINDOW_KEY = "window"
    private const val HEAP_DUMP_KEY = "heapDump"
    private const val LINK_KEY = "link"
    private const val REFUSAL_KEY = "refused"

    /** And why this app could not answer at all, which is a different thing. See [AgentSessionCall.error]. */
    private const val ERROR_KEY = "error"

    private const val OUTCOME_KEY = "outcome"
    private const val OPEN_HEAP_DUMPS_KEY = "openHeapDumps"
    private const val MILLIS_KEY = "millis"
    private const val ARGUMENTS_KEY = "arguments"

    /** The call as it was sent and the answer as it was read, verbatim. See [AgentSessionCall.input]. */
    private const val INPUT_KEY = "input"
    private const val OUTPUT_KEY = "output"
  }
}

/**
 * Which of the two ways into this app a message came in by. See [AgentSessionCall.over].
 *
 * Both end up in the same [McpSession] speaking the same protocol, which is the point of them — a refusal
 * met on a command line is the refusal an MCP client would have met. So the difference is invisible from
 * anywhere except the door, and it is worth recording at the door: "the agent sent that" and "I typed that
 * into a shell" are different claims about the same line, and reading a session is often working out which.
 */
enum class AgentTransport(
  /** How it is written in a session file, and answered to an agent asking what another one did. */
  val recorded: String,
  /** And what a person reading the screen is shown, which is what they would call it. */
  val words: String
) {

  /** A client holding a connection open, over the pipe or over this process's own stdin. */
  MCP("mcp", "MCP"),

  /** `--agent <tool> name=value …`, which is a process per call. See [AgentCommandLine]. */
  CLI("cli", "CLI");

  companion object {

    /**
     * The transport [recorded] names, or null having said in the run log which line named nothing.
     *
     * Null rather than a guess, for the reason a status this app can't read is skipped rather than defaulted:
     * a session that says it came in a way this build has never heard of is one to look at, and a line
     * quietly relabelled "MCP" is one nobody looks at.
     */
    internal fun ofRecorded(
      recorded: String,
      file: File,
      lineNumber: Int
    ): AgentTransport? = ofRecordedOrNull(recorded).also {
      if (it == null) {
        SharkLog.d { "Line $lineNumber of $file came in over \"$recorded\", which is no way into this build" }
      }
    }

    /**
     * The same lookup for the handshake, where there is no line of a file to name.
     *
     * The word crossing the socket is [recorded] rather than a spelling of its own, so that what a connection
     * says it is and what its lines are written as cannot come apart. See [AgentServer].
     */
    internal fun ofRecordedOrNull(recorded: String): AgentTransport? =
      entries.firstOrNull { it.recorded == recorded }
  }
}

/** One agent's session, read back off disk. See [AgentSessionFile]. */
class AgentSession(
  val sessionId: String,
  /** When the client connected, or when it first called something for a session with no header. */
  val startedAt: Instant?,
  /** What the client called itself in the handshake, and null for one that didn't say. */
  val client: String?,
  /** Which build of the app answered it. */
  val serverVersion: String?,
  val file: File,
  /** Every message of it, in the order it arrived — the protocol around the tools included. */
  val calls: List<AgentSessionCall>
) {

  /**
   * The ones that reached a tool, which is what an investigation is made of.
   *
   * For the readers that are counting rather than reading: how many calls a leak took is a number about the
   * tools, and it would move because a client says hello differently if it counted every message. The screen
   * draws [calls], because what somebody following an investigation needs is what happened.
   */
  val toolCalls: List<AgentSessionCall> get() = calls.filter { it.tool != null }

  /** How many of the calls were refused, which is the one number a list of sessions is worth showing. */
  val refusedCount: Int get() = calls.count { it.refusal != null }

  /** And how many this app could not answer at all, which is a different thing. See [AgentSessionCall.error]. */
  val errorCount: Int get() = calls.count { it.error != null }

  /** Which ways in were used, in the order they first were: one of them for almost every session. */
  val transports: List<AgentTransport> get() = calls.mapNotNull { it.over }.distinct()

  /**
   * Which heap dumps it read, in the order it first read each of them.
   *
   * Usually one, and a session is not *bound* to one: an agent can open a second dump, and comparing two is
   * a thing to do. Which is what the window listing these needs — a window is one heap dump, so the sessions
   * it shows are the ones that read the dump it has open, and the rest are read in the window of theirs.
   */
  val heapDumpPaths: List<String> get() = calls.mapNotNull { it.heapDumpPath }.distinct()
}

/**
 * One message an agent sent, and what went back.
 *
 * Usually a call to a tool, and **not only** a call to a tool: the handshake, `tools/list`, a ping, a
 * notification, a method this build has never heard of and a line that was not JSON at all are each one of
 * these too. [tool] is what separates them, and [method] is what a message that reached no tool arrived as.
 *
 * The place is what makes a row of the *Agent logs* screen clickable: it is where the window goes when the
 * row is clicked, so that reading what an agent did and going to look at it are the same move. Null for a
 * call about no place of a heap dump — the first one of every session is, since asking which dumps are open
 * is asking about the app rather than about a dump.
 */
class AgentSessionCall(
  val at: Instant,
  /**
   * Which way it came in: an MCP client's connection, or the `--agent` command line.
   *
   * Per message rather than per session, because a session file is a name and either adapter can call
   * itself by that name — a shell joining what an MCP client started is a thing somebody will do, and then
   * the header's client is the truth about the first message and about nothing else.
   *
   * Null for a session recorded before this was kept.
   */
  val over: AgentTransport?,
  /**
   * The JSON-RPC method it arrived as, and null for a line this app could not read one out of.
   *
   * `tools/call` for every call to a tool, which is what [tool] is the name from. The rest are the protocol
   * around the tools — `initialize`, `tools/list`, `ping`, the notifications — and they are here because a
   * session that keeps only what reached a tool cannot answer why nothing did.
   */
  val method: String?,
  /** The tool it called, and null for every message that reached no tool. See [method]. */
  val tool: String?,
  /** Why the agent said it was making the call, and null for one refused for not saying. */
  val reason: String?,
  val windowId: String?,
  val heapDumpPath: String?,
  val place: Place?,
  /** The rest of the arguments, by name, with `reason` and `window` left out: they have fields of their own. */
  val arguments: Map<String, String>,
  /**
   * What the agent sent, as the text it sent: for a call, the tool it named and the arguments it named it
   * with, formatted, nothing left out and nothing added — and for every other message, the line as it
   * arrived.
   *
   * The line for those because there is nothing else to have: a message that named no method, or no tool, or
   * was not JSON at all is one this app could make nothing of, and the bytes are the whole of what there is
   * to look at.
   *
   * Every other field of a call is *about* the call — the verb, the subject, the place, the arguments the
   * screen puts beside a verb — and every one of them is this app's reading of what happened. This is the
   * thing itself, which is what somebody debugging a session needs: an argument that looks right on the row
   * and was spelled wrong on the wire is invisible in a paraphrase and obvious here.
   *
   * **The name as the tool spells it**, `describe_object` rather than "Looked at", and not because [tool]
   * doesn't have it: what this field is for is being read as one thing, and a call whose name has been
   * lifted out of it is a set of values with nothing saying what they are values of. The verb beside it on
   * the screen is this app's word for the same call, which is exactly the pair worth seeing together when a
   * step doesn't follow.
   *
   * The name and the arguments and not the JSON-RPC envelope around them, because the envelope is the
   * client's and both of these are the model's: the id is a number the client counted to.
   *
   * Null for a session written before this was recorded, which is how the *Agent logs* screen knows to say
   * so rather than unfolding onto nothing.
   */
  val input: String?,
  /**
   * And exactly what it got back: the answer, formatted, as the text that reached the model.
   *
   * Not a summary of it — [outcome] is that, and only `conclude` has one. What this is for is following an
   * investigation afterwards: a step that reads as sound and was made on an answer that said nothing is a
   * step nobody can see the trouble with until they read what the agent read.
   *
   * **A refusal and a failure are in here too**, and that is not the same string twice beside [refusal] and
   * [error]: those two are this app's reading of what happened — one of them says the method sent the agent
   * back and the other says this app could not answer — and this is the text the agent was actually handed.
   * A session that keeps the reading and drops the text is one that cannot answer whether what was sent was
   * what somebody thinks was sent, which is the question a log is opened for.
   *
   * For a call it is the text of the tool's answer, which is what the model reads, and for every other
   * message it is the whole response, which is all there is of one. **Null only where nothing went back**:
   * a notification, which JSON-RPC forbids answering. And null for a session recorded before this was kept.
   */
  val output: String?,
  /** Why the call was refused, and null for one that was answered. See [AgentRefusal]. */
  val refusal: String?,
  /**
   * Why this app could not answer at all, and null for a message it answered.
   *
   * Apart from [refusal] because they are opposites in the one way that matters to whoever is reading: a
   * refusal is the surface working — the method sending an agent back to the heap dump — and this is the
   * surface failing. A malformed line, a method that doesn't exist, a `tools/call` naming a tool that
   * doesn't, a handler that threw. Counting the first as the second would say a run was refused into giving
   * up when what happened is that this app fell over. See `EvalScore`.
   */
  val error: String?,
  /**
   * What the call came to, for the calls whose answer is worth a word. See [outcomeOfTool].
   *
   * The other half of a refusal: `conclude` refused says why, and `conclude` answered says which reference
   * the heap dump agreed was at fault — which is the one line of a session anybody reads it for, and the one
   * the eval scores against the answer key. Null for a call whose answer is data rather than a conclusion.
   */
  val outcome: String?,
  /**
   * Which heap dumps the answer said were open, for the one call that asks about the app. See
   * [openHeapDumpsOfTool].
   *
   * Empty for every other call, and that is the whole of what it means: a row with these is a row whose
   * answer was a list of dumps, which the window unfolds and makes each of them somewhere to go. Recorded
   * because it is a list of what *was* open — the run has usually ended by the time anybody reads it, so
   * nothing can be asked again.
   */
  val openHeapDumps: List<String> = emptyList(),
  /** How long the app took to answer, which is mostly how long the heap dump read took. */
  val millis: Long
) {

  /**
   * The link to [place] in the heap dump the call was about, for a call that was about one.
   *
   * The heap dump and not [windowId], even though the window was open when the line was written: an agent's
   * session outlives its run, so by the time anybody reads this the window has almost always gone while the
   * heap dump is still there to open. See [DeepLink].
   */
  fun link(): String? {
    val place = place ?: return null
    val heapDumpPath = heapDumpPath ?: return null
    return DeepLink(File(heapDumpPath), place).toUri()
  }
}

/**
 * What the call did, as a couple of words, and never the thing it did it to.
 *
 * Here rather than in the window that draws it because this is where the tool names are: a screen spelling
 * them itself would be a second list of them to keep in step. Every tool of this build has one, which
 * `AgentSessionFileTest` is what keeps true — **so a name with no verb is a name this build has no tool for**,
 * a typo or a tool from a newer one, and it reads as itself unchanged, since the exact string is the whole of
 * what somebody is looking for when a call went nowhere.
 *
 * **Prose, so it can be drawn as prose.** What a row leads to is [subject] or [screen], and a verb that
 * swallowed the thing it was about — "Listed the leaks" — leaves a row with no part of it to be the link
 * except the whole sentence. So a verb ends where the thing begins, even when that makes it "Listed the".
 */
val AgentSessionCall.verb: String
  get() = tool?.let { verbOfTool(it, arguments) ?: "Called $it" } ?: verbOfMethod(method)

/**
 * And what to call a message that reached no tool, which is the protocol around them.
 *
 * In words like every other row, because the screen is one screen: a reader following an investigation should
 * not have to change how they are reading half way down it to find out that a client said hello. The ones
 * with no word of their own read as what arrived, which for a method this build has never heard of is the
 * whole of what is known about it.
 */
internal fun verbOfMethod(method: String?): String = when {
  method == null -> "Sent something this app could not read"
  method == "initialize" -> "Connected"
  method == "tools/list" -> "Asked what the tools are"
  // Which a client sends to find out whether this end is still there, and this end is a window somebody may
  // have closed.
  method == "ping" -> "Checked this window is still open"
  // A call that named no tool, which is the one tools/call that reaches none: an unknown *name* keeps its
  // name and reads as itself, since a typo is the thing worth seeing.
  method == "tools/call" -> "Called a tool it did not name"
  method.startsWith("notifications/") -> "Sent the notification $method"
  else -> "Sent $method"
}

/**
 * What the call was about, in the words the window uses for it: an address, a class name, a place.
 *
 * Null for a call whose subject is a screen of the heap dump, which is [screen], or the app itself, where
 * the verb says all of it.
 */
val AgentSessionCall.subject: String?
  get() = arguments[SUBJECT_OBJECT] ?: arguments[SUBJECT_PLACE] ?: arguments[SUBJECT_CLASS_NAME]
    ?: arguments[SUBJECT_SESSION]

/**
 * What the call was about where that is a whole screen of the heap dump rather than something in it.
 *
 * The other half of [verb], and the reason a verb is allowed to end mid-sentence: a call that named nothing
 * still went somewhere, so it still has one part of the row that leads there. "Read the" + "dominator tree",
 * where the second words are the link and the first are not.
 *
 * Spelled beside the tool names rather than taken from what the window calls that screen, because these read
 * inside a sentence: the tab says `Leaks` and the row says "Listed the leaks", and a row built from the tab
 * titles reads as "Listed the Leaks". Null for a call that named an object — [subject] is that — and for the
 * calls about the app rather than about a heap dump, which have no place of one to go to.
 */
val AgentSessionCall.screen: String? get() = tool?.let { screenOfTool(it, arguments)?.words }

/**
 * What the answer to a call came to, as a couple of words, and null when the answer is data rather than a
 * conclusion.
 *
 * Here beside [verbOfTool] and for the same reason: the tool names live in this file. Only `conclude` has one
 * today, which is the point of it — a session is read to find out what somebody concluded, and every other
 * call is how they got there.
 */
internal fun outcomeOfTool(
  tool: String,
  answer: JsonObject
): String? = when (tool) {
  "conclude" -> ((answer[ANSWER_FAULTY_REFERENCE] as? JsonArray)?.firstOrNull() as? JsonObject)
    ?.let { it[ANSWER_REFERENCE] as? JsonPrimitive }
    ?.content
  else -> null
}

/**
 * Which heap dumps an answer said were open, which is the second thing read off an answer rather than off
 * the arguments. See [AgentSessionCall.openHeapDumps].
 *
 * Only `open_heap_dumps`, and for the same reason `outcomeOfTool` is only `conclude`: this is the one call
 * whose answer is not about a heap dump but *is* a list of them, and a row saying "asked which dumps are
 * open" without saying which is a row that withholds the answer it is a record of. The paths, since a
 * window is opened on a path — the window ids beside them in that answer belong to a run that has usually
 * ended by the time anybody reads this.
 */
internal fun openHeapDumpsOfTool(
  tool: String,
  answer: JsonObject
): List<String> = when (tool) {
  "open_heap_dumps" -> (answer[ANSWER_HEAP_DUMPS] as? JsonArray).orEmpty()
    .mapNotNull { ((it as? JsonObject)?.get(ANSWER_HEAP_DUMP_PATH) as? JsonPrimitive)?.content }
  else -> emptyList()
}

/** Null for a tool this build has no verb for, which is what a test asserts never happens. */
internal fun verbOfTool(
  tool: String,
  arguments: Map<String, String>
): String? = when (tool) {
  "open_heap_dumps" -> "Asked which heap dumps are open"
  // Ending on "the", because what follows it is the link. See [AgentSessionCall.screen].
  "list_leaks" -> "Listed the"
  // Not "Described", which reads as the agent having written a description of something rather than having
  // asked what it is. Every tool here is a read unless it says otherwise, and the verbs have to say which.
  "describe_object" -> "Looked at"
  "chain_from_gc_root" -> "Read the chain to"
  "ways_held" -> "Looked for every way of holding"
  // Which is a search of the whole dump when it names no class, and that is the list of the biggest
  // objects rather than a search for nothing.
  "find_objects" -> if (SUBJECT_CLASS_NAME in arguments) "Searched for" else "Listed the"
  "dominator_tree" -> if (SUBJECT_OBJECT in arguments) "Read the dominator tree under" else "Read the"
  "set_verdict" -> "Recorded ${arguments[SUBJECT_VERDICT] ?: "a verdict"} on"
  "clear_verdict" -> "Took the verdict off"
  "read_notes" -> if (SUBJECT_PLACE in arguments) "Read the notes on" else "Read what has been written"
  // Worth the difference on the screen: a note replaced is a paragraph that was there and isn't any more,
  // which is the one thing an agent does here that a reader can't get back.
  "take_note" -> if (arguments[SUBJECT_REPLACE] == "true") "Rewrote the note on" else "Wrote a note on"
  // Reading what other agents did, which is the one call whose subject is another session of this screen.
  "agent_log" -> if (SUBJECT_SESSION in arguments) "Read what an agent did in" else "Read the"
  "show" -> "Showed"
  "conclude" -> "Concluded about"
  // The app rather than a heap dump, so each of these says the whole of what it did: there is no place of
  // an open dump to go to, the file one of them opens and the file another one writes not being one until
  // the call has been answered.
  "open_heap_dump" -> "Opened ${arguments[SUBJECT_PATH] ?: "a heap dump"}"
  "list_devices" -> arguments[SUBJECT_DEVICE]
    ?.let { "Listed the processes of $it" }
    ?: "Asked which devices are connected"
  "dump_heap" -> "Dumped the heap of ${arguments[SUBJECT_PROCESS] ?: "a process"}"
  else -> null
}

/**
 * A screen of the heap dump a whole call was about: what to call it inside a sentence, and where it is.
 *
 * One thing rather than two because the words and the place cannot be allowed to disagree — words with no
 * place are a link to nothing, and a place with no words is a call that went somewhere the reader is never
 * shown. `AgentTools.placeOrNull` reads the place off this, and the *Agent logs* screen draws the words.
 */
internal class AgentScreen(
  val words: String,
  val place: Place
)

/**
 * Which screen a call that named nothing was about, and null for a call that named something.
 *
 * The calls that name nothing are the ones where naming nothing *means* something: the leaks, the agent log
 * as a list, and the two tools that mean the whole heap dump when they are given no object — the tree from
 * its root, and the list of every object.
 */
internal fun screenOfTool(
  tool: String,
  arguments: Map<String, String>
): AgentScreen? = when (tool) {
  "list_leaks" -> AgentScreen("leaks", Place.Leaks())
  "agent_log" -> if (SUBJECT_SESSION in arguments) null else AgentScreen("agent log", Place.AgentLogs)
  "find_objects" ->
    if (SUBJECT_CLASS_NAME in arguments) null else AgentScreen("biggest objects", Place.Objects())
  "dominator_tree" ->
    if (SUBJECT_OBJECT in arguments) null else AgentScreen("dominator tree", Place.wholeHeapDump())
  else -> null
}

/** What `conclude` answers with the reference under, which is one of the two answers this file records. */
private const val ANSWER_FAULTY_REFERENCE = "faultyReference"
private const val ANSWER_REFERENCE = "reference"

/** And what `open_heap_dumps` answers with the dumps under. See `AgentJson.heapDump`. */
private const val ANSWER_HEAP_DUMPS = "heapDumps"
private const val ANSWER_HEAP_DUMP_PATH = "heapDumpPath"

private const val SUBJECT_OBJECT = "object"
private const val SUBJECT_PLACE = "place"
private const val SUBJECT_CLASS_NAME = "className"
private const val SUBJECT_VERDICT = "verdict"
private const val SUBJECT_REPLACE = "replace"
private const val SUBJECT_PATH = "path"
private const val SUBJECT_DEVICE = "device"
private const val SUBJECT_PROCESS = "process"
private const val SUBJECT_SESSION = "session"
