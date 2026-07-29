package shark.explorer.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import shark.explorer.ClassGroupSummary
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectSummary
import shark.explorer.HoldingPaths
import shark.explorer.LayoutCell
import shark.explorer.ObjectReferrers
import shark.explorer.PathStep
import shark.explorer.RadialLayout
import shark.explorer.RadialPresentation
import shark.explorer.ReachabilityStrength
import shark.explorer.Referrer
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
  /** Null while the pass over the heap dump that finds them is still running. */
  var referrers: ObjectReferrers? by remember(session) { mutableStateOf(null) }
  /** Null while the walk up to the GC roots is still running. */
  var holdingPaths: HoldingPaths? by remember(session) { mutableStateOf(null) }

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
    referrers = null
    holdingPaths = null
    selection = if (currentRequest == null) {
      null
    } else {
      session.read { explorer ->
        val tree = explorer.treeFor(followedStrengths)
        when (currentRequest) {
          is SelectionRequest.Object -> {
            val classGroup = tree.classGroupOrNull(currentRequest.objectId)
            when {
              classGroup != null -> Selection.ClassGroup(classGroup)
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

  // Finding what references an object means reading every object in the heap dump, so it lands after the
  // rest of the panel rather than holding it up. Keyed on the selection: a new one invalidates this.
  val selectedObjectId = (selection as? Selection.Object)?.summary?.objectId
  LaunchedEffect(session, followedStrengths, selectedObjectId) {
    referrers = selectedObjectId?.let { objectId ->
      session.read { explorer -> explorer.treeFor(followedStrengths).referrersOf(objectId) }
    }
  }

  // Walking up to the GC roots costs several passes over the heap dump, so it lands last of all. The
  // referrer list above it answers the same question one step deep, which is often enough.
  LaunchedEffect(session, followedStrengths, selectedObjectId) {
    holdingPaths = selectedObjectId?.let { objectId ->
      session.read { explorer -> explorer.treeFor(followedStrengths).holdingPathsTo(objectId) }
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
        referrers = referrers,
        holdingPaths = holdingPaths,
        scheme = scheme,
        onZoomInto = { navigation = navigation.zoomInto(it) },
        onInspect = { objectId ->
          selected = SelectedCell(objectId, isGroup = false)
          request = SelectionRequest.Object(objectId)
        },
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

  /** Every instance of one class under the root was clicked, so there's no one object to describe. */
  data class ClassGroup(val summary: ClassGroupSummary) : Selection

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
  referrers: ObjectReferrers?,
  holdingPaths: HoldingPaths?,
  scheme: CellColorScheme,
  onZoomInto: (Long) -> Unit,
  onInspect: (Long) -> Unit,
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
        is Selection.ClassGroup -> ClassGroupDetails(selection.summary, onZoomInto)
        is Selection.Object -> ObjectDetails(
          summary = selection.summary,
          referrers = referrers,
          holdingPaths = holdingPaths,
          scheme = scheme,
          onZoomInto = onZoomInto,
          onInspect = onInspect
        )
      }
    }
  }
}

/**
 * A cell standing for every instance of one class under the root. Says so in as many words: the count,
 * the class, and that these are separate objects that only happen to share it.
 */
@Composable
private fun ClassGroupDetails(
  summary: ClassGroupSummary,
  onZoomInto: (Long) -> Unit
) {
  Text(
    "${summary.instanceCount} instances",
    style = MaterialTheme.typography.titleMedium
  )
  Text(summary.className, style = MaterialTheme.typography.bodySmall)
  Text(CLASS_GROUP_EXPLANATION, style = MaterialTheme.typography.bodySmall)
  Detail("Retained together", formatByteSize(summary.retainedSize))
  Button(onClick = { onZoomInto(summary.nodeId) }) {
    Text("Zoom in")
  }
}

@Composable
private fun ObjectDetails(
  summary: HeapObjectSummary,
  referrers: ObjectReferrers?,
  holdingPaths: HoldingPaths?,
  scheme: CellColorScheme,
  onZoomInto: (Long) -> Unit,
  onInspect: (Long) -> Unit
) {
  Text(summary.label, style = MaterialTheme.typography.titleMedium, overflow = TextOverflow.Ellipsis)
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
    Box(Modifier.size(SWATCH_SIZE).background(legendColor(scheme, summary.strength)))
    Text(summary.strength.reachabilityText, style = MaterialTheme.typography.bodySmall)
  }
  Detail("Retained", formatByteSize(summary.retainedSize))
  Detail("Retained objects", summary.retainedCount.toString())
  Detail("Shallow", formatByteSize(summary.shallowSize))
  Detail("Dominates", "${summary.dominatedObjectCount} objects")
  summary.inspectorLabels.forEach { label ->
    Text(label, style = MaterialTheme.typography.bodySmall)
  }
  Button(onClick = { onZoomInto(summary.objectId) }, enabled = summary.dominatedObjectCount > 0) {
    Text("Zoom in")
  }
  Referrers(referrers, onInspect)
  WhatHoldsIt(holdingPaths, onInspect)
  Fields(summary, onInspect)
}

/**
 * What holds the selected object, and why it can be dominated by the root without being a GC root: two
 * referrers whose paths meet only at the root leave the root as the only thing that dominates it.
 */
@Composable
private fun Referrers(
  referrers: ObjectReferrers?,
  onInspect: (Long) -> Unit
) {
  if (referrers != null && referrers.referrerCount == 0) {
    // Nothing points at the virtual root. Everything else in the tree got there from a referrer.
    return
  }
  Text("Held by", style = MaterialTheme.typography.labelSmall)
  if (referrers == null) {
    Text(SEARCHING_REFERRERS, style = MaterialTheme.typography.bodySmall)
    return
  }
  if (referrers.isDominatedByRoot && referrers.holdingReferrerCount > 1) {
    Text(
      SHARED_EXPLANATION.format(referrers.holdingReferrerCount),
      style = MaterialTheme.typography.bodySmall
    )
  }
  referrers.referrers.forEach { referrer ->
    val label = referrer.fieldName?.let { "${referrer.label}.$it" } ?: referrer.label
    Inspectable(label + referrer.weakeningNote(), referrer.inspectableObjectId, onInspect)
  }
  if (referrers.hiddenReferrerCount > 0) {
    Text(
      "and ${referrers.hiddenReferrerCount} more",
      style = MaterialTheme.typography.bodySmall
    )
  }
}

/**
 * Why a referrer might be nowhere in the chains below: it holds the object without being what keeps it
 * in memory. Empty for an ordinary reference, which is nearly all of them.
 */
private fun Referrer.weakeningNote(): String {
  val strength = weakeningStrength ?: return ""
  return " · " + when {
    isFollowed -> "${strength.referenceText}, and the strongest thing holding it"
    strength == ReachabilityStrength.CACHE -> CACHED_REFERRER_NOTE
    else -> "${strength.referenceText}, which doesn't keep it in memory"
  }
}

/**
 * Every way the object is held, spelled out from a GC root down to it.
 *
 * The question a big rectangle under the root raises is what is keeping it in memory, and neither the
 * dominator tree nor the list of referrers answers it: the tree says "nothing in particular", and the
 * referrers are one step deep. The chains are the answer — the view showing a bitmap on two of them, an
 * image cache on the third — so the panel says which object the chains have in common, if any, and how
 * many chains each object is on.
 */
@Composable
private fun WhatHoldsIt(
  holdingPaths: HoldingPaths?,
  onInspect: (Long) -> Unit
) {
  Text(WHAT_HOLDS_IT, style = MaterialTheme.typography.labelSmall)
  if (holdingPaths == null) {
    Text(SEARCHING_PATHS, style = MaterialTheme.typography.bodySmall)
    return
  }
  if (holdingPaths.paths.isEmpty()) {
    Text(NO_PATHS, style = MaterialTheme.typography.bodySmall)
    return
  }
  val explanation = holdingPaths.commonHolderLabel?.let { COMMON_HOLDER_EXPLANATION.format(it) }
    ?: NO_COMMON_HOLDER_EXPLANATION.takeIf { holdingPaths.isDominatedByRoot }
  explanation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
  holdingPaths.paths.forEachIndexed { index, path ->
    Text(
      "Path ${index + 1} of ${holdingPaths.paths.size} · ${path.gcRootLabel}",
      style = MaterialTheme.typography.labelSmall
    )
    if (path.hiddenStepCount > 0) {
      Text(
        "$ELLIPSIS ${path.hiddenStepCount} steps between the GC root and here",
        style = MaterialTheme.typography.bodySmall
      )
    }
    path.steps.forEachIndexed { depth, step ->
      PathStepLine(step, depth, holdingPaths.paths.size, onInspect)
    }
  }
  if (holdingPaths.hiddenPathCount > 0) {
    Text(
      "and ${holdingPaths.hiddenPathCount} more objects holding it, not followed",
      style = MaterialTheme.typography.bodySmall
    )
  }
}

/** One step of a path: the field that reaches the object, and how many of the paths go through it. */
@Composable
private fun PathStepLine(
  step: PathStep,
  depth: Int,
  pathCount: Int,
  onInspect: (Long) -> Unit
) {
  val reference = step.referenceName?.let { name ->
    if (name.startsWith("[")) name else ".$name"
  }
  // Only worth pointing out when the paths differ: with one path everything is on all of them.
  val shared = if (step.pathCount > 1 && pathCount > 1) {
    " · on ${step.pathCount} of $pathCount"
  } else {
    ""
  }
  Inspectable(
    text = listOfNotNull(reference, step.label).joinToString(" ") + shared,
    objectId = step.objectId.takeIf { step.isInspectable },
    onInspect = onInspect,
    indentSteps = depth
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

/**
 * What a cache holding an object means when something else holds it too: the bytes are the something
 * else's, which is the whole point of treating a cache as weaker than a strong reference.
 */
internal const val CACHED_REFERRER_NOTE =
  "a cache that evicts, so this isn't what keeps it in memory"

/** Shown while the pass over the heap dump that finds the referrers is still running. */
internal const val SEARCHING_REFERRERS = "Reading the heap dump…"

/**
 * Why a big rectangle can sit flat under the root without being a GC root: more than one object holds
 * it, on paths that meet only at the root, so no single owner would free it and the dominator tree has
 * nowhere else to put its bytes.
 */
internal const val SHARED_EXPLANATION =
  "%d objects hold this one, on paths that meet only at the root — so releasing any one of them " +
    "wouldn't free it, and its bytes are attributed to the whole heap rather than to an owner."

/** The heading of the section spelling out how the selected object is held. */
internal const val WHAT_HOLDS_IT = "What holds it"

/** Shown while the walk up to the GC roots is still running. */
internal const val SEARCHING_PATHS = "Following what holds it up to the GC roots…"

/** Only the virtual root has nothing holding it, and it isn't shown as an object. */
private const val NO_PATHS = "Nothing in the heap dump points at this."

/**
 * What one object being on every path means: it's the answer to "what is keeping this in memory", which
 * is the question the dominator tree leaves open when it puts an object under the root.
 */
internal const val COMMON_HOLDER_EXPLANATION =
  "Every path here goes through %s, so this stays in memory for as long as that one does."

/** And when they share nothing, which is exactly when the root ends up dominating the object. */
internal const val NO_COMMON_HOLDER_EXPLANATION =
  "The paths here share nothing above this object, so no one of them would free it — which is why " +
    "the root is what dominates it."

private const val ELLIPSIS = "…"

internal const val BREADCRUMB_SEPARATOR = "›"

internal const val CLASS_GROUP_EXPLANATION =
  "Not one object: these are all the instances of this class that nothing owns on its own, gathered " +
    "so the root's children can be read. Zoom in to see them one by one."

private const val GROUP_EXPLANATION =
  "Too small or too many to draw one by one. Zoom into what holds them to see them."

private val DETAILS_WIDTH = 320.dp

/** How far one step of a path is indented past the one holding it, and where the cascade stops. */
private val STEP_INDENT = 4.dp
private const val MAX_INDENT_STEPS = 8

/** Panel lines that lead to another object, coloured like a link because that's what they are. */
private val LINK_COLOR = SELECTION_COLOR
internal val SWATCH_SIZE = 10.dp
