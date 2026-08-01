package shark.explorer.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.explorer.CellContent
import shark.explorer.CellSubject
import shark.explorer.LayoutCell
import shark.explorer.PresentedCell
import shark.explorer.ReachabilityStrength.STRONG
import shark.explorer.TreemapCell
import shark.explorer.TreemapLayout
import shark.explorer.TreemapPresentation
import shark.explorer.TreemapRect
import shark.explorer.TreemapTree

/**
 * Covers the wiring between clicks on the canvas and the callbacks reporting what was clicked or pointed
 * at. The layout and the hit testing themselves are unit tested in `shark-explorer-core`.
 *
 * The view is given exactly [VIEWPORT] pixels, so each test can lay a tree out itself and click the
 * middle of a rectangle it knows the position of.
 */
@OptIn(ExperimentalTestApi::class)
class TreemapViewTest {

  /** A root with a single child, so the child fills everything below the root's header. */
  private val oneChild = mapTree(ROOT to listOf(CHILD))

  private val leafRoot = mapTree(ROOT to emptyList())

  /**
   * One rectangle filling the view, holding more children than it has room to draw one by one.
   *
   * Under a rectangle and not under the root, because the node the view is rooted at draws as many
   * children as it can fit: a pile is what a rectangle inside the view leaves out.
   */
  private val manyChildren = mapTree(ROOT to listOf(PARENT), PARENT to (1000L..1499L).toList())

  @Test fun `clicking a rectangle reports it`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val clicked = mutableListOf<LayoutCell<Long>>()
      setContent { TreemapUnderTest(presentation, onClick = { clicked += it }) }

      onRoot().performMouseInput { click(presentation.centerOf(CHILD)) }

