package shark.explorer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectSummary
import shark.explorer.ReachabilityStrength
import shark.explorer.TreemapCell
import shark.explorer.TreemapLayout
import shark.explorer.TreemapNavigation
import shark.explorer.TreemapPresentation
import shark.explorer.TreemapRect
import shark.explorer.formatByteSize

/**
 * The treemap of one open heap dump, with the breadcrumbs and details panel around it.
 *
 * Every read of the heap dump goes through [session], which puts it on a thread of its own, so what's
 * drawn is state that arrives a little after whatever asked for it changed: [TreemapPresentation] is a
 * treemap already laid out and labelled somewhere else, and a selection is a summary already read.
 */
@Composable
fun HeapDumpExplorer(
  session: HeapDumpSession,
  followedStrengths: Set<ReachabilityStrength>,
  modifier: Modifier = Modifier
) {
  var navigation by remember(session) {
    mutableStateOf(TreemapNavigation(HeapDominatorTreemap.ROOT_OBJECT_ID))
  }
  var viewportSize by remember { mutableStateOf(IntSize.Zero) }
  var treemap by remember(session) { mutableStateOf(TreemapState.EMPTY) }
  var isLoading by remember(session) { mutableStateOf(true) }
  /** What the heap dump's thread is doing, when it says. */
  var loadingStep: String? by remember(session) { mutableStateOf(null) }
  var selectedObjectId: Long? by remember(session) { mutableStateOf(null) }
  var selection: Selection? by remember(session) { mutableStateOf(null) }

  val layout = rememberTreemapLayout()

  // Following a weaker strength rebuilds the whole tree, which is the slow case; resizing and zooming
  // only lay it out again. Both end up here, and both happen on the heap dump's thread.
  LaunchedEffect(session, followedStrengths, navigation, viewportSize, layout) {
    if (viewportSize == IntSize.Zero) {
      return@LaunchedEffect
    }
    isLoading = true
    val viewport = TreemapRect(
      left = 0.0,
      top = 0.0,
      right = viewportSize.width.toDouble(),
      bottom = viewportSize.height.toDouble()
    )
    treemap = session.read { explorer ->
      val tree = explorer.treeFor(followedStrengths) { step -> loadingStep = step }
      // An object zoomed into may not be a node of the tree the new strengths give, or may no longer
      // be dominated by the one above it on the path.
      val reachablePath = navigation.retainingWhere { it in tree }
      TreemapState(
        navigation = reachablePath,
        crumbs = reachablePath.path.map { objectId ->
          Crumb(objectId, "${tree.label(objectId)} · ${formatByteSize(tree.weight(objectId))}")
        },
        presentation = tree.present(layout, viewport, reachablePath.current)
      )
    }
    navigation = treemap.navigation
    loadingStep = null
    isLoading = false
  }

  LaunchedEffect(session, followedStrengths, selectedObjectId) {
    val objectId = selectedObjectId
    selection = if (objectId == null) {
      null
    } else {
      session.read { explorer ->
        val tree = explorer.treeFor(followedStrengths)
        if (objectId in tree) Selection.Object(tree.summarize(objectId)) else null
      }
    }
  }

  Column(modifier) {
    Breadcrumbs(crumbs = treemap.crumbs, onClick = { navigation = navigation.zoomInto(it) })
    Row(Modifier.weight(1f)) {
      Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewportSize = it }) {
        TreemapView(
          presentation = treemap.presentation,
          selected = selectedObjectId,
          onSelectObject = { objectId -> selectedObjectId = objectId },
          onSelectGroup = { group ->
            selectedObjectId = null
            selection = Selection.Group(group.nodeCount, group.weight)
          },
          onZoomInto = { navigation = navigation.zoomInto(it) },
          modifier = Modifier.fillMaxSize()
        )
        if (isLoading) {
          Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            CircularProgressIndicator()
            loadingStep?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
          }
        }
      }
      DetailsPanel(
        selection = selection,
        onZoomInto = { navigation = navigation.zoomInto(it) },
        modifier = Modifier.width(DETAILS_WIDTH).fillMaxHeight()
      )
    }
  }
}

/**
 * The layout, with its pixel thresholds scaled: a rectangle that's big enough to subdivide on one
 * display is too small on another.
 */
