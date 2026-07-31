package shark.explorer.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import shark.SharkLog
import shark.explorer.BitmapCounts
import shark.explorer.CellSubject
import shark.explorer.DeviceHeapDumps
import shark.explorer.ExplorerScreen
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectKind
import shark.explorer.HeapSizes
import shark.explorer.IndependentPaths
import shark.explorer.LayoutCell
import shark.explorer.NavigationHistory
import shark.explorer.ObjectDominator
import shark.explorer.ObjectList
import shark.explorer.ObjectListFilter
import shark.explorer.RadialLayout
import shark.explorer.RadialPresentation
import shark.explorer.TreemapLayout
import shark.explorer.TreemapNavigation
import shark.explorer.TreemapPresentation
import shark.explorer.TreemapRect
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount

/**
 * One open heap dump, read through whichever screen the breadcrumbs say: the dominator tree as a treemap
 * or as rings, the ways one object is held, every object as a list, the ones starred so far. The details
 * panel sits beside all of them.
 *
 * Every read of the heap dump goes through [session], which puts it on a thread of its own, so what's
 * drawn is state that arrives a little after whatever asked for it changed: a presentation is a view
 * already laid out and labelled somewhere else, and a selection is a summary already read.
 *
 * Where the explorer is, is one [NavigationHistory] of [ExplorerScreen]s, and what the panel describes
 * follows it — a screen the breadcrumbs name and a panel describing something else was the one thing about
 * this window that read as a bug.
 */
