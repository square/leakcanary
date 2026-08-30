package shark.dive.agent

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import shark.dive.exactHexObjectId
import shark.dive.objectIdOfHex

/**
 * What this server serves besides tools: the treemap as a drawing, and the page that plays one.
 *
 * **The point of it is that a person gets to look at the heap dump inside the conversation.** Everything
 * else on this surface answers a model in words, which is the right shape for a model and the wrong one for
 * a treemap — "the biggest thing is a 42 MB bitmap cache" is a sentence, and the picture is a hundred
 * rectangles with the cache filling a quarter of them. So `draw_treemap` hands back a drawing, an
 * [MCP Apps](https://github.com/modelcontextprotocol/ext-apps) host opens [APP_URI] beside the answer, and
 * the page inside it plays the drawing on a canvas.
 *
 * **A drawing rather than a picture**, which is the whole reason this is Remote Compose and not a PNG. The
 * rectangles carry click areas, so pressing one asks this server for the treemap under whatever was pressed
 * and the page plays that instead — a treemap somebody navigates, with no model in the loop, no image round
 * trip, and no token spent per step. The same reason resizing the panel redraws rather than scales: the
 * layout is done here, over the real tree, so a wider panel is more rectangles rather than fatter ones.
 *
 * **And it is a resource rather than a tool result.** [McpSession.toolResult] pretty-prints an answer into
 * the text a model reads, so a document in there would be half a megabyte of base64 in its context — for a
 * picture it cannot see. A resource is fetched by the page, which is the one party that wants the bytes.
 * Which is also what keeps a session stateless: the URI says which heap dump, which object and what size, so
 * nothing here has to remember what anybody is looking at.
 */
internal object AgentResources {

  /** The page, whose whole job is to play the drawings below. */
  const val APP_URI = "ui://shark-dive/treemap"

  /**
   * What an MCP Apps host looks for. A page served as anything else is a page it will not open, and this is
   * also the type a client says it supports under the `io.modelcontextprotocol/ui` extension.
   */
  const val APP_MIME = "text/html;profile=mcp-app"

  /**
   * Remote Compose has no registered media type, so this is a name and not a standard. What reads it is the
   * player vendored beside this file and nothing else, and a client that doesn't know it can still tell from
   * the `blob` that these are bytes rather than text.
   */
  const val DRAWING_MIME = "application/vnd.androidx.remotecompose"

  /** How the drawings are listed for a client that asks what it can read. See [TreemapDrawingUri]. */
  const val DRAWING_URI_TEMPLATE =
    "$DRAWING_SCHEME://treemap/{heapDump}/{object}?width={width}&height={height}"

  /** What `resources/list` answers with, which is the page: a drawing is a template rather than a listing. */
  fun listed(): JsonObject = buildJsonObject {
    putJsonArray("resources") {
      addJsonObject {
        put("uri", APP_URI)
        put("name", "Shark Dive treemap")
        put("description", "Where the memory has gone, as a treemap somebody can press into.")
        put("mimeType", APP_MIME)
        putJsonObject("_meta") { putJsonObject("ui") { uiMeta() } }
      }
    }
  }

  /**
   * And what `resources/templates/list` answers with, which is every drawing there could be.
   *
   * A template rather than a listing because a drawing is one per heap dump per object per canvas size, and
   * three of those four are things only the page knows. What a listing of them would be is a listing of the
   * heap dump's every object, at a size nobody asked for.
   */
  fun templates(): JsonObject = buildJsonObject {
    putJsonArray("resourceTemplates") {
      addJsonObject {
        put("uriTemplate", DRAWING_URI_TEMPLATE)
        put("name", "Treemap drawing")
        put(
          "description",
          "The treemap of one heap dump, rooted at one object, laid out at one size, as a Remote Compose " +
            "document. `heapDump` is the file name every tool here names a dump by, `object` is an " +
            "address as this surface spells one — ${exactHexObjectId(0)} for the whole heap dump — and " +
            "the size is the canvas it is going to be played on."
        )
        put("mimeType", DRAWING_MIME)
      }
    }
  }

  /** The page itself, with the player spliced into it. See `remote-compose-player.LICENSE.txt`. */
  fun appContents(): JsonObject = buildJsonObject {
    putJsonArray("contents") {
      addJsonObject {
        put("uri", APP_URI)
        put("mimeType", APP_MIME)
        put("text", appHtml())
        putJsonObject("_meta") { putJsonObject("ui") { uiMeta() } }
      }
    }
  }

  /** One drawing, as the bytes a player is handed. */
  fun drawingContents(
    uri: TreemapDrawingUri,
    drawing: TreemapDrawing
  ): JsonObject = buildJsonObject {
    putJsonArray("contents") {
      addJsonObject {
        put("uri", uri.toUri())
        put("mimeType", DRAWING_MIME)
        // A blob rather than text, since a document is bytes: base64 is what the protocol has for that.
        put("blob", Base64.getEncoder().encodeToString(drawing.document))
      }
    }
  }

  /**
   * The page and everything it needs, which is the player and no network at all.
   *
   * Read off the classpath per call rather than kept: this is the better part of a megabyte of characters,
   * and a run of Shark Dive that never has an agent connect — which is most of them — should not be holding
   * it. A page is read once per session, so there is nothing here to make faster.
   */
  private fun appHtml(): String = resourceText(APP_HTML_RESOURCE)
    .replace(PLAYER_TOKEN, resourceText(PLAYER_RESOURCE))

