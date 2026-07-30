package shark.explorer.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import shark.explorer.DominatorKind
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectSummary
import shark.explorer.IndependentPaths
import shark.explorer.LayoutCell
import shark.explorer.NavigationHistory
import shark.explorer.ObjectDominator
import shark.explorer.ObjectGroupKind
import shark.explorer.ObjectGroupSummary
import shark.explorer.PathStep
import shark.explorer.RadialLayout
import shark.explorer.RadialPresentation
import shark.explorer.TreemapLayout
import shark.explorer.TreemapNavigation
import shark.explorer.TreemapPresentation
import shark.explorer.TreemapRect
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount

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
  shape: ViewShape,
  coloring: CellColoring,
  modifier: Modifier = Modifier
) {
  var history by remember(session) {
    mutableStateOf(NavigationHistory(TreemapNavigation(HeapDominatorTreemap.ROOT_OBJECT_ID)))
  }
  val navigation = history.current
  var viewportSize by remember { mutableStateOf(IntSize.Zero) }
  var view by remember(session) { mutableStateOf(ViewState.EMPTY) }
  var isLoading by remember(session) { mutableStateOf(true) }
  /** What the heap dump's thread is doing, when it says. */
  var loadingStep: String? by remember(session) { mutableStateOf(null) }
  var selected: SelectedCell? by remember(session) { mutableStateOf(null) }
  var request: SelectionRequest? by remember(session) { mutableStateOf(null) }
  var selection: Selection? by remember(session) { mutableStateOf(null) }
  /** Null while the tree is still being asked, which takes no time at all. */
  var dominator: ObjectDominator? by remember(session) { mutableStateOf(null) }
  /** Null while the search for the paths is still running. */
  var paths: IndependentPaths? by remember(session) { mutableStateOf(null) }
  /** The objects starred so far, with everything the list shows about them read once. */
  var favourites by remember(session) { mutableStateOf(emptyList<Favourite>()) }
  var isShowingFavourites by remember(session) { mutableStateOf(false) }
  /** A node the panel asked to be shown, until the path the treemap has it under is worked out. */
  var nodeToOpen: Long? by remember(session) { mutableStateOf(null) }

  val treemapLayout = rememberTreemapLayout()
  val radialLayout = rememberRadialLayout()

  // Resizing, zooming and switching shape all lay the tree out again, which reads the heap dump for
  // every visible label. All of it ends up here, on the heap dump's thread.
  LaunchedEffect(
    session,
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
      val tree = explorer.tree
      // An object zoomed into may no longer be dominated by the one above it on the path.
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
    // Settling for the path that could actually be shown isn't a move, so it replaces where the view is
    // rather than being somewhere the back arrow returns to.
    history = history.replacingCurrent(view.navigation)
    loadingStep = null
    isLoading = false
  }

  // Reading what a cell stands for is a heap dump read too, so the details panel fills in a beat after
  // the click. Keyed on the request, so that nothing else clears it.
  LaunchedEffect(session, request) {
    val currentRequest = request
    dominator = null
    paths = null
    selection = if (currentRequest == null) {
      null
    } else {
      session.read { explorer ->
        val tree = explorer.tree
        when (currentRequest) {
          is SelectionRequest.Object -> {
            val group = tree.groupOrNull(currentRequest.objectId)
            when {
              group != null -> Selection.ObjectGroup(group)
              currentRequest.objectId in tree -> Selection.Object(tree.summarize(currentRequest.objectId))
              else -> null
            }
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

  // The tree already knows what dominates what, so this is a read of one label rather than a search.
  // Keyed on the selection: a new one invalidates this.
  val selectedObjectId = (selection as? Selection.Object)?.summary?.objectId
  LaunchedEffect(session, selectedObjectId) {
    dominator = selectedObjectId?.let { objectId ->
      session.read { explorer -> explorer.tree.dominatorOf(objectId) }
    }
  }

  // Searching for the paths is a walk in memory, but the first one of a session pays for the pass over the
  // heap dump that indexes which object points at which. So it lands after the rest of the panel.
  LaunchedEffect(session, selectedObjectId) {
    paths = selectedObjectId?.let { objectId ->
      session.read { explorer -> explorer.tree.independentPathsTo(objectId) }
    }
  }

  // Where the treemap draws a node takes walking up its dominators, so showing what the panel leads to is
  // a heap dump read as well.
  LaunchedEffect(session, nodeToOpen) {
    val node = nodeToOpen ?: return@LaunchedEffect
    val path = session.read { explorer -> explorer.tree.pathToOpen(node) }
    history = history.goTo(history.current.zoomInto(path))
    nodeToOpen = null
  }

  val onSelect: (LayoutCell<Long>) -> Unit = { cell ->
    selected = SelectedCell.of(cell.subject)
    request = SelectionRequest.of(cell)
  }
  val onZoomInto: (List<Long>) -> Unit = { path ->
    history = history.goTo(navigation.zoomInto(path))
  }
  /** Shows a node the panel led to on the treemap, and describes it, the way clicking a cell would. */
  val onOpen: (Long) -> Unit = { nodeId ->
    selected = SelectedCell(nodeId, isGroup = nodeId < 0L)
    request = SelectionRequest.Object(nodeId)
    nodeToOpen = nodeId
    isShowingFavourites = false
  }

  Column(modifier) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      HistoryArrows(
        canGoBack = history.canGoBack,
        canGoForward = history.canGoForward,
        onBack = { history = history.goBack() },
        onForward = { history = history.goForward() }
      )
      Breadcrumbs(
        crumbs = view.crumbs,
        onClick = { history = history.goTo(navigation.zoomInto(it)) },
        modifier = Modifier.weight(1f)
      )
    }
    Row(Modifier.weight(1f)) {
      Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewportSize = it }) {
        when (val presentation = view.presentation) {
          is ViewPresentation.Treemap -> TreemapView(
            presentation = presentation.presentation,
            coloring = coloring,
            selected = selected,
            onSelect = onSelect,
            onZoomInto = onZoomInto,
            modifier = Modifier.fillMaxSize()
          )
          is ViewPresentation.Radial -> RadialView(
            presentation = presentation.presentation,
            coloring = coloring,
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
        if (isShowingFavourites) {
          FavouritesList(
            favourites = favourites,
            onOpen = onOpen,
            onRemove = { objectId -> favourites = favourites.filterNot { it.objectId == objectId } },
            onClose = { isShowingFavourites = false },
            modifier = Modifier.fillMaxSize()
          )
        }
      }
      val selectedSummary = (selection as? Selection.Object)?.summary
      DetailsPanel(
        selection = selection,
        dominator = dominator,
        paths = paths,
        favouriteCount = favourites.size,
        isStarred = favourites.any { it.objectId == selectedSummary?.objectId },
        coloring = coloring,
        onOpen = onOpen,
        onShowFavourites = { isShowingFavourites = true },
        onToggleStar = {
          val summary = selectedSummary ?: return@DetailsPanel
          favourites = if (favourites.any { it.objectId == summary.objectId }) {
            favourites.filterNot { it.objectId == summary.objectId }
          } else {
            favourites + Favourite.of(summary, dominator)
          }
        },
        onInspect = { objectId ->
          selected = SelectedCell(objectId, isGroup = false)
          request = SelectionRequest.Object(objectId)
        },
        modifier = Modifier.width(DETAILS_WIDTH).fillMaxHeight()
      )
    }
  }
}

/** Back and forward through the moves made, which zooming out alone can't undo. See [NavigationHistory]. */
@Composable
private fun HistoryArrows(
  canGoBack: Boolean,
  canGoForward: Boolean,
  onBack: () -> Unit,
  onForward: () -> Unit
) {
  TextButton(onClick = onBack, enabled = canGoBack) {
    Text(BACK_ARROW)
  }
  TextButton(onClick = onForward, enabled = canGoForward) {
    Text(FORWARD_ARROW)
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

  /** A cell standing for many objects was clicked, so there's no one object to describe. */
  data class ObjectGroup(val summary: ObjectGroupSummary) : Selection

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
  onClick: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
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
  dominator: ObjectDominator?,
  paths: IndependentPaths?,
  favouriteCount: Int,
  isStarred: Boolean,
  coloring: CellColoring,
  onOpen: (Long) -> Unit,
  onShowFavourites: () -> Unit,
  onToggleStar: () -> Unit,
  onInspect: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      if (favouriteCount > 0) {
        TextButton(onClick = onShowFavourites) {
          Text("$STARRED_GLYPH $favouriteCount starred")
        }
      }
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
        is Selection.ObjectGroup -> ObjectGroupDetails(selection.summary, coloring, onOpen)
        is Selection.Object -> ObjectDetails(
          summary = selection.summary,
          dominator = dominator,
          paths = paths,
          isStarred = isStarred,
          coloring = coloring,
          onOpen = onOpen,
          onToggleStar = onToggleStar,
          onInspect = onInspect
        )
      }
    }
  }
}

/**
 * A cell standing for many objects: half of the heap dump, or every instance of one class under the
 * root. Says so in as many words, because a rectangle that isn't an object looks exactly like one that
 * is until something says otherwise.
 */
@Composable
private fun ObjectGroupDetails(
  summary: ObjectGroupSummary,
  coloring: CellColoring,
  onOpen: (Long) -> Unit
) {
  Text(summary.title(), style = MaterialTheme.typography.titleMedium)
  summary.className?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(Modifier.size(SWATCH_SIZE).background(legendColor(coloring, summary.strength)))
    Text(summary.explanation(), style = MaterialTheme.typography.bodySmall)
  }
  Detail("Retained together", formatByteSize(summary.retainedSize))
  Detail("Objects", formatObjectCount(summary.objectCount))
  Button(onClick = { onOpen(summary.nodeId) }) {
    Text("Zoom in")
  }
}

