package shark.explorer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import shark.explorer.DeviceHeapDumps
import shark.explorer.HeapLeaks
import shark.explorer.HeapObjectKind
import shark.explorer.HeapSizes
import shark.explorer.LayoutCell
import shark.explorer.NativeBitmapPixels
import shark.explorer.ObjectDominator
import shark.explorer.ObjectList
import shark.explorer.ObjectListFilter
import shark.explorer.Place
import shark.explorer.PresentedCell
import shark.explorer.RadialLayout
import shark.explorer.RadialPresentation
import shark.explorer.RootPath
import shark.explorer.RootPathWay
import shark.explorer.SemanticDominatorTreemap
import shark.explorer.StackLayout
import shark.explorer.StackPresentation
import shark.explorer.Tabs
import shark.explorer.TreemapLayout
import shark.explorer.TreemapPresentation
import shark.explorer.TreemapRect
import shark.explorer.detours
import shark.explorer.formatObjectCount
import shark.explorer.hexObjectId
import shark.explorer.nodeIdText
import shark.explorer.titleOf
import shark.explorer.waysOf

/**
 * One open heap dump, read through the tabs open on it.
 *
 * **An object is the thing this window is about**, and a tab open on one answers three questions at once,
 * left to right: what holds it, which is the chain from a GC root; what it holds, which is the dominator
 * tree **rooted at the object itself**; and what it is, which is the details panel. Each of the three
 * folds away and the outer two are dragged wider, because which of them the work is in changes with what
 * is being chased. See [Pane].
 *
 * Every read of the heap dump goes through [session], which puts it on a thread of its own, so what's
 * drawn is state that arrives a little after whatever asked for it changed: a presentation is a view
 * already laid out and labelled somewhere else, and a selection is a summary already read.
 *
 * **A click goes to a rectangle and the pointer asks about one.** So the window is about the object the
 * tab is on — the bar, the chain, the details panel, the star — and what the pointer is on gets a card at
 * the pointer and a few more steps on the end of the chain, which is enough to tell whether it's worth
 * going there.
 *
 * Where the window is, is one [Tabs] of [Place]s, and every pane follows from it. A place is the whole of
 * where a tab is, so the panes cannot describe something other than what is being shown — which was the
 * one thing about the old window that read as a bug, and used to be kept true by hand in four places.
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
  var tabs by remember { mutableStateOf(Tabs.opening(Place.wholeHeapDump())) }
  /** Where the tab being read is, and null once the last tab has been closed. */
  val place = tabs.place
  /** Which object the middle view is rooted at, which for a list place is none. */
  val viewRootObjectId = place?.viewRootObjectId
  val panes = remember { PanesState() }
  var shape by remember { mutableStateOf(ViewShape.TREEMAP) }
  var coloring by remember { mutableStateOf(CellColoring.DEFAULT) }
  var viewportSize by remember { mutableStateOf(IntSize.Zero) }
  var view by remember { mutableStateOf(ViewState.EMPTY) }
  var isLayingOut by remember { mutableStateOf(true) }
  /** Where the pointer last was on the view, which is what the floating chain describes while it's there. */
  var pointerCell: PointedCell? by remember { mutableStateOf(null) }
  /** And where in the view it was, which is where the card naming what it's on goes. See [PointerCard]. */
  var pointerOffset: Offset? by remember { mutableStateOf(null) }
  /** What the place the tab is on is, kept while the next one is being read so the panes never blank out. */
  var details: PlaceDetails? by remember { mutableStateOf(null) }
  /** And what the cell under the pointer is, once it has stayed on one long enough to be read. */
  var hoveredDetails: PlaceDetails? by remember { mutableStateOf(null) }
  /**
   * The other ways each stretch of the object's chain could have run, by
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
  /** What each tab is called, by the place it is on. Only grows: a place is named once and stays named. */
  var placeTitles by remember { mutableStateOf(emptyMap<Place, String>()) }

  // Nothing is under the pointer while a list is showing: the view isn't there, so the rectangle it was on
  // last is neither where the pointer is now nor what the tab is about.
  val hovered = pointerCell.takeIf { viewRootObjectId != null }
  // What the pointer being where it is leaves to read, which is nothing when it's on the place the tab is
  // already at: the panes are describing that one.
  val hoveredPlace = hovered?.place?.takeIf { it != place }
  // And what the floating chain says, which is nothing until the cell under the pointer has been read: the
  // details of the cell before it belong to a rectangle the pointer has left.
  val hoveredCellDetails = hoveredDetails?.takeIf { it.place == hoveredPlace }
  val describedSummary = (details?.selection as? Selection.Object)?.summary

  val treemapLayout = rememberTreemapLayout()
  val radialLayout = rememberRadialLayout()
  val stackLayout = rememberStackLayout()
  // In pixels, like everything a layout is measured in, so that how small is too small for an image to be
  // worth drawing is the same size on every display. See MIN_BITMAP_DRAW_SIZE.
  val minBitmapDrawSize = with(LocalDensity.current) { MIN_BITMAP_DRAW_SIZE.toPx() }

  // Everything one laid out view follows from, as one value: a view is asked for, and the one that comes
  // back is the answer to it. Null until the view has been measured, and for a place that is a list.
  val viewRequest = if (viewRootObjectId == null || viewportSize == IntSize.Zero) {
    null
  } else {
    ViewRequest(
      rootObjectId = viewRootObjectId,
      viewportSize = viewportSize,
      shape = shape,
      treemapLayout = treemapLayout,
      radialLayout = radialLayout,
      stackLayout = stackLayout
    )
  }

  // What each tab is called, for the tabs the window couldn't name itself. A label is cheap enough to draw
  // on every rectangle of the map, so naming a strip of them is one small read — but it is still a read,
  // and reads queue on the heap dump's one thread.
  //
  // Which is why this goes in ahead of the effect that lays the view out: opening a tab asks for both, and
  // the layout is the larger by orders of magnitude. Named after it, a tab would show its placeholder for
  // as long as laying the tree out takes, which on a real dump is what someone sees as a flicker.
  val unnamedPlaces = tabs.tabs.map { it.place }.filter { it.title == null && it !in placeTitles }
  LaunchedEffect(session, unnamedPlaces) {
    if (unnamedPlaces.isEmpty()) {
      return@LaunchedEffect
    }
    val named = session.read("what to call ${unnamedPlaces.size} tabs") { explorer ->
      unnamedPlaces.associateWith { explorer.tree.titleOf(it) }
    }
    placeTitles = placeTitles + named
  }

  // Resizing, going to an object and switching shape all lay the tree out again, which reads the heap dump
  // for every visible label. All of it ends up here, on the heap dump's thread. Keyed on the request rather
  // than on the place, so that typing into a list doesn't lay the map out again and the map of the tab
  // behind a list is ready by the time the list is left for it.
  LaunchedEffect(session, viewRequest) {
    if (viewRequest == null) {
      // Which is what a window showing a spinner and nothing else has been waiting for all along.
      SharkLog.d { "Not laying the tree out: no object to root it at, or the view has no size" }
      return@LaunchedEffect
    }
    isLayingOut = true
    view = session.read(viewRequest.description()) { explorer ->
      val tree = explorer.tree
      val requested = viewRequest.rootObjectId
      // A field or a row can lead to an object this tree has no node for, and there is nowhere to root a
      // view at one of those.
      val rooted = if (requested in tree) {
        requested
      } else {
        SharkLog.d {
          "${nodeIdText(requested)} is no node of the tree: rooting the view at the whole heap dump"
        }
        SemanticDominatorTreemap.ROOT_OBJECT_ID
      }
      ViewState(
        rootObjectId = rooted,
        presentation = when (viewRequest.shape) {
          ViewShape.TREEMAP -> ViewPresentation.Treemap(
            TreemapPresentation.of(tree, viewRequest.treemapLayout, viewRequest.viewport, rooted)
          )
          ViewShape.RADIAL -> ViewPresentation.Radial(
            RadialPresentation.of(tree, viewRequest.radialLayout, viewRequest.viewport, rooted)
          )
          ViewShape.STACK -> ViewPresentation.Stack(
            StackPresentation.of(tree, viewRequest.stackLayout, viewRequest.viewport, rooted)
          )
        }
      )
    }
    // What a view that came out looking empty or coarse actually drew. An object that dominates nothing is
    // one rectangle — its own bytes — which is the honest answer to what it holds, rather than a picture of
    // something else.
    SharkLog.d { "Laid out ${view.presentation.description()}" }
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
  // the presentation rather than the heap dump: going to an object asks for the bitmaps that brought into
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

  // What the tab is on, which is one read of the heap dump per move. Keyed on the place, so that nothing
  // else clears the panes — and because the place is the whole of where the tab is, there is no second
  // piece of state for this to fall out of step with.
  LaunchedEffect(session, place) {
    if (place == null || place.viewRootObjectId == null) {
      details = null
      return@LaunchedEffect
    }
    session.describing(place) { details = it }
  }

  // The same for the cell under the pointer, once it has stayed on one long enough to be looking at it.
  // Without that wait, a sweep across the map would ask about every rectangle it crossed: the reads that
  // are no longer wanted do get called off, but only where they next read the heap dump.
  LaunchedEffect(session, hoveredPlace) {
    if (hoveredPlace == null) {
      // Whatever was read for the last cell stays where it is: nothing is waiting for it, and the panes
      // are showing the tab's own place again anyway.
      return@LaunchedEffect
    }
    delay(HOVER_SETTLE_MILLIS)
    session.describing(hoveredPlace) { hoveredDetails = it }
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
  // only ever asked for the object the tab is on, and only once the chain to it is known — the chain is what
  // says where the stretches are. See [shark.explorer.detours].
  val describedRootPath = details?.rootPath
  LaunchedEffect(session, describedRootPath) {
    detourWays = emptyMap()
    chosenWays = emptyMap()
    val path = describedRootPath ?: return@LaunchedEffect
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
  val objectFilter = (place as? Place.Objects)?.filter
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
  LaunchedEffect(session, view.rootObjectId, leaks) {
    val rootNode = view.rootObjectId
    isRootLeaking = leaks != null && session.read("whether ${nodeIdText(rootNode)} is below a leak") {
      it.tree.isBelowLeakingObject(rootNode)
    }
  }

  // Only what the pointer is on, never where the window is: moving the mouse across the map is not a
  // move, so it leaves the back arrow alone.
  val onHover: (PointedAt?) -> Unit = { pointedAt ->
    pointerCell = pointedAt?.let { PointedCell.of(it.cell) }
    pointerOffset = pointedAt?.offset
  }
  /**
   * Where every way to a place in this window ends up, which is the whole of what a click means.
   *
   * One function for a rectangle of the map, a step of the chain, a field of the panel, a row of a list
   * and a button on the bar, so that they cannot drift apart — which is what "clicking an object always
   * does the same thing" has to mean to be true.
   */
  val open: (Place, OpenIn) -> Unit = { destination, openIn ->
    // Named before the tab exists, from the view the click came from: the read that names tabs is a beat
    // behind however small it is, and a tab that opens under a placeholder is one whose title, and width,
    // change as you watch. Everything a view draws is named already, which is most of what is ever clicked.
    view.presentation.cells.titleOf(destination)?.let { title ->
      placeTitles = placeTitles + (destination to title)
    }
    tabs = when (openIn) {
      OpenIn.CURRENT_TAB -> tabs.goTo(destination)
      OpenIn.NEW_TAB -> tabs.open(destination, inBackground = true)
    }
  }
  /** The same, for the panes and lists that lead to an object by its id. */
  val openObject: (Long, OpenIn) -> Unit = { objectId, openIn ->
    open(Place.Object(objectId), openIn)
  }
  /** And for the buttons on the bar, which always open a tab of their own, in front. */
  val openInNewTab: (Place) -> Unit = { destination -> tabs = tabs.open(destination) }
  /** Where a click on the view goes, which is wherever was clicked. Every rectangle is a move. */
  val onClickCell: (LayoutCell<Long>, OpenIn) -> Unit = { cell, openIn ->
    open(Place.of(cell), openIn)
  }
  /**
   * And where the view's right click menu goes, which is whatever the pointer is on.
   *
   * The menu can only be opened where the pointer already is, so what it is on is what the hover has
   * been reading all along — there is no second hit test to run for it.
   */
  val openHovered: (OpenIn) -> Unit = { openIn -> hovered?.place?.let { open(it, openIn) } }

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
      onShowWholeHeapDump = { openInNewTab(Place.wholeHeapDump()) },
      onListObjects = { openInNewTab(Place.Objects()) },
      onShowLeaks = { openInNewTab(Place.Leaks()) },
      onShowStarred = { openInNewTab(Place.Starred) },
      onFetchBitmaps = { showsBitmapsFromDevice = true }
    )
    TabStrip(
      tabs = tabs,
      titleOf = { it.title ?: placeTitles[it] ?: NAMING_TAB },
      onSelect = { id -> tabs = tabs.select(id) },
      onClose = { id -> tabs = tabs.close(id) }
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
      HistoryArrows(
        canGoBack = tabs.canGoBack,
        canGoForward = tabs.canGoForward,
        // A move undone is a place again, and the panes follow it: there is nothing else to put back.
        onBack = { tabs = tabs.goBack() },
        onForward = { tabs = tabs.goForward() }
      )
      // Which object the tab is on, beside the arrows that moved to it: above the panes rather than in
      // one, because it is the one thing that is as true of a list of objects as of the map.
      DescribedObject(details?.selection)
    }
    Box(Modifier.weight(1f)) {
      when {
        place == null -> NoTabOpen(Modifier.fillMaxSize())
        viewRootObjectId == null -> ListPlace(
          place = place,
          objects = objects,
          isListing = isListing,
          leaks = leaks,
          isFindingLeaks = isFindingLeaks,
          favourites = favourites,
          sizes = sizes,
          onOpen = openObject,
          onReplacePlace = { tabs = tabs.replacingCurrent(it) },
          onRemoveStar = { objectId -> favourites = favourites.filterNot { it.objectId == objectId } },
          modifier = Modifier.fillMaxSize()
        )
        else -> Row(Modifier.fillMaxSize()) {
          // The chain first, the map, then what the object holds: read left to right that is where the
          // object came from, where it is, and what it is keeping alive.
          ChainPane(
            panes = panes,
            details = details,
            hoveredDetails = hoveredCellDetails,
            rootNodeId = view.rootObjectId,
            sizes = sizes,
            ways = detourWays,
            chosenWays = chosenWays,
            onChooseWay = { detour, way -> chosenWays = chosenWays + (detour to way) },
            onOpen = openObject
          )
          ViewPane(
            panes = panes,
            view = view,
            sizes = sizes,
            shape = shape,
            coloring = coloring,
            leaks = leaks,
            isFindingLeaks = isFindingLeaks,
            shading = LeakShading(leakingObjectIds, isRootLeaking),
            selected = place.selectedCell(),
            hovered = hovered?.cell,
            pointedSelection = hoveredCellDetails?.selection,
            pointerOffset = pointerOffset,
            viewportSize = viewportSize,
            isLayingOut = isLayingOut,
            bitmapImages = bitmapImages,
            onColoringChange = { coloring = it },
            onShapeChange = { shape = it },
            onHover = onHover,
            onClick = onClickCell,
            onOpenHovered = openHovered,
            onMeasured = { viewportSize = it }
          )
          DetailsPane(
            panes = panes,
            selection = details?.selection,
            sizes = sizes,
            bitmap = describedBitmap,
            isStarred = favourites.any { it.objectId == describedSummary?.objectId },
            onOpen = openObject,
            onListInstances = { className ->
              open(
                Place.Objects(
                  ObjectListFilter(
                    query = className,
                    isExactMatch = true,
                    kinds = setOf(HeapObjectKind.INSTANCE)
                  )
                ),
                OpenIn.CURRENT_TAB
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
                  favourites + Favourite.of(summary, details?.dominator)
                }
              }
            }
          )
          // Every pane folded away leaves three strips and a window of nothing, which still has to be
          // laid out: without this the strips would be stretched across it instead.
          if (panes.filling == null) {
            Spacer(Modifier.weight(1f))
          }
        }
      }
    }
  }
}

