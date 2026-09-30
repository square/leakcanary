package shark.dive.agent

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import shark.SharkLog

/**
 * One agent talking to this app: a call per line in, an answer per line back. See [AgentWire].
 *
 * **There is no protocol here beyond naming a tool.** No handshake to answer, nothing to discover, no
 * capabilities to agree on — what the commands are is `--help`, which is text this build carries and needs no
 * run to print, and how to work here arrives in the first answer rather than in front of it. So a
 * caller is a socket, a line and a read, which is the whole of what [AgentCommandLine] does.
 *
 * A connection holds no state of its own. What an investigation accumulates — the verdicts, the notes — lives
 * in the heap dump's window, so a second call, or a second agent, reads what the first one concluded rather
 * than starting from nothing. Which is why a process per call costs nothing but the connect: the dump was
 * parsed and indexed once, in the window somebody is watching.
 */
internal class AgentConnection(
  private val tools: AgentTools,
  /**
   * Where this session is written down, which is what the window's *Agent logs* screen draws and what the
   * eval scores. See [AgentSessionFile].
   */
  private val sessionFile: AgentSessionFile
) {

  /**
   * Whether this session has already been handed [AgentMethod.SURFACE], which it is exactly once.
   *
   * Starts from the file rather than from false, because a session is not a connection: `--cli` is a process
   * per call, so an [AgentConnection] is one typed command and the session it belongs to is whatever
   * [AgentSessionFile] joined. See [AgentSessionFile.hasAnsweredACall] and [withTheSurface].
   */
  private var hasHandedOverTheSurface = sessionFile.hasAnsweredACall

  /**
   * Answers one call, as the line to write back.
   *
   * **Every line that arrives here is written down**, answered or refused or unreadable, before this returns.
   * A log that keeps the calls that worked is a log that cannot answer the question it gets opened for, which
   * is why nothing came back — so a line that is not JSON and a line that names no tool are each a row of a
   * session like any other. See [AgentSessionFile].
   */
  suspend fun answer(line: String): String {
    val at = Instant.now()
    val startedAt = System.nanoTime()
    val call = AgentWire.decodeOrNull(line)
      ?: return unreadable(line, "That is not one JSON object.", at, startedAt)
    val name = AgentWire.toolOf(call)
      ?: return unreadable(line, "A call needs the \"${AgentWire.TOOL}\" of a tool.", at, startedAt)
    return callTool(name, AgentWire.argumentsOf(call), at, startedAt)
  }

  /**
   * One call to a tool, written down whatever became of it.
   *
   * Which is three endings and not two: answered, refused, and a name no tool of this build has. The name is
   * kept for the last of those, since a typo is the whole of what somebody is looking for when a call went
   * nowhere.
   */
  private suspend fun callTool(
    name: String,
    arguments: JsonObject,
    at: Instant,
    startedAt: Long
  ): String {
    val tool = tools.byName(name)
    if (tool == null) {
      val failure = "There is no tool called \"$name\". This build has " +
        tools.all.joinToString(", ") { it.name } + "."
      recordCall(
        name,
        arguments,
        refusal = null,
        error = failure,
        output = failure,
        at = at,
        startedAt = startedAt
      )
      return AgentWire.encode(AgentWire.failure(failure))
    }
    // One line per call, before the reads it causes, so that a session log reads as what the agent was
    // trying to learn and then what that cost. See [AgentTools].
    SharkLog.d { "An agent called $name${arguments.logLine()}" }
    return try {
      val answer = withTheSurface(tool.call(arguments))
      // Formatted once and then both written down and answered with, so that the text in the session is the
      // text the caller printed rather than the same object printed a second way. See [AgentWire.pretty].
      val answered = AgentWire.pretty(answer)
      // The answer as well as the arguments, because two of them are things the arguments don't say: what
      // was concluded, and which heap dumps were open. See [outcomeOfTool] and [openHeapDumpsOfTool].
      recordCall(
        name,
        arguments,
        refusal = null,
        error = null,
        output = answered,
        outcome = outcomeOfTool(name, answer),
        openHeapDumps = openHeapDumpsOfTool(name, answer),
        at = at,
        startedAt = startedAt
      )
      AgentWire.encode(AgentWire.answer(answer))
    } catch (refused: AgentRefusal) {
      SharkLog.d { "Refused $name: ${refused.message}" }
      recordCall(
        name,
        arguments,
        refusal = refused.message,
        error = null,
        // The refusal again, and not a duplicate of the field above it: that one is this app's reading —
        // the method said no — and this is the text the agent was handed. See [AgentSessionCall.output].
        output = refused.message,
        at = at,
        startedAt = startedAt
      )
      AgentWire.encode(AgentWire.refusal(refused.message))
    } catch (throwable: Throwable) {
      // A read that failed or a bug of ours. Caught here rather than left to the connection so that the call
      // is written down as a call — pointing at the object it was about, with the reason the agent gave —
      // and not as a line saying only that something went wrong somewhere.
      SharkLog.d(throwable) { "An agent's $name failed" }
      val failure = throwable.toString()
      recordCall(
        name,
        arguments,
        refusal = null,
        error = failure,
        output = failure,
        at = at,
        startedAt = startedAt
      )
      AgentWire.encode(AgentWire.failure(failure))
    }
  }

  /**
   * An answer with [AgentMethod.SURFACE] in front of it, for the first answered call of a session.
   *
   * **The method travels as a tool result** because that is the one thing an agent is certain to read: it
   * asked for the answer, and nothing between here and the model drops it or cuts it short.
   *
   * **Once per session, and a session is not this object.** A command line is a process per call, so the flag
   * starts from what the session file already holds: the second typed command of an investigation finds a
   * session whose first call was answered and adds nothing. Which is the one thing this must not get wrong in
   * the other direction either — `AgentSessionFile.hasAnsweredACall` looks for an *answered* call rather than
   * for a line, because a first command that was refused carried no answer for this to be in, and a session
   * that counted it would be one where nobody ever read the surface.
   *
   * **And it is the only half of the method that travels in an answer.** [AgentMethod.LEAK] was in this same
   * field of `list_leaks`, which is why this used to merge the two, and it is now
   * [AgentCommandLine.LEAK_METHOD_OPTION] — text a session reads once, rather than a field an investigation of
   * four leaks was handed four times.
   */
  private fun withTheSurface(answer: JsonObject): JsonObject {
    if (hasHandedOverTheSurface) {
      return answer
    }
    hasHandedOverTheSurface = true
    return buildJsonObject {
      // First, because it is the part that says how to work here, and a model reads an answer from the top.
      put(AgentMethod.FIELD, AgentMethod.SURFACE)
      answer.forEach { (name, value) -> put(name, value) }
    }
  }

  /**
   * Writes a call down: answered, refused, failed, or made to a tool this build has never heard of.
   *
   * Here rather than in [AgentTool] because this is the one place that has both halves of a call: what was
   * asked, and what came back. Every call, in the order they were made, is what turns a session into
   * something a person can follow — and the reason for each is the agent's own sentence rather than a
   * paraphrase of it.
   */
  private fun recordCall(
    name: String,
    arguments: JsonObject,
    refusal: String?,
    error: String?,
    output: String?,
    outcome: String? = null,
    openHeapDumps: List<String> = emptyList(),
    at: Instant,
    startedAt: Long
  ) {
    // What the call is about, read off the arguments rather than out of the answer: a call that was refused,
    // or that named a tool nothing answers to, is still recorded pointing at whatever it was asking about,
    // which is most of what makes one worth reading afterwards.
    val target = tools.target(name, arguments)
    sessionFile.called(
      AgentSessionCall(
        at = at,
        tool = name,
        reason = (arguments[REASON_ARGUMENT] as? JsonPrimitive)?.content,
        heapDumpPath = target.heapDumpPath,
        place = target.place,
        arguments = arguments.recorded(),
        // The whole call, formatted the way the answer is: the fields above are what this app made of it,
        // and this is what it was. See [AgentSessionCall.input].
        input = "$name ${AgentWire.pretty(arguments)}",
        output = output,
        refusal = refusal,
        error = error,
        outcome = outcome,
        openHeapDumps = openHeapDumps,
        millis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
      )
    )
  }

  /**
   * And writes down a line this app could make no call of, answering with why.
   *
   * The line as it arrived is the whole of what there is to keep for one of these: there is no tool to name
   * it after, no arguments to read a subject out of, and nowhere in a heap dump for it to lead. Which is why
   * it is worth keeping — a session that holds only what reached a tool cannot say why nothing did.
   */
  private fun unreadable(
    line: String,
    failure: String,
    at: Instant,
    startedAt: Long
  ): String {
    SharkLog.d { "An agent sent a line that is no call: $failure" }
    sessionFile.called(
      AgentSessionCall(
        at = at,
        tool = null,
        reason = null,
        heapDumpPath = null,
        place = null,
        arguments = emptyMap(),
        input = line,
        output = failure,
        refusal = null,
        error = failure,
        outcome = null,
        millis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
      )
    )
    return AgentWire.encode(AgentWire.failure(failure))
  }

  private companion object {

    /** What the agent said it was after, which every tool takes. See [AgentTool]. */
    const val REASON_ARGUMENT = "reason"

    const val NANOS_PER_MILLI = 1_000_000L

    /**
     * The rest of the arguments, as text, for the row a session log keeps.
     *
     * Without the one that has a field of its own, and never as the JSON that arrived: what this is read back
     * for is a screen that says what an agent did in words, so a value here is one the window can put beside a
     * verb.
     */
    fun JsonObject.recorded(): Map<String, String> =
      filterKeys { it != REASON_ARGUMENT }
        .mapValues { (_, value) -> (value as? JsonPrimitive)?.content ?: value.toString() }

    /**
     * The arguments of a call on one line of the log, with the agent's `reason` first.
     *
     * Its own reason and not a paraphrase: what to read a session log for is whether the steps follow from
     * each other, and that is a question about the sentences the agent wrote at the time.
     */
    fun JsonObject.logLine(): String {
      if (isEmpty()) {
        return ""
      }
      val reason = (this[REASON_ARGUMENT] as? JsonPrimitive)?.content
      val rest = entries.filter { it.key != REASON_ARGUMENT }
        .joinToString(", ") { (key, value) -> "$key=${(value as? JsonPrimitive)?.content ?: value}" }
      return listOfNotNull(
        rest.takeIf { it.isNotEmpty() }?.let { "($it)" },
        reason?.let { " because: $it" }
      ).joinToString("")
    }
  }
}
