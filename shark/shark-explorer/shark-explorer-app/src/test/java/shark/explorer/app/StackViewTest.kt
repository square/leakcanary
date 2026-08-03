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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollAction
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
import shark.explorer.StackCell
import shark.explorer.StackLayout
import shark.explorer.StackPresentation
import shark.explorer.TreemapRect
import shark.explorer.TreemapTree

/**
 * The same wiring [TreemapViewTest] covers, for the stack: a click reports the block under the pointer,
 * which is where the window goes, and moving over one reports it as hovered. The layout and the hit
 * testing are unit tested in `shark-explorer-core`.
 *
 * What is only testable here is the scroll, which is this shape's alone: the blocks are laid out down a
 * stack taller than the view, so where the pointer is and which block is under it are a scroll apart.
 */
@OptIn(ExperimentalTestApi::class)
class StackViewTest {

  /** A root with a single child, so the child fills the whole row under it. */
  private val oneChild = mapTree(ROOT to listOf(CHILD))

  /**
   * A tree deeper than the view is tall, which is what a stack is for and the one thing it has to scroll
   * to show: a chain of single dominators is full width at every level, so nothing else stops it growing.
   */
  private val deeperThanTheView = chain(length = 40)

  @Test fun `clicking a block reports it`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val clicked = mutableListOf<LayoutCell<Long>>()
      setContent { StackUnderTest(presentation, onClick = { clicked += it }) }

      onRoot().performMouseInput { click(presentation.middleOf(CHILD)) }