      assertThat(clicked.map { it.node }).containsExactly(CHILD)
    }
  }

  @Test fun `clicking a header reports the parent`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val clicked = mutableListOf<LayoutCell<Long>>()
      setContent { TreemapUnderTest(presentation, onClick = { clicked += it }) }

      // The root keeps a header strip at the top for its own label, uncovered by its children.
      onRoot().performMouseInput { click(Offset(VIEWPORT.width.toFloat() / 2, 2f)) }

      assertThat(clicked.map { it.node }).containsExactly(ROOT)
    }
  }

  @Test fun `clicking a nested rectangle reports the rectangle rather than what it sits in`() {
    runComposeUiTest {
      // The window works out what to open from the node clicked, so a click on the innermost rectangle
      // reports that one and nothing of the chain above it.
      val presentation = mapTree(ROOT to listOf(PARENT), PARENT to listOf(CHILD)).present()
      val clicked = mutableListOf<LayoutCell<Long>>()
      setContent { TreemapUnderTest(presentation, onClick = { clicked += it }) }

      onRoot().performMouseInput { click(presentation.centerOf(CHILD)) }

      assertThat(clicked.map { it.node }).containsExactly(CHILD)
    }
  }

  @Test fun `clicking the name on a rectangle opens what it names rather than what is inside it`() {
    runComposeUiTest {
      // PARENT is one of the root's own children, so the map names it, and CHILD covers every pixel of it:
      // the name is drawn over CHILD, and pointing at it is how a container is reached without hunting for
      // the pixels of its edge.
      val presentation = mapTree(ROOT to listOf(PARENT), PARENT to listOf(CHILD)).present()
      val clicked = mutableListOf<LayoutCell<Long>>()
      setContent { TreemapUnderTest(presentation, onClick = { clicked += it }) }

      onRoot().performMouseInput { click(presentation.nameOf(PARENT)) }

      assertThat(clicked.map { it.node }).containsExactly(PARENT)
    }
  }

  @Test fun `moving the pointer onto the name on a rectangle reports what it names`() {
    runComposeUiTest {
      val presentation = mapTree(ROOT to listOf(PARENT), PARENT to listOf(CHILD)).present()
      val hovered = mutableListOf<LayoutCell<Long>?>()
      setContent { TreemapUnderTest(presentation, onHover = { hovered += it }) }

      onRoot().performMouseInput { hover(presentation.nameOf(PARENT)) }

      assertThat(hovered.last()?.node).isEqualTo(PARENT)
    }
  }

  @Test fun `a root without children fills the view on its own`() {
    runComposeUiTest {
      val presentation = leafRoot.present()
      val clicked = mutableListOf<LayoutCell<Long>>()
      setContent { TreemapUnderTest(presentation, onClick = { clicked += it }) }

      onRoot().performMouseInput { click(presentation.centerOf(ROOT)) }

      assertThat(clicked.map { it.node }).containsExactly(ROOT)
    }
  }

  @Test fun `clicking the rectangle standing for the siblings that did not fit reports a group`() {
    runComposeUiTest {
      // More children than a rectangle draws one by one, so the smallest ones end up in one of their
      // own. Under a rectangle rather than under the whole view, which draws all it has room for.
      val presentation = manyChildren.present()
      val clicked = mutableListOf<LayoutCell<Long>>()
      setContent { TreemapUnderTest(presentation, onClick = { clicked += it }) }

      onRoot().performMouseInput { click(presentation.centerOfGroupUnder(PARENT)) }

      val group = clicked.single().group
      assertThat(group.nodeCount).isEqualTo(300)
      assertThat(group.parent).isEqualTo(PARENT)
    }
  }

  @Test fun `each leftover rectangle is a cell of its own`() {
    runComposeUiTest {
      // Two subdivided nodes, each with more children than it draws: clicking one of the two leftover
      // rectangles has to report that one rather than every rectangle that looks like it.
      val presentation = mapTree(
        ROOT to listOf(PARENT, OTHER_PARENT),
        PARENT to (10L..14L).toList(),
        OTHER_PARENT to (20L..24L).toList()
      ).present(TreemapLayout(maxChildrenPerNode = 2))
      val clicked = mutableListOf<LayoutCell<Long>>()
      setContent { TreemapUnderTest(presentation, onClick = { clicked += it }) }

      onRoot().performMouseInput { click(presentation.centerOfGroupUnder(PARENT)) }

      val group = clicked.single().group
      assertThat(group.parent).isEqualTo(PARENT)
      assertThat(SelectedCell.of(group)).isNotEqualTo(
        SelectedCell.of(presentation.groupUnder(OTHER_PARENT).subject)
      )
      // Nor is a node ever named by its own leftover rectangle.
      assertThat(SelectedCell.of(group)).isNotEqualTo(
        SelectedCell.of(presentation.nodeCellOf(PARENT).subject)
      )
    }
  }

  @Test fun `the rectangle standing for the siblings that did not fit is drawn as dots`() {
    runComposeUiTest {
      // It can be a good part of the view, and one flat block that size reads as one enormous object,
      // which on a real heap dump means a bitmap. A texture says how many things are in there.
      val presentation = manyChildren.present()
      setContent { TreemapUnderTest(presentation) }

      val drawn = onRoot().captureToImage().toPixelMap()

      // A patch of it wide enough to hold dots whichever way the pattern falls, and clear of both the
      // dotted outline and the name written across the top. A fill on its own would be one colour.
      val rect = presentation.groupUnder(PARENT).rect
      val left = rect.left.toInt() + PATCH_INSET
      val bottom = rect.bottom.toInt() - PATCH_INSET
      val patch = (0 until PATCH_SIDE).flatMap { row ->
        (0 until PATCH_SIDE).map { column -> drawn[left + column, bottom - row] }
      }
      assertThat(patch.toSet()).hasSizeGreaterThan(1)
    }
  }

  @Test fun `a bitmap's pixels are drawn on its rectangle`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val pixels = solidImage(MAGENTA)
      setContent { TreemapUnderTest(presentation, bitmapImages = mapOf(CHILD to pixels)) }

      val drawn = onRoot().captureToImage().toPixelMap()

      // The middle of the rectangle, where an image fitted inside it always reaches.
      val center = presentation.centerOf(CHILD)
      assertThat(drawn[center.x.toInt(), center.y.toInt()]).isEqualTo(MAGENTA)
    }
  }

  @Test fun `a bitmap keeps its shape rather than filling its rectangle`() {
    runComposeUiTest {
      // A rectangle is whatever shape its share of the heap makes it, and an icon squashed into that
      // shape is not recognisable. So the image is centred, and the fill shows on either side of it.
      val presentation = leafRoot.present()
      setContent { TreemapUnderTest(presentation, bitmapImages = mapOf(ROOT to solidImage(MAGENTA))) }

      val drawn = onRoot().captureToImage().toPixelMap()

      // The view is wider than it is tall, so the image is as tall as the view and the sides are not it.
      assertThat(drawn[VIEWPORT.width.toInt() / 2, VIEWPORT.height.toInt() / 2]).isEqualTo(MAGENTA)
      assertThat(drawn[2, VIEWPORT.height.toInt() / 2]).isNotEqualTo(MAGENTA)
    }
  }

  @Test fun `moving the pointer onto a rectangle reports it as hovered`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val hovered = mutableListOf<LayoutCell<Long>?>()
      setContent { TreemapUnderTest(presentation, onHover = { hovered += it }) }

      onRoot().performMouseInput { hover(presentation.centerOf(CHILD)) }

      assertThat(hovered.last()?.node).isEqualTo(CHILD)
    }
  }

  @Test fun `moving the pointer out of the view reports nothing hovered`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val hovered = mutableListOf<LayoutCell<Long>?>()
      setContent { TreemapUnderTest(presentation, onHover = { hovered += it }) }

      onRoot().performMouseInput {
        hover(presentation.centerOf(CHILD))
        exit()
      }

      assertThat(hovered.last()).isNull()
    }
  }

  @Test fun `laying the tree out again reports what the pointer is on now`() {
    runComposeUiTest {
      // Zooming, resizing and switching shape move the rectangles rather than the pointer, and no pointer
      // event follows. Both trees are laid out in the same viewport, so the point the pointer is left on
      // belongs to the child in one and to the root in the other.
      var presentation by mutableStateOf(oneChild.present())
      val onChild = presentation.centerOf(CHILD)
      val hovered = mutableListOf<LayoutCell<Long>?>()
      setContent { TreemapUnderTest(presentation, onHover = { hovered += it }) }
      onRoot().performMouseInput { hover(onChild) }

      presentation = leafRoot.present()
      waitForIdle()

      assertThat(hovered.last()?.node).isEqualTo(ROOT)
    }
  }

  @Test fun `nodes left out for lack of room are reported`() {
    runComposeUiTest {
      // Not enough room to subdivide anything, so even the root is left as it is.
      val presentation = oneChild.present(TreemapLayout(maxCells = 2))
      setContent { TreemapUnderTest(presentation) }

      onNodeWithText("1 node not expanded").assertIsDisplayed()
    }
  }

  /** An image of one colour, so that a pixel of what was drawn says whether it was drawn. */
  private fun solidImage(color: Color): ImageBitmap {
    val bitmap = ImageBitmap(width = 2, height = 2)
    Canvas(bitmap).drawRect(
      left = 0f,
      top = 0f,
      right = 2f,
      bottom = 2f,
      paint = Paint().apply { this.color = color }
    )
    return bitmap
  }

  private fun mapTree(vararg children: Pair<Long, List<Long>>): TreemapTree<Long> {
    val childrenByNode = children.toMap()
    return object : TreemapTree<Long> {
      override val root = ROOT
      override fun weight(node: Long) = 100L
      override fun children(node: Long) = childrenByNode[node] ?: emptyList()
    }
  }

  /** What [shark.explorer.HeapDominatorTreemap.present] does, for a tree that isn't a heap dump. */
  private fun TreemapTree<Long>.present(
    layout: TreemapLayout<Long> = TreemapLayout()
  ): TreemapPresentation {
    val result = layout.layout(this, VIEWPORT)
    return TreemapPresentation(
      layout = result,
      cells = result.cells.map { cell ->
        when (val subject = cell.subject) {
          is CellSubject.Node -> PresentedCell(cell, "node ${subject.node}", CellContent.Object(STRONG))
          is CellSubject.Group -> PresentedCell(
            cell,
            "${subject.nodeCount} smaller objects",
            CellContent.Leftover(STRONG)
          )
          is CellSubject.Own -> PresentedCell(
            cell,
            "node ${subject.node}",
            CellContent.Object(STRONG)
          )
        }
      }
    )
  }

  private fun TreemapPresentation.nodeCellOf(node: Long): TreemapCell<Long> =
    layout.cells.last { (it.subject as? CellSubject.Node)?.node == node }

  private fun TreemapPresentation.groupUnder(parent: Long): TreemapCell<Long> =
    layout.cells.single { (it.subject as? CellSubject.Group)?.parent == parent }

  /** What a cell stands for, for the cells a test already knows the kind of. */
  private val LayoutCell<Long>.node: Long get() = (subject as CellSubject.Node).node
  private val LayoutCell<Long>.group: CellSubject.Group<Long>
    get() = subject as CellSubject.Group

  private fun TreemapPresentation.centerOf(node: Long): Offset = center(nodeCellOf(node).rect)

  /**
   * A point on the plate the map draws one of the root's children's name on, which is a target of its own.
   *
   * In the lettering rather than at the corner of the rectangle: the first [EDGE_GRAB] of a rectangle's
   * edge already belongs to it whatever is drawn there, so a point there would pass whether the name is a
   * target or not. Density is 1 in a UI test, so these are pixels, and the plate is a line of
   * [LABEL_STYLE] text with a couple of them around it.
   */
  private fun TreemapPresentation.nameOf(node: Long): Offset {
    val rect = nodeCellOf(node).rect
    return Offset(rect.left.toFloat() + NAME_X, rect.top.toFloat() + NAME_Y)
  }

  private fun TreemapPresentation.centerOfGroupUnder(parent: Long): Offset =
    center(groupUnder(parent).rect)

  private fun center(rect: TreemapRect) = Offset(
    ((rect.left + rect.right) / 2).toFloat(),
    ((rect.top + rect.bottom) / 2).toFloat()
  )

  companion object {
    private const val ROOT = 0L
    private const val CHILD = 1L
    private const val PARENT = 2L
    private const val OTHER_PARENT = 3L

    private val VIEWPORT = TreemapRect(left = 0.0, top = 0.0, right = 600.0, bottom = 400.0)

    /** How far into a rectangle its name is, past its edge and short of the end of the shortest label. */
    private const val NAME_X = 12f
    private const val NAME_Y = 9f

    /** A square of pixels a couple of dots across, taken well inside a rectangle's outline. */
    private const val PATCH_SIDE = 16
    private const val PATCH_INSET = 4

    /** A colour no cell is filled with, so that finding it is finding the image. */
    private val MAGENTA = Color(0xFFFF00FF)
  }
}

