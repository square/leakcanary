package shark.explorer.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseInjectionScope
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.v2.runSkikoComposeUiTest

/**
 * Runs [block] against a window the size one opens at — [WINDOW_WIDTH] by [WINDOW_HEIGHT].
 *
 * A Compose UI test defaults to a good deal less than that, and this window is three columns wide: the view,
 * the chain of objects holding what it's pointing at, and the details panel. At the default size the two
 * panes leave the view narrow enough that the controls above it are squeezed to nothing, so a test would be
 * pressing a window no user has. Density is 1 in a UI test, so a dp here is a pixel.
 */
@OptIn(ExperimentalTestApi::class)
internal fun explorerUiTest(block: ComposeUiTest.() -> Unit) {
  runSkikoComposeUiTest(size = Size(width = WINDOW_WIDTH.value, height = WINDOW_HEIGHT.value)) {
    block()
  }
}

/**
 * Waits until the window has a heap dump open with its tree drawn, which is where a test of it starts.
 *
 * By the view being there with nothing left spinning rather than by finding the root of the tree by name:
 * the whole view is one canvas, so a drawn map adds no text to the window for a test to wait for. Opening
 * the dump spins in the middle of the window and laying the tree out spins over the view, so a window with
 * a view and neither spinner has finished both.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.waitForTheTree(timeoutMillis: Long) {
  waitUntil(timeoutMillis = timeoutMillis) {
    onAllNodesWithContentDescription(VIEW_DESCRIPTION).fetchSemanticsNodes().isNotEmpty() &&
      onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
        .fetchSemanticsNodes()
        .isEmpty()
  }
}

/**
 * Moves the pointer onto [offset] the way a mouse gets anywhere: from a pixel away.
 *
 * The first move a view is sent is an enter rather than a move, and the views only describe what the
 * pointer *moved* onto, so that clicking a row of a list describes the object clicked rather than
 * whatever the map then puts under a pointer that hasn't moved. See [TreemapView]. So a hover a view
 * reacts to is two moves, both of them inside it.
 */
internal fun MouseInjectionScope.hover(offset: Offset) {
  moveTo(offset + Offset(1f, 1f))
  moveTo(offset)
}
