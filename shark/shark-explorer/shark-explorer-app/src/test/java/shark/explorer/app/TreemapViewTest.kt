package shark.explorer.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.explorer.TreemapTree

/**
 * Covers the wiring between clicks on the canvas and the selection and zoom callbacks. The layout
 * and the hit testing themselves are unit tested in `shark-explorer-core`.
 */
@OptIn(ExperimentalTestApi::class)
class TreemapViewTest {

  /** A root with a single child, so the child fills everything below the root's header. */
  private val oneChild = mapTree(ROOT to listOf(CHILD))

  private val leafRoot = mapTree(ROOT to emptyList())

  @Test fun `pressing a rectangle selects it`() {
    runComposeUiTest {
      val selected = mutableListOf<Long>()
      setContent { TreemapUnderTest(oneChild, onSelect = { selected += it }) }

      onRoot().performMouseInput { click() }

      assertThat(selected).containsExactly(CHILD)
    }
  }

  @Test fun `pressing a header selects the parent`() {
    runComposeUiTest {
      val selected = mutableListOf<Long>()
      setContent { TreemapUnderTest(oneChild, onSelect = { selected += it }) }

      // The root keeps a header strip at least 18 dp tall for its own label, uncovered by children.
      onRoot().performMouseInput { click(Offset(centerX.toFloat(), top + 2f)) }

      assertThat(selected).containsExactly(ROOT)
    }
  }

  @Test fun `double clicking a rectangle zooms into it`() {
    runComposeUiTest {
      val zoomed = mutableListOf<Long>()
      setContent { TreemapUnderTest(oneChild, onZoomInto = { zoomed += it }) }

      onRoot().performMouseInput { doubleClick() }

      assertThat(zoomed).containsExactly(CHILD)
    }
  }

  @Test fun `a root without children fills the view on its own`() {
    runComposeUiTest {
      val selected = mutableListOf<Long>()
      setContent { TreemapUnderTest(leafRoot, onSelect = { selected += it }) }

      onRoot().performMouseInput { click() }

      assertThat(selected).containsExactly(ROOT)
    }
  }

  private fun mapTree(vararg children: Pair<Long, List<Long>>): TreemapTree<Long> {
    val childrenByNode = children.toMap()
    return object : TreemapTree<Long> {
      override val root = ROOT
      override fun weight(node: Long) = 100L
      override fun children(node: Long) = childrenByNode[node] ?: emptyList()
    }
  }

  companion object {
    private const val ROOT = 0L
    private const val CHILD = 1L
  }
}

@Composable
private fun TreemapUnderTest(
  tree: TreemapTree<Long>,
  onSelect: (Long) -> Unit = {},
  onZoomInto: (Long) -> Unit = {}
) {
  MaterialTheme {
    var selected: Long? by remember { mutableStateOf(null) }
    TreemapView(
      tree = tree,
      root = tree.root,
      labelOf = { "node $it" },
      selected = selected,
      onSelect = {
        selected = it
        onSelect(it)
      },
      onZoomInto = onZoomInto
    )
  }
}