private fun ObjectGroupSummary.title(): String = when (kind) {
  ObjectGroupKind.GC_ROOTS -> HeapDominatorTreemap.GC_ROOTS_LABEL
  ObjectGroupKind.UNREACHABLE -> HeapDominatorTreemap.UNREACHABLE_LABEL
  ObjectGroupKind.CLASS -> "${formatObjectCount(objectCount)} of one class"
}

private fun ObjectGroupSummary.explanation(): String = when (kind) {
  ObjectGroupKind.GC_ROOTS -> GC_ROOTS_EXPLANATION
  ObjectGroupKind.UNREACHABLE -> UNREACHABLE_EXPLANATION
  ObjectGroupKind.CLASS -> CLASS_GROUP_EXPLANATION
}

@Composable
private fun ObjectDetails(
  summary: HeapObjectSummary,
  dominator: ObjectDominator?,
  paths: IndependentPaths?,
  isStarred: Boolean,
  coloring: CellColoring,
  onOpen: (Long) -> Unit,
  onToggleStar: () -> Unit,
  onInspect: (Long) -> Unit
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      summary.label,
      Modifier.weight(1f),
      style = MaterialTheme.typography.titleMedium,
      overflow = TextOverflow.Ellipsis
    )
    Hint(if (isStarred) UNSTAR_HINT else STAR_HINT) {
      Text(
        if (isStarred) STARRED_GLYPH else UNSTARRED_GLYPH,
        Modifier.clickable(onClick = onToggleStar).padding(4.dp),
        style = MaterialTheme.typography.titleMedium
      )
    }
  }
  Text(summary.className, style = MaterialTheme.typography.bodySmall)
  if (summary.objectId != HeapDominatorTreemap.ROOT_OBJECT_ID) {
    // Selectable so it can be copied out: an object id is how you point something else — a script, a
    // colleague, a bug report — at this one instance rather than at its class.
    SelectionContainer {
      Text(objectIdText(summary.objectId), style = MaterialTheme.typography.bodySmall)
    }
  }
  summary.headline?.let { headline ->
    Text(headline, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
  }
  Row(
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(Modifier.size(SWATCH_SIZE).background(legendColor(coloring, summary.strength)))
    Text(summary.strength.reachabilityText, style = MaterialTheme.typography.bodySmall)
  }
  Detail("Retained", formatByteSize(summary.retainedSize))
  Detail("Retained objects", summary.retainedCount.toString())
  Detail("Shallow", formatByteSize(summary.shallowSize))
  Detail("Dominates", "${summary.dominatedObjectCount} objects")
  summary.inspectorLabels.forEach { label ->
    Text(label, style = MaterialTheme.typography.bodySmall)
  }
  Button(onClick = { onOpen(summary.objectId) }, enabled = summary.dominatedObjectCount > 0) {
    Text("Zoom in")
  }
  DominatorSection(dominator, onOpen)
  IndependentPathsSection(paths, dominator, onOpen)
  Fields(summary, onInspect)
}