@Composable
private fun rememberTreemapLayout(): TreemapLayout<Long> {
  val density = LocalDensity.current
  return remember(density) {
    with(density) {
      TreemapLayout(
        minSubdivideWidth = MIN_SUBDIVIDE_WIDTH.toPx().toDouble(),
        minSubdivideHeight = MIN_SUBDIVIDE_HEIGHT.toPx().toDouble(),
        minDrawSize = MIN_DRAW_SIZE.toPx().toDouble(),
        headerHeight = HEADER_HEIGHT.toPx().toDouble()
      )
    }
  }
}

/** Everything read off the heap dump's thread to draw one treemap. */
private class TreemapState(
  /** The path actually laid out, which can be shorter than the one asked for. */
  val navigation: TreemapNavigation<Long>,
  val crumbs: List<Crumb>,
  val presentation: TreemapPresentation
) {
  companion object {
    val EMPTY = TreemapState(
      navigation = TreemapNavigation(HeapDominatorTreemap.ROOT_OBJECT_ID),
      crumbs = emptyList(),
      presentation = TreemapPresentation.EMPTY
    )
  }
}

private class Crumb(
  val objectId: Long,
  val label: String
)

/** What the details panel is showing. */
private sealed interface Selection {

  data class Object(val summary: HeapObjectSummary) : Selection

  /** A [TreemapCell.Group] was clicked, so there's no one object to describe. */
  data class Group(
    val nodeCount: Int,
    val byteCount: Long
  ) : Selection
}

@Composable
private fun Breadcrumbs(
  crumbs: List<Crumb>,
  onClick: (Long) -> Unit
) {
  Row(
    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    crumbs.forEachIndexed { index, crumb ->
      if (index > 0) {
        Text(BREADCRUMB_SEPARATOR, style = MaterialTheme.typography.bodyMedium)
      }
      if (index == crumbs.lastIndex) {
        Text(
          crumb.label,
          Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold
        )
      } else {
        TextButton(onClick = { onClick(crumb.objectId) }) {
          Text(crumb.label, maxLines = 1)
        }
      }
    }
  }
}

@Composable
private fun DetailsPanel(
  selection: Selection?,
  onZoomInto: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      when (selection) {
        null -> Text(NO_SELECTION, style = MaterialTheme.typography.bodyMedium)
        is Selection.Group -> {
          Text(
            "${selection.nodeCount} smaller objects",
            style = MaterialTheme.typography.titleMedium
          )
          Text(GROUP_EXPLANATION, style = MaterialTheme.typography.bodySmall)
          Detail("Retained", formatByteSize(selection.byteCount))
        }
        is Selection.Object -> ObjectDetails(selection.summary, onZoomInto)
      }
    }
  }
}

@Composable
private fun ObjectDetails(
  summary: HeapObjectSummary,
  onZoomInto: (Long) -> Unit
) {
  Text(summary.label, style = MaterialTheme.typography.titleMedium, overflow = TextOverflow.Ellipsis)
  Text(summary.className, style = MaterialTheme.typography.bodySmall)
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(Modifier.size(SWATCH_SIZE).background(legendColor(summary.strength)))
    Text(summary.strength.reachabilityText, style = MaterialTheme.typography.bodySmall)
  }
  Detail("Retained", formatByteSize(summary.retainedSize))
  Detail("Retained objects", summary.retainedCount.toString())
  Detail("Shallow", formatByteSize(summary.shallowSize.toLong()))
  Detail("Dominates", "${summary.dominatedObjectCount} objects")
  summary.stringValue?.let { Detail("Value", "\"$it\"") }
  summary.inspectorLabels.forEach { label ->
    Text(label, style = MaterialTheme.typography.bodySmall)
  }
  Button(onClick = { onZoomInto(summary.objectId) }, enabled = summary.dominatedObjectCount > 0) {
    Text("Zoom in")
  }
}

@Composable
private fun Detail(
  name: String,
  value: String
) {
  Column {
    Text(name, style = MaterialTheme.typography.labelSmall)
    Text(value, style = MaterialTheme.typography.bodyMedium)
  }
}

/** Shown by the details panel until something is selected. */
internal const val NO_SELECTION = "Click a rectangle to see what it retains."

internal const val BREADCRUMB_SEPARATOR = "›"

private const val GROUP_EXPLANATION =
  "Too small or too many to draw one by one. Zoom into what holds them to see them."

private val DETAILS_WIDTH = 320.dp
internal val SWATCH_SIZE = 10.dp
