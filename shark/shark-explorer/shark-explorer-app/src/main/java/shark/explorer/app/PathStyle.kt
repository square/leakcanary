package shark.explorer.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import shark.ReferenceLocationType
import shark.explorer.HeapObjectKind
import shark.explorer.PathReference
import shark.explorer.ReachabilityStrength

/**
 * How an object and a reference are drawn, wherever the window draws one.
 *
 * Two surfaces draw the heap dump's own objects and references rather than the tree's cells: a chain
 * from a GC root ([PathDrawing]) and the expandable graph ([GraphView]). They are the same picture in
 * two arrangements — a column of circles joined top to bottom, and circles joined left to right — so
 * what a circle, a line and an arrow head look like lives here and neither of them owns it. Change a
 * colour or a radius here and both change together, which is the whole point: two drawings of the same
 * heap dump that don't match are two things a reader has to learn instead of one.
 *
 * What each surface keeps for itself is its arrangement: how tall a row is, where the gutter runs,
 * what happens on a click.
 */

/**
 * One object's circle: the letter of its kind in it, in that kind's colour, and a ring around it when
 * it is more than a step on the way.
 *
 * The letter is what makes a picture of circles say what it is a picture of before any of the names
 * are read: `I` for an instance, `C` for a class. How firmly an object is held is said by the lines
 * into it rather than by the circle, which is what leaves the circle free to say what the object is.
 *
 * A null [kind] draws it hollow and empty, for something that is no object of the heap dump: the whole
 * heap dump itself, a GC root, a pile of objects the dominator tree gathered under one node.
 */
internal fun DrawScope.drawObjectCircle(
  center: Offset,
  kind: HeapObjectKind?,
  role: PathRole = PathRole.STEP,
  radius: Float = NODE_RADIUS.toPx(),
  measurer: TextMeasurer? = null
) {
  if (kind != null) {
    drawCircle(color = kind.badgeColor, radius = radius, center = center)
    measurer?.let { drawBadgeLetter(it, kind, center, radius) }
  }
  drawCircle(
    color = if (kind == null) CONNECTOR_COLOR else kind.badgeColor,
    radius = radius,
    center = center,
    style = Stroke(width = CONNECTOR_WIDTH.toPx())
  )
  when (role) {
    PathRole.STEP -> Unit
    PathRole.DOMINATOR -> drawNodeRing(center, radius, DOMINATOR_COLOR)
    PathRole.TARGET -> drawNodeRing(center, radius, SELECTION_COLOR)
  }
}

/** What picks one circle out of the rest: a ring outside it, clear of the circle's own outline. */
internal fun DrawScope.drawNodeRing(
  center: Offset,
  radius: Float,
  color: Color,
  gap: Float = RING_GAP.toPx()
) {
  drawCircle(
    color = color,
    radius = radius + gap,
    center = center,
    style = Stroke(width = RING_WIDTH.toPx())
  )
}

/** The letter of a kind, in the middle of its circle, and nothing at all when it won't fit in one. */
private fun DrawScope.drawBadgeLetter(
  measurer: TextMeasurer,
  kind: HeapObjectKind,
  center: Offset,
  radius: Float
) {
  val letter = measurer.measure(
    text = kind.badgeLetter,
    style = TextStyle(
      color = BADGE_LETTER_COLOR,
      fontSize = BADGE_LETTER_SIZE,
      fontWeight = FontWeight.Bold
    )
  )
  if (letter.size.width > radius * 2) {
    return
  }
  drawText(
    textLayoutResult = letter,
    topLeft = Offset(center.x - letter.size.width / 2f, center.y - letter.size.height / 2f)
  )
}

/** Says which way a line runs, at the end of it, where the object it points at is. */
internal fun DrawScope.drawArrowHead(
  tip: Offset,
  color: Color,
  /** Which way it points, as a unit vector. Down the gutter by default, which is a chain's direction. */
  direction: Offset = DOWNWARDS
) {
  val base = tip - direction * ARROW_HEIGHT.toPx()
  val across = Offset(-direction.y, direction.x) * (ARROW_WIDTH.toPx() / 2)
  val head = Path().apply {
    moveTo(tip.x, tip.y)
    lineTo(base.x - across.x, base.y - across.y)
    lineTo(base.x + across.x, base.y + across.y)
    close()
  }
  drawPath(head, color)
}

/**
 * The colour of the line into an object: the purple a leak trace is drawn in, or the red of a link the
 * garbage collector may let go of.
 */
internal fun connectorColor(strength: ReachabilityStrength): Color =
  if (strength == ReachabilityStrength.STRONG) CONNECTOR_COLOR else WEAK_CONNECTOR_COLOR

