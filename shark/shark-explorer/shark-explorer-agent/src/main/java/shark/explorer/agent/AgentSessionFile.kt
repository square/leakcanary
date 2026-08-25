package shark.explorer.agent

import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import shark.SharkLog
import shark.explorer.DeepLink
import shark.explorer.Place

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
 * JSON, one object per line, flushed per line, because the session worth reading is often the one that ended
 * by the agent giving up or the app being killed. A header line naming the session, then a line per call.
 * Beside the notes and the verdicts under `~/.shark-explorer`, since it is the same kind of thing: what
 * somebody concluded about a heap dump, kept where the next reader will find it.
 */
class AgentSessionFile private constructor(
  /** The file itself, shown in the window so that a session can be read without this app. */
  val file: File,
  /** What this session is called, in the window and in the file. See [newSessionId]. */
  val sessionId: String,
  private val startedAt: Instant,
  private val serverVersion: String
) {

  private var isHeaderWritten = false

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
     * The same housekeeping `shark.explorer.SessionLog` does for the run logs, for the same reason: a
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
      return AgentSessionFile(File(directory, name), sessionId, startedAt, serverVersion)
    }

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
          read[TOOL_KEY] != null -> read.asCallOrNull(file, index + 1)?.let { calls += it }
          read[SESSION_KEY] != null -> header = header ?: read
          else -> SharkLog.d { "Skipping line ${index + 1} of $file: it is neither a session nor a call" }
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
     * Random rather than counted, for the reason `DeepLink.newWindowId` is: ids handed out in order repeat
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
      put(TOOL_KEY, tool)
      reason?.let { put(REASON_KEY, it) }
      windowId?.let { put(WINDOW_KEY, it) }
      heapDumpPath?.let { put(HEAP_DUMP_KEY, it) }
      // As the link the window hands out for that place, which is the whole of what a row has to be
      // clickable: the place to go to, and a line the agent's human can paste anywhere. See [DeepLink].
      link()?.let { put(LINK_KEY, it) }
      refusal?.let { put(REFUSAL_KEY, it) }
      put(MILLIS_KEY, millis)
      if (arguments.isNotEmpty()) {
        putJsonObject(ARGUMENTS_KEY) {
          arguments.forEach { (name, value) -> put(name, value) }
        }
      }
    }

    private fun JsonObject.asCallOrNull(
      file: File,
      lineNumber: Int
    ): AgentSessionCall? {
      val tool = text(TOOL_KEY)
      val at = instant(AT_KEY)
      if (tool == null || at == null) {
        SharkLog.d { "Skipping line $lineNumber of $file: it says no tool, or no time it was called" }
        return null
      }
      val link = text(LINK_KEY)
      return AgentSessionCall(
        at = at,
        tool = tool,
        reason = text(REASON_KEY),
        windowId = text(WINDOW_KEY),
        heapDumpPath = text(HEAP_DUMP_KEY),
        place = link?.let { placeOfLinkOrNull(it, file, lineNumber) },
        arguments = this[ARGUMENTS_KEY]?.asStringMap().orEmpty(),
        refusal = text(REFUSAL_KEY),
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
     * How many sessions are kept. More than the run logs beside them, because these are a few kilobytes
     * each and because "what did that agent do" is asked about an investigation rather than about a run.
     */
    const val KEEP_SESSION_COUNT = 100

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
    private const val SERVER_KEY = "sharkExplorer"

    private const val AT_KEY = "at"
    private const val TOOL_KEY = "tool"
    private const val REASON_KEY = "reason"
    private const val WINDOW_KEY = "window"
    private const val HEAP_DUMP_KEY = "heapDump"
    private const val LINK_KEY = "link"
    private const val REFUSAL_KEY = "refused"
    private const val MILLIS_KEY = "millis"
    private const val ARGUMENTS_KEY = "arguments"
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
  val calls: List<AgentSessionCall>
) {

  /** How many of the calls were refused, which is the one number a list of sessions is worth showing. */
  val refusedCount: Int get() = calls.count { it.refusal != null }
}

/**
 * One call an agent made, and what it did.
 *
 * The place is what makes a row of the *Agent logs* screen clickable: it is where the window goes when the
 * row is clicked, so that reading what an agent did and going to look at it are the same move. Null for a
 * call about no place of a heap dump — the first one of every session is, since asking which dumps are open
 * is asking about the app rather than about a dump.
 */
class AgentSessionCall(
  val at: Instant,
  val tool: String,
  /** Why the agent said it was making the call, and null for one refused for not saying. */
  val reason: String?,
  val windowId: String?,
  val heapDumpPath: String?,
  val place: Place?,
  /** The rest of the arguments, by name, with `reason` and `window` left out: they have fields of their own. */
  val arguments: Map<String, String>,
  /** Why the call was refused, and null for one that was answered. See [AgentRefusal]. */
  val refusal: String?,
  /** How long the app took to answer, which is mostly how long the heap dump read took. */
  val millis: Long
) {

  /** The link to [place] in the window the call was made against, for a call that was about one. */
  fun link(): String? {
    val place = place ?: return null
    val windowId = windowId ?: return null
    return DeepLink(windowId, place).toUri()
  }
}

/**
 * What the call did, as a couple of words.
 *
 * Here rather than in the window that draws it because this is where the tool names are: a screen spelling
 * them itself would be a second list of them to keep in step. Every tool has one, which `AgentSessionFileTest`
 * is what keeps true — a tool added without a verb reads as its own name, which is the raw protocol showing
 * through on a screen that exists to not show it.
 */
val AgentSessionCall.verb: String get() = verbOfTool(tool, arguments) ?: tool.replace('_', ' ')

/**
 * What the call was about, in the words the window uses for it: an address, a class name, a place.
 *
 * Null for a call whose subject is the whole heap dump or the app itself, where the verb says all of it.
 */
val AgentSessionCall.subject: String?
  get() = arguments[SUBJECT_OBJECT] ?: arguments[SUBJECT_PLACE] ?: arguments[SUBJECT_CLASS_NAME]

/** Null for a tool this build has no verb for, which is what a test asserts never happens. */
internal fun verbOfTool(
  tool: String,
  arguments: Map<String, String>
): String? = when (tool) {
  "open_heap_dumps" -> "Asked which heap dumps are open"
  "list_leaks" -> "Listed the leaks"
  "describe_object" -> "Described"
  "chain_from_gc_root" -> "Read the chain to"
  "ways_held" -> "Looked for every way of holding"
  "find_objects" -> "Searched for"
  // Both of these are about the whole heap dump when they name nothing, so the verb has to stand on its
  // own: a row reads as the verb and then the subject, and "Read the notes on" alone says nothing.
  "dominator_tree" ->
    if (SUBJECT_OBJECT in arguments) "Read the dominator tree under" else "Read the dominator tree"
  "set_verdict" -> "Recorded ${arguments[SUBJECT_VERDICT] ?: "a verdict"} on"
  "clear_verdict" -> "Took the verdict off"
  "read_notes" -> if (SUBJECT_PLACE in arguments) "Read the notes on" else "Read what has been written"
  // Worth the difference on the screen: a note replaced is a paragraph that was there and isn't any more,
  // which is the one thing an agent does here that a reader can't get back.
  "take_note" -> if (arguments[SUBJECT_REPLACE] == "true") "Rewrote the note on" else "Wrote a note on"
  "show" -> "Showed"
  "conclude" -> "Concluded about"
  // The app rather than a heap dump, so each of these says the whole of what it did: there is no subject
  // to put after it, the heap dump it opens not existing as a place until it is open.
  "open_heap_dump" -> "Opened ${arguments[SUBJECT_PATH] ?: "a heap dump"}"
  "list_devices" -> arguments[SUBJECT_DEVICE]
    ?.let { "Listed the processes of $it" }
    ?: "Asked which devices are connected"
  "dump_heap" -> "Dumped the heap of ${arguments[SUBJECT_PROCESS] ?: "a process"}"
  else -> null
}

private const val SUBJECT_OBJECT = "object"
private const val SUBJECT_PLACE = "place"
private const val SUBJECT_CLASS_NAME = "className"
private const val SUBJECT_VERDICT = "verdict"
private const val SUBJECT_REPLACE = "replace"
private const val SUBJECT_PATH = "path"
private const val SUBJECT_DEVICE = "device"
private const val SUBJECT_PROCESS = "process"
