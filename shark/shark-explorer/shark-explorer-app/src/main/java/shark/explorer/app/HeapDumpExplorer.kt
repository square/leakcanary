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
import shark.explorer.CellSubject
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectSummary
import shark.explorer.LayoutCell
import shark.explorer.RadialLayout
import shark.explorer.RadialPresentation
import shark.explorer.ReachabilityStrength
import shark.explorer.TreemapLayout
import shark.explorer.TreemapNavigation
import shark.explorer.TreemapPresentation
import shark.explorer.TreemapRect
import shark.explorer.formatByteSize

/**
 * The dominator tree of one open heap dump, drawn as [shape] says, with the breadcrumbs and details
 * panel around it.
 *
 * Every read of the heap dump goes through [session], which puts it on a thread of its own, so what's
 * drawn is state that arrives a little after whatever asked for it changed: a presentation is a view
 * already laid out and labelled somewhere else, and a selection is a summary already read.
 */
@Composable
internal fun HeapDumpExplorer(
  session: HeapDumpSession,
  followedStrengths: Set<ReachabilityStrength>,
  shape: ViewShape,
  scheme: CellColorScheme,
  modifier: Modifier = Modifier
) {
  var navigation by remember(session) {
    mutableStateOf(TreemapNavigation(HeapDominatorTreemap.ROOT_OBJECT_ID))
  }
  var viewportSize by remember { mutableStateOf(IntSize.Zero) }
  var view by remember(session) { mutableStateOf(ViewState.EMPTY) }
  var isLoading by remember(session) { mutableStateOf(true) }
  /** What the heap dump's thread is doing, when it says. */
  var loadingStep: String? by remember(session) { mutableStateOf(null) }
  var selected: SelectedCell? by remember(session) { mutableStateOf(null) }
  var request: SelectionRequest? by remember(session) { mutableStateOf(null) }
  var selection: Selection? by remember(session) { mutableStateOf(null) }

  val treemapLayout = rememberTreemapLayout()
  val radialLayout = rememberRadialLayout()

  // Following a weaker strength rebuilds the whole tree, which is the slow case; resizing, zooming and
  // switching shape only lay it out again. All of it ends up here, on the heap dump's thread.
  LaunchedEffect(
    session,
    followedStrengths,
    navigation,
    viewportSize,
    shape,
    treemapLayout,
    radialLayout
  ) {
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
    view = session.read { explorer ->
      val tree = explorer.treeFor(followedStrengths) { step -> loadingStep = step }
      // An object zoomed into may not be a node of the tree the new strengths give, or may no longer
      // be dominated by the one above it on the path.
      val reachablePath = navigation.retainingWhere { it in tree }
      ViewState(
        navigation = reachablePath,
        crumbs = reachablePath.path.map { objectId ->
          Crumb(objectId, "${tree.label(objectId)} · ${formatByteSize(tree.weight(objectId))}")
        },
        presentation = when (shape) {
          ViewShape.TREEMAP -> ViewPresentation.Treemap(
            tree.present(treemapLayout, viewport, reachablePath.current)
          )
          ViewShape.RADIAL -> ViewPresentation.Radial(
            tree.presentRadial(radialLayout, viewport, reachablePath.current)
          )
        }
      )
    }
    navigation = view.navigation
    loadingStep = null
    isLoading = false
  }

  // Reading what a cell stands for is a heap dump read too, so the details panel fills in a beat after
  // the click. Keyed on the request, so that nothing else clears it.
  LaunchedEffect(session, followedStrengths, request) {
    val currentRequest = request
    selection = if (currentRequest == null) {
      null
    } else {
      session.read { explorer ->
        val tree = explorer.treeFor(followedStrengths)
        when (currentRequest) {
          is SelectionRequest.Object ->
            if (currentRequest.objectId in tree) {
              Selection.Object(tree.summarize(currentRequest.objectId))
            } else {
              null
            }
          is SelectionRequest.Group -> Selection.Group(
            nodeCount = currentRequest.nodeCount,
            byteCount = currentRequest.byteCount,
            parentLabel = tree.label(currentRequest.parentObjectId)
          )
        }
      }
    }
  }

  val onSelect: (LayoutCell<Long>) -> Unit = { cell ->
    selected = SelectedCell.of(cell.subject)
    request = SelectionRequest.of(cell)
  }
  val onZoomInto: (List<Long>) -> Unit = { path -> navigation = navigation.zoomInto(path) }

  Column(modifier) {
    Breadcrumbs(crumbs = view.crumbs, onClick = { navigation = navigation.zoomInto(it) })
    Row(Modifier.weight(1f)) {
      Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewportSize = it }) {
        when (val presentation = view.presentation) {
          is ViewPresentation.Treemap -> TreemapView(
            presentation = presentation.presentation,
            scheme = scheme,
            selected = selected,
            onSelect = onSelect,
            onZoomInto = onZoomInto,
            modifier = Modifier.fillMaxSize()
          )
          is ViewPresentation.Radial -> RadialView(
            presentation = presentation.presentation,
            scheme = scheme,
            selected = selected,
            onSelect = onSelect,
            onZoomInto = onZoomInto,
            modifier = Modifier.fillMaxSize()
          )
        }
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
        scheme = scheme,
        onZoomInto = { navigation = navigation.zoomInto(it) },
        modifier = Modifier.width(DETAILS_WIDTH).fillMaxHeight()
      )
    }
  }
}