  private fun resourceText(name: String): String =
    checkNotNull(AgentResources::class.java.getResourceAsStream(name)) {
      "$name is missing from shark-dive-agent's resources, so there is no MCP app to serve."
    }.use { it.readBytes().toString(Charsets.UTF_8) }

  /**
   * What the host is told about the page: it reaches nothing, and it would like a border.
   *
   * Every domain empty rather than left out, which is the same thing to a host and a different thing to
   * whoever reads this: the player is spliced into the page and the drawings arrive over the connection
   * that opened it, so a page of ours asking for an origin would be a page to look at twice.
   */
  private fun JsonObjectBuilder.uiMeta() {
    putJsonObject("csp") {
      putJsonArray("connectDomains") { }
      putJsonArray("resourceDomains") { }
      putJsonArray("frameDomains") { }
      putJsonArray("baseUriDomains") { }
    }
    put("prefersBorder", true)
  }

  private const val APP_HTML_RESOURCE = "mcp-app.html"
  private const val PLAYER_RESOURCE = "remote-compose-player.js"

  /** Where the player goes in the page. Spelled the same in `mcp-app.html`, which is where it is explained. */
  private const val PLAYER_TOKEN = "/*REMOTE_COMPOSE_PLAYER*/"
}

/**
 * A drawing named the way a URI names one: which heap dump, which object, and how big.
 *
 * **Self-describing on purpose.** A page that had to be handed an id would mean a session that remembers
 * what each page is looking at, and [McpSession] holds no state — an agent that reconnects, or a second one,
 * reads what the first concluded rather than resuming a conversation. Everything a drawing needs is in the
 * URI instead, so the page builds the next one itself: press a rectangle and it swaps the object, drag the
 * panel wider and it swaps the size.
 *
 * The address is [exactHexObjectId], like every address on this surface, and `0x0` is the whole heap dump —
 * `HeapDominatorTreemap.ROOT_OBJECT_ID`, which is the null reference because nothing is above the root.
 */
internal class TreemapDrawingUri(
  /** The file name, or the window id where one dump is open twice. The same string tools take. */
  val heapDump: String,
  val rootObjectId: Long,
  val width: Int,
  val height: Int
) {

  fun toUri(): String = "$DRAWING_PREFIX${encode(heapDump)}/${exactHexObjectId(rootObjectId)}" +
    "?width=$width&height=$height"

  companion object {

    /**
     * A drawing of that size, or of the nearest size worth laying a treemap out at.
     *
     * Clamped rather than refused, because the size comes off a panel rather than out of a model: a page
     * measuring itself while the host is still animating it into place reads a width of zero, and a drawing
     * refused for that would be a blank panel that stays blank. The bottom of the range is a treemap of a
     * few rectangles and the top is more than any screen, so both ends are somewhere to land.
     */
    fun of(
      heapDump: String,
      rootObjectId: Long,
      /** A panel's worth by default, which is what a client that hasn't measured one yet is handed. */
      width: Int = DEFAULT_WIDTH,
      height: Int = DEFAULT_HEIGHT
    ) = TreemapDrawingUri(
      heapDump = heapDump,
      rootObjectId = rootObjectId,
      width = width.coerceIn(MIN_SIZE, MAX_SIZE),
      height = height.coerceIn(MIN_SIZE, MAX_SIZE)
    )

    /** Reads one back, and null for a URI that is no drawing of ours — which is `resources/read`'s answer. */
    fun parseOrNull(uri: String): TreemapDrawingUri? {
      if (!uri.startsWith(DRAWING_PREFIX)) {
        return null
      }
      val rest = uri.substring(DRAWING_PREFIX.length)
      val path = rest.substringBefore('?')
      val heapDump = decode(path.substringBeforeLast('/', missingDelimiterValue = ""))
      val rootObjectId = objectIdOfHex(path.substringAfterLast('/'))
      if (heapDump.isEmpty() || rootObjectId == null) {
        return null
      }
      val query = rest.substringAfter('?', missingDelimiterValue = "")
        .split('&')
        .mapNotNull { parameter ->
          val name = parameter.substringBefore('=')
          val value = parameter.substringAfter('=', missingDelimiterValue = "").toIntOrNull()
          value?.let { name to it }
        }
        .toMap()
      return of(
        heapDump = heapDump,
        rootObjectId = rootObjectId,
        width = query["width"] ?: DEFAULT_WIDTH,
        height = query["height"] ?: DEFAULT_HEIGHT
      )
    }

    /**
     * Escaped both ways with the same pair, so that a name round trips whatever is in it.
     *
     * Which is the only promise being made here: [URLEncoder] is a form encoder rather than a URI one, so a
     * space comes back as `+` and a slash as `%2F`. Both read back as themselves, and nothing but this reads
     * these URIs.
     */
    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8")

    private fun decode(text: String): String = URLDecoder.decode(text, "UTF-8")

    /** What a client that asked for no size gets, which is a panel's worth of treemap. */
    private const val DEFAULT_WIDTH = 960
    private const val DEFAULT_HEIGHT = 600

    private const val MIN_SIZE = 64
    private const val MAX_SIZE = 4096
  }
}

/**
 * The scheme the drawings are under, which is deliberately not `shark://`.
 *
 * `shark://` is a link somebody clicks to open a place in a window of this app, and this is a document a
 * client fetches over the connection it is already on. Two schemes because they are two things: a run that
 * handed out one of these as a link would be pointing a person at bytes.
 */
private const val DRAWING_SCHEME = "shark-dive"

private const val DRAWING_PREFIX = "$DRAWING_SCHEME://treemap/"