/**
 * Which cell of the view to outline, which is only ever the pile of objects a rectangle had no room for.
 *
 * An object's own view is rooted at the object, so outlining it would be outlining the edge of the window:
 * the whole picture is already that object and everything under it.
 */
private fun Place.selectedCell(): SelectedCell? = when (this) {
  is Place.SmallerObjects -> SelectedCell(parentObjectId, isGroup = true)
  else -> null
}

/** The chain from a GC root to the object, folded away or dragged wider. */
@Composable
private fun RowScope.ChainPane(
  panes: PanesState,
  details: PlaceDetails?,
  hoveredDetails: PlaceDetails?,
  rootNodeId: Long,
  sizes: HeapSizes,
  ways: Map<Int, List<RootPathWay>>,
  chosenWays: Map<Int, Int>,
  onChooseWay: (Int, Int) -> Unit,
  onOpen: (Long, OpenIn) -> Unit
) {
  if (panes.isFolded(Pane.CHAIN)) {
    FoldedPane(Pane.CHAIN) { panes.toggleFold(Pane.CHAIN) }
    return
  }
  Column(paneWidth(panes, Pane.CHAIN).fillMaxHeight()) {
    PaneHeader(Pane.CHAIN) { panes.toggleFold(Pane.CHAIN) }
    RootPathPanel(
      selection = details?.selection,
      rootPath = details?.rootPath,
      // What the pointer is on, which is drawn onto the end of the chain rather than over it.
      hoveredSelection = hoveredDetails?.selection,
      hoveredRootPath = hoveredDetails?.rootPath,
      rootNodeId = rootNodeId,
      stronglyReachableByteCount = sizes.stronglyReachableByteCount,
      ways = ways,
      chosenWays = chosenWays,
      onChooseWay = onChooseWay,
      onOpen = onOpen,
      modifier = Modifier.weight(1f).fillMaxWidth()
    )
  }
  if (panes.filling != Pane.CHAIN) {
    PaneDivider { delta -> panes.resize(Pane.CHAIN, delta) }
  }
}

