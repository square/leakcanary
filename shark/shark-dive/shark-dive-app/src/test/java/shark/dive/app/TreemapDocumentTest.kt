package shark.dive.app

import androidx.compose.remote.core.Operation
import androidx.compose.remote.core.RemoteComposeBuffer
import androidx.compose.remote.core.operations.ClickArea
import androidx.compose.remote.core.operations.TextData
import java.io.ByteArrayInputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.dive.HeapDive
import shark.dive.HeapDominatorTreemap
import shark.dive.Place
import shark.dive.TreemapLayout
import shark.dive.TreemapPresentation
import shark.dive.TreemapRect
import shark.dive.exactHexObjectId
import shark.dive.objectIdOfHex
import shark.dive.titleOf

/**
 * What a client is handed when an agent draws the treemap, read back out of the bytes.
 *
 * The drawing is played on a canvas in somebody else's process, so nothing about it can be asserted through
 * this app: what this does instead is inflate the document's operations, which is the list the player walks.
 * The two worth pinning there are the two the walking is built on — **every rectangle the map names carries
 * the address a press leads to**, and **the title carries the way back out** — because a drawing whose click
 * areas said something else would look right in every screenshot and take whoever pressed one somewhere else
 * entirely.
 *
 * What it is drawn *like* is `TreemapView`'s and is covered by the tests of the window. See
 * [treemapDocument], and `shark.dive.agent.AgentResources` for what serves these.
 */
class TreemapDocumentTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @get:Rule
  val log = RecordedLog()

  private val heapDump: LeakyHeapDump by lazy { temporaryFolder.leakyHeapDump() }

  private val dive: HeapDive by lazy { HeapDive.open(heapDump.file) }

  @After
  fun tearDown() {
    dive.close()
  }

  @Test
  fun `every rectangle the map names carries the address a press on it leads to`() {
    val operations = drawing(of = HeapDominatorTreemap.ROOT_OBJECT_ID)

    val pressable = operations.clickAreas()
    assertThat(pressable).isNotEmpty
    pressable.forEach { (label, leadsTo) ->
      // What a person reads is what they press, so a rectangle worth pressing is one with a name on it.
      assertThat(label).describedAs("what $leadsTo is called").isNotEmpty()
      // An address as this surface spells one, which is the whole of what a press hands back to
      // `draw_treemap`: anything else here is a rectangle that leads nowhere.
      val leadsToObjectId = objectIdOfHex(leadsTo)
      assertThat(leadsToObjectId).describedAs("where \"$label\" leads").isNotNull
      assertThat(leadsToObjectId!! in dive.tree).describedAs("$leadsTo is a node").isTrue
    }
  }

  @Test
  fun `the title says what is drawn, and is the way back up out of it`() {
    val watched = heapDump.watchedObjectId
    val title = dive.tree.titleOf(Place.Object(watched))
    val holdingIt = dive.tree.parentOf(watched)!!

    val operations = drawing(of = watched)

    // A player has no history and no chrome of ours to put a button in, so up is a click area like every
    // other one, and the title strip is what carries it.
    val (label, leadsTo) = operations.clickAreas().single { it.leadsTo == exactHexObjectId(holdingIt) }
    assertThat(label).startsWith(UP_ARROW).endsWith(title)
    assertThat(leadsTo).isNotEqualTo(exactHexObjectId(watched))
  }

  @Test
  fun `a drawing of the whole heap dump says so, and has nowhere above it to lead`() {
    val operations = drawing(of = HeapDominatorTreemap.ROOT_OBJECT_ID)

    assertThat(operations.texts()).contains(dive.tree.titleOf(Place.wholeHeapDump()))
    assertThat(operations.texts()).noneMatch { it.startsWith(UP_ARROW) }
    assertThat(operations.clickAreas().map { it.leadsTo })
      .doesNotContain(exactHexObjectId(HeapDominatorTreemap.ROOT_OBJECT_ID))
  }

  /** The operations of the drawing rooted at [of], which is what a player is handed. */
  private fun drawing(of: Long): List<Operation> {
    val tree = dive.tree
    val document = treemapDocument(
      presentation = TreemapPresentation.of(
        tree = tree,
        layout = TreemapLayout(),
        viewport = TreemapRect(0.0, DRAWING_TITLE_HEIGHT.toDouble(), WIDTH.toDouble(), HEIGHT.toDouble()),
        root = of
      ),
      title = tree.titleOf(Place.Object(of)),
      parentObjectId = tree.parentOf(of),
      coloring = CellColoring.DEFAULT,
      shading = LeakShading.NONE,
      width = WIDTH,
      height = HEIGHT
    )
    val buffer = RemoteComposeBuffer()
    ByteArrayInputStream(document).use { RemoteComposeBuffer.read(it, buffer) }
    return ArrayList<Operation>().also { buffer.inflateFromBuffer(it) }
  }

  /** One pressable rectangle: the words on it, and the address pressing it leads to. */
  private data class Pressable(
    val label: String,
    val leadsTo: String
  )

  private companion object {

    /** A canvas the size a client asks for one, which is wide enough that several cells are named. */
    const val WIDTH = 800
    const val HEIGHT = 500

    /** What a title that leads somewhere starts with, spelled here so the test doesn't share the constant. */
    const val UP_ARROW = "↑"

    /** Every string in the document, which is one pool: the labels, the title and the addresses. */
    fun List<Operation>.texts(): List<String> = filterIsInstance<TextData>().map { it.mText }

    /**
     * Each click area as what it says and where it leads.
     *
     * Both are ids into that pool, so this is a join. The metadata is read by reflection because the field
     * is package private in Remote Compose and nothing public hands it back — and it is the one thing worth
     * asserting on, being where a press ends up.
     */
    fun List<Operation>.clickAreas(): List<Pressable> {
      val texts = filterIsInstance<TextData>().associate { it.mTextId to it.mText }
      val metadataField = ClickArea::class.java.getDeclaredField("mMetadata").apply { isAccessible = true }
      return filterIsInstance<ClickArea>().map { area ->
        Pressable(
          label = texts.getValue(area.contentDescriptionId),
          leadsTo = texts.getValue(metadataField.getInt(area))
        )
      }
    }
  }
}
