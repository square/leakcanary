package shark.dive

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A link to one place in one heap dump: `shark://<heap dump>/<place>?<what that place needs>`.
 *
 * The point of it is that anything on screen can be handed to someone else — or printed by a script or an
 * agent — as one line of text that puts them in front of it. So every [Place] has a spelling here, and the
 * spellings carry the whole of the place rather than a shorthand for it: a filtered list of objects arrives
 * filtered, and a page of leaks arrives with the same ones unfolded.
 *
 * **A heap dump and a place, and nothing else.** Every place there is belongs to the dump rather than to
 * whatever is showing it — an address, a leak, a filter over the object list, a note — so a link that named a
 * window was a link that died with the window, which is most links a day later. This one survives: the run it
 * was made from can be gone, and it still opens what it names, in a window of that dump if there is one and in
 * a new window if there isn't.
 *
 * [heapDumpName] is the whole of what it says about which file, because a file name is what a person reads,
 * what a window's title shows, and what every heap dump this app takes is given something unique for.
 * **Where that file is doesn't travel in the link**: it is looked up by whoever follows one, since a path is
 * most of the characters of a link and the least readable part of it. See [HeapDumpPaths], which is what
 * remembers it, and [heapDumpPath], for the link that does say.
 *
 * Immutable and in this module rather than in the UI, so that what a link means is unit tested rather than
 * found out by clicking one. See [Place] and `DiveWindows.open`.
 */