/** The dominator tree rooted at the object, with the controls that shape it above. */
@Composable
private fun RowScope.ViewPane(
  panes: PanesState,
  view: ViewState,
  sizes: HeapSizes,
  shape: ViewShape,
  coloring: CellColoring,
  leaks: HeapLeaks?,
  isFindingLeaks: Boolean,
  shading: LeakShading,
  selected: SelectedCell?,
  hovered: SelectedCell?,
  pointedSelection: Selection?,
  pointerOffset: Offset?,
  viewportSize: IntSize,
  isLayingOut: Boolean,
  bitmapImages: Map<Long, ImageBitmap>,
  onColoringChange: (CellColoring) -> Unit,
  onShapeChange: (ViewShape) -> Unit,
  onHover: (PointedAt?) -> Unit,
  onClick: (LayoutCell<Long>, OpenIn) -> Unit,
  /** Where the right click menu over the view leads, which is whatever the pointer is on. */
  onOpenHovered: (OpenIn) -> Unit,
  onMeasured: (IntSize) -> Unit
) {
  if (panes.isFolded(Pane.VIEW)) {
    FoldedPane(Pane.VIEW) { panes.toggleFold(Pane.VIEW) }
    return
  }
  Column(paneWidth(panes, Pane.VIEW).fillMaxHeight()) {
    // The fold control sits in the row of controls rather than in a header of its own: the view already
    // has a strip above it, and two thin rows would be one more than the picture can spare.
    Row(verticalAlignment = Alignment.CenterVertically) {
      FoldButton(Pane.VIEW) { panes.toggleFold(Pane.VIEW) }
      ViewControls(
        sizes = sizes,
        shape = shape,
        coloring = coloring,
        leakCount = leaks?.objectCount,
        isFindingLeaks = isFindingLeaks,
        onColoringChange = onColoringChange,
        onShapeChange = onShapeChange,
        modifier = Modifier.weight(1f)
      )
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
      // The one gesture the views can't read themselves: a right click is the menu's, and the menu is what
      // makes middle clicking a rectangle findable by someone who has never tried it.
      OpenTarget(onOpenHovered) {
        TreeScreen(
          view = view,
          stronglyReachableByteCount = sizes.stronglyReachableByteCount,
          coloring = coloring,
          shading = shading,
          selected = selected,
          hovered = hovered,
          // What the pointer is on, for the card that follows it, and where the pointer is. Read
          // already: a card that appears empty and fills in a beat later reads as a flicker.
          pointedSelection = pointedSelection,
          pointerOffset = pointerOffset,
          viewSize = viewportSize,
          isLayingOut = isLayingOut,
          bitmapImages = bitmapImages,
          onHover = onHover,
          onClick = onClick,
          // Measured here rather than around every place, so that leaving the map for a list and coming
          // back doesn't lay it out twice for a viewport that ends up the size it already was.
          modifier = Modifier.fillMaxSize().onSizeChanged(onMeasured)
        )
      }
    }
  }
}