/**
 * The one node the tree attributes the object's bytes to.
 *
 * Not the same question as what points at the object, and not the same answer: several objects can hold it
 * while exactly one dominates it. Which is worth explaining rather than assuming, hence the hint.
 */
@Composable
private fun DominatorSection(
  dominator: ObjectDominator?,
  onOpen: (Long) -> Unit
) {
  if (dominator == null) {
    return
  }
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(DOMINATOR, style = MaterialTheme.typography.labelSmall)
    Hint(dominator.hint()) {
      Text(
        HINT_GLYPH,
        Modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelSmall
      )
    }
  }
  Inspectable(
    text = "${dominator.label} · ${formatByteSize(dominator.retainedSize)}",
    objectId = dominator.nodeId,
    onInspect = onOpen
  )
}

private fun ObjectDominator.hint(): String = when (kind) {
  DominatorKind.OBJECT -> DOMINATOR_HINT
  DominatorKind.ALL_GC_ROOTS -> ALL_GC_ROOTS_DOMINATOR_HINT
  DominatorKind.UNCOLLECTED_GARBAGE -> GARBAGE_DOMINATOR_HINT
}

/**
 * Every way the object is held below its dominator, which is every way it is held with the part they all
 * share left out.
 *
 * There are at least two of them unless the dominator points straight at the object: one alone would mean
 * whatever it goes through is a closer dominator. Which is the interesting case — the view showing a bitmap
 * on one path, the image cache that loaded it on another.
 */