@Composable
internal fun HeapDumpExplorer(
  session: HeapDumpSession,
  sizes: HeapSizes,
  /** The way back to the live process, for the bitmaps this heap dump has no pixels for. */
  deviceHeapDumps: DeviceHeapDumps,
  modifier: Modifier = Modifier
) {
  var history by remember {
    mutableStateOf(NavigationHistory<ExplorerScreen>(ExplorerScreen.Tree(TreemapNavigation(ROOT_NODE))))
  }
  val screen = history.current
  val navigation = screen.treeNavigation
  var shape by remember { mutableStateOf(ViewShape.TREEMAP) }
  var coloring by remember { mutableStateOf(CellColoring.DEFAULT) }
  var viewportSize by remember { mutableStateOf(IntSize.Zero) }
  var view by remember { mutableStateOf(ViewState.EMPTY) }
  var isLayingOut by remember { mutableStateOf(true) }
  var selected: SelectedCell? by remember { mutableStateOf(null) }
  var request: SelectionRequest? by remember { mutableStateOf(null) }
  var selection: Selection? by remember { mutableStateOf(null) }
  /** Null while the tree is still being asked, which takes no time at all. */
  var dominator: ObjectDominator? by remember { mutableStateOf(null) }
  /** Null while the search for the paths is still running. */
  var paths: IndependentPaths? by remember { mutableStateOf(null) }
  var objects by remember { mutableStateOf(ObjectList.EMPTY) }
  var isListing by remember { mutableStateOf(false) }
  /** How many bitmaps this heap dump has and how many of them can be drawn, which fetching changes. */
  var bitmapCounts by remember { mutableStateOf(BitmapCounts.NONE) }
  /** The bitmaps decoded so far, by object id. Only grows: a decoded image stays valid. */
  var bitmapImages by remember { mutableStateOf(emptyMap<Long, ImageBitmap>()) }
  /** Bumped when pixels arrive from the device, which is what makes the bitmaps be asked for again. */
  var bitmapRevision by remember { mutableStateOf(0) }
  var showsBitmapsFromDevice by remember { mutableStateOf(false) }
  /** The objects starred so far, with everything the list shows about them read once. */
  var favourites by remember { mutableStateOf(emptyList<Favourite>()) }
  /** A node something led to, until the path the treemap has it under is worked out. */
  var nodeToOpen: NodeToOpen? by remember { mutableStateOf(null) }

  /**
   * Points the panel at a node, which every move does. Each screen says which node that is once it is
   * arrived at, so this is called with [ExplorerScreen.describedNode] rather than deciding for itself.
   */
  val describe: (Long) -> Unit = { nodeId ->
    selected = SelectedCell(nodeId, isGroup = nodeId < 0L)
    request = SelectionRequest.Object(nodeId)
  }

  val treemapLayout = rememberTreemapLayout()
  val radialLayout = rememberRadialLayout()
  // In pixels, like everything a layout is measured in, so that how small is too small for an image to be
  // worth drawing is the same size on every display. See MIN_BITMAP_DRAW_SIZE.
  val minBitmapDrawSize = with(LocalDensity.current) { MIN_BITMAP_DRAW_SIZE.toPx() }

  // Everything one laid out view follows from, as one value: a view is asked for, and the one that comes
  // back is the answer to it. Null until the view has been measured, which is the one state there is
  // nothing to lay out for.
  val viewRequest = viewportSize.takeIf { it != IntSize.Zero }?.let { size ->
    ViewRequest(
      navigation = navigation,
      viewportSize = size,
      shape = shape,
      treemapLayout = treemapLayout,
      radialLayout = radialLayout
    )
  }

  // Resizing, zooming and switching shape all lay the tree out again, which reads the heap dump for
  // every visible label. All of it ends up here, on the heap dump's thread. Keyed on the request rather
  // than on the screen, so that reading a list of objects doesn't lay the map out again and the map is
  // ready by the time the breadcrumbs lead back to it.
  LaunchedEffect(session, viewRequest) {
    if (viewRequest == null) {
      // Which is what a window showing a spinner and nothing else has been waiting for all along.
      SharkLog.d { "Not laying the tree out yet: the view has no size" }
      return@LaunchedEffect
    }
    isLayingOut = true
    view = session.read(viewRequest.description()) { explorer ->
      val tree = explorer.tree
      val requested = viewRequest.navigation
      // An object zoomed into may no longer be dominated by the one above it on the path.
      val reachablePath = requested.retainingWhere { it in tree }
      if (reachablePath.path.size < requested.path.size) {
        SharkLog.d {
          "Rooted at ${reachablePath.path.size} of the ${requested.path.size} nodes zoomed through: " +
            "${hexObjectId(requested.path[reachablePath.path.size])} is no node of the tree"
        }
      }
      ViewState(
        navigation = reachablePath,
        crumbs = reachablePath.path.map { objectId -> tree.crumb(objectId) },
        presentation = when (viewRequest.shape) {
          ViewShape.TREEMAP -> ViewPresentation.Treemap(
            tree.present(viewRequest.treemapLayout, viewRequest.viewport, reachablePath.current)
          )
          ViewShape.RADIAL -> ViewPresentation.Radial(
            tree.presentRadial(viewRequest.radialLayout, viewRequest.viewport, reachablePath.current)
          )
        }
      )
    }
    // What a view that came out looking empty or coarse actually drew.
    SharkLog.d { "Laid out ${view.presentation.description()}" }
    // Settling for the path that could actually be shown isn't a move, so it replaces where the explorer
    // is rather than being somewhere the back arrow returns to. Only if it's still there: a move made
    // while this was being laid out is not something to overwrite.
    if (history.current.treeNavigation == viewRequest.navigation) {
      history = history.replacingCurrent(history.current.withTreeNavigation(view.navigation))
    }
    isLayingOut = false
  }

  // How many bitmaps there are is a pass over the instances of one class, so it's read once. Fetching
  // pixels from the device changes the answer, and the fetch reports the new one rather than this running
  // again.
  LaunchedEffect(session) {
    bitmapCounts = session.read("how many bitmaps ${session.heapDumpFile.name} has") { explorer ->
      explorer.tree.bitmapCounts()
    }
  }

  // A bitmap is worth the pixels only once it's on the map and big enough to make out, so this follows
  // the presentation rather than the heap dump: zooming in asks for the bitmaps zooming in brought into
  // view. The ones already asked for are skipped, including the ones that turned out to have no pixels —
  // until some arrive from the device, which is what the revision is for.
  val askedForBitmaps = remember(session, bitmapRevision) { mutableSetOf<Long>() }
  LaunchedEffect(session, view.presentation, bitmapRevision) {
    val treemap = (view.presentation as? ViewPresentation.Treemap)?.presentation
      ?: return@LaunchedEffect
    val nodeIds = treemap.bitmapNodeIds(minBitmapDrawSize) - askedForBitmaps
    if (nodeIds.isEmpty()) {
      return@LaunchedEffect
    }
    askedForBitmaps += nodeIds
    val images = session.read("the pixels of ${nodeIds.size} bitmaps on the map") { explorer ->
      explorer.tree.bitmapImages(nodeIds, MAX_TREEMAP_BITMAP_PIXELS)
    }
    // Decoding is neither a heap dump read nor something to do on the thread drawing the window: a
    // presentation of a real dump can have a hundred images on it.
    bitmapImages = bitmapImages + withContext(Dispatchers.Default) {
      images.mapNotNull { (nodeId, image) -> image.toImageBitmap()?.let { nodeId to it } }
    }
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
      session.read(currentRequest.description()) { explorer ->
        val tree = explorer.tree
        when (currentRequest) {
          is SelectionRequest.Object -> {
            val group = tree.groupOrNull(currentRequest.objectId)
            when {
              group != null -> Selection.ObjectGroup(group)
              currentRequest.objectId in tree -> Selection.Object(tree.summarize(currentRequest.objectId))
              // Which is why the panel goes back to saying nothing is selected.
              else -> {
                SharkLog.d {
                  "Nothing to describe for ${hexObjectId(currentRequest.objectId)}: " +
                    "it is no node of the tree"
                }
                null
              }
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

  // The panel shows a selected bitmap as big as the panel is wide, so its pixels are read again at that
  // size: a treemap rectangle is a couple of hundred pixels across and this is four times that.
  var selectedBitmap: ImageBitmap? by remember { mutableStateOf(null) }
  LaunchedEffect(session, selectedObjectId, bitmapRevision) {
    val objectId = selectedObjectId
    // Nothing says whether the selection is a bitmap at all, and asking is cheaper than tracking it: an
    // object that isn't one comes back with no image.
    val image = if (objectId == null) {
      null
    } else {
      session.read("the pixels of ${hexObjectId(objectId)}") { explorer ->
        explorer.tree.bitmapImages(listOf(objectId), MAX_PANEL_BITMAP_PIXELS)[objectId]
      }
    }
    selectedBitmap = image?.let { withContext(Dispatchers.Default) { it.toImageBitmap() } }
  }

  LaunchedEffect(session, selectedObjectId) {
    dominator = selectedObjectId?.let { objectId ->
      session.read("the dominator of ${hexObjectId(objectId)}") { explorer ->
        explorer.tree.dominatorOf(objectId)
      }
    }
  }

  // Searching for the paths is a walk in memory, but the first one of a session pays for the pass over the
  // heap dump that indexes which object points at which. So it lands after the rest of the panel.
  LaunchedEffect(session, selectedObjectId) {
    paths = selectedObjectId?.let { objectId ->
      session.read("the paths holding ${hexObjectId(objectId)}") { explorer ->
        explorer.tree.independentPathsTo(objectId)
      }
    }
  }

  // Listing objects is a pass over every one of them, which is seconds on a large heap dump, so it waits
  // for the typing in the search box to stop rather than starting over on every keystroke.
  val objectFilter = (screen as? ExplorerScreen.Objects)?.filter
  LaunchedEffect(session, objectFilter) {
    if (objectFilter == null) {
      return@LaunchedEffect
    }
    isListing = true
    delay(FILTER_SETTLE_MILLIS)
    objects = session.read("the objects matching $objectFilter") { explorer ->
      explorer.tree.listObjects(objectFilter)
    }
    // What a list that came back looking empty or short was actually asked for.
    SharkLog.d {
      "Listed ${objects.entries.size} of the ${formatObjectCount(objects.matchCount)} matched, " +
        "out of ${formatObjectCount(objects.totalCount)}"
    }
    isListing = false
  }

  // Where the treemap draws a node takes walking up its dominators, so showing what a panel line or a row
  // of a list leads to is a heap dump read as well.
  LaunchedEffect(session, nodeToOpen) {
    val open = nodeToOpen ?: return@LaunchedEffect
    val path = session.read("where the map draws ${hexObjectId(open.nodeId)}") { explorer ->
      explorer.tree.pathToOpen(open.nodeId)
    }
    if (open.nodeId != ROOT_NODE && path == listOf(ROOT_NODE)) {
      // Clicking a field or a row led to an object the tree has no node for, so the map has nowhere to
      // go and stays on the whole heap dump.
      SharkLog.d { "${hexObjectId(open.nodeId)} is nowhere on the map: it is no node of the tree" }
    }
    val zoomed = history.current.treeNavigation.zoomInto(path)
    history = history.goTo(
      if (open.showsPaths) {
        ExplorerScreen.Paths(zoomed, open.nodeId)
      } else {
        ExplorerScreen.Tree(zoomed, open.nodeId)
      }
    )
    describe(open.nodeId)
    nodeToOpen = null
  }

  val onSelect: (LayoutCell<Long>) -> Unit = { cell ->
    val cellSelection = SelectedCell.of(cell.subject)
    selected = cellSelection
    request = SelectionRequest.of(cell)
    // Recorded on the screen rather than only shown, so that the back arrow returning to the map describes
    // what was being looked at on it. Cells are the tree's own, so this screen is one.
    history = history.replacingCurrent(ExplorerScreen.Tree(navigation, cellSelection.objectId))
  }
  val onZoomInto: (List<Long>) -> Unit = { path ->
    history = history.goTo(ExplorerScreen.Tree(navigation.zoomInto(path), path.last()))
    describe(path.last())
  }
  /** Shows a node on the map with it selected, and describes it, which is the object detail view. */
  val onOpen: (Long) -> Unit = { nodeId -> nodeToOpen = NodeToOpen(nodeId, showsPaths = false) }
  val onGoTo: (ExplorerScreen) -> Unit = { destination -> history = history.goTo(destination) }

  if (showsBitmapsFromDevice) {
    BitmapsFromDeviceDialog(
      origin = session.origin,
      counts = bitmapCounts,
      deviceHeapDumps = deviceHeapDumps,
      onFetched = { pixels ->
        val counts = session.read("which bitmaps the fetched pixels belong to") { explorer ->
          explorer.tree.addNativeBitmapPixels(pixels)
        }
        bitmapCounts = counts
        // Which is what has every bitmap on the map asked for again, this time with pixels behind it.
        bitmapRevision++
        counts
      },
      onDismiss = { showsBitmapsFromDevice = false }
    )
  }

  Column(modifier) {
    ScreenBar(
      starredCount = favourites.size,
      bitmapCounts = bitmapCounts,
      onListObjects = {
        onGoTo(ExplorerScreen.Objects(navigation, ObjectListFilter(), screen.describedNode))
      },
      onShowStarred = { onGoTo(ExplorerScreen.Starred(navigation, screen.describedNode)) },
      onFetchBitmaps = { showsBitmapsFromDevice = true }
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      HistoryArrows(
        canGoBack = history.canGoBack,
        canGoForward = history.canGoForward,
        // Whatever a move was about is only known to the move, so a move undone leaves the panel on the
        // node the map came back to.
        onBack = {
          history = history.goBack()
          describe(history.current.describedNode)
        },
        onForward = {
          history = history.goForward()
          describe(history.current.describedNode)
        }
      )
      Breadcrumbs(
        crumbs = view.crumbs,
        trailingCrumb = screen.trailingCrumb,
        onClick = { objectId ->
          onGoTo(ExplorerScreen.Tree(navigation.zoomInto(objectId), objectId))
          describe(objectId)
        },
        modifier = Modifier.weight(1f)
      )
    }
    Row(Modifier.weight(1f)) {
      Column(Modifier.weight(1f).fillMaxHeight()) {
        // Above the view and as wide as it, because that's what it controls, and only there: the list of
        // objects is coloured by nothing and shaped like a list.
        if (screen is ExplorerScreen.Tree) {
          ViewControls(
            sizes = sizes,
            shape = shape,
            coloring = coloring,
            onColoringChange = { coloring = it },
            onShapeChange = { shape = it },
            modifier = Modifier.fillMaxWidth()
          )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
          when (screen) {
            is ExplorerScreen.Tree -> TreeScreen(
              view = view,
              coloring = coloring,
              selected = selected,
              isLayingOut = isLayingOut,
              bitmapImages = bitmapImages,
              onSelect = onSelect,
              onZoomInto = onZoomInto,
              // Measured here rather than around every screen, so that leaving the map and coming back
              // doesn't lay it out twice for a viewport that ends up the size it already was.
              modifier = Modifier.fillMaxSize().onSizeChanged { viewportSize = it }
            )
            is ExplorerScreen.Paths -> PathsScreen(
              target = (selection as? Selection.Object)?.summary,
              dominator = dominator,
              paths = paths,
              coloring = coloring,
              onOpen = onOpen,
              modifier = Modifier.fillMaxSize()
            )
            is ExplorerScreen.Objects -> ObjectsScreen(
              list = objects,
              filter = screen.filter,
              isListing = isListing,
              coloring = coloring,
              onFilterChange = { filter ->
                // A keystroke isn't a move, so typing replaces where the explorer is: the back arrow
                // leaves the list rather than walking back through what was typed into it.
                history = history.replacingCurrent(screen.copy(filter = filter))
              },
              onOpen = onOpen,
              modifier = Modifier.fillMaxSize()
            )
            is ExplorerScreen.Starred -> StarredScreen(
              favourites = favourites,
              onOpen = onOpen,
              onRemove = { objectId -> favourites = favourites.filterNot { it.objectId == objectId } },
              modifier = Modifier.fillMaxSize()
            )
          }
        }
      }
      val selectedSummary = (selection as? Selection.Object)?.summary
      DetailsPanel(
        selection = selection,
        dominator = dominator,
        paths = paths,
        bitmap = selectedBitmap,
        isStarred = favourites.any { it.objectId == selectedSummary?.objectId },
        coloring = coloring,
        onOpen = onOpen,
        onShowPaths = { objectId -> nodeToOpen = NodeToOpen(objectId, showsPaths = true) },
        onListInstances = { className ->
          onGoTo(
            ExplorerScreen.Objects(
              navigation,
              ObjectListFilter(
                query = className,
                isExactMatch = true,
                kinds = setOf(HeapObjectKind.INSTANCE)
              ),
              screen.describedNode
            )
          )
        },
        onToggleStar = {
          val summary = selectedSummary
          if (summary == null) {
            // The star is only ever drawn beside a selected object, so this is the panel and the
            // selection disagreeing rather than a click on nothing.
            SharkLog.d { "Nothing to star: no object is selected" }
          } else {
            val wasStarred = favourites.any { it.objectId == summary.objectId }
            SharkLog.d {
              "${if (wasStarred) "Unstarred" else "Starred"} ${hexObjectId(summary.objectId)}"
            }
            favourites = if (wasStarred) {
              favourites.filterNot { it.objectId == summary.objectId }
            } else {
              favourites + Favourite.of(summary, dominator)
            }
          }
        },
        modifier = Modifier.width(DETAILS_WIDTH).fillMaxHeight()
      )
    }
  }
}

/** The screens an open heap dump can be read through that aren't the map, and how many are starred. */
@Composable
private fun ScreenBar(
  starredCount: Int,
  bitmapCounts: BitmapCounts,
  onListObjects: () -> Unit,
  onShowStarred: () -> Unit,
  onFetchBitmaps: () -> Unit
) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    TextButton(onClick = onListObjects) {
      Text(ExplorerScreen.OBJECTS_CRUMB)
    }
    TextButton(onClick = onShowStarred, enabled = starredCount > 0) {
      Text("$STARRED_GLYPH $starredCount starred")
    }
    // Only when there are bitmaps the dump has no pixels for, because that's the only thing a device can
    // add: pixels the dump carries are already on the map by the time this bar is read.
    if (bitmapCounts.withoutImageCount > 0) {
      TextButton(onClick = onFetchBitmaps) {
        Text("$FETCH_BITMAPS ${bitmapCountText(bitmapCounts.withoutImageCount)}")
      }
    }
  }
}

/** The dominator tree, drawn as rectangles or as rings. */
@Composable
private fun TreeScreen(
  view: ViewState,
  coloring: CellColoring,
  selected: SelectedCell?,
  isLayingOut: Boolean,
  /** The pixels read for the bitmaps of the treemap so far, by object id. */
  bitmapImages: Map<Long, ImageBitmap>,
  onSelect: (LayoutCell<Long>) -> Unit,
  onZoomInto: (List<Long>) -> Unit,
  modifier: Modifier = Modifier
) {
  // A shape drawn into one canvas is nothing to anything that isn't looking at it, which is what this
  // says instead. It's also how a test finds where the view starts, since none of the cells is a node
  // of its own.
  Box(modifier.semantics { contentDescription = VIEW_DESCRIPTION }) {
    when (val presentation = view.presentation) {
      is ViewPresentation.Treemap -> TreemapView(
        presentation = presentation.presentation,
        coloring = coloring,
        selected = selected,
        bitmapImages = bitmapImages,
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
    if (isLayingOut) {
      CircularProgressIndicator(Modifier.align(Alignment.Center))
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
 * Where the explorer is: the path down the tree, and the screen it was left for.
 *
 * Every crumb but the last leads back to the map, which is what makes a screen reached from it something
 * to come back from rather than somewhere else entirely.
 */
@Composable
private fun Breadcrumbs(
  crumbs: List<Crumb>,
  trailingCrumb: String?,
  onClick: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    val isTreeLast = trailingCrumb == null
    crumbs.forEachIndexed { index, crumb ->
      if (index > 0) {
        Text(BREADCRUMB_SEPARATOR, style = MaterialTheme.typography.bodyMedium)
      }
      if (isTreeLast && index == crumbs.lastIndex) {
        CurrentCrumb(crumb.label)
      } else {
        TextButton(onClick = { onClick(crumb.objectId) }) {
          Text(crumb.label, maxLines = 1)
        }
      }
    }
    if (trailingCrumb != null) {
      Text(BREADCRUMB_SEPARATOR, style = MaterialTheme.typography.bodyMedium)
      CurrentCrumb(trailingCrumb)
    }
  }
}

/** The last crumb, which is where the explorer is and therefore leads nowhere. */
@Composable
private fun CurrentCrumb(label: String) {
  Text(
    label,
    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.Bold
  )
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
        minDrawSize = MIN_DRAW_SIZE.toPx().toDouble()
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

/** What one breadcrumb says about a node of the tree. */
private fun HeapDominatorTreemap.crumb(objectId: Long): Crumb = Crumb(
  objectId = objectId,
  label = listOfNotNull(
    label(objectId),
    formatByteSize(weight(objectId)),
    // Which instance, not just which class: the id is how anything outside this window — a script, a
    // colleague, another heap analyzer — is pointed at the same object. Only the tree's own nodes have
    // none, the root and the piles of objects it gathers.
    hexObjectId(objectId).takeIf { objectId > 0L }
  ).joinToString(CRUMB_SEPARATOR)
)

/**
 * Everything one view of the tree follows from, which is therefore everything that lays it out again:
 * where the map is, how big the view is, which shape it's drawn as, and the thresholds that shape is laid
 * out to. A [ViewState] is the answer to one of these.
 *
 * One value rather than a key each on the effect that lays the tree out, because that effect has to work
 * off exactly what it was keyed on. Keying it on the viewport while reading the viewport back out of the
 * state is what laid every heap dump out twice as it opened: the run keyed on the size the view had before
 * it was measured ran after the measurement, laid the whole tree out to the size it found there, and had
 * that thrown away when the very measurement it had used relaunched it.
 */
private data class ViewRequest(
  val navigation: TreemapNavigation<Long>,
  /** In pixels, which is what the layouts and their thresholds work in. */
  val viewportSize: IntSize,
  val shape: ViewShape,
  val treemapLayout: TreemapLayout<Long>,
  val radialLayout: RadialLayout<Long>
) {
  val viewport: TreemapRect
    get() = TreemapRect(
      left = 0.0,
      top = 0.0,
      right = viewportSize.width.toDouble(),
      bottom = viewportSize.height.toDouble()
    )
}

/** What is being laid out, for the log. See [HeapDumpSession.read]. */
private fun ViewRequest.description(): String =
  "the ${shape.displayName.lowercase()} rooted at ${hexObjectId(navigation.current)}, " +
    "${viewportSize.width}×${viewportSize.height}"

/** Everything read off the heap dump's thread to draw one view of the tree. */
internal class ViewState(
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
internal sealed interface ViewPresentation {

  data class Treemap(val presentation: TreemapPresentation) : ViewPresentation

  data class Radial(val presentation: RadialPresentation) : ViewPresentation
}

/** What a laid out view amounts to, for the log: one that drew nothing at all says so here. */
private fun ViewPresentation.description(): String = when (this) {
  is ViewPresentation.Treemap ->
    "${presentation.cells.size} rectangles, ${presentation.truncatedNodeCount} nodes not expanded"
  is ViewPresentation.Radial ->
    "${presentation.cells.size} sectors, ${presentation.truncatedNodeCount} nodes not expanded"
}

internal class Crumb(
  val objectId: Long,
  val label: String
)

/** A node a panel line or a row of a list led to, and what to show once the map has been walked to it. */
private data class NodeToOpen(
  val nodeId: Long,
  /** Whether to spell out the ways it's held rather than to draw the map it sits on. */
  val showsPaths: Boolean
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
      // Clicking an object's own bytes is clicking that object.
      is CellSubject.Own -> Object(subject.node)
    }
  }
}

/** What the panel is being filled in for, for the log. See [HeapDumpSession.read]. */
private fun SelectionRequest.description(): String = when (this) {
  is SelectionRequest.Object -> "what ${hexObjectId(objectId)} is"
  is SelectionRequest.Group -> "what the $nodeCount objects under ${hexObjectId(parentObjectId)} are"
}

private const val ROOT_NODE = HeapDominatorTreemap.ROOT_OBJECT_ID

/** How long the search box waits for the typing to stop before reading the whole heap dump again. */
private const val FILTER_SETTLE_MILLIS = 250L

internal const val BACK_ARROW = "←"
internal const val FORWARD_ARROW = "→"

internal const val BREADCRUMB_SEPARATOR = "›"

/** What the button that goes back to the live process offers, before it says how many bitmaps. */
internal const val FETCH_BITMAPS = "Fetch the pixels of"

/** What the tree looks like to anything that can't look at it, a screen reader or a test. */
internal const val VIEW_DESCRIPTION =
  "The dominator tree of the heap dump: every cell is an object, drawn inside the one that holds it."

/** What a crumb puts between what a node is, what it retains, and which object it is. */
private const val CRUMB_SEPARATOR = " · "
