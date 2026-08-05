package shark.explorer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import shark.SharkLog
import shark.explorer.BitmapCounts
import shark.explorer.CellSubject
import shark.explorer.DeviceHeapDumps
import shark.explorer.ExplorerScreen
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapLeaks
import shark.explorer.HeapObjectKind
import shark.explorer.HeapSizes
import shark.explorer.LayoutCell
import shark.explorer.NativeBitmapPixels
import shark.explorer.NavigationHistory
import shark.explorer.ObjectDominator
import shark.explorer.ObjectList
import shark.explorer.ObjectListFilter
import shark.explorer.RadialLayout
import shark.explorer.RadialPresentation
import shark.explorer.RootPath
import shark.explorer.RootPathWay
import shark.explorer.StackLayout
import shark.explorer.StackPresentation
import shark.explorer.TreemapLayout
import shark.explorer.TreemapNavigation
import shark.explorer.TreemapPresentation
import shark.explorer.TreemapRect
import shark.explorer.detours
import shark.explorer.formatObjectCount
import shark.explorer.hexObjectId
import shark.explorer.nodeIdText
import shark.explorer.waysOf

/**
 * One open heap dump, read through one of its screens: the dominator tree as a treemap, as rings or as a
 * stack of rows, every object as a list, the ones starred so far.
 *
 * The map is the screen with panes: the chain holding an object on one side of it and what that object holds
 * on the other, with which object it is in the bar above them both. Every other screen is the width of the
 * window, because each of them is a list and a list wants that width more than it wants a pane.
 *
 * Every read of the heap dump goes through [session], which puts it on a thread of its own, so what's
 * drawn is state that arrives a little after whatever asked for it changed: a presentation is a view
 * already laid out and labelled somewhere else, and a selection is a summary already read.
 *
 * **A click goes to a rectangle and the pointer asks about one.** So the window is about the object clicked
 * — the bar, the chain, the details panel, the star — and what the pointer is on gets a card at the pointer
 * and a few more steps on the end of the chain, which is enough to tell whether it's worth going there. See
 * [DescribedCell].
 *
 * Where the explorer is, is one [NavigationHistory] of [ExplorerScreen]s, and what the panes describe
 * follows it: a window whose panes describe something other than what it is showing was the one thing about
 * this window that read as a bug.
 */
