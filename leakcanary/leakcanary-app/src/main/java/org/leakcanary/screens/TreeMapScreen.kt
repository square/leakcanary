package org.leakcanary.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.EnumSet
import java.util.Random
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.leakcanary.WhileSubscribedOrRetained
import org.leakcanary.screens.Destination.TreeMapDestination
import org.leakcanary.screens.TreeMapState.Loading
import org.leakcanary.screens.TreeMapState.Success
import org.leakcanary.screens.TreemapLayout.NodeValue
import org.leakcanary.service.TreeMapFetcher
import shark.AndroidReferenceMatchers
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.IgnoredReferenceMatcher
import shark.ObjectDominators
import shark.ObjectDominators.OfflineDominatorNode
import shark.ValueHolder

sealed interface TreeMapState {
  object Loading : TreeMapState
  class Success(val dominators: Map<Long, OfflineDominatorNode>) : TreeMapState
}

@HiltViewModel
class TreeMapViewModel @Inject constructor(
  // TODO Add a thing that can make IPC calls
  navigator: Navigator,
  private val treeMapFetcher: TreeMapFetcher,
) : ViewModel() {

  val state =
    navigator.filterDestination<TreeMapDestination>()
      .flatMapLatest { destination ->
        stateStream(destination.heapDump)
      }.stateIn(
        viewModelScope, started = WhileSubscribedOrRetained, initialValue = Loading
      )

  private fun stateStream(heapDump: File) = flow<TreeMapState> {
    // TODO Dynamic package
    val result = withContext(Dispatchers.IO) {
      heapDump.openHeapGraph().use { heapGraph ->
        val weakAndFinalizerRefs = EnumSet.of(
          AndroidReferenceMatchers.REFERENCES, AndroidReferenceMatchers.FINALIZER_WATCHDOG_DAEMON
        )
        val ignoredRefs =
          AndroidReferenceMatchers.buildKnownReferences(weakAndFinalizerRefs).map { matcher ->
            matcher as IgnoredReferenceMatcher
          }

        ObjectDominators().buildOfflineDominatorTree(heapGraph, ignoredRefs)
      }
    }
    // val result = emptyMap<Long, OfflineDominatorNode>()
    emit(Success(result))
  }
}

@OptIn(ExperimentalTextApi::class)
@Composable fun TreeMapScreen(viewModel: TreeMapViewModel = viewModel()) {
  val stateProp by viewModel.state.collectAsState()

  when (val state = stateProp) {
    is Loading -> {
      Text("Loading...")
    }

    is Success -> {
      val dominators = state.dominators
      val textMeasure = rememberTextMeasurer()
      val root = ValueHolder.NULL_REFERENCE
      val treemapInput = DominatorNodeMapper(
        dominators = dominators,
        maxDepth = 1,
        minSize = 10000
      ).mapToTreemapInput(root)
      Treemap(treemapInput) { dominators.getValue(it).name }
    }
  }
}

class DominatorNodeMapper(
  private val dominators: Map<Long, OfflineDominatorNode>,
  private val maxDepth: Int,
  private val minSize: Int
) {

  fun mapToTreemapInput(
    objectId: Long,
    depth: Int = 0
  ): NodeValue<Long> {
    val offlineNode = dominators.getValue(objectId)
    val node = offlineNode.node
    val children = if (depth > maxDepth) {
      emptyList()
    } else {
      node.dominatedObjectIds.mapNotNull { dominatedObjectId ->
        val node = dominators.getValue(dominatedObjectId).node
        // Ignoring small nodes.
        if ((node.shallowSize + node.retainedSize) >= minSize) {
          mapToTreemapInput(dominatedObjectId, depth + 1)
        } else {
          null
        }
      }
    }
    val value = if (objectId == ValueHolder.NULL_REFERENCE) {
      // Root is a forest, retained size isn't computed.
      node.dominatedObjectIds.sumOf { dominatedObjectId ->
        val childNode = dominators.getValue(dominatedObjectId).node
        childNode.shallowSize + childNode.retainedSize
      }
    } else {
      node.shallowSize + node.retainedSize
    }
    return NodeValue(
      value = value,
      content = objectId,
      children = children
    )
  }
}

@Composable
@Preview
fun TreemapPreview() {
  val root = NodeValue(
    25,
    "Root",
    listOf(
      NodeValue(10, "A", listOf(
        NodeValue(5, "A1", emptyList()),
        NodeValue(5, "A2", emptyList())
      )),
      NodeValue(5, "B", emptyList()),
      NodeValue(5, "C", emptyList()),
      NodeValue(5, "D", emptyList()),
    )
  )
  Treemap(root, text = { it })
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun <T> Treemap(root: NodeValue<T>, text: (T) -> String) {
  // TODO Colors should be a gradient related to depth
  //  Try colors from https://observablehq.com/@d3/nested-treemap
  //  Also colors as related to the node.
  //  d3.scaleSequential([8, 0], d3.interpolateMagma)
  val colors = listOf(
    Color(169, 64, 119),
    Color(206, 88, 98),
    Color(237, 143, 106),
    Color(253, 253, 198),
  )
  val textMeasure = rememberTextMeasurer()

  Canvas(modifier = Modifier.fillMaxSize()) {

    val layout = TreemapLayout<T>(
      // TODO This isn't working
      paddingInner = { 0f },
      paddingLeft = { 32f },
      paddingTop = { 64f },
      paddingRight = { 32f },
      paddingBottom = { 32f }

    ).layout(root, size)

    layout.depthFirstTraversal { node ->
      drawRect(
        colors[node.depth],
        topLeft = node.topLeft,
        size = node.size
      )
      // TODO Figure out what's up with negative numbers
      // java.lang.IllegalArgumentException: maxHeight(-1233) must be >= than minHeight(0)
      // if (node.x0 > 0 && node.y0 > 0) {
        drawText(
          textMeasurer = textMeasure,
          text = text(node.content),
          topLeft = node.topLeft
        )
      // }
    }
  }
}