/**
 * The treemap layout, with its pixel thresholds scaled: a rectangle that's big enough to subdivide on
 * one display is too small on another.
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

/** The radial layout, scaled the same way. */
@Composable
private fun rememberRadialLayout(): RadialLayout<Long> {
  val density = LocalDensity.current
  return remember(density) {
    with(density) {
      RadialLayout(
        minSubdivideArcLength = MIN_SUBDIVIDE_ARC_LENGTH.toPx().toDouble(),
        minDrawArcLength = MIN_DRAW_ARC_LENGTH.toPx().toDouble()
      )
    }
  }
}

/** Everything read off the heap dump's thread to draw one view of the tree. */
private class ViewState(
  /** The path actually laid out, which can be shorter than the one asked for. */
  val navigation: TreemapNavigation<Long>,
  val crumbs: List<Crumb>,
  val presentation: ViewPresentation
) {
  companion object {
    /** Nothing laid out yet. An empty treemap and an empty radial view draw the same nothing. */
    val EMPTY = ViewState(
      navigation = TreemapNavigation(HeapDominatorTreemap.ROOT_OBJECT_ID),
      crumbs = emptyList(),
      presentation = ViewPresentation.Treemap(TreemapPresentation.EMPTY)
    )
  }
}

/** One [ViewShape]'s worth of laid out cells. */
private sealed interface ViewPresentation {

  data class Treemap(val presentation: TreemapPresentation) : ViewPresentation

  data class Radial(val presentation: RadialPresentation) : ViewPresentation
}

private class Crumb(
  val objectId: Long,
  val label: String
)

/** A cell the details panel has been asked about, before the heap dump has been read for it. */
private sealed interface SelectionRequest {

  data class Object(val objectId: Long) : SelectionRequest

  data class Group(
    val parentObjectId: Long,
    val nodeCount: Int,
    val byteCount: Long
  ) : SelectionRequest

  companion object {
    fun of(cell: LayoutCell<Long>): SelectionRequest = when (val subject = cell.subject) {
      is CellSubject.Node -> Object(subject.node)
      is CellSubject.Group -> Group(subject.parent, subject.nodeCount, cell.weight)
    }
  }
}

/** What the details panel is showing. */
private sealed interface Selection {

  data class Object(val summary: HeapObjectSummary) : Selection

  /** A [CellSubject.Group] was clicked, so there's no one object to describe. */
  data class Group(
    val nodeCount: Int,
    val byteCount: Long,
    val parentLabel: String
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
  scheme: CellColorScheme,
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
          Text(
            "Held by ${selection.parentLabel}. $GROUP_EXPLANATION",
            style = MaterialTheme.typography.bodySmall
          )
          Detail("Retained", formatByteSize(selection.byteCount))
        }
        is Selection.Object -> ObjectDetails(selection.summary, scheme, onZoomInto)
      }
    }
  }
}

@Composable
private fun ObjectDetails(
  summary: HeapObjectSummary,
  scheme: CellColorScheme,
  onZoomInto: (Long) -> Unit
) {
  Text(summary.label, style = MaterialTheme.typography.titleMedium, overflow = TextOverflow.Ellipsis)
  Text(summary.className, style = MaterialTheme.typography.bodySmall)
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(Modifier.size(SWATCH_SIZE).background(legendColor(scheme, summary.strength)))
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
internal const val NO_SELECTION = "Click a rectangle or a sector to see what it retains."

internal const val BREADCRUMB_SEPARATOR = "›"

private const val GROUP_EXPLANATION =
  "Too small or too many to draw one by one. Zoom into what holds them to see them."

private val DETAILS_WIDTH = 320.dp
internal val SWATCH_SIZE = 10.dp