@Composable
private fun TreemapUnderTest(
  presentation: TreemapPresentation,
  bitmapImages: Map<Long, ImageBitmap> = emptyMap(),
  onClick: (LayoutCell<Long>) -> Unit = {},
  onHover: (LayoutCell<Long>?) -> Unit = {}
) {
  MaterialTheme {
    var selected: SelectedCell? by remember { mutableStateOf(null) }
    var hovered: SelectedCell? by remember { mutableStateOf(null) }
    val rect = presentation.layout.cells.first().rect
    val density = LocalDensity.current
    // Sized in pixels, matching the viewport the presentation was laid out in, so that a click at a
    // rectangle's coordinates lands on that rectangle.
    Box(
      Modifier.requiredSize(
        width = with(density) { rect.width.toFloat().toDp() },
        height = with(density) { rect.height.toFloat().toDp() }
      )
    ) {
      TreemapView(
        presentation = presentation,
        coloring = CellColoring.DEFAULT,
        selected = selected,
        bitmapImages = bitmapImages,
        hovered = hovered,
        onClick = {
          selected = SelectedCell.of(it.subject)
          onClick(it)
        },
        // Only which cell, since where the pointer is on it is the card's business rather than the view's.
        onHover = { pointedAt ->
          hovered = pointedAt?.let { SelectedCell.of(it.cell.subject) }
          onHover(pointedAt?.cell)
        }
      )
    }
  }
}
