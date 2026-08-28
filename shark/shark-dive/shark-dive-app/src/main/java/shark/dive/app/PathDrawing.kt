package shark.dive.app

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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import shark.ReferenceLocationType
import shark.dive.HeapDominatorTreemap
import shark.dive.HeapObjectKind
import shark.dive.LeakStatus
import shark.dive.PathReference
import shark.dive.PathStep
import shark.dive.ReachabilityStrength
import shark.dive.ReferencePage
import shark.dive.Topic
import shark.dive.formatByteSizeOfTotal
import shark.dive.formatObjectCount
import shark.dive.hexObjectId
import shark.dive.statusText

/**
 * How a chain of objects is drawn: a column of them with a line running through it, what each one is on
 * the right of it, and how the one above points at it under that.
 *
 * The same shape as a LeakCanary leak trace, because a leak trace is one of these. Every chain the window
 * draws is drawn by this — see [RootPathPanel] — so that two chains of the same heap dump never read
 * differently.
 *
 * Each object's circle carries the letter of its kind, in that kind's colour, so that a column of them says
 * what it is a column of before any of the names are read: `I` for an instance, `C` for a class. How firmly
 * a link holds is the line rather than the circle — colour and dashes — which is what leaves the circle free
 * to say what the object is.
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
  onOpen: (Long, OpenIn) -> Unit,
  onCopyLink: (Long) -> Unit
) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
    NodeGutter(kind = null, incoming = null, outgoing = nextStrength, endsInArrow = nextStrength != null)
    Column(Modifier.padding(bottom = PathDetail.FULL.rowSpacing)) {
      val open: (OpenIn) -> Unit = { openIn -> onOpen(HeapDominatorTreemap.ROOT_OBJECT_ID, openIn) }
      OpenTarget(open, { onCopyLink(HeapDominatorTreemap.ROOT_OBJECT_ID) }) {
        Text(
          HeapDominatorTreemap.ROOT_LABEL,
          Modifier.openable(open),
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Bold
        )
      }
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
  /** What a retained size here is a share of. See [shark.dive.HeapSizes.stronglyReachableByteCount]. */
  stronglyReachableByteCount: Long,
  onOpen: (Long, OpenIn) -> Unit,
  onCopyLink: (Long) -> Unit,
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
    // Green behind an object that is meant to be alive, red behind one that is meant to be gone, and the
    // blue of the object the panel is describing when nothing is known either way. The leak status wins
    // over the blue because it is the rarer thing to know, and because which object the panel is about is
    // already said twice over: the ring on its circle, and the panel itself.
    background = step.leakStatus.background ?: TARGET_BACKGROUND.takeIf { role == PathRole.TARGET }
  ) {
    if (detail == PathDetail.FULL) {
      ObjectIdentity(
        className = step.className,
        typeName = step.kind.typeName,
        objectId = step.objectId,
        // Folded objects are drawn nowhere on the map: a string's characters are counted inside the string.
        onOpen = if (step.isInspectable) ({ openIn -> onOpen(step.objectId, openIn) }) else null,
        onCopyLink = { onCopyLink(step.objectId) }
      )
    } else {
      BriefStepLine(step, stronglyReachableByteCount)
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
          "Retaining ${formatByteSizeOfTotal(step.retainedSize, stronglyReachableByteCount)} in " +
            formatObjectCount(step.retainedCount),
          style = MaterialTheme.typography.bodySmall
        )
      }
    }
    if (step.strength != ReachabilityStrength.STRONG) {
      Text(
        step.strength.label,
        style = MaterialTheme.typography.bodySmall,
        color = objectStrengthColor(step.strength)
      )
    }
    if (detail == PathDetail.FULL) {
      step.inspectorLabels.forEach { label ->
        Text(label, style = MaterialTheme.typography.bodySmall, color = MUTED_TEXT)
      }
      // Why this object is one or the other, which is most of the answer: half the objects of a chain are
      // green or red because of what an object above or below them is, and the reason is what says so.
      step.leakStatusReason?.let { reason ->
        Text(
          "${step.leakStatus.statusText}: $reason",
          style = MaterialTheme.typography.bodySmall,
          color = step.leakStatus.textColor
        )
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
 * What a chain that starts part way down starts with — see [shark.dive.stepsBelow]. The dots are it
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
 * How much a chain says about each of its objects.
 *
 * A chain from a GC root down to a bitmap of a real app runs through dozens of objects, and everything
 * worth saying about each of them is four lines: that chain is taller than any window. So a chain the
 * reader is only glancing at says as little as still names the object, and the one they stopped on says
 * all of it.
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
  /**
   * What the first line is drawn at, which is the body of the text everywhere but one.
   *
   * The row above the panes says which object the whole window is about, so there it is a title — see
   * `DescribedObject`. The grey lines under it stay as they are: what they are for is being skipped.
   */
  nameStyle: TextStyle? = null,
  /** Where clicking it goes, or null for a name that is already what the window is showing. */
  onOpen: ((OpenIn) -> Unit)? = null,
  /** Put on the clipboard by the menu beside "open in a new tab", so only where there is one. */
  onCopyLink: () -> Unit = {}
) {
  if (onOpen == null) {
    ObjectIdentityLines(className, typeName, objectId, nameStyle, modifier)
    return
  }
  OpenTarget(onOpen, onCopyLink) {
    ObjectIdentityLines(className, typeName, objectId, nameStyle, modifier.openable(onOpen))
  }
}

/** The lines themselves, which are the same whether or not this name leads anywhere. */
@Composable
private fun ObjectIdentityLines(
  className: String,
  typeName: String?,
  objectId: Long?,
  nameStyle: TextStyle?,
  modifier: Modifier
) {
  Column(modifier) {
    Text(
      // One line of text rather than two words side by side: what the object is reads as one phrase, and
      // anything that has to find this line by what it says — a test, a screen reader — finds one of it.
      buildAnnotatedString {
        append(className.substringAfterLast('.'))
        typeName?.let { withStyle(MUTED_SPAN) { append(" $it") } }
      },
      style = nameStyle ?: MaterialTheme.typography.bodyMedium,
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
private fun BriefStepLine(
  step: PathStep,
  stronglyReachableByteCount: Long
) {
  Text(
    buildAnnotatedString {
      append(step.className.substringAfterLast('.'))
      if (step.retainedCount > 0) {
        // Beside the class name rather than under it, which is the one place a brief chain has room for how
        // much of the heap a step holds — and that is what the map is being read for.
        withStyle(MUTED_SPAN) {
          append(" · ${formatByteSizeOfTotal(step.retainedSize, stronglyReachableByteCount)}")
        }
      }
    },
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.Bold
  )
}

/** How the object above points at the one below: the field, on the class that declares it. */
@Composable
private fun ReferenceLine(reference: PathReference) {
  val line = @Composable {
    Text(
      buildAnnotatedString {
        withStyle(MUTED_SPAN) { append(reference.ownerPrefix()) }
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
        // The one line of a chain that says where to go and change code, so it is the one thing on a
        // chain drawn bold: the shades on the objects say what a leak left behind, and this says what the
        // leak is. See [shark.dive.PathReference.isFaulty].
        if (reference.isFaulty) {
          withStyle(FAULTY_REFERENCE_SPAN) { append(" $FAULTY_REFERENCE") }
        }
        // Which is what makes a chain through a known leak readable as one: the objects below this
        // reference are held by code the app doesn't control, and this is the reference that does it.
        if (reference.libraryLeak != null) {
          withStyle(LIBRARY_LEAK_SPAN) { append(" $LIBRARY_LEAK") }
        }
      },
      style = MaterialTheme.typography.bodySmall
    )
  }
  // Why this reference and not another, and what is known about a leak somebody else's code holds. One
  // sentence for the first of those, because the `?` above the chain leads to the rest of it: a step of a
  // chain is one row of a column of rows, and a paragraph per row is a pane nobody reads twice.
  val explanation = listOfNotNull(
    ReferencePage.of(Topic.FAULTY_REFERENCE).hint.takeIf { reference.isFaulty },
    reference.libraryLeak?.description?.takeIf { it.isNotEmpty() }
  )
  if (explanation.isEmpty()) {
    line()
  } else {
    Hint(explanation.joinToString("\n\n"), content = line)
  }
}

/**
 * A line of a chain that leads somewhere: clicking it goes to that object.
 *
 * The hand is the whole of how it says so, rather than a colour: which object a step is, is drawn the same
 * way everywhere the window names one, and half of those places are nothing to click.
 */
internal fun Modifier.clickableRow(
  /** Off while what the click would need hasn't been read yet, which leaves the row as plain text. */
  enabled: Boolean = true,
  onClick: () -> Unit
): Modifier =
  pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
    .clickable(enabled = enabled, onClick = onClick)

/** The class the field is read on, with no dot before an array index: `Tile.view`, `Object[][3]`. */
private fun PathReference.ownerPrefix(): String =
  if (locationType == ReferenceLocationType.ARRAY_ENTRY) ownerClassName else "$ownerClassName."

private fun PathReference.displayName(): String = when (locationType) {
  ReferenceLocationType.ARRAY_ENTRY -> "[$name]"
  ReferenceLocationType.LOCAL -> LOCAL_VARIABLE
  ReferenceLocationType.INSTANCE_FIELD, ReferenceLocationType.STATIC_FIELD -> name
}

/**
 * The line running through the objects of a chain: this one's circle with the letter of its kind in it, and
 * half a line to each of its neighbours, which the neighbour draws the other half of.
 *
 * A link no stronger than a `java.lang.ref.Reference` or a cache is dashed and takes that strength's
 * colour, which is how a path that only holds until memory runs short reads as one.
 *
 * A null [kind] draws the circle hollow and empty, for a row that stands above the objects of a chain rather
 * than being one of them. A [role] other than [PathRole.STEP] rings it, which is how the objects that own
 * the one at the end of the chain are picked out of the ones merely on the way.
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
    val center = Offset(centerX, centerY)
    if (kind != null) {
      drawCircle(color = kind.badgeColor, radius = radius, center = center)
      drawBadgeLetter(measurer, kind, center)
    }
    drawCircle(
      color = if (kind == null) CONNECTOR_COLOR else kind.badgeColor,
      radius = radius,
      center = center,
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
        center = center,
        style = Stroke(width = RING_WIDTH.toPx())
      )
    }
  }
}

/** The letter of a kind, in the middle of its circle: what the object is, before its name is read. */
private fun DrawScope.drawBadgeLetter(
  measurer: TextMeasurer,
  kind: HeapObjectKind,
  center: Offset
) {
  val letter = measurer.measure(
    text = kind.badgeLetter,
    style = TextStyle(
      color = BADGE_LETTER_COLOR,
      fontSize = BADGE_LETTER_SIZE,
      fontWeight = FontWeight.Bold
    )
  )
  drawText(
    textLayoutResult = letter,
    topLeft = Offset(center.x - letter.size.width / 2f, center.y - letter.size.height / 2f)
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
    PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx()))
  }

/** What a step that dominates the object at the end of the chain says about itself. */
internal const val DOMINATES_BELOW = "Dominates ↓"

/** How a leak trace names the reference a running method holds, which has no field to name. */
private const val LOCAL_VARIABLE = "<local variable>"

/** What a reference Shark knows leaks in code the app doesn't control says about itself. */
internal const val LIBRARY_LEAK = "· known library leak"

/**
 * And what the reference the leak is says about itself, which is the one thing on a chain to go and fix.
 *
 * Two words rather than a sentence, in the red of the objects it left behind: a chain is read as a column
 * of names, and this is the line to stop on.
 */
internal const val FAULTY_REFERENCE = "· faulty reference"

/** Wide enough for the line, its arrow head and a ring to sit clear of the text beside it. */
private val GUTTER_WIDTH = 26.dp

/** Where down a row the object's circle sits, which is the middle of its first line of text. */
private val NODE_CENTER_Y = 11.dp

/** Big enough for a letter to be read in, which is what makes the circle say what the object is. */
private val NODE_RADIUS = 8.dp

/** And where down a reference's own row the bracket tying it to the line sits, which is its first line. */
private val REFERENCE_TIE_Y = 9.dp

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

private val BADGE_LETTER_SIZE = 10.sp

/** On every kind's colour, all of which are dark enough to read white out of. */
private val BADGE_LETTER_COLOR = Color.White

/**
 * The shade behind an object whose status is known, and none behind the objects nothing knows about,
 * which is most of a chain.
 *
 * A background rather than a colour on the text, because it is about the object rather than about any one
 * line naming it, and the lines a row is made of already mean things by their colour. Faint enough that a
 * column of them still reads as a chain: the shade says where along the chain something went wrong, and
 * the reason under the object says what.
 *
 * Reachable from outside this drawing, because the row above the panes says what the object the whole window
 * is about is in these same colours: what a status looks like is one thing, whether it is being read on a
 * chain or over the panes. See [LeakStatusBanner].
 */
internal val LeakStatus.background: Color?
  get() = when (this) {
    LeakStatus.EXPECTED -> ALIVE_BACKGROUND
    LeakStatus.UNKNOWN -> null
    LeakStatus.STUCK -> LEAKING_BACKGROUND
  }

internal val LeakStatus.textColor: Color
  get() = when (this) {
    LeakStatus.EXPECTED -> ALIVE_TEXT
    LeakStatus.UNKNOWN -> MUTED_TEXT
    LeakStatus.STUCK -> LEAKING_TEXT
  }

/** Green for an object something knows is still needed. */
private val ALIVE_BACKGROUND = Color(0x1A2E7D32)
private val ALIVE_TEXT = Color(0xFF2E7D32)

/** And red for one that should have been collected. */
private val LEAKING_BACKGROUND = Color(0x1AC62828)
private val LEAKING_TEXT = Color(0xFFC62828)

/** What the object the panel is describing is drawn behind, which is the end of the chain. */
private val TARGET_BACKGROUND = Color(0x1A2196F3)
internal val TARGET_SHAPE = RoundedCornerShape(4.dp)
internal val TARGET_PADDING = 4.dp

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

/** The same, for the part of a line that is one text: greyed, and unbolded where the line is bold. */
private val MUTED_SPAN = SpanStyle(color = MUTED_TEXT, fontWeight = FontWeight.Normal)

/** And for the words saying a reference is a known library leak, in the red of the leaks it explains. */
private val LIBRARY_LEAK_SPAN = SpanStyle(color = LEAKING_TEXT, fontWeight = FontWeight.Normal)

/** The same red for the reference the leak is, bold: nothing else on a chain is the thing to fix. */
private val FAULTY_REFERENCE_SPAN = SpanStyle(color = LEAKING_TEXT, fontWeight = FontWeight.Bold)

/** The letter drawn in an object's circle, which is what kind of object it is. */
private val HeapObjectKind.badgeLetter: String
  get() = when (this) {
    HeapObjectKind.CLASS -> "C"
    HeapObjectKind.INSTANCE -> "I"
    HeapObjectKind.OBJECT_ARRAY -> "A"
    HeapObjectKind.PRIMITIVE_ARRAY -> "P"
  }

/**
 * And the colour of that circle: one per kind, so that a chain of instances and a chain that runs through
 * an array read differently at a glance.
 *
 * Every one of them dark enough for the white letter inside it, and none of them a colour a chain already
 * uses: the purple of the line, the red of a link that may be let go of, the teal of a dominator.
 */
private val HeapObjectKind.badgeColor: Color
  get() = when (this) {
    HeapObjectKind.CLASS -> Color(0xFFEF6C00)
    HeapObjectKind.INSTANCE -> Color(0xFF3949AB)
    HeapObjectKind.OBJECT_ARRAY -> Color(0xFF00838F)
    HeapObjectKind.PRIMITIVE_ARRAY -> Color(0xFF558B2F)
  }
