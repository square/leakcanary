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
  private val sessionFile: AgentSessionFile
) {

  /**
   * Answers one message, or null for one that wants no answer.
   *
   * Notifications are the null case and it is not optional: JSON-RPC forbids answering a message with no
   * id, and a client that gets one back for `notifications/initialized` treats the session as broken.
   */
  suspend fun answer(line: String): String? {
    val message = try {
      JSON.parseToJsonElement(line).jsonObject
    } catch (notJson: Exception) {
      SharkLog.d(notJson) { "An agent sent something that is no JSON-RPC message" }
      return JSON.encodeToString(
        JsonElement.serializer(),
        errorResponse(id = null, code = PARSE_ERROR, message = "That is not JSON: $notJson")
      )
    }
    val id = message["id"]
    val method = (message["method"] as? JsonPrimitive)?.content
    if (method == null) {
      return JSON.encodeToString(
        JsonElement.serializer(),
        errorResponse(id, INVALID_REQUEST, "A request needs a \"method\".")
      )
    }
    // No id is a notification: nothing is waiting for an answer and sending one is a protocol error.
    if (id == null) {
      SharkLog.d { "An agent sent the notification $method" }
      return null
    }
    val response = try {
      successResponse(id, dispatch(method, message["params"]?.jsonObject ?: EMPTY_PARAMS))
    } catch (unknown: UnknownMethod) {
      errorResponse(id, METHOD_NOT_FOUND, unknown.message)
    } catch (throwable: Throwable) {
      // A read that failed or a bug of ours, either way told to the agent rather than dropped: a client
      // waiting for a response it never gets has no way to tell that from the app having gone away.
      SharkLog.d(throwable) { "An agent's $method failed" }
      errorResponse(id, INTERNAL_ERROR, throwable.toString())
    }
    return JSON.encodeToString(JsonElement.serializer(), response)
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
    "tools/call" -> callTool(params)
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

  private suspend fun callTool(params: JsonObject): JsonObject {
    val name = (params["name"] as? JsonPrimitive)?.content
      ?: return toolError("A tools/call needs the \"name\" of a tool.")
    val tool = tools.byName(name)
      ?: return toolError(
        "There is no tool called \"$name\". This server has " +
          tools.all.joinToString(", ") { it.name } + "."
      )
    val arguments = params["arguments"]?.jsonObject ?: EMPTY_PARAMS
    // One line per call, before the reads it causes, so that a session log reads as what the agent was
    // trying to learn and then what that cost. See [AgentTools].
    SharkLog.d { "An agent called $name${arguments.logLine()}" }
    // What the call is about, read before it is made rather than after: a refused call is recorded pointing
    // at whatever it was asking about, which is most of what makes a refusal worth reading afterwards.
    val target = tools.target(name, arguments)
    val at = Instant.now()
    val startedAt = System.nanoTime()
    return try {
      val answer = tool.call(arguments)
      // The answer as well as the arguments, because two of them are things the arguments don't say: what
      // was concluded, and which heap dumps were open. See [outcomeOfTool] and [openHeapDumpsOfTool].
      record(
        name,
        arguments,
        target,
        refusal = null,
        outcome = outcomeOfTool(name, answer),
        openHeapDumps = openHeapDumpsOfTool(name, answer),
        at = at,
        startedAt = startedAt
      )
      toolResult(answer)
    } catch (refused: AgentRefusal) {
      // A refusal is an answer to the agent and not a failure of the server, so it comes back as a tool
      // result the model reads rather than as a JSON-RPC error the client may swallow.
      SharkLog.d { "Refused $name: ${refused.message}" }
      record(
        name,
        arguments,
        target,
        refusal = refused.message,
        outcome = null,
        at = at,
        startedAt = startedAt
      )
      toolError(refused.message)
    }
  }

  /**
   * Writes the call down, answered or refused.
   *
   * Here rather than in [AgentTool] because this is the one place that has both halves of a call: what was
   * asked, and what came back. Every call, in the order they were made, is what turns a session into
   * something a person can follow — and the reason for each is the agent's own sentence rather than a
   * paraphrase of it.
   */
  private fun record(
    name: String,
    arguments: JsonObject,
    target: AgentTarget,
    refusal: String?,
    outcome: String?,
    openHeapDumps: List<String> = emptyList(),
    at: Instant,
    startedAt: Long
  ) {
    sessionFile.called(
      AgentSessionCall(
        at = at,
        tool = name,
        reason = (arguments[REASON_ARGUMENT] as? JsonPrimitive)?.content,
        windowId = target.windowId,
        heapDumpPath = target.heapDumpPath,
        place = target.place,
        arguments = arguments.recorded(),
        refusal = refusal,
        outcome = outcome,
        openHeapDumps = openHeapDumps,
        millis = (System.nanoTime() - startedAt) / NANOS_PER_MILLI
      )
    )
  }

  private fun toolResult(result: JsonObject): JsonObject = buildJsonObject {
    putJsonArray("content") {
      addJsonObject {
        put("type", "text")
        // Indented, because the reader is a model reading a chain of twenty steps and every one of them
        // matters. The newlines are escaped by being inside a JSON string, so the wire stays one line.
        put("text", PRETTY_JSON.encodeToString(JsonElement.serializer(), result))
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