@Composable
private fun IndependentPathsSection(
  paths: IndependentPaths?,
  dominator: ObjectDominator?,
  onOpen: (Long) -> Unit
) {
  if (dominator == null) {
    return
  }
  Row(
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(INDEPENDENT_PATHS, style = MaterialTheme.typography.labelSmall)
    Hint(INDEPENDENT_PATHS_HINT) {
      Text(
        HINT_GLYPH,
        Modifier.background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelSmall
      )
    }
  }
  if (paths == null) {
    Text(SEARCHING_PATHS, style = MaterialTheme.typography.bodySmall)
    return
  }
  if (paths.isStraightFromDominator(dominator)) {
    Text(NO_PATHS, style = MaterialTheme.typography.bodySmall)
    return
  }
  if (paths.paths.isEmpty()) {
    Text(NO_PATH_FOUND, style = MaterialTheme.typography.bodySmall)
    return
  }
  paths.paths.forEachIndexed { index, path ->
    Text(
      listOfNotNull("Path ${index + 1} of ${paths.paths.size}", path.gcRootLabel).joinToString(" · "),
      style = MaterialTheme.typography.labelSmall
    )
    if (path.hiddenStepCount > 0) {
      Text(
        "$ELLIPSIS ${path.hiddenStepCount} steps between the dominator and here",
        style = MaterialTheme.typography.bodySmall
      )
    }
    path.steps.forEachIndexed { depth, step ->
      PathStepLine(step, depth, onOpen)
    }
  }
  if (paths.hasMore) {
    Text(MORE_PATHS, style = MaterialTheme.typography.bodySmall)
  }
}

/**
 * Whether the one path is the dominator's own field pointing at the object, which the panel has nothing to
 * add to: the dominator is the line right above.
 *
 * A single step below a group is a different thing — there the step is the GC root's own object, and which
 * kind of root reaches it is the whole answer — and so is an empty list, which means the search found
 * nothing to say.
 */
private fun IndependentPaths.isStraightFromDominator(dominator: ObjectDominator): Boolean =
  dominator.kind == DominatorKind.OBJECT && paths.singleOrNull()?.steps?.size == 1

