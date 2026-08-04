package shark.explorer.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import shark.explorer.HeapDominatorTreemap
import shark.explorer.HeapObjectKind
import shark.explorer.PathReference
import shark.explorer.PathStep
import shark.explorer.ReachabilityStrength
import shark.explorer.formatByteSize
import shark.explorer.formatObjectCount
import shark.explorer.hexObjectId

/**
 * How a chain of objects is drawn: a column of them with a line running through it, what each one is on
 * the right of it, and how the one above points at it under that.
 *
 * The same shape as a LeakCanary leak trace, because a leak trace is one of these. Every chain the window
 * draws is drawn by this — see [RootPathPanel] — so that two chains of the same heap dump never read
 * differently.
 *
 * What a circle, a line and an arrow head look like is [PathStyle], which the graph draws its objects with
 * too. What this file owns is the column: a row per object, the gutter down the left of it, and the
 * reference tied to that line under each row.
 */

/**
 * The whole heap dump, which every chain hangs below and which the map opens on.
 *
 * Drawn as a step of the chain so that the way back to the whole heap is where the chain says the whole heap
 * is, rather than being a control somewhere else. Its circle is hollow: it is no object of the heap dump.
 */
@Composable
internal fun PathRootRow(
  nextStrength: ReachabilityStrength?,
  onOpen: (Long) -> Unit
) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    NodeGutter(kind = null, incoming = null, outgoing = nextStrength, endsInArrow = nextStrength != null)
    Column(Modifier.padding(bottom = PathDetail.FULL.rowSpacing)) {
      Text(
        HeapDominatorTreemap.ROOT_LABEL,
        Modifier.clickableRow { onOpen(HeapDominatorTreemap.ROOT_OBJECT_ID) },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

/**
 * Where a chain starts below the whole heap dump: which kind of GC root reaches its first object.
 *
 * A row of its own so that the reference leaving it has an owner to sit under, the way a leak trace's GC
 * root row does, and hollow like the row above it — a GC root is a record of the heap dump rather than an
 * object of it, so there is nothing to open and no kind to letter it with.
 */
@Composable
internal fun PathHeadRow(
  label: String,
  /** What the head points at, which is nothing for a GC root: a root reaches its object with no field. */
  reference: PathReference?,
  nextStrength: ReachabilityStrength?,
  /** What hangs under the label, which is where a stretch of the chain running off the head is switched. */
  below: @Composable () -> Unit = {}
) {
  ChainRow(
    reference = reference,
    nextStrength = nextStrength,
    detail = PathDetail.FULL,
    gutter = { endsInArrow ->
      NodeGutter(kind = null, incoming = null, outgoing = nextStrength, endsInArrow = endsInArrow)
    }
  ) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    below()
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
  detail: PathDetail = PathDetail.FULL,
  /** What hangs under the object, which is where a stretch of the chain below it is switched. */
  below: @Composable () -> Unit = {}
) {
  ChainRow(
    reference = reference,
    nextStrength = nextStrength,
    detail = detail,
    gutter = { endsInArrow ->
      NodeGutter(
        kind = step.kind,
        // The line above this object is how it is held, and the one below is how it holds the next.
        incoming = step.strength,
        outgoing = nextStrength,
        endsInArrow = endsInArrow,
        role = role
      )
    },
    // The object the panel is describing, marked as such: a chain and a panel side by side that don't say
    // which of the chain's objects the panel is about are two answers to two questions nobody asked.
    background = if (role == PathRole.TARGET) TARGET_BACKGROUND else null
  ) {
    if (detail == PathDetail.FULL) {
      ObjectIdentity(
        className = step.className,
        typeName = step.kind.typeName,
        objectId = step.objectId,
        // Folded objects are drawn nowhere on the map: a string's characters are counted inside the string.
        onOpen = if (step.isInspectable) ({ onOpen(step.objectId) }) else null
      )
    } else {
      BriefStepLine(step)
    }
    if (role == PathRole.DOMINATOR && detail == PathDetail.FULL) {
      // In words as well as with the ring: a chain from a GC root runs through a dozen objects, and which
      // of them the treemap draws this one inside is the whole reason to read it. The arrow is what it
      // dominates — everything below it on the chain, down to the object at the end.
      Text(
        DOMINATES_BELOW,
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
    }
    below()
  }
}

/**
 * One object of a chain and, under it, the reference the object below is reached through.
 *
 * Two rows rather than one column beside one gutter, so that the reference can be tied to the line with a
 * bracket: the field named there and the arrow drawn in the gutter are the same reference said twice, and
 * nothing else in the drawing said so. Which also puts the arrow head at the bottom of whichever of the two
 * rows is the last, where the next object is.
 */
@Composable
private fun ChainRow(
  reference: PathReference?,
  nextStrength: ReachabilityStrength?,
  detail: PathDetail,
  gutter: @Composable (Boolean) -> Unit,
  background: Color? = null,
  content: @Composable () -> Unit
) {
  // A chain glanced at while the pointer moves is read as a column of class names, so which field holds the
  // next object is left to the one the reader stopped on: it's the question they have once they're on it.
  val tiedReference = reference.takeIf { detail == PathDetail.FULL && nextStrength != null }
  Column(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
      gutter(tiedReference == null)
      Column(
        Modifier.padding(bottom = if (tiedReference == null) detail.rowSpacing else 0.dp)
          .then(if (background == null) Modifier else Modifier.background(background, TARGET_SHAPE))
          .padding(horizontal = if (background == null) 0.dp else TARGET_PADDING)
      ) {
        content()
      }
    }
    if (tiedReference != null) {
      ReferenceRow(tiedReference, nextStrength!!, detail.rowSpacing)
    }
  }
}

/** How the object above points at the one below, tied to the line that says the same thing. */
@Composable
private fun ReferenceRow(
  reference: PathReference,
  /** How firmly the object below is held, which is what the line past it is drawn as. */
  strength: ReachabilityStrength,
  bottomPadding: Dp
) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    Canvas(Modifier.width(GUTTER_WIDTH).fillMaxHeight()) {
      val centerX = size.width / 2
      val color = connectorColor(strength)
      drawLine(
        color = color,
        start = Offset(centerX, 0f),
        end = Offset(centerX, size.height),
        strokeWidth = CONNECTOR_WIDTH.toPx(),
        pathEffect = dashOrNull(strength)
      )
      // The bracket to the field name, which is what the line down to the next object is.
      drawLine(
        color = color,
        start = Offset(centerX, REFERENCE_TIE_Y.toPx()),
        end = Offset(size.width, REFERENCE_TIE_Y.toPx()),
        strokeWidth = CONNECTOR_WIDTH.toPx()
      )
      drawArrowHead(Offset(centerX, size.height), color)
    }
    Column(Modifier.padding(bottom = bottomPadding)) {
      ReferenceLine(reference)
    }
  }
}

