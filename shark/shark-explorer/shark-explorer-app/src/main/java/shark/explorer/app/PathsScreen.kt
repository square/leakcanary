package shark.explorer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import shark.ReferenceLocationType
import shark.explorer.HeapObjectSummary
import shark.explorer.IndependentPath
import shark.explorer.IndependentPaths
import shark.explorer.ObjectDominator
import shark.explorer.PathReference
import shark.explorer.PathStep
import shark.explorer.ReachabilityStrength
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount

/**
 * Every way one object is held below its dominator, each drawn as a chain from the holder down to it.
 *
 * The same shape as a LeakCanary leak trace, because a leak trace is one of these: a column of objects
 * with a line running through them, what each one is on the right of it, and how the one above points at
 * it under that. A weaker link is drawn as a dashed line in the colour of its strength, so a path held
 * only by a cache or a thread local reads as one at a glance.
 */
@Composable
internal fun PathsScreen(
  target: HeapObjectSummary?,
  dominator: ObjectDominator?,
  paths: IndependentPaths?,
  coloring: CellColoring,
  onOpen: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(modifier, color = MaterialTheme.colorScheme.surface) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Text(heading(target, dominator), style = MaterialTheme.typography.titleMedium)
      // Room for the whole explanation here, unlike in the panel, where it's behind a question mark.
      Text(INDEPENDENT_PATHS_HINT, style = MaterialTheme.typography.bodySmall)
      when {
        paths == null -> Text(SEARCHING_PATHS, style = MaterialTheme.typography.bodyMedium)
        paths.paths.isEmpty() -> Text(NO_PATH_FOUND, style = MaterialTheme.typography.bodyMedium)
        else -> paths.paths.forEachIndexed { index, path ->
          PathTrace(
            path = path,
            index = index,
            pathCount = paths.paths.size,
            dominator = dominator,
            coloring = coloring,
            onOpen = onOpen
          )
        }
      }
      if (paths != null && paths.hasMore) {
        Text(MORE_PATHS, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

private fun heading(
  target: HeapObjectSummary?,
  dominator: ObjectDominator?
): String = when {
  target == null -> INDEPENDENT_PATHS
  dominator == null -> "How ${target.label} is held"
  else -> "How ${target.label} is held below ${dominator.label}"
}

/** One way the object is held, from what the chain starts at down to it. */
@Composable
private fun PathTrace(
  path: IndependentPath,
  index: Int,
  pathCount: Int,
  dominator: ObjectDominator?,
  coloring: CellColoring,
  onOpen: (Long) -> Unit
) {
  Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
    Text("Path ${index + 1} of $pathCount", style = MaterialTheme.typography.labelMedium)
    PathHeadRow(
      label = path.headLabel(dominator),
      // What the head points at, which is nothing for a GC root: a root reaches its object with no
      // field to name.
      reference = path.steps.firstOrNull()?.reference,
      nextStrength = path.steps.firstOrNull()?.strength,
      nodeId = if (path.gcRootLabel == null) dominator?.nodeId else null,
      onOpen = onOpen
    )
    path.steps.forEachIndexed { depth, step ->
      val next = path.steps.getOrNull(depth + 1)
      PathStepRow(
        step = step,
        // How this step points at the next one, which is what the next step was reached through.
        reference = next?.reference,
        nextStrength = next?.strength,
        coloring = coloring,
        onOpen = onOpen
      )
    }
  }
}

/**
 * What the chain starts at: the GC root that reaches it, or the dominator the paths run below.
 *
 * When steps were left out, the head stands for the elided chain rather than for the dominator, so it
 * says so — the reference under it belongs to the last of the objects left out, and the class it names
 * is how the reader can tell that's not the dominator's own field.
 */
private fun IndependentPath.headLabel(dominator: ObjectDominator?): String = when {
  gcRootLabel != null -> gcRootLabel!!
  hiddenStepCount > 0 ->
    "$ELLIPSIS $hiddenStepCount steps from ${dominator?.label ?: THE_DOMINATOR} to here"
  else -> dominator?.label ?: THE_DOMINATOR
}

/**
 * Where the chain starts, drawn as a step of it so that the reference leaving it has an owner to sit
 * under, the way a leak trace's GC root row does.
 *
 * Its circle is hollow: what holds a GC root is nothing, and how firmly the dominator itself is held is
 * the panel's business rather than this chain's, so there is no strength to colour it by.
 */
@Composable
private fun PathHeadRow(
  label: String,
  reference: PathReference?,
  nextStrength: ReachabilityStrength?,
  nodeId: Long?,
  onOpen: (Long) -> Unit
) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    Canvas(Modifier.width(GUTTER_WIDTH).fillMaxHeight()) {
      drawConnector(nodeColor = null, incoming = null, outgoing = nextStrength)
    }
    Column(Modifier.padding(bottom = 8.dp)) {
      Text(
        label,
        modifier = if (nodeId != null) Modifier.clickable { onOpen(nodeId) } else Modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = if (nodeId != null) LINK_COLOR else Color.Unspecified
      )
      reference?.let { ReferenceLine(it) }
    }
  }
}