data class DeepLink(
  /** The heap dump's file name: what a link is read as, and all of it that has to be typed. */
  val heapDumpName: String,
  val place: Place,
  /**
   * Where that dump is, for the link that says: null in every link this app writes.
   *
   * Written by hand — or by a script — about a heap dump this machine has never opened, which is the one case
   * a file name cannot answer. A link that has one is matched by path rather than by name, so it is also how
   * to say *which* `com.squareup.hprof` when two of them off two devices have been opened here.
   */
  val heapDumpPath: File? = null
) {

  /**
   * A link to a place in a heap dump this app has open, which is every link the app itself writes.
   *
   * Names it by its file name and nothing else — where the file is doesn't travel in the link, see
   * [heapDumpPath] — so this takes the [File] to save every caller writing `.name`, and to be the one
   * spelling of "a link to what this window is showing".
   */
  constructor(
    heapDumpFile: File,
    place: Place
  ) : this(
    heapDumpName = heapDumpFile.name,
    place = place
  )

  /** The link as text, which is what gets copied, printed and pasted. */
  fun toUri(): String {
    val parameters = place.linkParameters() + listOfNotNull(
      heapDumpPath?.let { DUMP_PARAMETER to it.path }
    )
    val query = if (parameters.isEmpty()) {
      ""
    } else {
      "?" + parameters.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
    }
    return "$SCHEME://${encodeSegment(heapDumpName)}/${place.linkPath()}$query"
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
     * Reads a link, or throws [IllegalArgumentException] saying what is wrong with it.
     *
     * Thrown rather than returned as a null, and worded for whoever typed it, because every one of these
     * arrives from outside the app: a command line, another run of it, or the OS handing over a URL somebody
     * pasted into a browser. Same shape as `DiveArguments.parse`, for the same reason.
     */
    fun parse(uri: String): DeepLink {
      require(looksLikeOne(uri)) {
        "A link starts with \"$PREFIX\", and this one is \"$uri\". ${usage()}"
      }
      val afterScheme = uri.substring(PREFIX.length)
      val query = afterScheme.substringAfter('?', "")
      val segments = afterScheme.substringBefore('?').split('/').filter { it.isNotEmpty() }
      require(segments.size == 2) {
        "A link is a heap dump and a place, \"$PREFIX<heap dump>/<place>\", and \"$uri\" names " +
          "${segments.size} of the two. ${usage()}"
      }
      val parameters = parseQuery(query)
      return DeepLink(
        heapDumpName = decode(segments[0]),
        place = placeOf(segments[1], parameters, uri),
        heapDumpPath = parameters.firstOrNull(DUMP_PARAMETER)?.let { File(it) }
      )
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
      REFERENCE_PATH -> Place.Reference(parameters.topic(uri))
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

    /**
     * Which page of the reference, by its file name: `?topic=leak-fingerprint`.
     *
     * By name rather than by [Enum.name], so that a link written down today still opens the same page after
     * the topics have been reordered or one has been renamed in Kotlin — and so that a link is readable.
     */
    private fun List<Pair<String, String>>.topic(uri: String): Topic {
      val page = required(TOPIC_PARAMETER, uri)
      return Topic.ofPage(page)
        ?: throw IllegalArgumentException(
          "\"$page\" is no page of the reference. Pages are " +
            Topic.values().joinToString(", ") { it.page } + "."
        )
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

    /**
     * A file name as it goes in front of the first `/`, where a `+` is a `+` rather than a space.
     *
     * [URLEncoder] writes a form field, which is the query and not this: a heap dump called `my dump.hprof`
     * would come out as `my+dump.hprof`, and every reader of a URL outside this file — the OS handing one
     * over, a terminal, a browser — reads that as a plus sign.
     */
    private fun encodeSegment(value: String): String = encode(value).replace("+", "%20")

    private fun decode(value: String): String = URLDecoder.decode(value, CHARSET)

    /** Spelled as a name because the [java.nio.charset.Charset] overloads are Java 10, and this is Java 8. */
    private const val CHARSET = "UTF-8"

    private const val PREFIX = "$SCHEME://"

    internal const val OBJECT_PATH = "object"
    internal const val SMALLER_OBJECTS_PATH = "smaller-objects"
    internal const val OBJECTS_PATH = "objects"
    internal const val LEAKS_PATH = "leaks"
    internal const val STARRED_PATH = "starred"
    internal const val REFERENCE_PATH = "reference"
    internal const val AGENT_LOGS_PATH = "agent-logs"
    internal const val AGENT_LOG_PATH = "agent-log"

    private val PLACE_PATHS = listOf(
      OBJECT_PATH,
      SMALLER_OBJECTS_PATH,
      OBJECTS_PATH,
      LEAKS_PATH,
      STARRED_PATH,
      REFERENCE_PATH,
      AGENT_LOGS_PATH,
      AGENT_LOG_PATH
    )

    /**
     * Where the heap dump is: the one parameter that is about the link rather than about the place, and the
     * one no [Place] may spell — they are read off the same query, and none of them does. `DeepLinkTest`
     * holds them apart.
     *
     * Public because a message telling somebody to add one to a link has to spell it the way this does.
     */
    const val DUMP_PARAMETER = "dump"

    internal const val ID_PARAMETER = "id"
    internal const val PARENT_PARAMETER = "parent"
    internal const val COUNT_PARAMETER = "count"
    internal const val BYTES_PARAMETER = "bytes"
    internal const val QUERY_PARAMETER = "query"
    internal const val EXACT_PARAMETER = "exact"
    internal const val KINDS_PARAMETER = "kinds"
    internal const val EXPANDED_PARAMETER = "expanded"
    internal const val TOPIC_PARAMETER = "topic"
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
  is Place.Reference -> DeepLink.REFERENCE_PATH
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
  is Place.Reference -> listOf(DeepLink.TOPIC_PARAMETER to topic.page)
  is Place.AgentLogs -> emptyList()
  is Place.AgentLog -> listOf(DeepLink.SESSION_PARAMETER to sessionId)
}

/** The exact 64 bits, unsigned, which is what `DeepLink.nodeId` reads back. */
private fun linkNodeId(nodeId: Long): String =
  DeepLink.HEX_PREFIX + java.lang.Long.toUnsignedString(nodeId, 16)