@Composable
internal fun HeapDumpExplorer(
  session: HeapDumpSession,
  sizes: HeapSizes,
  /** The way back to the live process, for the bitmaps this heap dump has no pixels for. */
  deviceHeapDumps: DeviceHeapDumps,
  /** Already fetched off the device, when the dump was taken with the pixels asked for in the same go. */
  fetchedBitmapPixels: NativeBitmapPixels? = null,
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
  /** The cell the window is about, which every pane but the floating chain describes. */
  var clicked: DescribedCell? by remember { mutableStateOf(null) }
  /** Where the pointer last was on the view, which is what the floating chain describes while it's there. */
  var pointerCell: DescribedCell? by remember { mutableStateOf(null) }
  /** And where in the view it was, which is where the card naming what it's on goes. See [PointerCard]. */
  var pointerOffset: Offset? by remember { mutableStateOf(null) }
  /** What that cell is, kept while the next one is being read so that the panes never blank out. */
  var clickedDetails: CellDetails? by remember { mutableStateOf(null) }
  /** And what the cell under the pointer is, once it has stayed on one long enough to be read. */
  var hoveredDetails: CellDetails? by remember { mutableStateOf(null) }
  /**
   * The other ways each stretch of the clicked object's chain could have run, by
   * [shark.explorer.RootPathDetour.fromIndex]. Empty until the searches come back.
   */
  var detourWays by remember { mutableStateOf(emptyMap<Int, List<RootPathWay>>()) }
  /** And which of them the reader has switched that stretch of the chain to. */
  var chosenWays by remember { mutableStateOf(emptyMap<Int, Int>()) }
  var objects by remember { mutableStateOf(ObjectList.EMPTY) }
  var isListing by remember { mutableStateOf(false) }
  /** Every leaking object of the heap dump. Null until the pass that finds them is done. */
  var leaks: HeapLeaks? by remember { mutableStateOf(null) }
  // On from the moment the window opens, because that is when the pass that finds them starts.
  var isFindingLeaks by remember { mutableStateOf(true) }
  /** Whether the map is rooted inside a leak, which makes everything it draws leaking too. */
  var isRootLeaking by remember { mutableStateOf(false) }
  /** How many bitmaps this heap dump has and how many of them can be drawn, which fetching changes. */
  var bitmapCounts by remember { mutableStateOf(BitmapCounts.NONE) }
  /** The bitmaps decoded so far, by object id. Only grows: a decoded image stays valid. */
  var bitmapImages by remember { mutableStateOf(emptyMap<Long, ImageBitmap>()) }
  /** Bumped when pixels arrive from the device, which is what makes the bitmaps be asked for again. */
  var bitmapRevision by remember { mutableStateOf(0) }
  var showsBitmapsFromDevice by remember { mutableStateOf(false) }
  /** The objects starred so far, with everything the list shows about them read once. */
  var favourites by remember { mutableStateOf(emptyList<Favourite>()) }
  /** A move something led to, until the path the treemap has its destination under is worked out. */
  var nodeToOpen: NodeToOpen? by remember { mutableStateOf(null) }

  /**
   * Points the panels at a node, which is what walking the history does. Each screen says which node that
   * is, so this is called with [ExplorerScreen.describedNode] rather than deciding for itself — a move
   * forwards carries what it is about along with it instead, see [NodeToOpen].
   */
  val describe: (Long) -> Unit = { nodeId -> clicked = DescribedCell.of(nodeId) }

  // Nothing is under the pointer while a screen other than the map is showing: the view isn't there, so
  // the rectangle it was on last is neither where the pointer is now nor what the screen is about.
  val hovered = pointerCell.takeIf { screen is ExplorerScreen.Tree }
  // What the pointer being where it is leaves to read, which is nothing when it's on the cell the window is
  // already about: the panes are describing that one.
  val hoveredRequest = hovered?.request?.takeIf { it != clicked?.request }
  // What the panes say, which is the cell clicked and never the one under the pointer. The pointer asking
  // its own questions is what the floating chain is for: a rectangle is pointed at to find out whether it's
  // worth going to, and having the whole window follow the mouse made that impossible to read.
  val details = clickedDetails
  // And what the floating chain says, which is nothing until the cell under the pointer has been read: the
  // details of the cell before it belong to a rectangle the pointer has left.
  val hoveredCellDetails = hoveredDetails?.takeIf { it.request == hoveredRequest }
  val describedSummary = (details?.selection as? Selection.Object)?.summary

  val treemapLayout = rememberTreemapLayout()
  val radialLayout = rememberRadialLayout()
  val stackLayout = rememberStackLayout()
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
      radialLayout = radialLayout,
      stackLayout = stackLayout
    )
  }

  // Resizing, zooming and switching shape all lay the tree out again, which reads the heap dump for
  // every visible label. All of it ends up here, on the heap dump's thread. Keyed on the request rather
  // than on the screen, so that reading a list of objects doesn't lay the map out again and the map is
  // ready by the time a screen is left for it.
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
            "${nodeIdText(requested.path[reachablePath.path.size])} is no node of the tree"
        }
      }
      ViewState(
        navigation = reachablePath,
        presentation = when (viewRequest.shape) {
          ViewShape.TREEMAP -> ViewPresentation.Treemap(
            TreemapPresentation.of(
              tree,
              viewRequest.treemapLayout,
              viewRequest.viewport,
              reachablePath.current
            )
          )
          ViewShape.RADIAL -> ViewPresentation.Radial(
            RadialPresentation.of(
              tree,
              viewRequest.radialLayout,
              viewRequest.viewport,
              reachablePath.current
            )
          )
          ViewShape.STACK -> ViewPresentation.Stack(
            StackPresentation.of(
              tree,
              viewRequest.stackLayout,
              viewRequest.viewport,
              reachablePath.current
            )
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
  // again. Pixels fetched along with the dump go in here, before anything has been asked to draw: adding
  // them later would mean every bitmap already on screen having to be asked for a second time.
  LaunchedEffect(session) {
    bitmapCounts = session.read("how many bitmaps ${session.heapDumpFile.name} has") { explorer ->
      if (fetchedBitmapPixels == null) {
        explorer.tree.bitmapCounts()
      } else {
        explorer.tree.addNativeBitmapPixels(fetchedBitmapPixels)
      }
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

  // Reading what a cell stands for is a heap dump read too, so the panels fill in a beat after the click.
  // Keyed on the request, so that nothing else clears them.
  LaunchedEffect(session, clicked?.request) {
    val clickedRequest = clicked?.request
    if (clickedRequest == null) {
      clickedDetails = null
      return@LaunchedEffect
    }
    session.describing(clickedRequest) { clickedDetails = it }
  }

  // The same for the cell under the pointer, once it has stayed on one long enough to be looking at it.
  // Without that wait, a sweep across the map would ask about every rectangle it crossed: the reads that
  // are no longer wanted do get called off, but only where they next read the heap dump.
  LaunchedEffect(session, hoveredRequest) {
    if (hoveredRequest == null) {
      // Whatever was read for the last cell stays where it is: nothing is waiting for it, and the panels
      // are showing the clicked cell again anyway.
      return@LaunchedEffect
    }
    delay(HOVER_SETTLE_MILLIS)
    session.describing(hoveredRequest) { hoveredDetails = it }
  }

  // The panel shows the bitmap it describes as big as the panel is wide, so its pixels are read again at
  // that size: a treemap rectangle is a couple of hundred pixels across and this is four times that.
  var describedBitmap: ImageBitmap? by remember { mutableStateOf(null) }
  LaunchedEffect(session, describedSummary?.objectId, bitmapRevision) {
    val objectId = describedSummary?.objectId
    // Nothing says whether what's described is a bitmap at all, and asking is cheaper than tracking it: an
    // object that isn't one comes back with no image.
    val image = if (objectId == null) {
      null
    } else {
      session.read("the pixels of ${hexObjectId(objectId)}") { explorer ->
        explorer.tree.bitmapImages(listOf(objectId), MAX_PANEL_BITMAP_PIXELS)[objectId]
      }
    }
    describedBitmap = image?.let { withContext(Dispatchers.Default) { it.toImageBitmap() } }
  }

  // Which stretches of the chain could have run elsewhere is a walk in memory per stretch, over an index of
  // what points at what, and each walks several times. Far too much to run as the pointer moves, so it is
  // only ever asked for the object clicked, and only once the chain to it is known — the chain is what says
  // where the stretches are. See [shark.explorer.detours].
  val clickedRootPath = clickedDetails?.rootPath
  LaunchedEffect(session, clickedRootPath) {
    detourWays = emptyMap()
    chosenWays = emptyMap()
    val path = clickedRootPath ?: return@LaunchedEffect
    val detours = path.detours()
    val target = path.steps.lastOrNull()?.step?.objectId
    if (detours.isEmpty() || target == null) {
      return@LaunchedEffect
    }
    detourWays = session.read("the other ways ${hexObjectId(target)} is held") { explorer ->
      detours.associate { detour ->
        val from = detour.fromObjectId
        val found = if (from == null) {
          explorer.tree.independentPathsFromRoots(detour.toObjectId)
        } else {
          explorer.tree.independentPathsBetween(from, detour.toObjectId)
        }
        detour.fromIndex to path.waysOf(detour, found)
      }
    }
  }

  // Walking up to the GC roots needs an index of which object points at which, and building it is a pass
  // over the whole heap dump: seconds on a large one, once per session. Started as soon as the map is up
  // rather than left for the first rectangle the pointer lands on, and after it so that the map isn't the
  // thing waiting.
  //
  // The leaks come with it, for the same reason and in the same breath: the map is shaded by them, so they
  // are what the window shows before anyone asks it anything, rather than what a checkbox goes looking for.
  LaunchedEffect(session) {
    snapshotFlow { view }.first { it !== ViewState.EMPTY }
    val objectCount = session.read("the index of what points at what") { explorer ->
      explorer.tree.indexReferrers()
    }
    SharkLog.d { "Indexed what points at each of ${formatObjectCount(objectCount)}" }
    leaks = session.read("the leaking objects of ${session.heapDumpFile.name}") { explorer ->
      explorer.tree.findLeaks()
    }
    isFindingLeaks = false
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

  // Whether the map is rooted inside a leak, which the cells themselves can't say: a rectangle is drawn
  // inside the one that dominates it, so a view rooted below a leaking object has every rectangle of it
  // leaking and not one of them in the list. A walk up the dominators, so it follows where the map is.
  val leakingObjectIds = remember(leaks) { leaks?.leakingObjectIds ?: emptySet() }
  LaunchedEffect(session, view.navigation.current, leaks) {
    val rootNode = view.navigation.current
    isRootLeaking = leaks != null && session.read("whether ${nodeIdText(rootNode)} is below a leak") {
      it.tree.isBelowLeakingObject(rootNode)
    }
  }

  // Where the treemap draws a node takes walking up its dominators, so showing what a panel line or a row
  // of a list leads to is a heap dump read as well.
  LaunchedEffect(session, nodeToOpen) {
    val move = nodeToOpen ?: return@LaunchedEffect
    val openNodeId = move.nodeId
    val path = session.read("where the map draws ${nodeIdText(openNodeId)}") { explorer ->
      explorer.tree.pathToOpen(openNodeId)
    }
    if (openNodeId != ROOT_NODE && path == listOf(ROOT_NODE)) {
      // Clicking a field or a row led to an object the tree has no node for, so the map has nowhere to
      // go and stays on the whole heap dump.
      SharkLog.d { "${nodeIdText(openNodeId)} is nowhere on the map: it is no node of the tree" }
    }
    val zoomed = history.current.treeNavigation.zoomInto(path)
    history = history.goTo(ExplorerScreen.Tree(zoomed, openNodeId))
    clicked = move.described
    nodeToOpen = null
  }

  // Only what the pointer is on, never where the explorer is: moving the mouse across the map is not a
  // move, so it leaves the back arrow alone.
  val onHover: (PointedAt?) -> Unit = { pointedAt ->
    pointerCell = pointedAt?.let { DescribedCell.of(it.cell) }
    pointerOffset = pointedAt?.offset
  }
  /**
   * Where a click on the view goes, which is wherever was clicked. Every rectangle is a move.
   *
   * Through the same walk as a click on a line of a panel, so that the two land in the same place: an
   * object that dominates nothing is shown inside what holds it and described there, rather than as a view
   * with one rectangle in it and nothing to read.
   */
  val onClick: (LayoutCell<Long>) -> Unit = { cell ->
    val subject = cell.subject
    nodeToOpen = if (subject is CellSubject.Group) {
      // The siblings too small to draw are no node of the tree, so where a click on them goes is the
      // rectangle they were left out of: rooted there, the map has the room to draw them one by one.
      // The panels stay on the pile, because the pile is what was clicked.
      NodeToOpen(subject.parent, DescribedCell.of(cell))
    } else {
      NodeToOpen.of(SelectedCell.of(subject).objectId)
    }
  }
  /** Shows a node on the map with it selected, and describes it, which is the object detail view. */
  val onOpen: (Long) -> Unit = { nodeId -> nodeToOpen = NodeToOpen.of(nodeId) }
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
      onShowWholeHeapDump = { onOpen(ROOT_NODE) },
      onListObjects = {
        onGoTo(ExplorerScreen.Objects(navigation, ObjectListFilter(), screen.describedNode))
      },
      onShowLeaks = {
        onGoTo(ExplorerScreen.Leaks(navigation, describedNode = screen.describedNode))
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
      // Which object the window is about, beside the arrows that moved to it: above every screen rather than
      // in a pane, because it is the one thing that is as true of a list of objects as of the map.
      DescribedObject(details?.selection)
    }
    Row(Modifier.weight(1f)) {
      // The chain first, the map, then what the object holds: read left to right that is where the object
      // came from, where it is, and what it is keeping alive. Beside the map alone — a list of objects wants
      // the width of the window more than it wants either pane.
      if (screen is ExplorerScreen.Tree) {
        RootPathPanel(
          selection = details?.selection,
          rootPath = details?.rootPath,
          // What the pointer is on, which is drawn onto the end of the chain rather than over it.
          hoveredSelection = hoveredCellDetails?.selection,
          hoveredRootPath = hoveredCellDetails?.rootPath,
          rootNodeId = view.navigation.current,
          ways = detourWays,
          chosenWays = chosenWays,
          onChooseWay = { detour, way -> chosenWays = chosenWays + (detour to way) },
          onOpen = onOpen,
          modifier = Modifier.width(ROOT_PATH_WIDTH).fillMaxHeight()
        )
      }
      Column(Modifier.weight(1f).fillMaxHeight()) {
        // Above the view and as wide as it, because that's what it controls, and only there: the list of
        // objects is coloured by nothing and shaped like a list.
        if (screen is ExplorerScreen.Tree) {
          ViewControls(
            sizes = sizes,
            shape = shape,
            coloring = coloring,
            leakCount = leaks?.objectCount,
            isFindingLeaks = isFindingLeaks,
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
              shading = LeakShading(leakingObjectIds, isRootLeaking),
              selected = clicked?.cell,
              hovered = hovered?.cell,
              // What the pointer is on, for the card that follows it, and where the pointer is. Read
              // already: a card that appears empty and fills in a beat later reads as a flicker.
              pointedSelection = hoveredCellDetails?.selection,
              pointerOffset = pointerOffset,
              viewSize = viewportSize,
              isLayingOut = isLayingOut,
              bitmapImages = bitmapImages,
              onHover = onHover,
              onClick = onClick,
              // Measured here rather than around every screen, so that leaving the map and coming back
              // doesn't lay it out twice for a viewport that ends up the size it already was.
              modifier = Modifier.fillMaxSize().onSizeChanged { viewportSize = it }
            )
            is ExplorerScreen.Objects -> ObjectsScreen(
              list = objects,
              filter = screen.filter,
              isListing = isListing,
              onFilterChange = { filter ->
                // A keystroke isn't a move, so typing replaces where the explorer is: the back arrow
                // leaves the list rather than walking back through what was typed into it.
                history = history.replacingCurrent(screen.copy(filter = filter))
              },
              onOpen = onOpen,
              modifier = Modifier.fillMaxSize()
            )
            is ExplorerScreen.Leaks -> LeaksScreen(
              leaks = leaks ?: HeapLeaks.NONE,
              isFindingLeaks = isFindingLeaks,
              expandedGroups = screen.expandedGroups,
              // Unfolding a leak isn't a move, so it replaces where the explorer is: the back arrow
              // leaves the leaks rather than folding them up one at a time.
              onToggleGroup = { groupKey ->
                val expanded = screen.expandedGroups
                history = history.replacingCurrent(
                  screen.copy(
                    expandedGroups = if (groupKey in expanded) {
                      expanded - groupKey
                    } else {
                      expanded + groupKey
                    }
                  )
                )
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
      if (screen is ExplorerScreen.Tree) {
        DetailsPanel(
          selection = details?.selection,
          bitmap = describedBitmap,
          isStarred = favourites.any { it.objectId == describedSummary?.objectId },
          onOpen = onOpen,
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
            val summary = describedSummary
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
                // Described, since a summary to star came from the same read as the dominator beside it.
                favourites + Favourite.of(summary, details.dominator)
              }
            }
          },
          modifier = Modifier.width(DETAILS_WIDTH).fillMaxHeight()
        )
      }
    }
  }
}

/**
 * Which object the window is about, in the bar above every screen.
 *
 * Above rather than in the details panel, where it used to be, because it is the answer to a different
 * question than the rest of that panel: which object, as against what that object holds. It is also the one
 * line that is worth having on a list of objects and on the starred ones, where there is no panel.
 */
@Composable
private fun DescribedObject(selection: Selection?) {
  when (selection) {
    null -> Unit
    // Selectable so it can be copied out: an object id is how you point something else — a script, a
    // colleague, a bug report — at this one instance rather than at its class.
    is Selection.Object -> SelectionContainer {
      ObjectIdentity(
        className = selection.summary.className,
        typeName = selection.summary.kind?.typeName,
        objectId = selection.summary.objectId
      )
    }
    is Selection.ObjectGroup -> Text(
      selection.summary.className ?: HeapDominatorTreemap.UNREACHABLE_LABEL,
      style = MaterialTheme.typography.bodyMedium
    )
    is Selection.Group -> Text(
      "${selection.nodeCount} smaller objects",
      style = MaterialTheme.typography.bodyMedium
    )
  }
}

/** The screens an open heap dump can be read through, and how many objects are starred. */
@Composable
private fun ScreenBar(
  starredCount: Int,
  bitmapCounts: BitmapCounts,
  onShowWholeHeapDump: () -> Unit,
  onListObjects: () -> Unit,
  onShowLeaks: () -> Unit,
  onShowStarred: () -> Unit,
  onFetchBitmaps: () -> Unit
) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    // First, because it is the screen the others were opened from and the way back out to all of it: the
    // map zoomed in far enough is several clicks and a guess away from the top, and the back arrow walks
    // where the reader has been rather than out.
    TextButton(onClick = onShowWholeHeapDump) {
      Text(HeapDominatorTreemap.ROOT_LABEL)
    }
    TextButton(onClick = onListObjects) {
      Text(ExplorerScreen.OBJECTS_LABEL)
    }
    // Beside the list of every object, because it is the same list with the answer already found in it:
    // the objects that shouldn't be there, gathered into the leaks they are instances of.
    TextButton(onClick = onShowLeaks) {
      Text(ExplorerScreen.LEAKS_LABEL)
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

/** The dominator tree, drawn as one of the [ViewShape]s, with a card naming what the pointer is on. */
@Composable
private fun TreeScreen(
  view: ViewState,
  coloring: CellColoring,
  /** Which of the objects drawn are leaking, for the colouring that shades them. */
  shading: LeakShading,
  selected: SelectedCell?,
  hovered: SelectedCell?,
  /** What the cell under the pointer is, once it has been read, and null while it hasn't. */
  pointedSelection: Selection?,
  /** Where the pointer is in the view, which is what the card naming that cell is placed by. */
  pointerOffset: Offset?,
  /** How big the view is, so that the card stays inside it. */
  viewSize: IntSize,
  isLayingOut: Boolean,
  /** The pixels read for the bitmaps of the treemap so far, by object id. */
  bitmapImages: Map<Long, ImageBitmap>,
  onHover: (PointedAt?) -> Unit,
  onClick: (LayoutCell<Long>) -> Unit,
  modifier: Modifier = Modifier
) {
  // Kept across cards rather than per card, so that the first frame of the next one is placed by the size of
  // the last: measuring one and placing it are a frame apart, and a card is a card's width of text.
  var cardSize by remember { mutableStateOf(IntSize.Zero) }
  val cardGap = with(LocalDensity.current) { POINTER_CARD_GAP.toPx() }
  // A shape drawn into one canvas is nothing to anything that isn't looking at it, which is what this
  // says instead. It's also how a test finds where the view starts, since none of the cells is a node
  // of its own.
  Box(modifier.semantics { contentDescription = VIEW_DESCRIPTION }) {
    when (val presentation = view.presentation) {
      is ViewPresentation.Treemap -> TreemapView(
        presentation = presentation.presentation,
        coloring = coloring,
        shading = shading,
        selected = selected,
        bitmapImages = bitmapImages,
        hovered = hovered,
        onHover = onHover,
        onClick = onClick,
        modifier = Modifier.fillMaxSize()
      )
      is ViewPresentation.Radial -> RadialView(
        presentation = presentation.presentation,
        coloring = coloring,
        shading = shading,
        selected = selected,
        hovered = hovered,
        onHover = onHover,
        onClick = onClick,
        modifier = Modifier.fillMaxSize()
      )
      is ViewPresentation.Stack -> StackView(
        presentation = presentation.presentation,
        coloring = coloring,
        shading = shading,
        selected = selected,
        hovered = hovered,
        onHover = onHover,
        onClick = onClick,
        modifier = Modifier.fillMaxSize()
      )
    }
    // Beside the pointer, over the map, because what a rectangle is, is the question being asked by pointing
    // at it: an answer at the edge of the window is read by looking away from the rectangle it is about.
    if (pointedSelection != null && pointerOffset != null) {
      PointerCard(
        selection = pointedSelection,
        modifier = Modifier
          .onSizeChanged { cardSize = it }
          // Placed as it is laid out rather than in a state read while composing, so that a card that has
          // just changed size lands in one frame.
          .offset { placeCard(pointerOffset, cardSize, viewSize, cardGap) }
          // Until the very first card of a window has been measured there is nowhere to put it, and the
          // pointer's own corner is the one place it must not be.
          .alpha(if (cardSize == IntSize.Zero) 0f else 1f)
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

/** And the stack layout, whose row height is a line of text and so is scaled like the rest of them. */
@Composable
private fun rememberStackLayout(): StackLayout<Long> {
  val density = LocalDensity.current
  return remember(density) {
    with(density) {
      StackLayout(
        rowHeight = STACK_ROW_HEIGHT.toPx().toDouble(),
        minSubdivideWidth = MIN_SUBDIVIDE_STACK_WIDTH.toPx().toDouble(),
        minDrawWidth = MIN_DRAW_STACK_WIDTH.toPx().toDouble()
      )
    }
  }
}

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
  val radialLayout: RadialLayout<Long>,
  val stackLayout: StackLayout<Long>
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
  "the ${shape.displayName.lowercase()} rooted at ${nodeIdText(navigation.current)}, " +
    "${viewportSize.width}×${viewportSize.height}"

/** Everything read off the heap dump's thread to draw one view of the tree. */
internal class ViewState(
  /** The path actually laid out, which can be shorter than the one asked for. */
  val navigation: TreemapNavigation<Long>,
  val presentation: ViewPresentation
) {
  companion object {
    /** Nothing laid out yet. An empty treemap and an empty radial view draw the same nothing. */
    val EMPTY = ViewState(
      navigation = TreemapNavigation(HeapDominatorTreemap.ROOT_OBJECT_ID),
      presentation = ViewPresentation.Treemap(TreemapPresentation.EMPTY)
    )
  }
}

/** One [ViewShape]'s worth of laid out cells. */
internal sealed interface ViewPresentation {

  data class Treemap(val presentation: TreemapPresentation) : ViewPresentation

  data class Radial(val presentation: RadialPresentation) : ViewPresentation

  data class Stack(val presentation: StackPresentation) : ViewPresentation
}

/** What a laid out view amounts to, for the log: one that drew nothing at all says so here. */
private fun ViewPresentation.description(): String = when (this) {
  is ViewPresentation.Treemap ->
    "${presentation.cells.size} rectangles, ${presentation.truncatedNodeCount} nodes not expanded"
  is ViewPresentation.Radial ->
    "${presentation.cells.size} sectors, ${presentation.truncatedNodeCount} nodes not expanded"
  // How deep it came out as well, since that is what a stack has instead of a shape that fits the view.
  is ViewPresentation.Stack ->
    "${presentation.cells.size} blocks in ${presentation.layout.rowCount} rows, " +
      "${presentation.truncatedNodeCount} nodes not expanded"
}

/**
 * A cell described somewhere in the window: which one to outline, and what to read the heap dump for.
 *
 * There are two of these at a time — the cell clicked, which the panels are about, and the cell under the
 * pointer, which the floating chain is. Keeping them apart is what makes moving the pointer off the map
 * free: the clicked cell's details were never thrown away, so putting the chain for it back is not a read.
 */
private data class DescribedCell(
  val cell: SelectedCell,
  val request: SelectionRequest
) {
  companion object {
    fun of(cell: LayoutCell<Long>): DescribedCell = DescribedCell(
      cell = SelectedCell.of(cell.subject),
      request = SelectionRequest.of(cell)
    )

    /** A node of the tree, which is what a move arrives at and what every pane but the map leads to. */
    fun of(nodeId: Long): DescribedCell = DescribedCell(
      // Never a group: the one cell that isn't a node stands for the siblings its parent didn't draw,
      // and nothing outside the map leads to one of those.
      cell = SelectedCell(nodeId, isGroup = false),
      request = SelectionRequest.Object(nodeId)
    )
  }
}

/**
 * A move the map is about to make, waiting on the read that says where it draws [nodeId].
 *
 * [described] is what the panels are about once there, which is the destination itself for every move but
 * a click on the siblings a rectangle had no room for: those are no node of the tree, so the map goes to
 * the rectangle holding them while the panels stay on the pile that was clicked.
 */
private data class NodeToOpen(
  val nodeId: Long,
  val described: DescribedCell
) {
  companion object {
    fun of(nodeId: Long) = NodeToOpen(nodeId, DescribedCell.of(nodeId))
  }
}

/**
 * Everything the panels say about one cell, filled in over the two reads it takes.
 *
 * What a cell is comes first and the chain holding it after, because the chain is the slower of the two by
 * far: what a rectangle stands for should never wait on a walk up to the GC roots.
 */
private class CellDetails(
  /** Which cell these are of, so that a pane can tell them from the ones it is waiting to replace. */
  val request: SelectionRequest,
  val selection: Selection?,
  val dominator: ObjectDominator?,
  /** Null until the walk up to the GC roots comes back. */
  val rootPath: RootPath?
)

/**
 * Reads what one cell is, then how a GC root reaches it, handing each to [onDetails] as it arrives.
 *
 * Two reads rather than one so that the panels fill in progressively, and both of them here rather than in
 * an effect each so that a cell is described in the order it's read: a chain and a summary of two different
 * objects side by side is the one way these panels can lie.
 */
private suspend fun HeapDumpSession.describing(
  request: SelectionRequest,
  onDetails: (CellDetails) -> Unit
) {
  val cellDetails = read(request.description()) { explorer ->
    val tree = explorer.tree
    val selection = when (request) {
      is SelectionRequest.Object -> {
        val group = tree.groupOrNull(request.objectId)
        when {
          group != null -> Selection.ObjectGroup(group)
          request.objectId in tree -> Selection.Object(tree.summarize(request.objectId))
          // Which is why the panel goes back to saying nothing is selected.
          else -> {
            SharkLog.d {
              "Nothing to describe for ${nodeIdText(request.objectId)}: it is no node of the tree"
            }
            null
          }
        }
      }
      is SelectionRequest.Group -> Selection.Group(
        nodeCount = request.nodeCount,
        byteCount = request.byteCount,
        parentLabel = tree.label(request.parentObjectId)
      )
    }
    // The tree already knows what dominates what, so this is a read of one label rather than a search,
    // and it belongs in the same read: two of them means the panel showing a dominator a beat late.
    val objectId = (selection as? Selection.Object)?.summary?.objectId
    CellDetails(
      request = request,
      selection = selection,
      dominator = objectId?.let { tree.dominatorOf(it) },
      rootPath = null
    )
  }
  onDetails(cellDetails)
  val objectId = (cellDetails.selection as? Selection.Object)?.summary?.objectId ?: return
  val rootPath = read("what holds ${hexObjectId(objectId)}") { explorer ->
    explorer.tree.rootPathTo(objectId)
  }
  onDetails(
    CellDetails(
      request = request,
      selection = cellDetails.selection,
      dominator = cellDetails.dominator,
      rootPath = rootPath
    )
  )
}

/** A cell the panels have been asked about, before the heap dump has been read for it. */
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
  is SelectionRequest.Object -> "what ${nodeIdText(objectId)} is"
  is SelectionRequest.Group -> "what the $nodeCount objects under ${nodeIdText(parentObjectId)} are"
}

private const val ROOT_NODE = HeapDominatorTreemap.ROOT_OBJECT_ID

/** How long the search box waits for the typing to stop before reading the whole heap dump again. */
private const val FILTER_SETTLE_MILLIS = 250L

/**
 * And how long the pointer has to stay on one cell before the heap dump is read for it.
 *
 * Short enough that landing on a rectangle and reading about it feels like one thing, long enough that
 * crossing the map costs the rectangles the pointer stopped on rather than every rectangle on the way.
 */
private const val HOVER_SETTLE_MILLIS = 100L

internal const val BACK_ARROW = "←"
internal const val FORWARD_ARROW = "→"

/** What the button that goes back to the live process offers, before it says how many bitmaps. */
internal const val FETCH_BITMAPS = "Fetch the pixels of"

/**
 * What the tree looks like to anything that can't look at it, a screen reader or a test.
 *
 * The same for every [ViewShape], because it says what is drawn rather than how: only one of the three
 * draws a cell inside the one that holds it.
 */
internal const val VIEW_DESCRIPTION =
  "The dominator tree of the heap dump: every cell is an object, as big as what it retains."