/** One step of a path: the field that reaches the object, and the object it reaches. */
@Composable
private fun PathStepLine(
  step: PathStep,
  depth: Int,
  onOpen: (Long) -> Unit
) {
  val reference = step.referenceName?.let { name ->
    if (name.startsWith("[")) name else ".$name"
  }
  Inspectable(
    text = listOfNotNull(reference, step.label).joinToString(" "),
    objectId = step.objectId.takeIf { step.isInspectable },
    onInspect = onOpen,
    indentSteps = depth
  )
}

/**
 * The objects starred so far, everything about them read when they were starred.
 *
 * Comparing what two rectangles hold means looking at them one after the other, and a treemap has no room
 * to keep the first one on screen. Starring is how a handful of objects stay comparable.
 */
@Composable
private fun FavouritesList(
  favourites: List<Favourite>,
  onOpen: (Long) -> Unit,
  onRemove: (Long) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "$STARRED_GLYPH Starred objects",
          Modifier.weight(1f),
          style = MaterialTheme.typography.titleMedium
        )
        TextButton(onClick = onClose) {
          Text("Close")
        }
      }
      favourites.forEach { favourite ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Inspectable(favourite.label, favourite.objectId, onOpen)
            Text(favourite.className, style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
              Text(objectIdText(favourite.objectId), style = MaterialTheme.typography.bodySmall)
            }
            Text(
              "Retained ${formatByteSize(favourite.retainedSize)} · " +
                "shallow ${formatByteSize(favourite.shallowSize)} · " +
                "dominated by ${favourite.dominatorLabel}",
              style = MaterialTheme.typography.bodySmall
            )
          }
          TextButton(onClick = { onRemove(favourite.objectId) }) {
            Text(STARRED_GLYPH)
          }
        }
      }
    }
  }
}

/** One starred object, with what the list shows about it kept rather than read again. */
private data class Favourite(
  val objectId: Long,
  val label: String,
  val className: String,
  val shallowSize: Long,
  val retainedSize: Long,
  val dominatorLabel: String
) {
  companion object {
    fun of(
      summary: HeapObjectSummary,
      dominator: ObjectDominator?
    ) = Favourite(
      objectId = summary.objectId,
      label = summary.headline?.let { "${summary.label} · $it" } ?: summary.label,
      className = summary.className,
      shallowSize = summary.shallowSize,
      retainedSize = summary.retainedSize,
      dominatorLabel = dominator?.label ?: UNKNOWN_DOMINATOR
    )
  }
}

/** Whatever it wraps, with [text] shown while the pointer rests on it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hint(
  text: String,
  content: @Composable () -> Unit
) {
  TooltipArea(
    tooltip = {
      Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
        Text(
          text,
          Modifier.width(HINT_WIDTH).padding(8.dp),
          style = MaterialTheme.typography.bodySmall
        )
      }
    },
    content = content
  )
}

/** Every field of the object, so that the panel says what the heap dump holds and not just its shape. */
@Composable
private fun Fields(
  summary: HeapObjectSummary,
  onInspect: (Long) -> Unit
) {
  if (summary.fields.isEmpty()) {
    return
  }
  Text("Fields", style = MaterialTheme.typography.labelSmall)
  summary.fields.forEach { field ->
    Inspectable("${field.name} = ${field.value}", field.inspectableObjectId, onInspect)
  }
  if (summary.hiddenFieldCount > 0) {
    Text(
      "and ${summary.hiddenFieldCount} more",
      style = MaterialTheme.typography.bodySmall
    )
  }
}