/** What the object is: its size, what the inspectors make of it, its fields. */
@Composable
private fun RowScope.DetailsPane(
  panes: PanesState,
  selection: Selection?,
  sizes: HeapSizes,
  bitmap: ImageBitmap?,
  isStarred: Boolean,
  onOpen: (Long, OpenIn) -> Unit,
  onListInstances: (String) -> Unit,
  onToggleStar: () -> Unit
) {
  if (panes.isFolded(Pane.DETAILS)) {
    FoldedPane(Pane.DETAILS) { panes.toggleFold(Pane.DETAILS) }
    return
  }
  if (panes.filling != Pane.DETAILS) {
    PaneDivider { delta -> panes.resize(Pane.DETAILS, -delta) }
  }
  Column(paneWidth(panes, Pane.DETAILS).fillMaxHeight()) {
    PaneHeader(Pane.DETAILS) { panes.toggleFold(Pane.DETAILS) }
    DetailsPanel(
      selection = selection,
      stronglyReachableByteCount = sizes.stronglyReachableByteCount,
      bitmap = bitmap,
      isStarred = isStarred,
      onOpen = onOpen,
      onListInstances = onListInstances,
      onToggleStar = onToggleStar,
      modifier = Modifier.weight(1f).fillMaxWidth()
    )
  }
}