/**
 * Where a chain has been cut: a dotted line down into the step below it, and no object at the top of it.
 *
 * What a chain that starts part way down starts with — see [shark.explorer.stepsBelow]. The dots are it
 * saying there is more above than it shows, in the same gutter the rest of the line runs down, and the rest
 * of the answer is already on screen: the rectangle the map draws the first step in fills the view.
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
 * What one object is to the object a drawing is about, drawn as a ring around its circle by
 * [drawObjectCircle]. Named after the chain because that is where it was first said, and read by
 * everything that draws an object: the graph gives its circles a role from the same three.
 */
internal enum class PathRole {

  /** An object on the way rather than the reason: no ring. */
  STEP,

  /**
   * An object that dominates what hangs below it: every path from a GC root to those objects goes
   * through it, so releasing it is what would free them.
   */
  DOMINATOR,

  /** The object the window is describing, which the panels beside the view are about. */
  TARGET
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

/**
 * Which object this is: its class, then its class in full and its address under that.
 *
 * The same three lines wherever the window names an object — a step of a chain, the card at the pointer, the
 * bar above the map — because they are the same question, and a reader who has learnt to skip the grey lines
 * once should not have to learn where they are again on the next surface.
 */
@Composable
internal fun ObjectIdentity(
  className: String,
  /** What kind of object it is, said after its name: `Tile instance`. Null where it isn't known. */
  typeName: String?,
  /** Null for the whole heap dump, which is no object and has no address. */
  objectId: Long?,
  modifier: Modifier = Modifier,
  /** Where clicking it goes, or null for a name that is already what the window is showing. */
  onOpen: (() -> Unit)? = null
) {
  Column(if (onOpen == null) modifier else modifier.clickableRow(onOpen)) {
    Text(
      // One line of text rather than two words side by side: what the object is reads as one phrase, and
      // anything that has to find this line by what it says — a test, a screen reader — finds one of it.
      buildAnnotatedString {
        append(className.substringAfterLast('.'))
        typeName?.let { withStyle(MUTED_SPAN) { append(" $it") } }
      },
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Bold
    )
    // Only where there is a package to read past: for a name that has none the two lines would be the same
    // line twice, which is what the whole heap dump's own row was.
    if ('.' in className) {
      Text(className, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
    }
    if (objectId != null && objectId != HeapDominatorTreemap.ROOT_OBJECT_ID) {
      Text(hexObjectId(objectId), style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
    }
  }
}

/** What a step of a chain that is only being glanced at says: its class, and how much of the heap it holds. */
@Composable
private fun BriefStepLine(step: PathStep) {
  Text(
    buildAnnotatedString {
      append(step.className.substringAfterLast('.'))
      if (step.retainedCount > 0) {
        // Beside the class name rather than under it, which is the one place a brief chain has room for how
        // much of the heap a step holds — and that is what the map is being read for.
        withStyle(MUTED_SPAN) { append(" · ${formatByteSize(step.retainedSize)}") }
      }
    },
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.Bold
  )
}

/** How the object above points at the one below: the field, on the class that declares it. */
@Composable
private fun ReferenceLine(reference: PathReference) {
  Text(
    buildAnnotatedString {
      // Which class the field is read on as well as its name, because the row above names the object's
      // own class and an inherited field is declared on another one.
      withStyle(MUTED_SPAN) { append(reference.ownerPrefix()) }
      append(referenceName(reference))
    },
    style = MaterialTheme.typography.bodySmall
  )
}

/**
 * A line of a chain that leads somewhere: clicking it goes to that object.
 *
 * The hand is the whole of how it says so, rather than a colour: which object a step is, is drawn the same
 * way everywhere the window names one, and half of those places are nothing to click.
 */
internal fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
  pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick)

/**
 * The line running through the objects of a chain: this one's circle, and half a line to each of its
 * neighbours, which the neighbour draws the other half of.
 *
 * A link no stronger than a `java.lang.ref.Reference` or a cache is dashed and takes that strength's
 * colour, which is how a path that only holds until memory runs short reads as one. The circle itself is
 * [drawObjectCircle], which is also what the graph draws its objects with.
 */
@Composable
private fun NodeGutter(
  kind: HeapObjectKind?,
  incoming: ReachabilityStrength?,
  outgoing: ReachabilityStrength?,
  /** Whether the line leaving this row arrives at the next object, or runs on to a reference under it. */
  endsInArrow: Boolean,
  role: PathRole = PathRole.STEP
) {
  val measurer = rememberTextMeasurer()
  Canvas(Modifier.width(GUTTER_WIDTH).fillMaxHeight()) {
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
      if (endsInArrow) {
        drawArrowHead(Offset(centerX, size.height), connectorColor(outgoing))
      }
    }
    drawObjectCircle(
      center = Offset(centerX, centerY),
      kind = kind,
      role = role,
      radius = radius,
      measurer = measurer
    )
  }
}

/** What a step that dominates the object at the end of the chain says about itself. */
internal const val DOMINATES_BELOW = "Dominates ↓"

/** Wide enough for the line, its arrow head and a ring to sit clear of the text beside it. */
private val GUTTER_WIDTH = 26.dp

/** Where down a row the object's circle sits, which is the middle of its first line of text. */
private val NODE_CENTER_Y = 11.dp

/** And where down a reference's own row the bracket tying it to the line sits, which is its first line. */
private val REFERENCE_TIE_Y = 9.dp

/** Tall enough for the dots above a cut chain to read as a line, short enough to cost it no room. */
private val CUT_ROW_HEIGHT = 14.dp
private val CUT_DASH_ON = 2.dp
private val CUT_DASH_OFF = 4.dp

/** What the object the panel is describing is drawn behind, which is the end of the chain. */
private val TARGET_BACKGROUND = Color(0x1A2196F3)
private val TARGET_SHAPE = RoundedCornerShape(4.dp)
private val TARGET_PADDING = 4.dp
