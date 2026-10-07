package shark.dive.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import shark.dive.objectIdOfHex

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
  /**
   * One line, for the list of every command there is — `--cli --help` — where the descriptions below would be
   * several screens.
   *
   * Written rather than taken as the first sentence of [description], because the two are read at different
   * moments and answer different questions. This one is read by somebody choosing *which* command, so it says
   * what the command is about and nothing about when to reach for it; the description is read after that choice
   * and is where the method, the warnings and the pointers at other tools belong. Deriving one from the other
   * would make the list a function of where a full stop happens to fall.
   */
  val summary: String,
  val description: String,
  /** JSON Schema for the arguments, which is what a client validates against before calling. */
  val schema: JsonObject,
  private val handler: suspend (AgentArguments) -> JsonObject
) {

  /** Every argument this tool takes, which is the same list the schema publishes. See [onlyTakes]. */
  private val takes: Set<String> = (schema[PROPERTIES] as? JsonObject)?.keys.orEmpty()

  suspend fun call(arguments: JsonObject): JsonObject {
    val read = AgentArguments(name, arguments)
    read.onlyTakes(takes)
    return handler(read)
  }
}

/**
 * Why a call was refused, worded for the agent that made it.
 *
 * **Every one of these says what to do instead**, because a refusal is the one message an agent is certain
 * to read: it is where the method is enforced rather than described. "That object has no verdict, and here
 * is what the heap dump says about it" turns a wrong answer into the next thing to look at, and that is the
 * whole mechanism — the server refuses, so it works with any client and nothing here has to call a model back.
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
   *
   * **Read by the tools that use it, rather than by every call.** [AgentTool.call] used to read it first on
   * all of them, so that a missing reason was refused whoever had called — and what that cost is the person
   * typing one command: `--cli list_heap_dumps` is a question with no sentence to write under it, and being
   * refused for not writing one teaches nobody anything. The rule is for the reader a session log has, which
   * is somebody reviewing an agent, and an agent is also the caller that names its own session — so
   * [AgentCommandLine.SESSION_OPTION] and this are asked for as a pair, in the one place that knows both.
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

  /**
   * Refuses an argument this tool doesn't take, naming the ones it does.
   *
   * Enforced here for the same reason `reason` is, and it matters more: an argument nobody reads is a call
   * that answered about something else. Measured on a real dump — `find_objects` given `query`, which is
   * what the window's own search box is called, matched nothing in particular and answered with the 30
   * biggest objects out of 86,056, which reads exactly like an answer to the question asked.
   */
  fun onlyTakes(names: Set<String>) {
    val unknown = (arguments.keys - names).sorted()
    if (unknown.isNotEmpty()) {
      throw AgentRefusal(
        "$toolName does not take ${unknown.joinToString { "`$it`" }}. It takes " +
          "${names.sorted().joinToString { "`$it`" }}, and nothing else."
      )
    }
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
      "to 16 hexadecimal digits, the way this surface writes one. Never a decimal number: a 64 bit " +
      "address loses precision as a JSON number." + lookItUpInstead(text)
  )

  private fun wrongType(
    name: String,
    expected: String,
    value: Any
  ) = AgentRefusal("`$name` of $toolName has to be $expected, and it was \"$value\".")
}

/**
 * The call that turns a class name into the address this wanted, for the one wrong value that has one.
 *
 * **A class is an object of the heap dump like any other**, with an address and fields — its static ones —
 * so `describe_object` on `android.os.Build${'$'}VERSION` is exactly the right thing to want, and the only thing
 * missing is the lookup. Which is why this is here rather than in a tool: told only that its class name is no
 * address, an agent has to guess that a second tool is what turns one into the other, and the method
 * `list_leak_groups` answers with sent it here in the first place. See [AgentMethod].
 *
 * A name with a dot or a `${'$'}` in it, since those are the two things a class name has that nothing else sent
 * here does. A bare `Bitmap` gets the plain refusal: it is as likely to be a typo as a class.
 */
private fun lookItUpInstead(text: String): String {
  val looksLikeAClassName = text.firstOrNull()?.isLetter() == true && text.any { it == '.' || it == '$' }
  if (!looksLikeAClassName) {
    return ""
  }
  return " \"$text\" reads as a class name, and a class is an object of this heap dump with an address of " +
    "its own: `find_objects` with `className=$text`, `exactMatch=true` and `kinds=CLASS` answers with that " +
    "address, and describing it reads the class's static fields."
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
 * Added here rather than written out once per tool, so that there is no tool it can be forgotten on: a
 * command with no reason recorded beside it is the gap this surface exists to close. See [AgentTools].
 *
 * **The same sentence on every tool**, which it was not while `conclude` was here: that one took the root
 * cause it was reporting under this name, so its description had to say something else. Nothing on this
 * surface asks for a finding any more — what an investigation comes to is derived from the verdicts it
 * recorded — so there is one description of `reason` and it is [REASON_PROPERTY].
 */
internal fun schema(vararg properties: Pair<String, AgentProperty>): JsonObject {
  val all = properties.toList() + (REASON to REASON_PROPERTY)
  return buildJsonObject {
    put("type", "object")
    putJsonObject(PROPERTIES) {
      all.forEach { (name, property) -> put(name, property.schema) }
    }
    putJsonArray("required") {
      all.filter { it.second.isRequired }.forEach { add(it.first) }
    }
    // Said to the client as well as enforced in [AgentTool.call], since a client that validates is one that
    // can catch this before the call rather than after it.
    put("additionalProperties", false)
  }
}

private const val PROPERTIES = "properties"

/** Why a call was made, on every tool. One name for one thing — see [schema]. */
internal const val REASON = "reason"

private val REASON_PROPERTY = string(
  "Why you are making this call: what you are trying to learn, or what you concluded from the last " +
    "answer. It goes in the log beside the reads it causes, so that somebody can follow the " +
    "investigation afterwards. Required on a command line that names its session, which is every agent's."
)

/** How every address on this surface starts. See [AgentJson]. */
internal const val HEX_PREFIX = "0x"

/**
 * What every tool calls the object it is about, out here beside the spelling of one.
 *
 * Top level rather than a constant of [AgentTools] because a refusal about a place has to be able to name it:
 * `show` takes an object like the rest of the surface, so the sentence that sends a screen to `place` instead
 * is written where the places are. See [showItInstead].
 */
internal const val OBJECT = "object"