/**
 * A fixed width, unless this is the pane taking whatever the other two leave.
 *
 * In [RowScope] because that is where a weight means anything, which is also why the three panes are
 * written as extensions of it rather than as composables that could be dropped anywhere.
 */
private fun RowScope.paneWidth(
  panes: PanesState,
  pane: Pane
): Modifier = when {
  panes.filling == pane -> Modifier.weight(1f)
  pane == Pane.CHAIN -> Modifier.width(panes.chainWidth)
  pane == Pane.DETAILS -> Modifier.width(panes.detailsWidth)
  // The view has no width of its own to fall back on: it is only ever what the other two leave.
  else -> Modifier
}

/**
 * A list of the width of the window: every object, the leaks, the starred ones.
 *
 * No pane either side, because each of these is a list and a list wants that width more than it wants a
 * chain it has nothing to put in.
 */
@Composable
private fun ListPlace(
  place: Place,
  objects: ObjectList,
  isListing: Boolean,
  leaks: HeapLeaks?,
  isFindingLeaks: Boolean,
  favourites: List<Favourite>,
  sizes: HeapSizes,
  onOpen: (Long, OpenIn) -> Unit,
  onReplacePlace: (Place) -> Unit,
  onRemoveStar: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  when (place) {
    is Place.Objects -> ObjectsScreen(
      list = objects,
      stronglyReachableByteCount = sizes.stronglyReachableByteCount,
      filter = place.filter,
      isListing = isListing,
      // A keystroke isn't a move, so typing replaces where the tab is: the back arrow leaves the list
      // rather than walking back through what was typed into it.
      onFilterChange = { filter -> onReplacePlace(place.copy(filter = filter)) },
      onOpen = onOpen,
      modifier = modifier
    )
    is Place.Leaks -> LeaksScreen(
      leaks = leaks ?: HeapLeaks.NONE,
      isFindingLeaks = isFindingLeaks,
      expandedGroups = place.expandedGroups,
      // Unfolding a leak isn't a move either, and for the same reason.
      onToggleGroup = { groupKey ->
        val expanded = place.expandedGroups
        onReplacePlace(
          place.copy(
            expandedGroups = if (groupKey in expanded) expanded - groupKey else expanded + groupKey
          )
        )
      },
      onOpen = onOpen,
      modifier = modifier
    )
    is Place.Starred -> StarredScreen(
      favourites = favourites,
      stronglyReachableByteCount = sizes.stronglyReachableByteCount,
      onOpen = onOpen,
      onRemove = onRemoveStar,
      modifier = modifier
    )
    // The places with a view of their own are drawn by the panes, not here.
    is Place.Object, is Place.SmallerObjects -> Unit
  }
}

