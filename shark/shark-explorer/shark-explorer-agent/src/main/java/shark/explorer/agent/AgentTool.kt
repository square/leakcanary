package shark.explorer.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import shark.explorer.objectIdOfHex

/**
 * One thing an agent can do, as a client of this protocol sees it: a name, what it is for, the shape of its
 * arguments, and what it answers with.
 *
 * The description is written for a model rather than for a person, which in practice means it says **when to
 * reach for this** and what the answer is worth, not what it returns — the schema says that. A tool
 * described only by its return type is one that gets called in the wrong order.
 */
internal class AgentTool(
  val name: String,
  val description: String,
  /** JSON Schema for the arguments, which is what a client validates against before calling. */
  val schema: JsonObject,
  private val handler: suspend (AgentArguments) -> JsonObject
) {

  suspend fun call(arguments: JsonObject): JsonObject {
    val read = AgentArguments(name, arguments)
    // Read before the handler and on every tool, so that a call with no reason is refused rather than
    // logged as a call whose reason was left blank. The schema asks for it and a client is free to ignore a
    // schema, so this is where "every call says why it was made" is a rule instead of a hope.
    read.reason
    return handler(read)
  }
}

/**
 * Why a call was refused, worded for the agent that made it.
 *
 * **Every one of these says what to do instead**, because a refusal is the one message an agent is certain
 * to read: it is where the method is enforced rather than described. "Not concluded, 3 steps have no
 * verdict, here they are" turns a wrong answer into the next thing to look at, and that is the whole
 * mechanism — the server refuses, so it works with any client and nothing here has to call a model back.
 *
 * Public because [AgentHeapDump] is: the app implements it, and the app has refusals of its own to make —
 * writing over a note somebody is typing in, recording a verdict into a file that hasn't been read.
 */
class AgentRefusal(override val message: String) : Exception(message)

/**
 * The arguments of one call, read the way a schema promised them.
 *
 * Reading is strict and the messages name the tool, because these arrive from a model: a number where a
 * string was asked for is something it can fix on the next call if it is told which argument of which tool,
 * and a silent default is a call that answered about something else.
 */
internal class AgentArguments(
  private val toolName: String,
  private val arguments: JsonObject
) {

  /**
   * What the agent said it was trying to learn, which every tool takes and which goes in this run's log
   * beside the reads it caused. See [AgentTools].
   */
  val reason: String get() = string(REASON)

  fun string(name: String): String {
    val value = optionalString(name)
    if (value.isNullOrBlank()) {
      throw AgentRefusal(
        "$toolName needs `$name`, and it was ${if (value == null) "not given" else "blank"}."
      )
    }
    return value
  }

  fun optionalString(name: String): String? {
    val element = arguments[name] ?: return null
    val primitive = element as? JsonPrimitive ?: throw wrongType(name, "a string", element)
    return primitive.content
  }

  fun boolean(
    name: String,
    default: Boolean
  ): Boolean {
    val text = optionalString(name) ?: return default
    return when (text.lowercase()) {
      "true" -> true
      "false" -> false
      else -> throw wrongType(name, "true or false", text)
    }
  }

  fun int(
    name: String,
    default: Int
  ): Int {
    val text = optionalString(name) ?: return default
    return text.toIntOrNull() ?: throw wrongType(name, "a whole number", text)
  }

  fun objectId(name: String): Long = objectIdOf(name, string(name))

  fun optionalObjectId(name: String): Long? = optionalString(name)?.let { objectIdOf(name, it) }

  fun stringList(name: String): List<String>? {
    val element = arguments[name] ?: return null
    val array = element as? JsonArray ?: throw wrongType(name, "a list of strings", element)
    return array.map { item ->
      (item as? JsonPrimitive)?.content ?: throw wrongType(name, "a list of strings", element)
    }
  }

  /**
   * An address as everything this surface spells one, or a refusal.
   *
   * The refusal names the decimal case, because that is the mistake worth catching: a model that has seen a
   * JSON number for an address somewhere else will write one here, and `140234878714368` is an address this
   * would otherwise have to either reject blankly or accept as something else. See [AgentJson].
   */
  fun objectIdOf(
    name: String,
    text: String
  ): Long = objectIdOfHex(text) ?: throw AgentRefusal(
    "`$name` of $toolName is \"$text\", which is no object address. An address is \"$HEX_PREFIX\" and up " +
      "to 16 hexadecimal digits, exactly as this surface writes one — never a decimal number, since a " +
      "64 bit address does not survive being one in JSON."
  )

  private fun wrongType(
    name: String,
    expected: String,
    value: Any
  ) = AgentRefusal("`$name` of $toolName has to be $expected, and it was \"$value\".")
}

/** One argument of a tool: what it is, and whether a call without it is a call at all. */
internal class AgentProperty(
  val schema: JsonObject,
  val isRequired: Boolean = true
)

internal fun AgentProperty.optional(): AgentProperty = AgentProperty(schema, isRequired = false)

internal fun string(description: String): AgentProperty = AgentProperty(
  buildJsonObject {
    put("type", "string")
    put("description", description)
  }
)

internal fun boolean(description: String): AgentProperty = AgentProperty(
  buildJsonObject {
    put("type", "boolean")
    put("description", description)
  }
)

internal fun integer(description: String): AgentProperty = AgentProperty(
  buildJsonObject {
    put("type", "integer")
    put("description", description)
  }
)

internal fun enumString(
  description: String,
  values: List<String>
): AgentProperty = AgentProperty(
  buildJsonObject {
    put("type", "string")
    put("description", description)
    putJsonArray("enum") { values.forEach { add(it) } }
  }
)

internal fun enumArray(
  description: String,
  values: List<String>
): AgentProperty = AgentProperty(
  buildJsonObject {
    put("type", "array")
    put("description", description)
    putJsonObject("items") {
      put("type", "string")
      putJsonArray("enum") { values.forEach { add(it) } }
    }
  }
)

/**
 * The schema of a tool's arguments, with `reason` added to every one of them.
 *
 * Added here rather than written out eleven times, so that there is no tool it can be forgotten on: a
 * command with no reason recorded beside it is the gap this surface exists to close. See [AgentTools].
 */
internal fun schema(vararg properties: Pair<String, AgentProperty>): JsonObject {
  val all = properties.toList() + (REASON to REASON_PROPERTY)
  return buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
      all.forEach { (name, property) -> put(name, property.schema) }
    }
    putJsonArray("required") {
      all.filter { it.second.isRequired }.forEach { add(it.first) }
    }
  }
}

private const val REASON = "reason"

private val REASON_PROPERTY = string(
  "Why you are making this call: what you are trying to learn, or what you concluded from the last " +
    "answer. Logged beside the reads it causes, which is what makes this investigation something a person " +
    "can follow afterwards rather than a conclusion they have to trust."
)

/** How every address on this surface starts. See [AgentJson]. */
internal const val HEX_PREFIX = "0x"
