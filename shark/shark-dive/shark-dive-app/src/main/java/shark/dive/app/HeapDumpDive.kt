package shark.dive.app

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import shark.SharkLog
import shark.dive.BitmapCounts
import shark.dive.DeepLink
import shark.dive.DeviceHeapDumps
import shark.dive.HeapDominatorTreemap
import shark.dive.HeapLeaks
import shark.dive.HeapObjectKind
import shark.dive.HeapSizes
import shark.dive.LayoutCell
import shark.dive.LeakStatusOverrides
import shark.dive.NativeBitmapPixels
import shark.dive.Note
import shark.dive.NoteLink
import shark.dive.ObjectList
import shark.dive.ObjectListEntry
import shark.dive.ObjectListFilter
import shark.dive.Place
import shark.dive.PresentedCell
import shark.dive.RadialLayout
import shark.dive.RadialPresentation
import shark.dive.ReachabilityStrength
import shark.dive.ReferencePage
import shark.dive.RootPath
import shark.dive.RootPathWay
import shark.dive.StackLayout
import shark.dive.StackPresentation
import shark.dive.Tabs
import shark.dive.Topic
import shark.dive.TreemapLayout
import shark.dive.TreemapPresentation
import shark.dive.TreemapRect
import shark.dive.agent.AgentSession
import shark.dive.agent.subject
import shark.dive.detours
import shark.dive.exactHexObjectId
import shark.dive.formatObjectCount
import shark.dive.hexObjectId
import shark.dive.leakStatusConflictsWith
import shark.dive.nodeIdText
import shark.dive.referencesOf
import shark.dive.titleOf
import shark.dive.waysOf

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
internal fun HeapDumpDive(
  session: HeapDumpSession,
  sizes: HeapSizes,
  /** The way back to the live process, for the bitmaps this heap dump has no pixels for. */
  deviceHeapDumps: DeviceHeapDumps,
  /** Already fetched off the device, when the dump was taken with the pixels asked for in the same go. */
  fetchedBitmapPixels: NativeBitmapPixels? = null,
  /** What has been written about the places of this heap dump, shared with every other window on it. */
  notes: HeapDumpNotes,
  /** And what has been decided about its objects by hand, shared the same way. See [LeakStatusDetail]. */
  leakStatuses: HeapDumpLeakStatuses,
  /** And which of its objects are starred, shared the same way. See [HeapDumpStars]. */
  stars: HeapDumpStars,
  /** Places a link has asked for, opened as tabs. See [DiveWindow.linkedPlaces]. */
  linkedPlaces: List<Place> = emptyList(),
  onLinkedPlaceOpened: (Place) -> Unit = {},
  /**
   * Where a `shark://` link written in the notes goes, which is wherever it names: this window, another
   * window of this run, another run of the app, or nowhere. See [DeepLinkPeers.follow].
   */
  followDeepLink: (DeepLink) -> Unit = { link -> SharkLog.d { "Nothing here to follow $link with" } },
  /**
   * And where a row of an agent's session about another heap dump goes, which is that heap dump.
   *
   * Only the application knows, for the reason [followDeepLink] is its too: which window a heap dump opens
   * in is a question about every window of the run. See [DiveWindows.goToHeapDump].
   */
  onOpenHeapDump: (File, Place) -> Unit = { file, place ->
    SharkLog.d { "Nothing here to open $place of $file with" }
  },
  /**
   * What every agent that has connected to this app did, read whenever the screen showing them is open.
   *
   * A function rather than state, because this is a directory of files an agent is appending to while the
   * screen is being read: re-reading it is what makes the screen live. Overridden by tests, which have
   * sessions of their own rather than the ones under this machine's home directory.
   */
  agentSessions: () -> List<AgentSession> = ::agentSessions,
  /** Overridden by tests, which have no browser. */
  openUrl: (String) -> Unit = ::openInBrowser,
  /** Overridden by tests, which have no system clipboard and want to read what would have been copied. */
  copyToClipboard: (String) -> Unit = ::copyTextToClipboard,
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
   * [shark.dive.RootPathDetour.fromIndex]. Empty until the searches come back.
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
  /** The objects starred so far, as the rows that draw them. Read from the addresses in [stars]. */
  var starredObjects by remember { mutableStateOf(emptyList<ObjectListEntry>()) }
  /** What the agents that have worked through this app did, while a screen showing them is open. */
  var sessions by remember { mutableStateOf(emptyList<AgentSession>()) }
  /** What each tab is called, by the place it is on. Only grows: a place is named once and stays named. */
  var placeTitles by remember { mutableStateOf(emptyMap<Place, String>()) }
  /**
   * And what to call the places an agent asked about, which is the same question with one difference: an
   * agent can name an address this heap dump has no object at, so this map answers for a place a tab could
   * not be opened on. See [agentPlaceTitle].
   */
  var agentPlaceTitles by remember { mutableStateOf(emptyMap<Place, String>()) }
  /**
   * The note about the tab on screen, and null once the last tab has been closed — which is the one state
   * with no tab to write about.
   */
  val tabNote = place?.let { current ->
    TabNote(
      notes = notes.of(current),
      subject = current.title ?: placeTitles[current] ?: NAMING_TAB
    )
  }
  /**
   * Where saving a star runs, which is the composition rather than an effect.
   *
   * A `LaunchedEffect` keyed on what was clicked is how the leak status dialog does it, and it can't be that
   * here: two stars set in a row would key the effect twice, and the second would cancel the first between
   * its write landing and the list on screen being told about it. See [HeapDumpStars.toggle].
   */
  val starring = rememberCoroutineScope()
  /** Which objects of this heap dump have a status someone set, which is what every chain is read with. */
  val overrides = leakStatuses.overrides
  /** Whether the dialog that sets one is open, for the object the tab is on. */
  var setsLeakStatus by remember { mutableStateOf(false) }

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
  /**
   * Whether the object the tab is on is meant to be in memory, for the panel that says what it is and the
   * dialog that overrules it.
   *
   * From the last step of the chain when there is one, because that is the status with everything above and
   * below the object taken into account, and from the object's own reading until the walk up to the GC roots
   * lands — or for good, for an object nothing reaches. So this can say `Unknown` for a beat and then say
   * `Stuck`, which is the panes filling in rather than the window changing its mind.
   *
   * Nothing for the whole heap dump, which is no object of it: there is nothing to inspect and nothing to
   * decide about.
   */
  val describedLeakStatus = describedSummary
    ?.takeIf { it.objectId != HeapDominatorTreemap.ROOT_OBJECT_ID }
    ?.let { summary ->
      val onChain = details?.rootPath?.steps?.lastOrNull()?.step
        ?.takeIf { it.objectId == summary.objectId }
      ObjectLeakStatus(
        objectId = summary.objectId,
        objectName = summary.className.substringAfterLast('.') +
          summary.kind?.let { " ${it.typeName}" }.orEmpty(),
        status = onChain?.leakStatus ?: summary.leakStatus,
        reason = onChain?.leakStatusReason ?: summary.leakStatusReason,
        setByHand = overrides[summary.objectId]
      )
    }

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

  // A link that arrived is a tab, always a new one and always in front: following a link is asking to be
  // somewhere else, and it must not throw away the tab it was followed from. One per run of this effect
  // rather than a loop over the list, so that a link arriving while another is being opened is queued
  // behind it rather than racing it.
  LaunchedEffect(linkedPlaces) {
    val linked = linkedPlaces.firstOrNull() ?: return@LaunchedEffect
    SharkLog.d { "A link asked this window for $linked" }
    tabs = tabs.open(linked)
    onLinkedPlaceOpened(linked)
  }

  // What each tab is called, for the tabs the window couldn't name itself. A label is cheap enough to draw
  // on every rectangle of the map, so naming a strip of them is one small read — but it is still a read,
  // and reads queue on the heap dump's one thread.
  //
  // Which is why this goes in ahead of the effect that lays the view out: opening a tab asks for both, and
  // the layout is the larger by orders of magnitude. Named after it, a tab would show its placeholder for
  // as long as laying the tree out takes, which on a real dump is what someone sees as a flicker.
  // Every place of every history rather than just the one each tab is on, because the right click menus on
  // the history arrows list them by name: a menu entry called "Naming this tab…" is a move nobody will make.
  val unnamedPlaces = tabs.tabs.flatMap { it.history.entries }
    .filter { it.title == null && it !in placeTitles }
    .distinct()
  LaunchedEffect(session, unnamedPlaces) {
    if (unnamedPlaces.isEmpty()) {
      return@LaunchedEffect
    }
    val named = session.read("what to call ${unnamedPlaces.size} tabs") { dive ->
      unnamedPlaces.associateWith { dive.tree.titleOf(it) }
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
    view = session.read(viewRequest.description()) { dive ->
      val tree = dive.tree
      val requested = viewRequest.rootObjectId
      // A field or a row can lead to an object this tree has no node for, and there is nowhere to root a
      // view at one of those.
      val rooted = if (requested in tree) {
        requested
      } else {
        SharkLog.d {
          "${nodeIdText(requested)} is no node of the tree: rooting the view at the whole heap dump"
        }
        HeapDominatorTreemap.ROOT_OBJECT_ID
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
    bitmapCounts = session.read("how many bitmaps ${session.heapDumpFile.name} has") { dive ->
      if (fetchedBitmapPixels == null) {
        dive.tree.bitmapCounts()
      } else {
        dive.tree.addNativeBitmapPixels(fetchedBitmapPixels)
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
    val images = session.read("the pixels of ${nodeIds.size} bitmaps on the map") { dive ->
      dive.tree.bitmapImages(nodeIds, MAX_TREEMAP_BITMAP_PIXELS)
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
  //
  // And on the statuses set by hand, because they are half of what a chain says: setting one is asking the
  // window to read the heap dump through it, which is this read again.
  LaunchedEffect(session, place, overrides) {
    // Whatever was being decided was being decided about the object this is leaving, and a dialog that
    // outlived the tab it was opened from would set a status on an object nobody is looking at.
    setsLeakStatus = false
    if (place == null || place.viewRootObjectId == null) {
      details = null
      return@LaunchedEffect
    }
    session.describing(place, overrides) { details = it }
  }

  // The same for the cell under the pointer, once it has stayed on one long enough to be looking at it.
  // Without that wait, a sweep across the map would ask about every rectangle it crossed: the reads that
  // are no longer wanted do get called off, but only where they next read the heap dump.
  LaunchedEffect(session, hoveredPlace, overrides) {
    if (hoveredPlace == null) {
      // Whatever was read for the last cell stays where it is: nothing is waiting for it, and the panes
      // are showing the tab's own place again anyway.
      return@LaunchedEffect
    }
    delay(HOVER_SETTLE_MILLIS)
    session.describing(hoveredPlace, overrides) { hoveredDetails = it }
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
      session.read("the pixels of ${hexObjectId(objectId)}") { dive ->
        dive.tree.bitmapImages(listOf(objectId), MAX_PANEL_BITMAP_PIXELS)[objectId]
      }
    }
    describedBitmap = image?.let { withContext(Dispatchers.Default) { it.toImageBitmap() } }
  }

  // Which stretches of the chain could have run elsewhere is a walk in memory per stretch, over an index of
  // what points at what, and each walks several times. Far too much to run as the pointer moves, so it is
  // only ever asked for the object the tab is on, and only once the chain to it is known — the chain is what
  // says where the stretches are. See [shark.dive.detours].
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
    detourWays = session.read("the other ways ${hexObjectId(target)} is held") { dive ->
      detours.associate { detour ->
        val from = detour.fromObjectId
        val found = if (from == null) {
          dive.tree.independentPathsFromRoots(detour.toObjectId, overrides)
        } else {
          dive.tree.independentPathsBetween(from, detour.toObjectId, overrides)
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
  //
  // And again whenever a status is set by hand, because the list is read through those: marking something
  // leaking halfway up a chain makes it a leak and takes what it was holding off the list, which is a
  // different list rather than a different colour on the same one. See [HeapDominatorTreemap.findLeaks].
  LaunchedEffect(session, overrides) {
    snapshotFlow { view }.first { it !== ViewState.EMPTY }
    val objectCount = session.read("the index of what points at what") { dive ->
      dive.tree.indexReferrers()
    }
    SharkLog.d { "Indexed what points at each of ${formatObjectCount(objectCount)}" }
    isFindingLeaks = true
    leaks = session.read("the leaking objects of ${session.heapDumpFile.name}") { dive ->
      dive.tree.findLeaks(overrides)
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
    objects = session.read("the objects matching $objectFilter") { dive ->
      dive.tree.listObjects(objectFilter)
    }
    // What a list that came back looking empty or short was actually asked for.
    SharkLog.d {
      "Listed ${objects.entries.size} of the ${formatObjectCount(objects.matchCount)} matched, " +
        "out of ${formatObjectCount(objects.totalCount)}"
    }
    isListing = false
  }

  // Which places have been written about, once per run of the app: a directory listing rather than a note
  // opened per tab, since what it answers is a question about the whole strip. See [HeapDumpNotes.list].
  LaunchedEffect(notes) { notes.list() }

  // What the agents that have connected to this app did, for as long as a screen showing them is open. Read
  // again on a timer rather than watched, because an agent appends to its session file while somebody is
  // reading it — being able to watch an investigation happen is the point — and a handful of small files is
  // cheaper to read again than a file watcher is to set up and take down per tab.
  val showsAgentLogs = place is Place.AgentLogs || place is Place.AgentLog
  LaunchedEffect(showsAgentLogs) {
    if (!showsAgentLogs) {
      return@LaunchedEffect
    }
    while (true) {
      sessions = withContext(Dispatchers.IO) { agentSessions() }
      delay(AGENT_LOGS_REFRESH_MILLIS)
    }
  }

  // And what to call the objects those agents asked about, so that a row of a session names an object the
  // way the tab it opens does — `MainActivity 0x12d368b8` — rather than as the bare address the agent wrote.
  // The session file holds addresses on purpose: an address is what an agent said, and what it stands for is
  // a read of the heap dump this window has open, which is the same read that names a tab. Which is why the
  // sessions listed here are the ones that read this dump — the calls about another are left as written.
  val unnamedAgentPlaces = (place as? Place.AgentLog)
    ?.let { open -> sessions.firstOrNull { it.sessionId == open.sessionId } }
    ?.calls.orEmpty()
    .filter { it.heapDumpPath == null || it.heapDumpPath == session.heapDumpFile.absolutePath }
    // Only the calls that named what they were about, since those are the only rows that show a name: the
    // place of a call that named nothing comes from which tool it is, and its verb already says it.
    .filter { it.subject != null }
    .mapNotNull { it.place }
    .filter { it !in agentPlaceTitles }
    .distinct()
  LaunchedEffect(session, unnamedAgentPlaces) {
    if (unnamedAgentPlaces.isEmpty()) {
      return@LaunchedEffect
    }
    val named = session.read("what to call ${unnamedAgentPlaces.size} places an agent asked about") { dive ->
      unnamedAgentPlaces.associateWith { dive.tree.agentPlaceTitle(it) }
    }
    agentPlaceTitles = agentPlaceTitles + named
  }

  // And what has been decided about this heap dump's objects by hand, also once per run: one small file,
  // read before anything is drawn from it, because a chain read without it would be the heap dump's own
  // answer where someone has already recorded another. See [HeapDumpLeakStatuses].
  LaunchedEffect(leakStatuses) { leakStatuses.read() }

  // And which of its objects are starred, the same way and for the same reason: a star is drawn beside every
  // object the panel describes, so a window that hasn't read the file yet would say every one of them isn't.
  LaunchedEffect(stars) { stars.read() }

  // What those addresses stand for, which is a read of the heap dump like every other list of objects. Not
  // gated on the starred screen being open: it is a handful of objects rather than a pass over the dump, and
  // one that has left the tree is a row that quietly isn't there, which is worth being in the log early.
  LaunchedEffect(session, stars.objectIds) {
    val starredIds = stars.objectIds
    if (starredIds.isEmpty()) {
      starredObjects = emptyList()
      return@LaunchedEffect
    }
    starredObjects = session.read("the ${starredIds.size} starred objects") { dive ->
      dive.tree.listObjects(starredIds)
    }
  }

  // And what the note about this one says, once per run of the app. Here rather than in the section that draws
  // it, because a place nobody has written about has no section at all until the read says it has none.
  LaunchedEffect(tabNote?.notes) { tabNote?.notes?.read() }

  // What a note means, whenever there is a new one to mean anything: reading the markdown is in memory and
  // cheap, and asking the heap dump what the names in it stand for is a read, so it only happens for a note
  // that mentions something. Once per save rather than per keystroke, since a draft being typed is drawn as
  // the text it is. See [Note].
  LaunchedEffect(session, tabNote?.notes) {
    val notepad = tabNote?.notes ?: return@LaunchedEffect
    snapshotFlow { notepad.text }.collectLatest { text ->
      val parsed = Note.of(text)
      notepad.parsed(parsed)
      val mentions = parsed.mentions
      if (mentions.isEmpty) {
        return@collectLatest
      }
      val references = session.read("what the note on ${tabNote.subject} mentions") { dive ->
        dive.tree.referencesOf(mentions)
      }
      notepad.parsed(parsed.resolvedWith(references))
    }
  }

  // Whether the map is rooted inside a leak, which the cells themselves can't say: a rectangle is drawn
  // inside the one that dominates it, so a view rooted below a leaking object has every rectangle of it
  // leaking and not one of them in the list. A walk up the dominators, so it follows where the map is.
  val leakingObjectIds = remember(leaks) { leaks?.leakingObjectIds ?: emptySet() }
  LaunchedEffect(session, view.rootObjectId, leaks) {
    val rootNode = view.rootObjectId
    isRootLeaking = leaks != null && session.read("whether ${nodeIdText(rootNode)} is below a leak") {
      it.tree.isBelowLeakingObject(rootNode, overrides)
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
  /**
   * Where a `?` goes: the page of the reference about that label, in a tab in front of what is being read.
   *
   * A tab rather than a browser, and in front rather than behind: clicking a `?` is asking to read the page,
   * unlike ⌘ clicking a rectangle, which is parking somewhere to come back to. What was being read is a tab
   * away, and the back arrow comes back from it. See [Explain] and [ReferenceScreen].
   */
  val explain: (Topic) -> Unit = { topic -> openInNewTab(Place.Reference(topic)) }
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
  /**
   * Where every "copy link" about another heap dump ends up, which the *Agent logs* screens are full of: a
   * link is a heap dump and a place, so a row about a dump this window hasn't got is something to send as
   * well as something to click. See [shark.dive.DeepLink].
   */
  val copyHeapDumpLink: (File, Place) -> Unit = { heapDumpFile, destination ->
    val link = DeepLink(heapDumpFile, destination).toUri()
    // In the log as well as on the clipboard, so that a link someone reports as not working can be compared
    // against the one this window actually handed out.
    SharkLog.d { "Copied $link" }
    copyToClipboard(link)
  }
  /**
   * And for this window's own heap dump, which is every other "copy link" there is, for the same reason
   * [open] is one function: a link to a rectangle, a row, a field, a button and a tab is one thing, and five
   * of them would drift.
   */
  val copyLink: (Place) -> Unit = { destination ->
    copyHeapDumpLink(session.heapDumpFile, destination)
  }
  /** The same, for everything that names an object by its id. */
  val copyObjectLink: (Long) -> Unit = { objectId -> copyLink(Place.Object(objectId)) }
  /** And for the view's right click menu, which is on whatever the pointer is on. */
  val copyHoveredLink: () -> Unit = { hovered?.place?.let { copyLink(it) } }
  /**
   * Where a link written in the notes goes: out to a browser, into whichever window a `shark://` link
   * names, or to an object of this heap dump.
   *
   * An object opens a tab in front, like a button on the bar rather than like a row of a list: the notes
   * are what the reader is working from, and replacing them with the object they just linked to would take
   * away the thing they are reading. A `shark://` link is followed the way one arriving from outside the
   * app is, which is the whole point of it being the same link.
   */
  val followNoteLink: (NoteLink) -> Unit = { link ->
    when (link) {
      is NoteLink.Web -> openUrl(link.url)
      is NoteLink.Deep -> followDeepLink(link.deepLink)
      is NoteLink.Object -> openInNewTab(Place.Object(link.objectId))
    }
  }

  if (showsBitmapsFromDevice) {
    BitmapsFromDeviceDialog(
      origin = session.origin,
      counts = bitmapCounts,
      deviceHeapDumps = deviceHeapDumps,
      onFetched = { pixels ->
        val counts = session.read("which bitmaps the fetched pixels belong to") { dive ->
          dive.tree.addNativeBitmapPixels(pixels)
        }
        bitmapCounts = counts
        // Which is what has every bitmap on the map asked for again, this time with pixels behind it.
        bitmapRevision++
        counts
      },
      onDismiss = { showsBitmapsFromDevice = false }
    )
  }

  if (setsLeakStatus && describedLeakStatus != null) {
    LeakStatusDialog(
      status = describedLeakStatus,
      // A walk up the references per status already set, which is a read of the heap dump like any other —
      // and one asked for, so it is not on the path the pointer takes. See [leakStatusConflictsWith].
      onFindConflicts = { override ->
        session.read("what setting ${hexObjectId(override.objectId)} to ${override.status} disagrees with") {
          it.tree.leakStatusConflictsWith(override, overrides)
        }
      },
      onSet = { override, solved -> leakStatuses.set(override, solved) },
      onClear = { leakStatuses.clear(describedLeakStatus.objectId) },
      onDismiss = { setsLeakStatus = false }
    )
  }

  Column(modifier) {
    ScreenBar(
      starredCount = stars.objectIds.size,
      bitmapCounts = bitmapCounts,
      onOpen = openInNewTab,
      onCopyLink = copyLink,
      onFetchBitmaps = { showsBitmapsFromDevice = true }
    )
    TabStrip(
      tabs = tabs,
      titleOf = { it.title ?: placeTitles[it] ?: NAMING_TAB },
      hasNote = { notes.hasNote(it) },
      onSelect = { id -> tabs = tabs.select(id) },
      onClose = { id -> tabs = tabs.close(id) },
      onCopyLink = copyLink
    )
    // Two things in one row, and a rule between them: how the tab got here, then what it is on. The height is
    // the taller of the two so that the rule is as tall as the row whether the title is one line or three.
    Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
      HistoryArrows(
        // Named the way the tabs are, since these are the same places under another name.
        back = tabs.backPlaces.map { it.title ?: placeTitles[it] ?: NAMING_TAB },
        forward = tabs.forwardPlaces.map { it.title ?: placeTitles[it] ?: NAMING_TAB },
        // A move undone is a place again, and the panes follow it: there is nothing else to put back.
        onBack = { steps -> tabs = tabs.goBack(steps) },
        onForward = { steps -> tabs = tabs.goForward(steps) }
      )
      VerticalDivider(Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
      // Which object the tab is on, beside the arrows that moved to it: above the panes rather than in
      // one, because it is the one thing that is as true of a list of objects as of the map.
      Column(Modifier.weight(1f)) {
        DescribedObject(details?.selection)
        // Under the title, and only until there is a note: the note will be about what the title names, and
        // this is where it will appear, so the button is where its own result goes. Small, since that costs a
        // line of every tab nobody has written about. Once there is a note it is here instead and carries the
        // way back into the box, so there is never one of each. See [AddNoteButton].
        if (tabNote != null) {
          AddNoteButton(tabNote.notes)
        }
      }
    }
    // Under the title it is about and above everything that describes it, so that what it is a note about is
    // the whole of what this tab is showing rather than whichever pane it happens to sit against.
    if (tabNote != null) {
      NoteSection(
        notes = tabNote.notes,
        onLink = followNoteLink,
        height = panes.noteHeight,
        onResize = { delta -> panes.resizeNote(delta) }
      )
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
          starredObjects = starredObjects,
          sessions = sessions,
          heapDumpFile = session.heapDumpFile,
          agentPlaceTitles = agentPlaceTitles,
          onOpenHeapDump = onOpenHeapDump,
          onCopyHeapDumpLink = copyHeapDumpLink,
          sizes = sizes,
          onOpen = openObject,
          onCopyLink = copyObjectLink,
          onOpenPlace = open,
          onCopyPlaceLink = copyLink,
          onReplacePlace = { tabs = tabs.replacingCurrent(it) },
          onRemoveStar = { objectId -> starring.launch { stars.toggle(objectId) } },
          onFollowLink = followNoteLink,
          onExplain = explain,
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
            onOpen = openObject,
            onCopyLink = copyObjectLink,
            onExplain = explain
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
            onCopyHoveredLink = copyHoveredLink,
            onMeasured = { viewportSize = it },
            onExplain = explain
          )
          DetailsPane(
            panes = panes,
            selection = details?.selection,
            sizes = sizes,
            bitmap = describedBitmap,
            isStarred = describedSummary?.let { stars.isStarred(it.objectId) } == true,
            leakStatus = describedLeakStatus,
            isLeakStatusRead = leakStatuses.isRead,
            leakStatusProblem = leakStatuses.problem,
            onChangeLeakStatus = { setsLeakStatus = true },
            onOpen = openObject,
            onCopyLink = copyObjectLink,
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
                starring.launch { stars.toggle(summary.objectId) }
              }
            },
            onExplain = explain
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
 * The note about the tab on screen: what has been written in it, and what to call the tab it is about.
 *
 * One value rather than two, because both are the same question — which tab is on screen — asked by the
 * section and by the read that works out what the names in it mean. See [NoteSection].
 */
private data class TabNote(
  val notes: PlaceNotes,
  /** What the log calls the tab, since a read of the heap dump is logged with what it was for. */
  val subject: String
)

/**
 * Puts [text] on the system clipboard, through AWT.
 *
 * AWT's clipboard rather than Compose's, because this is one call from an event handler rather than
 * something a composable has to hold: Compose's is a composition local and a suspending write, both of
 * which would have to be threaded down to the tab that is being copied.
 */
internal fun copyTextToClipboard(text: String) {
  try {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
  } catch (throwable: Throwable) {
    // A headless JVM and a desktop with no clipboard both land here, and neither is worth a dialog.
    SharkLog.d(throwable) { "Could not put \"$text\" on the clipboard" }
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
  onOpen: (Long, OpenIn) -> Unit,
  onCopyLink: (Long) -> Unit,
  onExplain: (Topic) -> Unit
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
      onCopyLink = onCopyLink,
      onExplain = onExplain,
      modifier = Modifier.weight(1f).fillMaxWidth()
    )
  }
  if (panes.filling != Pane.CHAIN) {
    PaneDivider(resizeHint(Pane.CHAIN)) { delta -> panes.resize(Pane.CHAIN, delta) }
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
  /** And what that menu copies a link to, which is the same rectangle. */
  onCopyHoveredLink: () -> Unit,
  onMeasured: (IntSize) -> Unit,
  onExplain: (Topic) -> Unit
) {
  if (panes.isFolded(Pane.VIEW)) {
    FoldedPane(Pane.VIEW) { panes.toggleFold(Pane.VIEW) }
    return
  }
  Column(paneWidth(panes, Pane.VIEW).fillMaxHeight()) {
    // Named like the two panes either side of it, and for the same reason: left to right the three of them
    // are three questions about the object, and a middle one that didn't say which question it answers left
    // the other two reading as a pair.
    PaneHeader(Pane.VIEW) { panes.toggleFold(Pane.VIEW) }
    ViewControls(
      sizes = sizes,
      shape = shape,
      coloring = coloring,
      leakCount = leaks?.objectCount,
      isFindingLeaks = isFindingLeaks,
      onColoringChange = onColoringChange,
      onShapeChange = onShapeChange,
      onExplain = onExplain,
      modifier = Modifier.fillMaxWidth()
    )
    Box(Modifier.weight(1f).fillMaxWidth()) {
      // The one gesture the views can't read themselves: a right click is the menu's, and the menu is what
      // makes middle clicking a rectangle findable by someone who has never tried it.
      OpenTarget(onOpenHovered, onCopyHoveredLink) {
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

/**
 * What the object is: whether it is meant to be in memory, its size, what the inspectors make of it, and
 * its fields.
 */
@Composable
private fun RowScope.DetailsPane(
  panes: PanesState,
  selection: Selection?,
  sizes: HeapSizes,
  bitmap: ImageBitmap?,
  isStarred: Boolean,
  leakStatus: ObjectLeakStatus?,
  isLeakStatusRead: Boolean,
  leakStatusProblem: String?,
  onChangeLeakStatus: () -> Unit,
  onOpen: (Long, OpenIn) -> Unit,
  onCopyLink: (Long) -> Unit,
  onListInstances: (String) -> Unit,
  onToggleStar: () -> Unit,
  onExplain: (Topic) -> Unit
) {
  if (panes.isFolded(Pane.DETAILS)) {
    FoldedPane(Pane.DETAILS) { panes.toggleFold(Pane.DETAILS) }
    return
  }
  if (panes.filling != Pane.DETAILS) {
    PaneDivider(resizeHint(Pane.DETAILS)) { delta -> panes.resize(Pane.DETAILS, -delta) }
  }
  Column(paneWidth(panes, Pane.DETAILS).fillMaxHeight()) {
    PaneHeader(Pane.DETAILS) { panes.toggleFold(Pane.DETAILS) }
    DetailsPanel(
      selection = selection,
      stronglyReachableByteCount = sizes.stronglyReachableByteCount,
      bitmap = bitmap,
      isStarred = isStarred,
      leakStatus = leakStatus,
      isLeakStatusRead = isLeakStatusRead,
      leakStatusProblem = leakStatusProblem,
      onChangeLeakStatus = onChangeLeakStatus,
      onOpen = onOpen,
      onCopyLink = onCopyLink,
      onListInstances = onListInstances,
      onToggleStar = onToggleStar,
      onExplain = onExplain,
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
  starredObjects: List<ObjectListEntry>,
  /** What the agents that have worked through this app did, for the screens that draw them. */
  sessions: List<AgentSession>,
  /** Which heap dump this window has open, which is what decides where an agent's row leads. */
  heapDumpFile: File,
  /** What this window calls the places those agents asked about. See [agentPlaceTitle]. */
  agentPlaceTitles: Map<Place, String>,
  /** And where a session or a row about another heap dump leads: that dump. See [AgentLogsScreen]. */
  onOpenHeapDump: (File, Place) -> Unit,
  /** And the link to it, which names that dump rather than this window. */
  onCopyHeapDumpLink: (File, Place) -> Unit,
  sizes: HeapSizes,
  onOpen: (Long, OpenIn) -> Unit,
  onCopyLink: (Long) -> Unit,
  /** Where a row leading to something that is not an object goes. See [AgentLogsScreen]. */
  onOpenPlace: (Place, OpenIn) -> Unit,
  onCopyPlaceLink: (Place) -> Unit,
  onReplacePlace: (Place) -> Unit,
  onRemoveStar: (Long) -> Unit,
  /** And where a link written in prose goes: a browser, this heap dump, or another window. */
  onFollowLink: (NoteLink) -> Unit,
  /** And where a `?` goes, which is the page of the reference on that label. See [Explain]. */
  onExplain: (Topic) -> Unit,
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
      onCopyLink = onCopyLink,
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
      onCopyLink = onCopyLink,
      onExplain = onExplain,
      onFollowLink = onFollowLink,
      modifier = modifier
    )
    is Place.Starred -> StarredScreen(
      entries = starredObjects,
      stronglyReachableByteCount = sizes.stronglyReachableByteCount,
      onOpen = onOpen,
      onCopyLink = onCopyLink,
      onRemove = onRemoveStar,
      modifier = modifier
    )
    is Place.Reference -> ReferenceScreen(
      page = ReferencePage.of(place.topic),
      onOpenTopic = { topic, openIn -> onOpenPlace(Place.Reference(topic), openIn) },
      onCopyTopicLink = { topic -> onCopyPlaceLink(Place.Reference(topic)) },
      onLink = onFollowLink,
      modifier = modifier
    )
    is Place.AgentLogs -> AgentLogsScreen(
      sessions = sessions,
      heapDumpFile = heapDumpFile,
      onOpen = onOpenPlace,
      onCopyLink = onCopyPlaceLink,
      onOpenHeapDump = onOpenHeapDump,
      onCopyHeapDumpLink = onCopyHeapDumpLink,
      modifier = modifier
    )
    is Place.AgentLog -> AgentLogScreen(
      // Null for a session that has been pushed out by newer ones, or one from another machine's link.
      session = sessions.firstOrNull { it.sessionId == place.sessionId },
      heapDumpFile = heapDumpFile,
      placeTitles = agentPlaceTitles,
      onOpen = onOpenPlace,
      onCopyLink = onCopyPlaceLink,
      onOpenHeapDump = onOpenHeapDump,
      onCopyHeapDumpLink = onCopyHeapDumpLink,
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
  // Larger than the same name is anywhere else in the window, because here it is a title rather than a
  // mention: everything under it — three panes or a list, and the note — is about this object.
  val titleStyle = MaterialTheme.typography.titleMedium
  when (selection) {
    null -> Unit
    // Selectable so it can be copied out: an object id is how you point something else — a script, a
    // colleague, a bug report — at this one instance rather than at its class.
    is Selection.Object -> SelectionContainer {
      ObjectIdentity(
        className = selection.summary.className,
        typeName = selection.summary.kind?.typeName,
        objectId = selection.summary.objectId,
        nameStyle = titleStyle
      )
    }
    is Selection.ObjectGroup -> Text(
      selection.summary.className ?: ReachabilityStrength.UNREACHABLE.label,
      style = titleStyle,
      fontWeight = FontWeight.Bold
    )
    is Selection.Group -> Text(
      formatObjectCount(selection.nodeCount),
      style = titleStyle,
      fontWeight = FontWeight.Bold
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
  onOpen: (Place) -> Unit,
  /** A link to the screen a button leads to, from the right click menu on it. See [CopyLinkTarget]. */
  onCopyLink: (Place) -> Unit,
  onFetchBitmaps: () -> Unit
) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    ScreenButton(Place.wholeHeapDump(), HeapDominatorTreemap.ROOT_LABEL, onOpen, onCopyLink)
    ScreenButton(Place.Objects(), Place.OBJECTS_LABEL, onOpen, onCopyLink)
    // Beside the list of every object, because it is the same list with the answer already found in it:
    // the objects that shouldn't be there, gathered into the leaks they are instances of.
    ScreenButton(Place.Leaks(), Place.LEAKS_LABEL, onOpen, onCopyLink)
    ScreenButton(
      place = Place.Starred,
      label = "$STARRED_GLYPH $starredCount starred",
      onOpen = onOpen,
      onCopyLink = onCopyLink,
      isEnabled = starredCount > 0
    )
    // Beside the reader's own trail through the heap dump, because it is the same kind of thing: what has
    // been looked at, by whoever was looking. An agent works in this window rather than in one of its own,
    // so what it did belongs on this bar and not in a file somebody has to be told about.
    ScreenButton(Place.AgentLogs, Place.AGENT_LOGS_LABEL, onOpen, onCopyLink)
    // Last, and here at all so that the pages every `?` leads to can be found without one: a reader who
    // wondered about a label an hour ago and has moved on has nothing left to hover. See [Explain].
    ScreenButton(Place.Reference(Topic.values().first()), Place.REFERENCE_LABEL, onOpen, onCopyLink)
    // Only when there are bitmaps the dump has no pixels for, because that's the only thing a device can
    // add: pixels the dump carries are already on the map by the time this bar is read.
    if (bitmapCounts.withoutImageCount > 0) {
      TextButton(onClick = onFetchBitmaps) {
        Text("$FETCH_BITMAPS ${bitmapCountText(bitmapCounts.withoutImageCount)}")
      }
    }
  }
}

/**
 * One button of the bar: a screen it always opens a tab of its own on, and a link to that screen.
 *
 * No "open in a new tab" in its menu, unlike everything else that leads somewhere: this is the one kind of
 * way to a place that has no other tab to open in.
 */
@Composable
private fun ScreenButton(
  place: Place,
  label: String,
  onOpen: (Place) -> Unit,
  onCopyLink: (Place) -> Unit,
  isEnabled: Boolean = true
) {
  CopyLinkTarget({ onCopyLink(place) }) {
    TextButton(onClick = { onOpen(place) }, enabled = isEnabled) {
      Text(label)
    }
  }
}

/** The dominator tree, drawn as one of the [ViewShape]s, with a card naming what the pointer is on. */
@Composable
private fun TreeScreen(
  view: ViewState,
  /** What a retained size here is a share of. See [shark.dive.HeapSizes.stronglyReachableByteCount]. */
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

/** Back and forward through the moves made in this tab. See [shark.dive.NavigationHistory]. */
@Composable
private fun HistoryArrows(
  /** Where back leads, one click first. See [Tabs.backPlaces]. */
  back: List<String>,
  forward: List<String>,
  /** How many moves at once, which is 1 for a click on the arrow itself. */
  onBack: (Int) -> Unit,
  onForward: (Int) -> Unit
) {
  HistoryArrow(BACK_ARROW, back, onBack)
  HistoryArrow(FORWARD_ARROW, forward, onForward)
}

/**
 * One arrow: a click is one move, and a right click is the list of them.
 *
 * Which is the browser gesture, and it is worth having here for the browser's reason: a tab that has walked
 * twenty objects down a chain is one where getting back to where the walk started is twenty clicks and a
 * guess about which of them it was. The list says where each one lands, by the name the tab strip uses.
 */
@Composable
private fun HistoryArrow(
  arrow: String,
  places: List<String>,
  onGo: (Int) -> Unit
) {
  if (places.isEmpty()) {
    // Nowhere to go, so nothing to right click either: an empty menu under the pointer reads as the window
    // having lost the history rather than as there being none.
    TextButton(onClick = {}, enabled = false) {
      Text(arrow)
    }
    return
  }
  ContextMenuArea(
    items = {
      // The nearest few rather than all of them, because the menu is drawn where the pointer is and one
      // taller than the window has entries that cannot be reached. Everything past them is still one click
      // of the arrow at a time away.
      places.take(HISTORY_MENU_LIMIT).mapIndexed { index, title ->
        ContextMenuItem(title) { onGo(index + 1) }
      }
    }
  ) {
    Hint("$HISTORY_MENU_HINT ${places.first()}") {
      TextButton(onClick = { onGo(1) }) {
        Text(arrow)
      }
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
      rootObjectId = HeapDominatorTreemap.ROOT_OBJECT_ID,
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
  /** The statuses set by hand, which decide half of what the chain and the row above the panes say. */
  overrides: LeakStatusOverrides,
  onDetails: (PlaceDetails) -> Unit
) {
  val placeDetails = read(place.description()) { dive ->
    val tree = dive.tree
    val selection = when (place) {
      is Place.Object -> {
        val group = tree.groupOrNull(place.objectId)
        when {
          group != null -> Selection.ObjectGroup(group)
          place.objectId in tree -> Selection.Object(tree.summarize(place.objectId, overrides))
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
    PlaceDetails(
      place = place,
      selection = selection,
      rootPath = null
    )
  }
  onDetails(placeDetails)
  val objectId = (placeDetails.selection as? Selection.Object)?.summary?.objectId ?: return
  val rootPath = read("what holds ${hexObjectId(objectId)}") { dive ->
    dive.tree.rootPathTo(objectId, overrides)
  }
  onDetails(
    PlaceDetails(
      place = place,
      selection = placeDetails.selection,
      rootPath = rootPath
    )
  )
}

/**
 * What this window calls a place an agent asked about: the title a tab on it would have.
 *
 * The same [titleOf] the tabs are named by, so that a row of a session and the tab clicking it opens read the
 * same — an agent and the person watching it are looking at one object, and two spellings of it would be two
 * objects to them.
 *
 * With the one difference that makes this a function of its own: an agent can name an address this heap dump
 * has no object at, which is a call it was refused and still a row worth reading. [titleOf] would throw on
 * it, so the address is asked about first and stands for itself when it is nothing here.
 */
private fun HeapDominatorTreemap.agentPlaceTitle(place: Place): String = when (place) {
  is Place.Object ->
    if (objectNameOrNull(place.objectId) == null) exactHexObjectId(place.objectId) else titleOf(place)
  else -> titleOf(place)
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

/**
 * How often the sessions of the agents that have connected are read again, while a screen showing them is
 * open.
 *
 * A second, because what this is for is watching an agent work: a row appearing as the call it stands for is
 * answered is the difference between following an investigation and reading a report of one. Off entirely
 * while no such screen is open, which is nearly always.
 */
private const val AGENT_LOGS_REFRESH_MILLIS = 1_000L

/** What a tab is called for the beat between it being opened and the heap dump having named it. */
private const val NAMING_TAB = "…"

/**
 * How many of the places behind an arrow its menu lists.
 *
 * Enough for the walk anyone takes in one go, few enough that the menu fits under the pointer on a window
 * that isn't full height.
 */
private const val HISTORY_MENU_LIMIT = 15

private const val HISTORY_MENU_HINT = "Right click for everywhere this leads. One click:"

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