/**
 * Which object the tab is on, in the bar above the panes.
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
      selection.summary.className ?: SemanticDominatorTreemap.UNREACHABLE_LABEL,
      style = MaterialTheme.typography.bodyMedium
    )
    is Selection.Group -> Text(
      "${selection.nodeCount} smaller objects",
      style = MaterialTheme.typography.bodyMedium
    )
  }
}

/**
 * The places an open heap dump can be read from, and how many objects are starred.
 *
 * Every one of these opens a tab of its own, always: these are the way in rather than places you pass
 * through, and a reader who clicks "Leaks" while reading an object wants both, not one instead of the
 * other. Two lists of objects filtered differently are two useful tabs; two identical lists of leaks are
 * one tab too many, and closing one is a click.
 */
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
    TextButton(onClick = onShowWholeHeapDump) {
      Text(SemanticDominatorTreemap.ROOT_LABEL)
    }
    TextButton(onClick = onListObjects) {
      Text(Place.OBJECTS_LABEL)
    }
    // Beside the list of every object, because it is the same list with the answer already found in it:
    // the objects that shouldn't be there, gathered into the leaks they are instances of.
    TextButton(onClick = onShowLeaks) {
      Text(Place.LEAKS_LABEL)
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
  /** What a retained size here is a share of. See [shark.explorer.HeapSizes.stronglyReachableByteCount]. */
  stronglyReachableByteCount: Long,
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
  onClick: (LayoutCell<Long>, OpenIn) -> Unit,
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
        stronglyReachableByteCount = stronglyReachableByteCount,
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

/** Back and forward through the moves made in this tab. See [shark.explorer.NavigationHistory]. */
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
 * which object it is rooted at, how big the view is, which shape it's drawn as, and the thresholds that
 * shape is laid out to. A [ViewState] is the answer to one of these.
 *
 * One value rather than a key each on the effect that lays the tree out, because that effect has to work
 * off exactly what it was keyed on. Keying it on the viewport while reading the viewport back out of the
 * state is what laid every heap dump out twice as it opened: the run keyed on the size the view had before
 * it was measured ran after the measurement, laid the whole tree out to the size it found there, and had
 * that thrown away when the very measurement it had used relaunched it.
 */
private data class ViewRequest(
  val rootObjectId: Long,
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
  "the ${shape.displayName.lowercase()} rooted at ${nodeIdText(rootObjectId)}, " +
    "${viewportSize.width}×${viewportSize.height}"

/** Everything read off the heap dump's thread to draw one view of the tree. */
internal class ViewState(
  /** Which object it was actually rooted at, which is the whole heap dump for one the tree has no node for. */
  val rootObjectId: Long,
  val presentation: ViewPresentation
) {
  companion object {
    /** Nothing laid out yet. An empty treemap and an empty radial view draw the same nothing. */
    val EMPTY = ViewState(
      rootObjectId = SemanticDominatorTreemap.ROOT_OBJECT_ID,
      presentation = ViewPresentation.Treemap(TreemapPresentation.EMPTY)
    )
  }
}

/** One [ViewShape]'s worth of laid out cells. */
internal sealed interface ViewPresentation {

  data class Treemap(val presentation: TreemapPresentation) : ViewPresentation

  data class Radial(val presentation: RadialPresentation) : ViewPresentation

  data class Stack(val presentation: StackPresentation) : ViewPresentation

  /**
   * What was drawn and what it was named, whatever shape it came out as: every place a click on the view
   * leads to, already named. See [titleOf].
   */
  val cells: List<PresentedCell<*>> get() = when (this) {
    is Treemap -> presentation.cells
    is Radial -> presentation.cells
    is Stack -> presentation.cells
  }
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
 * A cell the pointer is on: which one to outline, and where it would lead.
 *
 * The place is what makes moving the pointer off the map free — the tab's own details were never thrown
 * away, so putting the chain for it back is not a read — and it is the same value a click would go to,
 * which is what stops pointing at a rectangle and clicking it describing two different things.
 */
private data class PointedCell(
  val cell: SelectedCell,
  val place: Place
) {
  companion object {
    fun of(cell: LayoutCell<Long>): PointedCell = PointedCell(
      cell = SelectedCell.of(cell.subject),
      place = Place.of(cell)
    )
  }
}

/**
 * Everything the panes say about one place, filled in over the two reads it takes.
 *
 * What a place is comes first and the chain holding it after, because the chain is the slower of the two by
 * far: what a rectangle stands for should never wait on a walk up to the GC roots.
 */
private class PlaceDetails(
  /** Which place these are of, so that a pane can tell them from the ones it is waiting to replace. */
  val place: Place,
  val selection: Selection?,
  val dominator: ObjectDominator?,
  /** Null until the walk up to the GC roots comes back. */
  val rootPath: RootPath?
)

/**
 * Reads what one place is, then how a GC root reaches it, handing each to [onDetails] as it arrives.
 *
 * Two reads rather than one so that the panes fill in progressively, and both of them here rather than in
 * an effect each so that a place is described in the order it's read: a chain and a summary of two different
 * objects side by side is the one way these panes can lie.
 */
private suspend fun HeapDumpSession.describing(
  place: Place,
  onDetails: (PlaceDetails) -> Unit
) {
  val placeDetails = read(place.description()) { explorer ->
    val tree = explorer.tree
    val selection = when (place) {
      is Place.Object -> {
        val group = tree.groupOrNull(place.objectId)
        when {
          group != null -> Selection.ObjectGroup(group)
          place.objectId in tree -> Selection.Object(tree.summarize(place.objectId))
          // Which is why the panel goes back to saying nothing is selected.
          else -> {
            SharkLog.d {
              "Nothing to describe for ${nodeIdText(place.objectId)}: it is no node of the tree"
            }
            null
          }
        }
      }
      is Place.SmallerObjects -> Selection.Group(
        nodeCount = place.nodeCount,
        byteCount = place.byteCount,
        parentLabel = tree.label(place.parentObjectId)
      )
      // A list has no one object to describe, so nothing asks this about one.
      else -> null
    }
    // The tree already knows what dominates what, so this is a read of one label rather than a search,
    // and it belongs in the same read: two of them means the panel showing a dominator a beat late.
    val objectId = (selection as? Selection.Object)?.summary?.objectId
    PlaceDetails(
      place = place,
      selection = selection,
      dominator = objectId?.let { tree.dominatorOf(it) },
      rootPath = null
    )
  }
  onDetails(placeDetails)
  val objectId = (placeDetails.selection as? Selection.Object)?.summary?.objectId ?: return
  val rootPath = read("what holds ${hexObjectId(objectId)}") { explorer ->
    explorer.tree.rootPathTo(objectId)
  }
  onDetails(
    PlaceDetails(
      place = place,
      selection = placeDetails.selection,
      dominator = placeDetails.dominator,
      rootPath = rootPath
    )
  )
}

/** What the panes are being filled in for, for the log. See [HeapDumpSession.read]. */
private fun Place.description(): String = when (this) {
  is Place.Object -> "what ${nodeIdText(objectId)} is"
  is Place.SmallerObjects -> "what the $nodeCount objects under ${nodeIdText(parentObjectId)} are"
  else -> "what $this is"
}

/** How long the search box waits for the typing to stop before reading the whole heap dump again. */
private const val FILTER_SETTLE_MILLIS = 250L

/**
 * And how long the pointer has to stay on one cell before the heap dump is read for it.
 *
 * Short enough that landing on a rectangle and reading about it feels like one thing, long enough that
 * crossing the map costs the rectangles the pointer stopped on rather than every rectangle on the way.
 */
private const val HOVER_SETTLE_MILLIS = 100L

/** What a tab is called for the beat between it being opened and the heap dump having named it. */
private const val NAMING_TAB = "…"

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
