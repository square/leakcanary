package shark.dive.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * What crosses the socket, spelled in one place: a call out, and one of three things back.
 *
 * ```
 * {"tool":"list_leak_groups","arguments":{"heapDumpKey":"leak.hprof","reason":"Starting on the leaks"}}
 * {"answer":{"objectCount":…,"sections":[…]}}
 * ```
 *
 * **Both ends of it are in this module** — [AgentConnection] answers and [AgentCommandLine] asks — so the
 * keys are here rather than on either of them: a key spelled in two places is a call one end sends and the
 * other cannot read, with nothing to say which of the two is wrong.
 *
 * **Three answers and not two**, which is the one thing about this shape worth arguing for. A refusal is the
 * surface working — a tool sending an agent back to the heap dump, with the next thing to do in the
 * message — and a failure is this app not answering at all: a read that threw, a tool name nothing here has.
 * They reach a caller as different exit codes and they are different rows of the *Agent logs* screen, so a
 * wire that carried one flag for both would be a caller told it was refused by an app that fell over. See
 * [AgentCommandLine.REFUSED].
 *
 * One line each way, and JSON rather than anything terser, because the answer is Shark Dive's model: a path
 * of twenty steps with a verdict on each is what the tools hand back, and a caller has `jq`.
 */
internal object AgentWire {

  /**
   * One call: the tool to run and what to run it with.
   *
   * The arguments as the tool's own schema asks for them, since what reads them is [AgentTool] either
   * way — a command line's job is turning `name=value` into this and nothing more.
   */
  fun call(
    tool: String,
    arguments: JsonObject
  ): JsonObject = buildJsonObject {
    put(TOOL, tool)
    put(ARGUMENTS, arguments)
  }

  /** What the tool answered, which is the JSON an agent reads. */
  fun answer(answer: JsonObject): JsonObject = buildJsonObject { put(ANSWER, answer) }

  /** What a tool said no to, and why: the message is the next thing to do. See [AgentRefusal]. */
  fun refusal(message: String): JsonObject = buildJsonObject { put(REFUSED, message) }

  /** And what this app could not answer at all, which is not the same thing. */
  fun failure(message: String): JsonObject = buildJsonObject { put(FAILED, message) }

  /** The tool a call names, and null for a line this app can make no call of. */
  fun toolOf(call: JsonObject): String? = call.text(TOOL)

  /** What it named it with, and nothing for a call that named no arguments — which `reason` then refuses. */
  fun argumentsOf(call: JsonObject): JsonObject = call[ARGUMENTS] as? JsonObject ?: EMPTY

  fun answerOf(response: JsonObject): JsonObject? = response[ANSWER] as? JsonObject

  fun refusalOf(response: JsonObject): String? = response.text(REFUSED)

  fun failureOf(response: JsonObject): String? = response.text(FAILED)

  /** One line, which is what both ends read a message off. */
  fun encode(message: JsonObject): String = JSON.encodeToString(JsonElement.serializer(), message)

  /** And back, or null for a line that is no JSON object, which each end reports its own way. */
  fun decodeOrNull(line: String): JsonObject? = try {
    JSON.parseToJsonElement(line).jsonObject
  } catch (notJson: Exception) {
    null
  }

  /**
   * Indented, for whoever is reading the answer: a model working down a path of twenty steps, or a person
   * who ran the command in a terminal.
   *
   * The same text on both ends — the app writes it into the session log and the command line prints it — so
   * that a session can be compared against a transcript character for character. See
   * [AgentSessionCall.output].
   */
  fun pretty(message: JsonObject): String = PRETTY_JSON.encodeToString(JsonElement.serializer(), message)

  private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

  /** What a call names a tool, and what it names its arguments. */
  const val TOOL = "tool"
  const val ARGUMENTS = "arguments"

  private const val ANSWER = "answer"
  private const val REFUSED = "refused"
  private const val FAILED = "failed"

  private val EMPTY = JsonObject(emptyMap())

  private val JSON = Json {
    ignoreUnknownKeys = true
    // A caller that leaves an optional argument out sends null for it often enough to matter, and a tool
    // asking for a missing argument reads the same either way.
    explicitNulls = false
  }

  private val PRETTY_JSON = Json(JSON) { prettyPrint = true }
}
