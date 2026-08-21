package shark.explorer.agent

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
 * MCP rather than a command line or a protocol of ours, for one reason: **the app is already running and an
 * agent has to reach into it**. A CLI would have to open the heap dump again — seconds and hundreds of
 * megabytes per question, and answers about a dump nobody is looking at — while MCP is the one interface
 * every agent already has, so a client is configured once and nothing here ever calls a model.
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
  private val serverVersion: String
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
    val clientName = (params["clientInfo"] as? JsonObject)
      ?.let { (it["name"] as? JsonPrimitive)?.content }
    SharkLog.d { "An agent connected: ${clientName ?: "a client that did not say who it is"}" }
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
    return try {
      toolResult(tool.call(arguments))
    } catch (refused: AgentRefusal) {
      // A refusal is an answer to the agent and not a failure of the server, so it comes back as a tool
      // result the model reads rather than as a JSON-RPC error the client may swallow.
      SharkLog.d { "Refused $name: ${refused.message}" }
      toolError(refused.message)
    }
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
    const val SERVER_NAME = "shark-explorer"

    /**
     * Answered to a client that named no version, which is not a client this has met: the field is required
     * of an initialize. The revision this was written against, so that such a client gets a real answer.
     */
    const val FALLBACK_PROTOCOL_VERSION = "2025-06-18"

    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INTERNAL_ERROR = -32603

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
      val reason = (this["reason"] as? JsonPrimitive)?.content
      val rest = entries.filter { it.key != "reason" }
        .joinToString(", ") { (key, value) -> "$key=${(value as? JsonPrimitive)?.content ?: value}" }
      return listOfNotNull(
        rest.takeIf { it.isNotEmpty() }?.let { "($it)" },
        reason?.let { " because: $it" }
      ).joinToString("")
    }
  }
}