/** One object along a path, the line running through it, and how it points at the one below. */
@Composable
private fun PathStepRow(
  step: PathStep,
  reference: PathReference?,
  nextStrength: ReachabilityStrength?,
  coloring: CellColoring,
  onOpen: (Long) -> Unit
) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    Canvas(Modifier.width(GUTTER_WIDTH).fillMaxHeight()) {
      drawConnector(
        nodeColor = legendColor(coloring, step.strength),
        // The line above this object is how it is held, and the one below is how it holds the next.
        incoming = step.strength,
        outgoing = nextStrength
      )
    }
    Column(Modifier.padding(bottom = 8.dp)) {
      ClassNameLine(step, onOpen)
      step.headline?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
      if (step.retainedCount > 0) {
        Text(
          "Retaining ${formatByteSize(step.retainedSize)} in " +
            formatObjectCount(step.retainedCount),
          style = MaterialTheme.typography.bodySmall
        )
      }
      if (step.strength != ReachabilityStrength.STRONG) {
        Text(
          step.strength.reachabilityText,
          style = MaterialTheme.typography.bodySmall,
          color = legendColor(coloring, step.strength)
        )
      }
      step.inspectorLabels.forEach { label ->
        Text(label, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
      }
      reference?.let { ReferenceLine(it) }
    }
  }
}

/**
 * Which object this step is: its package greyed out and its class name in full, the way a leak trace
 * prints one, so that a column of them reads as class names rather than as package names.
 */
@Composable
private fun ClassNameLine(
  step: PathStep,
  onOpen: (Long) -> Unit
) {
  val simpleName = step.className.substringAfterLast('.')
  val packageName = step.className.substringBeforeLast('.', missingDelimiterValue = "")
  val text = buildAnnotatedString {
    if (packageName.isNotEmpty()) {
      withStyle(SpanStyle(color = MUTED_TEXT)) { append("$packageName.") }
    }
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(simpleName) }
    withStyle(SpanStyle(color = MUTED_TEXT)) { append(" ${step.kind.typeName}") }
  }
  Text(
    text,
    // Folded objects are drawn nowhere, so there is nowhere to take a click on one: a string's characters
    // are counted inside the string.
    modifier = if (step.isInspectable) Modifier.clickable { onOpen(step.objectId) } else Modifier,
    style = MaterialTheme.typography.bodyMedium,
    color = if (step.isInspectable) LINK_COLOR else Color.Unspecified
  )
}

/** How the object above points at the one below: the field, on the class that declares it. */
@Composable
private fun ReferenceLine(reference: PathReference) {
  val text = buildAnnotatedString {
    withStyle(SpanStyle(color = MUTED_TEXT)) { append(reference.ownerPrefix()) }
    withStyle(
      SpanStyle(
        textDecoration = TextDecoration.Underline,
        // Italic for a static field, which belongs to the class rather than to the instance above.
        fontStyle = if (reference.locationType == ReferenceLocationType.STATIC_FIELD) {
          FontStyle.Italic
        } else {
          FontStyle.Normal
        }
      )
    ) {
      append(reference.displayName())
    }
  }
  Text(text, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall)
}

/** The class the field is read on, with no dot before an array index: `Tile.view`, `Object[][3]`. */
private fun PathReference.ownerPrefix(): String =
  if (locationType == ReferenceLocationType.ARRAY_ENTRY) ownerClassName else "$ownerClassName."

private fun PathReference.displayName(): String = when (locationType) {
  ReferenceLocationType.ARRAY_ENTRY -> "[$name]"
  ReferenceLocationType.LOCAL -> LOCAL_VARIABLE
  ReferenceLocationType.INSTANCE_FIELD, ReferenceLocationType.STATIC_FIELD -> name
}

