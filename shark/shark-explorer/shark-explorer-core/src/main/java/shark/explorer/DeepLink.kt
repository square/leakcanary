package shark.explorer

import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.random.Random

/**
 * A link to one place in one open window: `shark://<window>/<place>?<what that place needs>`.
 *
 * The point of it is that anything on screen can be handed to someone else — or printed by a script or an
 * agent — as one line of text that puts them in front of it. So every [Place] has a spelling here, and the
 * spellings carry the whole of the place rather than a shorthand for it: a filtered list of objects arrives
 * filtered, and a page of leaks arrives with the same ones unfolded.
 *
 * **It names a window and not a heap dump.** The same dump is often open twice — that is what comparing two
 * of them is — so a path would be ambiguous exactly when it matters. A [windowId] is not ambiguous, and it
 * also settles what a link means once the window is gone: nothing, which is the honest answer, rather than
 * the same object in whichever other window happened to have that file open.
 *
 * Immutable and in this module rather than in the UI, so that what a link means is unit tested rather than
 * found out by clicking one. See [Place].
 */
data class DeepLink(
  /** Which open window answers to this link. See [newWindowId]. */
  val windowId: String,
  val place: Place
) {

  /** The link as text, which is what gets copied, printed and pasted. */
  fun toUri(): String {
    val parameters = place.linkParameters()
    val query = if (parameters.isEmpty()) {
      ""
    } else {
      "?" + parameters.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
    }
    return "$SCHEME://$windowId/${place.linkPath()}$query"
  }

  companion object {

    /**
     * Ours, and short because it is typed and pasted by hand: no application on this machine claimed it and
     * it is not one of the schemes IANA has registered.
     */
    const val SCHEME = "shark"

    /** Whether [argument] is a link rather than a heap dump path, which is all a command line has to ask. */
    fun looksLikeOne(argument: String): Boolean = argument.startsWith(PREFIX)

    /**
     * A window id: eight lowercase characters, from an alphabet with no `l`, `1`, `o` or `0` in it so that a
     * link read off a screen and typed back in is the link that was read.
     *
     * Random rather than counted up, which is not a detail. Ids handed out in order would repeat across
     * runs, so a link copied yesterday would open *something* today — the second window of this run rather
     * than the window it was made from — and be wrong without saying so. A random id is either the window it
     * names or no window at all, and the second of those is an error message.
     */
    fun newWindowId(random: Random = Random.Default): String =
      (1..WINDOW_ID_LENGTH).map { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] }.joinToString("")

    /**
     * Reads a link, or throws [IllegalArgumentException] saying what is wrong with it.
     *
     * Thrown rather than returned as a null, and worded for whoever typed it, because every one of these
     * arrives from outside the app: a command line, another run of it, or the OS handing over a URL somebody
     * pasted into a browser. Same shape as `ExplorerArguments.parse`, for the same reason.
     */
    fun parse(uri: String): DeepLink {
      require(looksLikeOne(uri)) {
        "A link starts with \"$PREFIX\", and this one is \"$uri\". ${usage()}"
      }
      val afterScheme = uri.substring(PREFIX.length)
      val query = afterScheme.substringAfter('?', "")
      val segments = afterScheme.substringBefore('?').split('/').filter { it.isNotEmpty() }
      require(segments.size == 2) {
        "A link is a window and a place, \"$PREFIX<window>/<place>\", and \"$uri\" names " +
          "${segments.size} of the two. ${usage()}"
      }
      return DeepLink(windowId = segments[0], place = placeOf(segments[1], parseQuery(query), uri))
    }

    private fun placeOf(
      path: String,
      parameters: List<Pair<String, String>>,
      uri: String
    ): Place = when (path) {
      OBJECT_PATH -> Place.Object(parameters.nodeId(ID_PARAMETER, uri))
      SMALLER_OBJECTS_PATH -> Place.SmallerObjects(
        parentObjectId = parameters.nodeId(PARENT_PARAMETER, uri),
        nodeCount = parameters.required(COUNT_PARAMETER, uri).toIntOrThrow(COUNT_PARAMETER, uri),
        byteCount = parameters.required(BYTES_PARAMETER, uri).toLongOrThrow(BYTES_PARAMETER, uri)
      )
      OBJECTS_PATH -> Place.Objects(
        ObjectListFilter(
          query = parameters.firstOrNull(QUERY_PARAMETER).orEmpty(),
          isExactMatch = parameters.firstOrNull(EXACT_PARAMETER).toBoolean(),
          // Absent means every kind, which is the filter a list opens with. Present and empty is a filter
          // matching nothing, which the checkboxes can also be put into, so the two can't be merged.
          kinds = parameters.firstOrNull(KINDS_PARAMETER)?.parseKinds(uri)
            ?: HeapObjectKind.values().toSet()
        )
      )
      LEAKS_PATH -> Place.Leaks(parameters.all(EXPANDED_PARAMETER).toSet())
      STARRED_PATH -> Place.Starred
      AGENT_LOGS_PATH -> Place.AgentLogs
      AGENT_LOG_PATH -> Place.AgentLog(parameters.required(SESSION_PARAMETER, uri))
      else -> throw IllegalArgumentException("\"$path\" is no place of \"$uri\". ${usage()}")
    }

    /**
     * What every message about a bad link ends with, so that it says what to type instead.
     *
     * A function rather than a constant because it reads a constant declared below it, and a companion
     * object's properties are initialised in the order they are written.
     */
    private fun usage(): String = "A place is one of ${PLACE_PATHS.joinToString(", ")}."

    private fun String.parseKinds(uri: String): Set<HeapObjectKind> =
      split(',').filter { it.isNotEmpty() }.map { name ->
        HeapObjectKind.values().firstOrNull { it.name == name }
          ?: throw IllegalArgumentException(
            "\"$name\" is no object kind of \"$uri\". Kinds are " +
              HeapObjectKind.values().joinToString(", ") { it.name } + "."
          )
      }.toSet()

    private fun parseQuery(query: String): List<Pair<String, String>> =
      if (query.isEmpty()) {
        emptyList()
      } else {
        query.split('&').filter { it.isNotEmpty() }.map { parameter ->
          decode(parameter.substringBefore('=')) to decode(parameter.substringAfter('=', ""))
        }
      }

    private fun List<Pair<String, String>>.firstOrNull(name: String): String? =
      firstOrNull { it.first == name }?.second

    private fun List<Pair<String, String>>.all(name: String): List<String> =
      filter { it.first == name }.map { it.second }

    private fun List<Pair<String, String>>.required(
      name: String,
      uri: String
    ): String = firstOrNull(name)
      ?: throw IllegalArgumentException("\"$uri\" needs a \"$name\" to say which place it is.")

    /**
     * A node id as a link writes one: `0x` and the whole 64 bits, unsigned.
     *
     * Not [hexObjectId], which masks an id down to its low 32 bits so that an object of a 32 bit dump reads
     * the way every other tool writes it. That is right for a label and wrong here — a link has to come back
     * as the id it was made from, and a tree's nodes run the full range of [Long]: the uncollected garbage
     * and the class piles sit at the bottom of it, and an object above the 2 GB mark of a 32 bit dump is
     * negative. See [HeapDominatorTreemap.isPileId].
     */
    private fun List<Pair<String, String>>.nodeId(
      name: String,
      uri: String
    ): Long {
      val text = required(name, uri)
      return try {
        java.lang.Long.parseUnsignedLong(text.removePrefix(HEX_PREFIX), HEX_RADIX)
      } catch (notANumber: NumberFormatException) {
        throw IllegalArgumentException(
          "\"$name\" of \"$uri\" is \"$text\", which is no object id. An object id is \"$HEX_PREFIX\" " +
            "and up to 16 hexadecimal digits."
        )
      }
    }

    private fun String.toIntOrThrow(
      name: String,
      uri: String
    ): Int = toIntOrNull()
      ?: throw IllegalArgumentException("\"$name\" of \"$uri\" is \"$this\", which is no whole number.")

    private fun String.toLongOrThrow(
      name: String,
      uri: String
    ): Long = toLongOrNull()
      ?: throw IllegalArgumentException("\"$name\" of \"$uri\" is \"$this\", which is no whole number.")

    private fun encode(value: String): String = URLEncoder.encode(value, CHARSET)

    private fun decode(value: String): String = URLDecoder.decode(value, CHARSET)

    /** Spelled as a name because the [java.nio.charset.Charset] overloads are Java 10, and this is Java 8. */
    private const val CHARSET = "UTF-8"

    private const val PREFIX = "$SCHEME://"

    private const val WINDOW_ID_LENGTH = 8

    /** No `l`, `1`, `o` or `0`: a window id is read off a screen and typed back in often enough to care. */
    private const val ID_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

    internal const val OBJECT_PATH = "object"
    internal const val SMALLER_OBJECTS_PATH = "smaller-objects"
    internal const val OBJECTS_PATH = "objects"
    internal const val LEAKS_PATH = "leaks"
    internal const val STARRED_PATH = "starred"
    internal const val AGENT_LOGS_PATH = "agent-logs"
    internal const val AGENT_LOG_PATH = "agent-log"

    private val PLACE_PATHS = listOf(
      OBJECT_PATH,
      SMALLER_OBJECTS_PATH,
      OBJECTS_PATH,
      LEAKS_PATH,
      STARRED_PATH,
      AGENT_LOGS_PATH,
      AGENT_LOG_PATH
    )

    internal const val ID_PARAMETER = "id"
    internal const val PARENT_PARAMETER = "parent"
    internal const val COUNT_PARAMETER = "count"
    internal const val BYTES_PARAMETER = "bytes"
    internal const val QUERY_PARAMETER = "query"
    internal const val EXACT_PARAMETER = "exact"
    internal const val KINDS_PARAMETER = "kinds"
    internal const val EXPANDED_PARAMETER = "expanded"
    internal const val SESSION_PARAMETER = "session"

    internal const val HEX_PREFIX = "0x"
    private const val HEX_RADIX = 16
  }
}

