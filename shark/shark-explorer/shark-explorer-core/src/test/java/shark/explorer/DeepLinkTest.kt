package shark.explorer

import java.io.File
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class DeepLinkTest {

  @Test
  fun `link to an object names the heap dump and the object`() {
    val link = DeepLink("leak.hprof", Place.Object(0x12ab34cd))

    assertThat(link.toUri()).isEqualTo("shark://leak.hprof/object?id=0x12ab34cd")
  }

  @Test
  fun `link to an object is read back as the object`() {
    assertThat(DeepLink.parse("shark://leak.hprof/object?id=0x12ab34cd"))
      .isEqualTo(DeepLink("leak.hprof", Place.Object(0x12ab34cd)))
  }

  /**
   * The whole heap dump is [HeapDominatorTreemap.ROOT_OBJECT_ID], which is no address, and the uncollected
   * garbage and the class piles are at the very bottom of [Long]. A link to any of them is a link to a tab
   * somebody has open, so all of them have to survive the trip.
   */
  @Test
  fun `every node id survives being written and read back`() {
    val nodeIds = listOf(
      HeapDominatorTreemap.ROOT_OBJECT_ID,
      HeapDominatorTreemap.UNREACHABLE_NODE_ID,
      HeapDominatorTreemap.UNREACHABLE_NODE_ID + 1,
      Long.MAX_VALUE,
      -1L,
      // An object above the 2 GB mark of a 32 bit heap dump, which shark widens by sign.
      Int.MIN_VALUE.toLong(),
      -2112345088L
    )

    nodeIds.forEach { nodeId ->
      val link = DeepLink("leak.hprof", Place.Object(nodeId))
      assertThat(DeepLink.parse(link.toUri()).place)
        .describedAs(link.toUri())
        .isEqualTo(Place.Object(nodeId))
    }
  }

  /**
   * [hexObjectId] masks an id down to its low 32 bits so a label reads the way other tools write an address.
   * A link cannot do that: two different nodes would come back as one.
   */
  @Test
  fun `a negative object id is not written the way a label writes it`() {
    val link = DeepLink("leak.hprof", Place.Object(-2112345088L)).toUri()

    assertThat(link).isEqualTo("shark://leak.hprof/object?id=0xffffffff82182c00")
    // Which is 0x82182c00 on a label, and that is a different node of the tree.
    assertThat(link).doesNotContain(hexObjectId(-2112345088L))
  }

  @Test
  fun `link to a pile of smaller objects carries what the pile is`() {
    val place = Place.SmallerObjects(parentObjectId = 0x40, nodeCount = 42, byteCount = 9876)

    assertThat(DeepLink("leak.hprof", place).toUri())
      .isEqualTo("shark://leak.hprof/smaller-objects?parent=0x40&count=42&bytes=9876")
    assertThat(DeepLink.parse(DeepLink("leak.hprof", place).toUri()).place).isEqualTo(place)
  }

  @Test
  fun `link to an unfiltered object list is the list and nothing else`() {
    assertThat(DeepLink("leak.hprof", Place.Objects()).toUri()).isEqualTo("shark://leak.hprof/objects")
  }

  @Test
  fun `an object list arrives filtered the way it was left`() {
    val place = Place.Objects(
      ObjectListFilter(
        query = "android.graphics.Bitmap",
        isExactMatch = true,
        kinds = setOf(HeapObjectKind.INSTANCE, HeapObjectKind.CLASS)
      )
    )

    val uri = DeepLink("leak.hprof", place).toUri()

    assertThat(uri).isEqualTo(
      "shark://leak.hprof/objects?query=android.graphics.Bitmap&exact=true&kinds=CLASS%2CINSTANCE"
    )
    assertThat(DeepLink.parse(uri).place).isEqualTo(place)
  }

  /** Unchecking every kind is a list showing nothing, which is a state the checkboxes can be left in. */
  @Test
  fun `an object list filtered to no kind at all is not an unfiltered one`() {
    val place = Place.Objects(ObjectListFilter(kinds = emptySet()))

    val uri = DeepLink("leak.hprof", place).toUri()

    assertThat(uri).isEqualTo("shark://leak.hprof/objects?kinds=")
    assertThat(DeepLink.parse(uri).place).isEqualTo(place)
  }

  /** A query is typed by hand, so it holds spaces, `&` and every other character a URL is built from. */
  @Test
  fun `a query full of the characters a URL is made of survives`() {
    val query = "com.example a&b=c?d/e#f+g%h"
    val place = Place.Objects(ObjectListFilter(query = query))

    assertThat(DeepLink.parse(DeepLink("leak.hprof", place).toUri()).place).isEqualTo(place)
  }

  @Test
  fun `leaks arrive with the same ones unfolded`() {
    val place = Place.Leaks(expandedGroups = setOf("APPLICATION 12ab", "LIBRARY 34cd"))

    val uri = DeepLink("leak.hprof", place).toUri()

    assertThat(uri)
      .isEqualTo("shark://leak.hprof/leaks?expanded=APPLICATION+12ab&expanded=LIBRARY+34cd")
    assertThat(DeepLink.parse(uri).place).isEqualTo(place)
  }

  @Test
  fun `leaks with nothing unfolded is the page and nothing else`() {
    assertThat(DeepLink("leak.hprof", Place.Leaks()).toUri()).isEqualTo("shark://leak.hprof/leaks")
    assertThat(DeepLink.parse("shark://leak.hprof/leaks").place).isEqualTo(Place.Leaks())
  }

  @Test
  fun `starred is a place with nothing to say about it`() {
    assertThat(DeepLink("leak.hprof", Place.Starred).toUri()).isEqualTo("shark://leak.hprof/starred")
    assertThat(DeepLink.parse("shark://leak.hprof/starred").place).isEqualTo(Place.Starred)
  }

  /**
   * Which is what an agent's own session is handed over by: the human it is working for gets a link to what
   * it did, rather than a path to a file and instructions for finding the row.
   */
  @Test
  fun `one agent's session is named by the session`() {
    val place = Place.AgentLog("1a2b3c4d")

    assertThat(DeepLink("leak.hprof", place).toUri())
      .isEqualTo("shark://leak.hprof/agent-log?session=1a2b3c4d")
    assertThat(DeepLink.parse("shark://leak.hprof/agent-log?session=1a2b3c4d").place).isEqualTo(place)
  }

  @Test
  fun `an agent log link with no session says what is missing`() {
    assertThatThrownBy { DeepLink.parse("shark://leak.hprof/agent-log") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("needs a \"session\"")
  }

  /**
   * The one that has to keep being true as places are added: every [Place] is reachable by link, which is
   * the whole promise. A place with no spelling here fails this rather than being found out by clicking one.
   */
  @Test
  fun `every place survives being written and read back`() {
    val places = listOf(
      Place.wholeHeapDump(),
      Place.Object(0x12ab34cd),
      Place.SmallerObjects(parentObjectId = 0x40, nodeCount = 42, byteCount = 9876),
      Place.Objects(),
      Place.Objects(ObjectListFilter(query = "Bitmap", isExactMatch = true)),
      Place.Leaks(),
      Place.Leaks(expandedGroups = setOf("APPLICATION 12ab")),
      Place.Starred,
      Place.AgentLogs,
      Place.AgentLog("1a2b3c4d")
    )

    places.forEach { place ->
      val uri = DeepLink("leak.hprof", place).toUri()
      assertThat(DeepLink.parse(uri).place).describedAs(uri).isEqualTo(place)
    }
  }

  /** Two copies of one tab's link are compared and pasted next to each other, so they have to be one string. */
  @Test
  fun `a place is always written the same way`() {
    val place = Place.Leaks(expandedGroups = setOf("LIBRARY 34cd", "APPLICATION 12ab"))
    val sameOtherWayRound = Place.Leaks(expandedGroups = setOf("APPLICATION 12ab", "LIBRARY 34cd"))

    assertThat(DeepLink("leak.hprof", place).toUri())
      .isEqualTo(DeepLink("leak.hprof", sameOtherWayRound).toUri())
  }

  @Test
  fun `something that is not a link says so`() {
    assertThatThrownBy { DeepLink.parse("https://example.com/object?id=0x1") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("starts with \"shark://\"")
  }

  @Test
  fun `a link naming no place says which places there are`() {
    assertThatThrownBy { DeepLink.parse("shark://leak.hprof") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("object, smaller-objects, objects, leaks, starred")
  }

  @Test
  fun `a link to a place this app has no screen for says so`() {
    assertThatThrownBy { DeepLink.parse("shark://leak.hprof/dominators") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("\"dominators\" is no place")
  }

  @Test
  fun `an object link with no object says what is missing`() {
    assertThatThrownBy { DeepLink.parse("shark://leak.hprof/object") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("needs a \"id\"")
  }

  @Test
  fun `an object id that is not one says so`() {
    assertThatThrownBy { DeepLink.parse("shark://leak.hprof/object?id=the+big+one") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("which is no object id")
  }

  @Test
  fun `an object kind this app has never heard of says which kinds there are`() {
    assertThatThrownBy { DeepLink.parse("shark://leak.hprof/objects?kinds=WIDGETS") }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("Kinds are CLASS, INSTANCE, OBJECT_ARRAY, PRIMITIVE_ARRAY")
  }

  @Test
  fun `a window id is eight characters of an alphabet nothing can be misread in`() {
    val ids = (1..200).map { DeepLink.newWindowId(Random(it)) }

    assertThat(ids).allMatch { it.length == 8 }
    assertThat(ids.joinToString("")).matches("[abcdefghijkmnpqrstuvwxyz23456789]+")
  }

  @Test
  fun `two windows do not get one id`() {
    val ids = (1..1000).map { DeepLink.newWindowId() }

    assertThat(ids.toSet()).hasSize(ids.size)
  }

  /**
   * The link the app itself writes, and the whole of what it is for: a heap dump, a place in it, where that
   * dump is, and which window it was copied from.
   */
  @Test
  fun `a link from a window carries the dump, the file and the window`() {
    val link = DeepLink(File("/dumps/leak.hprof"), Place.Leaks(), windowId = "abcd2345")

    assertThat(link.toUri())
      .isEqualTo("shark://leak.hprof/leaks?dump=%2Fdumps%2Fleak.hprof&window=abcd2345")
    assertThat(DeepLink.parse(link.toUri())).isEqualTo(link)
  }

  /** Because a link is read out of a heap dump's notes months later, from a machine with another home. */
  @Test
  fun `the path in a link is absolute and has no dots in it`() {
    val link = DeepLink(File("dumps/./over/../leak.hprof"), Place.Starred)

    assertThat(link.heapDumpPath).isEqualTo(File(File("").absoluteFile, "dumps/leak.hprof"))
  }

  /**
   * Which is a link somebody typed, or shortened by hand to the two things worth reading. It resolves
   * against whatever is open, so it is worth being able to write. See `ExplorerWindows.windowFor`.
   */
  @Test
  fun `a link is a heap dump and a place and needs nothing else`() {
    val link = DeepLink.parse("shark://leak.hprof/leaks")

    assertThat(link.heapDumpName).isEqualTo("leak.hprof")
    assertThat(link.place).isEqualTo(Place.Leaks())
    assertThat(link.heapDumpPath).isNull()
    assertThat(link.windowId).isNull()
  }

  /**
   * `+` is a space to [java.net.URLEncoder], which writes a form field rather than the part of a URL in
   * front of the first `/` — so a dump with a space in its name has to be written the other way, or every
   * reader of the link outside this class reads a plus sign.
   */
  @Test
  fun `a heap dump whose name needs escaping survives the trip`() {
    val link = DeepLink(File("/dumps/my dump (2).hprof"), Place.Starred)

    assertThat(link.toUri()).startsWith("shark://my%20dump%20%282%29.hprof/starred")
    assertThat(DeepLink.parse(link.toUri())).isEqualTo(link)
  }

  /**
   * The two parameters that are about the link rather than about the place share the query with the ones that
   * are, so a [Place] spelling a parameter `dump` or `window` would quietly take one of them over. This is
   * what would fail.
   */
  @Test
  fun `no place takes the dump or the window off a link`() {
    val places = listOf(
      Place.wholeHeapDump(),
      Place.Object(0x12ab34cd),
      Place.SmallerObjects(parentObjectId = 0x40, nodeCount = 42, byteCount = 9876),
      Place.Objects(),
      Place.Objects(ObjectListFilter(query = "Bitmap", isExactMatch = true, kinds = emptySet())),
      Place.Leaks(),
      Place.Leaks(expandedGroups = setOf("APPLICATION 12ab", "LIBRARY 34cd")),
      Place.Starred,
      Place.AgentLogs,
      Place.AgentLog("1a2b3c4d")
    )

    places.forEach { place ->
      val link = DeepLink(File("/dumps/leak.hprof"), place, windowId = "abcd2345")
      assertThat(DeepLink.parse(link.toUri())).describedAs(link.toUri()).isEqualTo(link)
    }
  }

  @Test
  fun `a link tells itself apart from a heap dump path`() {
    assertThat(DeepLink.looksLikeOne("shark://leak.hprof/starred")).isTrue()
    assertThat(DeepLink.looksLikeOne("/Users/me/dumps/shark.hprof")).isFalse()
    assertThat(DeepLink.looksLikeOne("--title=Reading a leak")).isFalse()
  }
}
