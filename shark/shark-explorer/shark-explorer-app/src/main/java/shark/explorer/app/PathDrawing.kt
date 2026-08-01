package shark.explorer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import shark.ReferenceLocationType
import shark.explorer.PathReference
import shark.explorer.PathStep
import shark.explorer.ReachabilityStrength
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount

/**
 * How a chain of objects is drawn: a column of them with a line running through it, what each one is on
 * the right of it, and how the one above points at it under that.
 *
 * The same shape as a LeakCanary leak trace, because a leak trace is one of these. Shared by every screen
 * that draws a chain — [PathsScreen] draws the ways an object is held below its dominator, [RootPathPanel]
 * the shortest way a GC root reaches it — so that two chains of the same heap dump never read differently.
 */

/**
 * Where a chain starts, drawn as a step of it so that the reference leaving it has an owner to sit under,
 * the way a leak trace's GC root row does.
 *
 * Its circle is hollow: what holds a GC root is nothing, and a chain that starts below a dominator has the
 * dominator described elsewhere, so there is no strength to colour it by either.
 */
@Composable
internal fun PathHeadRow(
  label: String,
  /** What the head points at, which is nothing for a GC root: a root reaches its object with no field. */
  reference: PathReference?,
  nextStrength: ReachabilityStrength?,
  /** The object the label names, when it is one and can therefore be opened. */
  nodeId: Long?,
  onOpen: (Long) -> Unit
) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    Canvas(Modifier.width(GUTTER_WIDTH).fillMaxHeight()) {
      drawConnector(nodeColor = null, incoming = null, outgoing = nextStrength, role = PathRole.STEP)
    }
    // Only ever the head of a chain that says everything: a brief one starts part way down, where there is
    // no GC root to name. See [PathCutRow].
    Column(Modifier.padding(bottom = PathDetail.FULL.rowSpacing)) {
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

/** One object along a chain, the line running through it, and how it points at the one below. */
@Composable
internal fun PathStepRow(
  step: PathStep,
  /** How this step points at the next one, which is what that step was reached through. */
  reference: PathReference?,
  nextStrength: ReachabilityStrength?,
  coloring: CellColoring,
  onOpen: (Long) -> Unit,
  role: PathRole = PathRole.STEP,
  detail: PathDetail = PathDetail.FULL
) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    Canvas(Modifier.width(GUTTER_WIDTH).fillMaxHeight()) {
      drawConnector(
        nodeColor = legendColor(coloring, step.strength),
        // The line above this object is how it is held, and the one below is how it holds the next.
        incoming = step.strength,
        outgoing = nextStrength,
        role = role
      )
    }
    Column(Modifier.padding(bottom = detail.rowSpacing)) {
      ClassNameLine(step, onOpen, detail)
      if (role == PathRole.DOMINATOR && detail == PathDetail.FULL) {
        // In words as well as with the ring: a chain from a GC root runs through a dozen objects, and
        // which of them the treemap draws this one inside is the whole reason to read it.
        Text(
          DOMINATES_TARGET,
          style = MaterialTheme.typography.bodySmall,
          color = DOMINATOR_COLOR,
          fontWeight = FontWeight.Bold
        )
      }
      if (detail == PathDetail.FULL) {
        step.headline?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (step.retainedCount > 0) {
          Text(
            "Retaining ${formatByteSize(step.retainedSize)} in " + formatObjectCount(step.retainedCount),
            style = MaterialTheme.typography.bodySmall
          )
        }
      }
      if (step.strength != ReachabilityStrength.STRONG) {
        Text(
          step.strength.reachabilityText,
          style = MaterialTheme.typography.bodySmall,
          color = legendColor(coloring, step.strength)
        )
      }
      if (detail == PathDetail.FULL) {
        step.inspectorLabels.forEach { label ->
          Text(label, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
        }
        // Which field holds the next object is a line of its own, and a chain that is only being glanced at
        // has the arrow in the gutter saying that one holds the next: which field is the question the reader
        // has once they've stopped on it, and a brief chain is read as a column of class names.
        reference?.let { ReferenceLine(it) }
      }
    }
  }
}

/**
 * Where a chain has been cut: a dotted line down into the step below it, and no object at the top of it.
 *
 * What a brief chain starts with, because it starts part way down — see [shark.explorer.stepsBelow]. The dots
 * are it saying there is more above than it shows, in the same gutter the rest of the line runs down, and the
 * rest of the answer is already on screen: the rectangle the map draws the first step in fills the view.
 */
@Composable
internal fun PathCutRow(
  /** How firmly the step below is held, which is what the line down into it is drawn as. */
  nextStrength: ReachabilityStrength
) {
  Row(Modifier.fillMaxWidth().height(CUT_ROW_HEIGHT)) {
    Canvas(Modifier.width(GUTTER_WIDTH).fillMaxHeight()) {
      val centerX = size.width / 2
      val color = connectorColor(nextStrength)
      drawLine(
        color = color,
        start = Offset(centerX, 0f),
        end = Offset(centerX, size.height),
        strokeWidth = CONNECTOR_WIDTH.toPx(),
        // Sparser than the dashes of a link the collector may let go of, which are the other broken line a
        // chain draws: this one is about how much of the chain is shown rather than about how it holds.
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(CUT_DASH_ON.toPx(), CUT_DASH_OFF.toPx()))
      )
      drawArrowHead(Offset(centerX, size.height), color)
    }
  }
}

/**
 * How much a chain says about each of its objects.
 *
 * A chain from a GC root down to a bitmap of a real app runs through a dozen objects and can be cut at
 * twenty, and everything worth saying about each of them is four lines: that chain is taller than any
 * window. So a chain the reader is only glancing at says as little as still names the object, and the one
 * they stopped on says all of it.
 */
