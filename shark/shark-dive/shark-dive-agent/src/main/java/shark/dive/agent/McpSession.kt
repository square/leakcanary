package shark.dive.agent

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import shark.SharkLog

/**
 * One agent's connection to this app, spoken as [MCP](https://modelcontextprotocol.io).
 *
 * MCP rather than a protocol of ours because it is the one interface every agent already has: a client is
 * configured once, discovers the tools and their schemas itself, and nothing here ever calls a model.
 *
 * What it has over [AgentCommandLine], which reaches the same run over the same socket, is that **a
 * connection is a session**. One handshake for an investigation, the tools and the method arriving in band,
 * and every call of it in one file for the *Agent logs* screen to draw. A command line has to be told which
 * session it is joining to get the last of those, and nothing at all to get the first two.
 *
 * JSON-RPC 2.0, one message per line. That framing is stdio MCP's own, which is what lets the bridge in
 * [AgentStdioBridge] be a pipe and nothing more.
 *
 * A session holds no state of its own. What an investigation accumulates — the verdicts, the notes — lives
 * in the heap dump's window, so an agent that reconnects, or a second agent, reads what the first one
 * concluded rather than starting from nothing.
 */
internal class McpSession(
  private val tools: AgentTools,
  /** Which build of the app is answering, for a client that logs what it connected to. */
  private val serverVersion: String,
  /**
   * Where this session is written down, which is what the window's *Agent logs* screen draws and what the
   * eval scores. One per connection, so that two agents at one heap dump are two files. See
   * [AgentSessionFile].
   */
  private val sessionFile: AgentSessionFile,
  /**
   * Which way in this session is being spoken through, stamped on every line it writes.
   *
   * Told rather than worked out: both adapters arrive here over the same socket speaking the same protocol,
   * which is what makes them one surface, so the only place that knows is the one that opened the door. See
   * [AgentTransport].
   */
  private val over: AgentTransport
) {

  /**
   * Answers one message, or null for one that wants no answer.
   *
   * Notifications are the null case and it is not optional: JSON-RPC forbids answering a message with no
   * id, and a client that gets one back for `notifications/initialized` treats the session as broken.
   *
   * **Every message that arrives here is written down**, answered or refused or unreadable, before this
   * returns. A log that keeps the calls that worked is a log that cannot answer the question it gets opened
   * for, which is why nothing came back — so a line that is not JSON, a method this build has never heard
   * of and a call to a tool that does not exist are each a row of a session like any other. See
   * [AgentSessionFile].
   */
  suspend fun answer(line: String): String? {
    val at = Instant.now()
    val startedAt = System.nanoTime()
    val message = try {
      JSON.parseToJsonElement(line).jsonObject
    } catch (notJson: Exception) {
      SharkLog.d(notJson) { "An agent sent something that is no JSON-RPC message" }
      val error = "That is not JSON: $notJson"
      return answered(errorResponse(id = null, code = PARSE_ERROR, message = error), line, null, error, at, startedAt)
    }
    val id = message["id"]
    val method = (message["method"] as? JsonPrimitive)?.content
    if (method == null) {
      val error = "A request needs a \"method\"."
      return answered(errorResponse(id, INVALID_REQUEST, error), line, null, error, at, startedAt)
    }
    val params = message["params"]?.jsonObject ?: EMPTY_PARAMS
    // No id is a notification: nothing is waiting for an answer and sending one is a protocol error. The
    // line still goes down, with nothing as what came back, since that is what happened.
    if (id == null) {
      SharkLog.d { "An agent sent the notification $method" }
      recordMessage(line, method, output = null, error = null, at = at, startedAt = startedAt)
      return null
    }
    // Written down by the one place that has a tool's own reading of a call, rather than here.
    if (method == TOOLS_CALL) {
      return JSON.encodeToString(
        JsonElement.serializer(),
        successResponse(id, callTool(line, params, at, startedAt))
      )
    }
    return try {
      answered(successResponse(id, dispatch(method, params)), line, method, null, at, startedAt)
    } catch (unknown: UnknownMethod) {
      answered(
        errorResponse(id, METHOD_NOT_FOUND, unknown.message), line, method, unknown.message, at, startedAt
      )
    } catch (throwable: Throwable) {
      // A read that failed or a bug of ours, either way told to the agent rather than dropped: a client
      // waiting for a response it never gets has no way to tell that from the app having gone away.
      SharkLog.d(throwable) { "An agent's $method failed" }
      answered(
        errorResponse(id, INTERNAL_ERROR, throwable.toString()),
        line,
        method,
        throwable.toString(),
        at,
        startedAt
      )
    }
  }

  /**
   * The response as the line it goes back as, written down on the way out.
   *
   * The whole response for these rather than the text inside it, which is what a call records: there is no
   * inside to one of these — a `tools/list` answer and a JSON-RPC error are each the whole of what the client
   * read. See [AgentSessionCall.output].
   */
  private fun answered(
    response: JsonObject,
    input: String,
    method: String?,
    error: String?,
    at: Instant,
    startedAt: Long
  ): String {
    val text = JSON.encodeToString(JsonElement.serializer(), response)
    recordMessage(input, method, output = text, error = error, at = at, startedAt = startedAt)
    return text
  }

  private suspend fun dispatch(
    method: String,
    params: JsonObject
  ): JsonObject = when (method) {
    "initialize" -> initialize(params)
    "tools/list" -> buildJsonObject {
      putJsonArray("tools") {
        tools.all.forEach { tool ->
          addJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("inputSchema", tool.schema)
          }
        }
      }
    }
    // Answered because clients use it to find out whether this end is still there, and this end is a
    // window someone may have closed.
    "ping" -> buildJsonObject { }
    else -> throw UnknownMethod("This server has no \"$method\". It has tools, and nothing else.")
  }

  /**
   * The handshake, which is also where the method is handed over.
   *
   * **The client's protocol version is echoed back** rather than one of ours being asserted. The spec asks a
   * server to answer with the same version when it supports it, and this server has no version-specific
   * behaviour at all — it serves tools, which every revision of MCP has — so echoing is both correct and
   * the thing that keeps it working against a client newer than this build.
   */
  private fun initialize(params: JsonObject): JsonObject {
    val clientVersion = (params["protocolVersion"] as? JsonPrimitive)?.content
    val clientInfo = params["clientInfo"] as? JsonObject
    val clientName = listOfNotNull(
      (clientInfo?.get("name") as? JsonPrimitive)?.content,
      (clientInfo?.get("version") as? JsonPrimitive)?.content
    ).joinToString(" ").takeIf { it.isNotEmpty() }
    SharkLog.d { "An agent connected: ${clientName ?: "a client that did not say who it is"}" }
    sessionFile.opened(client = clientName, protocolVersion = clientVersion)
    return buildJsonObject {
      put("protocolVersion", clientVersion ?: FALLBACK_PROTOCOL_VERSION)
      putJsonObject("capabilities") {
        putJsonObject("tools") { }
      }
      putJsonObject("serverInfo") {
        put("name", SERVER_NAME)
        put("version", serverVersion)
      }
      // Some clients show this to the model and some drop it, which is why AgentMethod is handed over with
      // the first tool answer as well.
      put("instructions", AgentMethod.INSTRUCTIONS)
    }
  }

  /**
   * One call to a tool, written down whatever became of it.
   *
   * Which is four endings and not two: answered, refused, a name no tool of this build has, and no name at
   * all. The last two are a message that reached no tool, so they are recorded as one — with the name kept
   * where there was one, since a typo is the whole of what somebody is looking for when a call went nowhere.
   */
  private suspend fun callTool(
    line: String,
    params: JsonObject,
    at: Instant,
    startedAt: Long
  ): JsonObject {
    val name = (params["name"] as? JsonPrimitive)?.content
    if (name == null) {
      val error = "A tools/call needs the \"name\" of a tool."
      recordMessage(line, TOOLS_CALL, output = error, error = error, at = at, startedAt = startedAt)
      return toolError(error)
    }
    val arguments = params["arguments"]?.jsonObject ?: EMPTY_PARAMS
    val tool = tools.byName(name)
    if (tool == null) {
      val error = "There is no tool called \"$name\". This server has " +
        tools.all.joinToString(", ") { it.name } + "."
      recordCall(name, arguments, refusal = null, error = error, output = error, at = at, startedAt = startedAt)
      return toolError(error)
    }
    // One line per call, before the reads it causes, so that a session log reads as what the agent was
    // trying to learn and then what that cost. See [AgentTools].
    SharkLog.d { "An agent called $name${arguments.logLine()}" }
    return try {
      val answer = tool.call(arguments)
      // Formatted once and then both answered with and written down, so that the text in the session is the
      // text the model read rather than the same object printed a second way. See [AgentSessionCall.output].
      val answered = PRETTY_JSON.encodeToString(JsonElement.serializer(), answer)
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
      toolResult(answer, answered)
    } catch (refused: AgentRefusal) {
      // A refusal is an answer to the agent and not a failure of the server, so it comes back as a tool
      // result the model reads rather than as a JSON-RPC error the client may swallow.
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
      toolError(refused.message)
    } catch (throwable: Throwable) {
      // A read that failed or a bug of ours. Caught here rather than left to [answer] so that the call is
      // written down as a call — pointing at the object it was about, with the reason the agent gave — and
      // not as a line saying only that something went wrong somewhere.
      SharkLog.d(throwable) { "An agent's $name failed" }
      val error = throwable.toString()
      recordCall(name, arguments, refusal = null, error = error, output = error, at = at, startedAt = startedAt)
      toolError(error)
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
        over = over,
        method = TOOLS_CALL,
        tool = name,
        reason = (arguments[REASON_ARGUMENT] as? JsonPrimitive)?.content,
        windowId = target.windowId,
        heapDumpPath = target.heapDumpPath,
        place = target.place,
        arguments = arguments.recorded(),
        // The whole call, formatted the way the answer is: the fields above are what this app made of it,
        // and this is what it was. See [AgentSessionCall.input].
        input = "$name ${PRETTY_JSON.encodeToString(JsonElement.serializer(), arguments)}",
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
   * And writes down a message that reached no tool, which is the protocol around them.
   *
   * The line as it arrived is the whole of what there is to keep for one of these: there is no tool to name
   * it after, no arguments to read a subject out of, and nowhere in a heap dump for it to lead. Which is why
   * they are worth keeping — a session that holds only what reached a tool cannot say why nothing did.
   */
  private fun recordMessage(
    input: String,
    method: String?,
    output: String?,
    error: String?,
    at: Instant,
    startedAt: Long
  ) {
    sessionFile.called(
      AgentSessionCall(
        at = at,
        over = over,
        method = method,
        tool = null,
        reason = null,
        windowId = null,
        heapDumpPath = null,
        place = null,
        arguments = emptyMap(),
        input = input,
        output = output,
        refusal = null,
        error = error,
        outcome = null,
        millis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
      )
    )
  }

  private fun toolResult(
    result: JsonObject,
    /**
     * [result] as text, which the caller already has because the session file keeps the same string.
     *
     * Indented, because the reader is a model reading a chain of twenty steps and every one of them matters.
     * The newlines are escaped by being inside a JSON string, so the wire stays one line.
     */
    text: String
  ): JsonObject = buildJsonObject {
    putJsonArray("content") {
      addJsonObject {
        put("type", "text")
        put("text", text)
      }
    }
    // For the clients that read it, alongside the text for the ones that don't.
    put("structuredContent", result)
  }

  private fun toolError(message: String): JsonObject = buildJsonObject {
    putJsonArray("content") {
      addJsonObject {
        put("type", "text")
        put("text", message)
      }
    }
    put("isError", true)
  }

  private fun successResponse(
    id: JsonElement,
    result: JsonObject
  ): JsonObject = buildJsonObject {
    put("jsonrpc", JSONRPC_VERSION)
    put("id", id)
    put("result", result)
  }

  private fun errorResponse(
    id: JsonElement?,
    code: Int,
    message: String
  ): JsonObject = buildJsonObject {
    put("jsonrpc", JSONRPC_VERSION)
    // Null when the id could not be read at all, which the spec asks for rather than leaving the key out.
    put("id", id ?: JsonNull)
    putJsonObject("error") {
      put("code", code)
      put("message", message)
    }
  }

  private class UnknownMethod(override val message: String) : Exception(message)

  private companion object {

    val JSON = Json {
      ignoreUnknownKeys = true
      // A client that leaves an optional argument out sends null for it often enough to matter, and a
      // tool asking for a missing argument reads the same either way.
      explicitNulls = false
    }

    val PRETTY_JSON = Json(JSON) { prettyPrint = true }

    val EMPTY_PARAMS = JsonObject(emptyMap())

    const val JSONRPC_VERSION = "2.0"

    /** What a client sees this server called, and what an agent's MCP configuration names. */
    const val SERVER_NAME = "shark-dive"

    /**
     * Answered to a client that named no version, which is not a client this has met: the field is required
     * of an initialize. The revision this was written against, so that such a client gets a real answer.
     */
    const val FALLBACK_PROTOCOL_VERSION = "2025-06-18"

    /** The one method that reaches a tool, which is what this whole surface is. */
    const val TOOLS_CALL = AgentSessionFile.TOOLS_CALL_METHOD

    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INTERNAL_ERROR = -32603

    /** What the agent said it was after, which every tool takes. See [AgentTool]. */
    const val REASON_ARGUMENT = "reason"

    /** Which window a call names, which the session log keeps as a field of its own. */
    const val WINDOW_ARGUMENT = "window"

    const val NANOS_PER_MILLI = 1_000_000L

    /**
     * The rest of the arguments, as text, for the row a session log keeps.
     *
     * Without the two that have fields of their own, and never as the JSON that arrived: what this is read
     * back for is a screen that says what an agent did in words, so a value here is one the window can put
     * beside a verb.
     */
    fun JsonObject.recorded(): Map<String, String> =
      filterKeys { it != REASON_ARGUMENT && it != WINDOW_ARGUMENT }
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