/** A line of the panel that leads somewhere: clicking it shows that object instead. */
@Composable
private fun Inspectable(
  text: String,
  objectId: Long?,
  onInspect: (Long) -> Unit,
  /** How deep down a path this line sits, so that a chain of references reads as one. */
  indentSteps: Int = 0
) {
  val modifier = Modifier.padding(start = STEP_INDENT * indentSteps.coerceAtMost(MAX_INDENT_STEPS))
  if (objectId == null) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall)
  } else {
    Text(
      text,
      modifier.clickable { onInspect(objectId) },
      style = MaterialTheme.typography.bodySmall,
      color = LINK_COLOR
    )
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

/**
 * How the panel prints an object id: decimal, which is what Shark's own APIs take, and hex, which is
 * what a heap dump records and every other heap analyzer prints.
 */
internal fun objectIdText(objectId: Long): String = "id $objectId · 0x${objectId.toString(16)}"

/** Shown by the details panel until something is selected. */
internal const val NO_SELECTION = "Click a rectangle or a sector to see what it retains."

/** The heading of the section naming the one node the tree attributes the object's bytes to. */
internal const val DOMINATOR = "Dominator"

/** What a dominator is, for the ordinary case where one object owns another. */
internal const val DOMINATOR_HINT =
  "The one object that would free this one: every path from a GC root here goes through it, so this " +
    "stays in memory for exactly as long as that does. Which is why the treemap draws this rectangle " +
    "inside that one, and why there is only ever one answer — several objects can point at this one " +
    "while exactly one dominates it."

/** And what it means when there isn't one, which is what puts a rectangle flat under the root. */
internal const val ALL_GC_ROOTS_DOMINATOR_HINT =
  "No single object would free this one: it's held from several places at once, on paths that meet " +
    "nowhere, so releasing any one of them would leave the others holding it. With no owner to " +
    "attribute its bytes to, the treemap draws it at the top of the reachable heap — and the paths " +
    "below say who those holders are."

internal const val GARBAGE_DOMINATOR_HINT =
  "No GC root reaches this, so nothing keeps it in memory: it's garbage that hadn't been collected " +
    "when the heap dump was written. Whatever points at it is garbage as well."

/** The heading of the section spelling out the ways the object is held below its dominator. */
internal const val INDEPENDENT_PATHS = "Paths from the dominator"

internal const val INDEPENDENT_PATHS_HINT =
  "Every way this object is held, with the part they all share — everything above the dominator — " +
    "left out. The paths have no object in common in between either, which graph theory calls " +
    "internally vertex-disjoint, or independent: there are always at least two of them unless the " +
    "dominator points straight at this object, because one alone would mean whatever it goes through " +
    "is a closer dominator. Found greedily, so there can be more of them than are shown, and two " +
    "paths shown apart may well reference each other."

/** Shown while the search for the paths is still running. */
internal const val SEARCHING_PATHS = "Searching for the ways it's held…"

/** The dominator points straight at the object, so there is nothing between the two to spell out. */
internal const val NO_PATHS = "The dominator points straight at this object."

private const val NO_PATH_FOUND = "No path from the dominator down to this object was found."

private const val MORE_PATHS =
  "The search stopped here. There may be more ways this object is held."

/** Hovering the question mark is how the panel explains a dominator without a paragraph in the way. */
private const val HINT_GLYPH = "?"

internal const val STARRED_GLYPH = "★"
internal const val UNSTARRED_GLYPH = "☆"
private const val STAR_HINT = "Star this object, to compare it with others later."
private const val UNSTAR_HINT = "Remove this object from the starred list."
private const val UNKNOWN_DOMINATOR = "not read yet"

internal const val BACK_ARROW = "←"
internal const val FORWARD_ARROW = "→"

private const val ELLIPSIS = "…"

internal const val BREADCRUMB_SEPARATOR = "›"

internal const val GC_ROOTS_EXPLANATION =
  "Not one object: everything the garbage collector reaches, so everything that is still in memory " +
    "on purpose."

internal const val UNREACHABLE_EXPLANATION =
  "Not one object: everything no GC root reaches, so garbage that hadn't been collected when the heap " +
    "dump was written. The next collection would take all of it."

internal const val CLASS_GROUP_EXPLANATION =
  "Not one object: these are all the instances of this class that nothing owns on its own, gathered " +
    "so the root's children can be read. Zoom in to see them one by one."

private const val GROUP_EXPLANATION =
  "Too small or too many to draw one by one. Zoom into what holds them to see them."

private val DETAILS_WIDTH = 320.dp

/** Wide enough for the hints to read as paragraphs rather than as one long line. */
private val HINT_WIDTH = 320.dp

/** How far one step of a path is indented past the one holding it, and where the cascade stops. */
private val STEP_INDENT = 4.dp
private const val MAX_INDENT_STEPS = 8

/** Panel lines that lead to another object, coloured like a link because that's what they are. */
private val LINK_COLOR = SELECTION_COLOR
internal val SWATCH_SIZE = 10.dp