internal enum class PathDetail(val rowSpacing: Dp) {

  /** What each object is, what it retains, what an inspector makes of it, and what holds the next. */
  FULL(8.dp),

  /** Its class alone, with its retained size beside it, and the gutter for how it is held. */
  BRIEF(6.dp)
}

/** What one object of a chain is to the object the chain leads to, drawn as a ring around its circle. */
internal enum class PathRole {

  /** An object the chain runs through, on the way rather than the reason. */
  STEP,

  /**
   * An object that dominates the one the chain leads to: every path from a GC root goes through it, so
   * releasing it is what would free the object.
   */
  DOMINATOR,

  /** The object the chain leads to, which is the last of its steps. */
  TARGET
}

/**
 * Which object this step is: its package greyed out and its class name in full, the way a leak trace
 * prints one, so that a column of them reads as class names rather than as package names.
 */
@Composable
private fun ClassNameLine(
  step: PathStep,
  onOpen: (Long) -> Unit,
  detail: PathDetail
) {
  val simpleName = step.className.substringAfterLast('.')
  val packageName = step.className.substringBeforeLast('.', missingDelimiterValue = "")
  val text = buildAnnotatedString {
    if (detail == PathDetail.FULL && packageName.isNotEmpty()) {
      withStyle(SpanStyle(color = MUTED_TEXT)) { append("$packageName.") }
    }
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(simpleName) }
    if (detail == PathDetail.FULL) {
      withStyle(SpanStyle(color = MUTED_TEXT)) { append(" ${step.kind.typeName}") }
    } else if (step.retainedCount > 0) {
      // On the class name's own line rather than under it, which is the one place a brief chain has room
      // for how much of the heap a step holds — and that is what the map is being read for.
      withStyle(SpanStyle(color = MUTED_TEXT)) { append(" · ${formatByteSize(step.retainedSize)}") }
    }
  }
  // Nothing to open from a chain that only shows while the pointer is somewhere else, and folded objects
  // are drawn nowhere either: a string's characters are counted inside the string.
  val isClickable = step.isInspectable && detail == PathDetail.FULL
  Text(
    text,
    modifier = if (isClickable) Modifier.clickable { onOpen(step.objectId) } else Modifier,
    style = MaterialTheme.typography.bodyMedium,
    color = if (isClickable) LINK_COLOR else Color.Unspecified
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
 * The line running through the objects of a chain: a circle for this one, and half a line to each of its
 * neighbours, which the neighbour draws the other half of.
 *
 * A link no stronger than a `java.lang.ref.Reference` or a cache is dashed and takes that strength's
 * colour, which is how a path that only holds until memory runs short reads as one.
 *
 * A null [nodeColor] draws the circle hollow, for a row that stands at the head of a chain rather than
 * being one of its objects. A [role] other than [PathRole.STEP] rings it, which is how the objects that
 * own the one at the end of the chain are picked out of the ones merely on the way.
 */
private fun DrawScope.drawConnector(
  nodeColor: Color?,
  incoming: ReachabilityStrength?,
  outgoing: ReachabilityStrength?,
  role: PathRole
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
  val ringColor = when (role) {
    PathRole.STEP -> null
    PathRole.DOMINATOR -> DOMINATOR_COLOR
    PathRole.TARGET -> SELECTION_COLOR
  }
  if (ringColor != null) {
    drawCircle(
      color = ringColor,
      radius = radius + RING_GAP.toPx(),
      center = Offset(centerX, centerY),
      style = Stroke(width = RING_WIDTH.toPx())
    )
  }
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
    PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx()))
  }

/** What a step that dominates the object at the end of the chain says about itself. */
internal const val DOMINATES_TARGET = "Dominates this object"

/** How a leak trace names the reference a running method holds, which has no field to name. */
private const val LOCAL_VARIABLE = "<local variable>"

/** Wide enough for the line, its arrow head and a ring to sit clear of the text beside it. */
private val GUTTER_WIDTH = 20.dp

/** Where down a row the object's circle sits, which is the middle of its first line of text. */
private val NODE_CENTER_Y = 11.dp
private val NODE_RADIUS = 5.dp
private val CONNECTOR_WIDTH = 1.5.dp
private val ARROW_WIDTH = 7.dp
private val ARROW_HEIGHT = 5.dp
private val DASH_ON = 3.dp
private val DASH_OFF = 3.dp

/** Tall enough for the dots above a cut chain to read as a line, short enough to cost it no room. */
private val CUT_ROW_HEIGHT = 14.dp
private val CUT_DASH_ON = 2.dp
private val CUT_DASH_OFF = 4.dp

/** How far outside a step's own circle the ring picking it out sits. */
private val RING_GAP = 2.5.dp
private val RING_WIDTH = 1.5.dp

/** The purple LeakCanary draws a leak trace in, which this is the same shape as. */
private val CONNECTOR_COLOR = Color(0xFF7E57C2)

/** And what a link the garbage collector may let go of is drawn in, dashed. */
private val WEAK_CONNECTOR_COLOR = Color(0xFFB0453A)

/**
 * What an object that owns the one at the end of a chain is ringed and labelled in. Its own hue, because
 * the two colours already in a chain mean how firmly a link holds, and this says nothing about that.
 */
internal val DOMINATOR_COLOR = Color(0xFF00796B)

/** For the parts of a line that say what an object is rather than which one it is. */
internal val MUTED_TEXT = Color(0xFF6E6E6E)