/**
 * The line running through the objects of a path: a circle for this one, and half a line to each of its
 * neighbours, which the neighbour draws the other half of.
 *
 * A link no stronger than a `java.lang.ref.Reference` or a cache is dashed and takes that strength's
 * colour, which is how a path that only holds until memory runs short reads as one.
 *
 * A null [nodeColor] draws the circle hollow, for a row that stands at the head of a chain rather than
 * being one of its objects.
 */
private fun DrawScope.drawConnector(
  nodeColor: Color?,
  incoming: ReachabilityStrength?,
  outgoing: ReachabilityStrength?
) {
  val centerX = size.width / 2
  val centerY = NODE_CENTER_Y.toPx()
  val radius = NODE_RADIUS.toPx()
  val strokeWidth = CONNECTOR_WIDTH.toPx()
  if (incoming != null) {
    drawLine(
      color = connectorColor(incoming),
      start = Offset(centerX, 0f),
      end = Offset(centerX, centerY - radius),
      strokeWidth = strokeWidth,
      pathEffect = dashOrNull(incoming)
    )
  }
  if (outgoing != null) {
    drawLine(
      color = connectorColor(outgoing),
      start = Offset(centerX, centerY + radius),
      end = Offset(centerX, size.height),
      strokeWidth = strokeWidth,
      pathEffect = dashOrNull(outgoing)
    )
    drawArrowHead(Offset(centerX, size.height), connectorColor(outgoing))
  }
  if (nodeColor != null) {
    drawCircle(color = nodeColor, radius = radius, center = Offset(centerX, centerY))
  }
  drawCircle(
    color = CONNECTOR_COLOR,
    radius = radius,
    center = Offset(centerX, centerY),
    style = Stroke(width = strokeWidth)
  )
}

/** Says which way the line runs, at the bottom of it, where the next object is. */
private fun DrawScope.drawArrowHead(
  tip: Offset,
  color: Color
) {
  val halfWidth = ARROW_WIDTH.toPx() / 2
  val height = ARROW_HEIGHT.toPx()
  val head = Path().apply {
    moveTo(tip.x, tip.y)
    lineTo(tip.x - halfWidth, tip.y - height)
    lineTo(tip.x + halfWidth, tip.y - height)
    close()
  }
  drawPath(head, color)
}

private fun connectorColor(strength: ReachabilityStrength): Color =
  if (strength == ReachabilityStrength.STRONG) CONNECTOR_COLOR else WEAK_CONNECTOR_COLOR

private fun DrawScope.dashOrNull(strength: ReachabilityStrength): PathEffect? =
  if (strength == ReachabilityStrength.STRONG) {
    null
  } else {
    PathEffect.dashPathEffect(
      floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx())
    )
  }

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

internal const val NO_PATH_FOUND = "No path from the dominator down to this object was found."

internal const val MORE_PATHS =
  "The search stopped here. There may be more ways this object is held."

/** Stands in for the dominator's name until the panel has read it, which is a beat behind the paths. */
private const val THE_DOMINATOR = "the dominator"

/** How a leak trace names the reference a running method holds, which has no field to name. */
private const val LOCAL_VARIABLE = "<local variable>"

private const val ELLIPSIS = "…"

/** Wide enough for the line and its arrow head to sit clear of the text beside it. */
private val GUTTER_WIDTH = 20.dp

/** Where down a row the object's circle sits, which is the middle of its first line of text. */
private val NODE_CENTER_Y = 11.dp
private val NODE_RADIUS = 5.dp
private val CONNECTOR_WIDTH = 1.5.dp
private val ARROW_WIDTH = 7.dp
private val ARROW_HEIGHT = 5.dp
private val DASH_ON = 3.dp
private val DASH_OFF = 3.dp

/** The purple LeakCanary draws a leak trace in, which this is the same shape as. */
private val CONNECTOR_COLOR = Color(0xFF7E57C2)

/** And what a link the garbage collector may let go of is drawn in, dashed. */
private val WEAK_CONNECTOR_COLOR = Color(0xFFB0453A)

/** For the parts of a line that say what an object is rather than which one it is. */
internal val MUTED_TEXT = Color(0xFF6E6E6E)