/** And its dashes, which is how a path that only holds until memory runs short reads as one. */
internal fun DrawScope.dashOrNull(strength: ReachabilityStrength): PathEffect? =
  if (strength == ReachabilityStrength.STRONG) {
    null
  } else {
    PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx()))
  }

/**
 * The field a reference is held in: underlined, and italic for a static field, which belongs to the
 * class rather than to the instance holding it.
 *
 * Without the class it is declared on, which is [ownerPrefix] and is written before this wherever
 * there is room: on an arrow of the graph the class is the circle the arrow leaves.
 */
internal fun referenceName(reference: PathReference): AnnotatedString = buildAnnotatedString {
  withStyle(
    SpanStyle(
      textDecoration = TextDecoration.Underline,
      fontStyle = if (reference.locationType == ReferenceLocationType.STATIC_FIELD) {
        FontStyle.Italic
      } else {
        FontStyle.Normal
      }
    )
  ) {
    append(
      when (reference.locationType) {
        ReferenceLocationType.ARRAY_ENTRY -> "[${reference.name}]"
        ReferenceLocationType.LOCAL -> LOCAL_VARIABLE
        ReferenceLocationType.INSTANCE_FIELD, ReferenceLocationType.STATIC_FIELD -> reference.name
      }
    )
  }
}

/** The class the field is read on, with no dot before an array index: `Tile.view`, `Object[][3]`. */
internal fun PathReference.ownerPrefix(): String =
  if (locationType == ReferenceLocationType.ARRAY_ENTRY) ownerClassName else "$ownerClassName."

/** How a leak trace names the reference a running method holds, which has no field to name. */
private const val LOCAL_VARIABLE = "<local variable>"

/** Where a line goes when nothing says otherwise, which is down a chain's gutter to the next object. */
private val DOWNWARDS = Offset(0f, 1f)

/** Big enough for a letter to be read in, which is what makes a circle say what the object is. */
internal val NODE_RADIUS = 8.dp

internal val CONNECTOR_WIDTH = 1.5.dp
internal val ARROW_WIDTH = 7.dp
internal val ARROW_HEIGHT = 5.dp
internal val DASH_ON = 3.dp
internal val DASH_OFF = 3.dp

/** How far outside a circle the ring picking it out sits. */
internal val RING_GAP = 2.5.dp
internal val RING_WIDTH = 1.5.dp

private val BADGE_LETTER_SIZE = 10.sp

/** On every kind's colour, all of which are dark enough to read white out of. */
private val BADGE_LETTER_COLOR = Color.White

/** The purple LeakCanary draws a leak trace in, which a chain of objects is the same shape as. */
internal val CONNECTOR_COLOR = Color(0xFF7E57C2)

/** And what a link the garbage collector may let go of is drawn in, dashed. */
internal val WEAK_CONNECTOR_COLOR = Color(0xFFB0453A)

/**
 * What an object that dominates what hangs below it is ringed and labelled in. Its own hue, because the
 * two colours a chain already uses mean how firmly a link holds, and this says nothing about that.
 */
internal val DOMINATOR_COLOR = Color(0xFF00796B)

/** For the parts of a line that say what an object is rather than which one it is. */
internal val MUTED_TEXT = Color(0xFF6E6E6E)

/** The same, for the part of a line that is one text: greyed, and unbolded where the line is bold. */
internal val MUTED_SPAN = SpanStyle(color = MUTED_TEXT, fontWeight = FontWeight.Normal)

/** The letter drawn in an object's circle, which is what kind of object it is. */
private val HeapObjectKind.badgeLetter: String
  get() = when (this) {
    HeapObjectKind.CLASS -> "C"
    HeapObjectKind.INSTANCE -> "I"
    HeapObjectKind.OBJECT_ARRAY -> "A"
    HeapObjectKind.PRIMITIVE_ARRAY -> "P"
  }

/**
 * And the colour of that circle: one per kind, so that a picture of instances and one that runs through
 * an array read differently at a glance.
 *
 * Every one of them dark enough for the white letter inside it, and none of them a colour these drawings
 * already use: the purple of a line, the red of a link that may be let go of, the teal of a dominator.
 */
private val HeapObjectKind.badgeColor: Color
  get() = when (this) {
    HeapObjectKind.CLASS -> Color(0xFFEF6C00)
    HeapObjectKind.INSTANCE -> Color(0xFF3949AB)
    HeapObjectKind.OBJECT_ARRAY -> Color(0xFF00838F)
    HeapObjectKind.PRIMITIVE_ARRAY -> Color(0xFF558B2F)
  }