/** Which place this is written as. The other half of `DeepLink.placeOf`, and it has to stay so. */
private fun Place.linkPath(): String = when (this) {
  is Place.Object -> DeepLink.OBJECT_PATH
  is Place.SmallerObjects -> DeepLink.SMALLER_OBJECTS_PATH
  is Place.Objects -> DeepLink.OBJECTS_PATH
  is Place.Leaks -> DeepLink.LEAKS_PATH
  is Place.Starred -> DeepLink.STARRED_PATH
  is Place.AgentLogs -> DeepLink.AGENT_LOGS_PATH
  is Place.AgentLog -> DeepLink.AGENT_LOG_PATH
}

/**
 * Everything the place needs beyond which of the five it is.
 *
 * What is at its default is left out, so that the link to a list nobody has filtered is `…/objects` and not
 * that plus three empty answers. The sets are written in a fixed order rather than in theirs, so that one
 * tab always gives the same link — a link that changed between two copies of it would be one nobody could
 * compare, or test.
 */
private fun Place.linkParameters(): List<Pair<String, String>> = when (this) {
  is Place.Object -> listOf(DeepLink.ID_PARAMETER to linkNodeId(objectId))
  is Place.SmallerObjects -> listOf(
    DeepLink.PARENT_PARAMETER to linkNodeId(parentObjectId),
    DeepLink.COUNT_PARAMETER to nodeCount.toString(),
    DeepLink.BYTES_PARAMETER to byteCount.toString()
  )
  is Place.Objects -> buildList {
    if (filter.query.isNotEmpty()) {
      add(DeepLink.QUERY_PARAMETER to filter.query)
    }
    if (filter.isExactMatch) {
      add(DeepLink.EXACT_PARAMETER to true.toString())
    }
    if (filter.kinds != HeapObjectKind.values().toSet()) {
      add(DeepLink.KINDS_PARAMETER to filter.kinds.sortedBy { it.ordinal }.joinToString(",") { it.name })
    }
  }
  is Place.Leaks -> expandedGroups.sorted().map { DeepLink.EXPANDED_PARAMETER to it }
  is Place.Starred -> emptyList()
  is Place.AgentLogs -> emptyList()
  is Place.AgentLog -> listOf(DeepLink.SESSION_PARAMETER to sessionId)
}

/** The exact 64 bits, unsigned, which is what `DeepLink.nodeId` reads back. */
private fun linkNodeId(nodeId: Long): String =
  DeepLink.HEX_PREFIX + java.lang.Long.toUnsignedString(nodeId, 16)