      assertThat(clicked.map { it.node }).containsExactly(CHILD)
    }
  }

  @Test fun `clicking the row across the top reports the root`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val clicked = mutableListOf<LayoutCell<Long>>()
      setContent { StackUnderTest(presentation, onClick = { clicked += it }) }

      onRoot().performMouseInput { click(presentation.middleOf(ROOT)) }

      assertThat(clicked.map { it.node }).containsExactly(ROOT)
    }
  }

  @Test fun `clicking a block two rows down reports that block`() {
    runComposeUiTest {
      // The window works out what to open from the node clicked, so a block two rows down reports
      // itself rather than the dominators drawn above it.
      val presentation = mapTree(ROOT to listOf(PARENT), PARENT to listOf(CHILD)).present()
      val clicked = mutableListOf<LayoutCell<Long>>()
      setContent { StackUnderTest(presentation, onClick = { clicked += it }) }

      onRoot().performMouseInput { click(presentation.middleOf(CHILD)) }

      assertThat(clicked.map { it.node }).containsExactly(CHILD)
    }
  }

  @Test fun `clicking the block standing for the siblings that did not fit reports a group`() {
    runComposeUiTest {
      val presentation = mapTree(ROOT to listOf(PARENT), PARENT to (1000L..1049L).toList())
        .present(StackLayout(maxChildrenPerNode = 10))
      val clicked = mutableListOf<LayoutCell<Long>>()
      setContent { StackUnderTest(presentation, onClick = { clicked += it }) }

      onRoot().performMouseInput { click(presentation.middleOfGroupUnder(PARENT)) }

      val group = clicked.single().subject as CellSubject.Group
      assertThat(group.nodeCount).isEqualTo(40)
      assertThat(group.parent).isEqualTo(PARENT)
    }
  }

  @Test fun `moving the pointer onto a block reports it as hovered`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val hovered = mutableListOf<LayoutCell<Long>?>()
      setContent { StackUnderTest(presentation, onHover = { hovered += it }) }

      onRoot().performMouseInput { hover(presentation.middleOf(CHILD)) }

      assertThat(hovered.last()?.node).isEqualTo(CHILD)
    }
  }

  @Test fun `moving the pointer out of the view reports nothing hovered`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val hovered = mutableListOf<LayoutCell<Long>?>()
      setContent { StackUnderTest(presentation, onHover = { hovered += it }) }

      onRoot().performMouseInput {
        hover(presentation.middleOf(CHILD))
        exit()
      }

      assertThat(hovered.last()).isNull()
    }
  }

  @Test fun `clicking below the last row reports nothing`() {
    runComposeUiTest {
      val presentation = oneChild.present()
      val clicked = mutableListOf<LayoutCell<Long>>()
      setContent { StackUnderTest(presentation, onClick = { clicked += it }) }

      // Two rows of a view four hundred pixels tall: the rest of it is no block of the stack.
      onRoot().performMouseInput { click(Offset(VIEWPORT_WIDTH / 2, VIEWPORT_HEIGHT - 1)) }

      assertThat(clicked).isEmpty()
    }
  }

  @Test fun `the stack scrolls when it is taller than the view`() {
    runComposeUiTest {
      setContent { StackUnderTest(deeperThanTheView.present()) }

      assertThat(verticalScrollRange().maxValue()).isGreaterThan(0f)
    }
  }

  @Test fun `a stack that fits does not scroll`() {
    runComposeUiTest {
      setContent { StackUnderTest(oneChild.present()) }

      // Two rows of a view four hundred pixels tall, so there is nothing below to scroll to. The canvas
      // being exactly as tall as the stack, rather than as tall as the view, is what this holds.
      assertThat(verticalScrollRange().maxValue()).isEqualTo(0f)
    }
  }

  @Test fun `scrolling down brings deeper rows under a pointer that has not moved`() {
    runComposeUiTest {
      val presentation = deeperThanTheView.present()
      val hovered = mutableListOf<LayoutCell<Long>?>()
      setContent { StackUnderTest(presentation, onHover = { hovered += it }) }
      val middle = Offset(VIEWPORT_WIDTH / 2, VIEWPORT_HEIGHT / 2)

      onRoot().performMouseInput { hover(middle) }
      val beforeScrolling = hovered.last()!!.depth
      val scrolled = scrollDown()

      // No pointer event says the blocks moved, so this is the view working out what it is on again from
      // the scroll alone: the block hovered is the one that was `scrolled` pixels further down the stack.
      val deeper = presentation.layout.cellAt(middle.movedDown(scrolled).toTreemapPoint())!!
      assertThat(deeper.depth).isGreaterThan(beforeScrolling)
      assertThat(hovered.last()!!.depth).isEqualTo(deeper.depth)
    }
  }

  @Test fun `nodes left out for lack of room are reported`() {
    runComposeUiTest {
      val presentation = mapTree(ROOT to listOf(PARENT), PARENT to listOf(CHILD))
        .present(StackLayout(maxCells = 3))
      setContent { StackUnderTest(presentation) }

      onNodeWithText("1 node not expanded").assertIsDisplayed()
    }
  }

  /**
   * Turns the wheel down over the stack, and answers how far it scrolled.
   *
   * How far is read back rather than worked out, because how many pixels one turn of a wheel is worth is
   * the platform's business: a headless desktop test has no AWT wheel event to read an amount off, so it
   * falls back to ten pixels a notch, which is not something to write into a test.
   */
  private fun ComposeUiTest.scrollDown(): Float {
    onRoot().performMouseInput { scroll(SCROLLED_NOTCHES) }
    waitForIdle()
    return verticalScrollRange().value()
  }

  /** Where the view is scrolled to and how far it can go, which is the only place either is readable. */
  private fun ComposeUiTest.verticalScrollRange(): ScrollAxisRange =
    onNode(hasScrollAction()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]

  private fun mapTree(vararg children: Pair<Long, List<Long>>): TreemapTree<Long> {
    val childrenByNode = children.toMap()
    return object : TreemapTree<Long> {
      override val root = ROOT
      override fun weight(node: Long) = 100L
      override fun children(node: Long) = childrenByNode[node] ?: emptyList()
    }
  }

  /** A tree that is one chain of single dominators, [length] of them below the root. */
  private fun chain(length: Int): TreemapTree<Long> = object : TreemapTree<Long> {
    override val root = ROOT
    override fun weight(node: Long) = 100L
    override fun children(node: Long) =
      if (node < length) listOf(node + 1) else emptyList()
  }

  /** What [shark.explorer.HeapDominatorTreemap.presentStack] does, for a tree that isn't a heap dump. */
  private fun TreemapTree<Long>.present(
    layout: StackLayout<Long> = StackLayout()
  ): StackPresentation {
    val result = layout.layout(this, VIEWPORT)
    return StackPresentation(
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

  private val LayoutCell<Long>.node: Long get() = (subject as CellSubject.Node).node

  private fun StackPresentation.middleOf(node: Long): Offset =
    middleOf(layout.cells.last { (it.subject as? CellSubject.Node)?.node == node })

  private fun StackPresentation.middleOfGroupUnder(parent: Long): Offset =
    middleOf(layout.cells.single { (it.subject as? CellSubject.Group)?.parent == parent })

  /** The middle of a block, in the view's coordinates, which is the stack's while it hasn't scrolled. */
  private fun middleOf(cell: StackCell<Long>): Offset = Offset(
    x = ((cell.rect.left + cell.rect.right) / 2).toFloat(),
    y = ((cell.rect.top + cell.rect.bottom) / 2).toFloat()
  )

  private fun Offset.movedDown(pixels: Float): Offset = Offset(x, y + pixels)

  companion object {
    private const val ROOT = 0L
    private const val CHILD = 1L
    private const val PARENT = 2L

    private const val VIEWPORT_WIDTH = 600f
    private const val VIEWPORT_HEIGHT = 400f

    private val VIEWPORT = TreemapRect(
      left = 0.0,
      top = 0.0,
      right = VIEWPORT_WIDTH.toDouble(),
      bottom = VIEWPORT_HEIGHT.toDouble()
    )

    /** Turns of the wheel. Enough of them to be sure of clearing a row, whatever one is worth. */
    private const val SCROLLED_NOTCHES = 10f
  }
}

@Composable
private fun StackUnderTest(
  presentation: StackPresentation,
  onClick: (LayoutCell<Long>) -> Unit = {},
  onHover: (LayoutCell<Long>?) -> Unit = {}
) {
  MaterialTheme {
    var selected: SelectedCell? by remember { mutableStateOf(null) }
    var hovered: SelectedCell? by remember { mutableStateOf(null) }
    val density = LocalDensity.current
    // Sized in pixels, matching the viewport the presentation was laid out in, so that a click at a
    // block's coordinates lands on that block.
    Box(
      Modifier.requiredSize(
        width = with(density) { 600f.toDp() },
        height = with(density) { 400f.toDp() }
      )
    ) {
      StackView(
        presentation = presentation,
        coloring = CellColoring.DEFAULT,
        selected = selected,
        hovered = hovered,
        onClick = {
          selected = SelectedCell.of(it.subject)
          onClick(it)
        },
        // Only which block, as in [TreemapViewTest]: where the pointer is on it is the card's business.
        onHover = { pointedAt ->
          hovered = pointedAt?.let { SelectedCell.of(it.cell.subject) }
          onHover(pointedAt?.cell)
        }
      )
    }
  }
}
