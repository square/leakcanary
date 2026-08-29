package shark.dive.app

import androidx.compose.remote.core.operations.paint.PaintBundle
import androidx.compose.remote.creation.JvmRcPlatformServices
import androidx.compose.remote.creation.RcPaint
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import shark.dive.CellContent
import shark.dive.ObjectGroupKind
import shark.dive.Place
import shark.dive.PresentedCell
import shark.dive.TreemapCell
import shark.dive.TreemapPresentation
import shark.dive.exactHexObjectId

/**
 * The same laid out treemap [TreemapView] draws, written as a
 * [Remote Compose](https://developer.android.com/jetpack/androidx/releases/compose-remote) document.
 *
 * **A second renderer of one model, not a second model.** It takes the same [TreemapPresentation], asks
 * [CellColors] for the same colours and follows the same passes in the same order, so a rectangle here and a
 * rectangle in the window are the same rectangle. Which is the whole reason this lives beside `TreemapView`
 * rather than in `shark-dive-agent`: the colours are here, and a drawing coloured by a copy of this scheme
 * would drift from the window within a release.
 *
 * Why Remote Compose rather than a PNG: **a document is clickable**. Every rectangle the map names carries an
 * `addClickArea` whose metadata is the object's address, so a client that plays this can let somebody walk
 * into the map — asking Shark Dive for the treemap under whatever they pressed — with no model in the loop
 * and no image round trip. See `shark.dive.agent.AgentResources`, which is what serves these.
 *
 * The document is written by a plain JVM and played by a canvas in the client, so nothing here may assume a
 * display or a font: text is placed by arithmetic rather than measured, which is the one thing this does
 * differently from the window. See [labelOf].
 */
internal fun treemapDocument(
  presentation: TreemapPresentation,
  /** What the drawing is of, drawn along the top and carried as the document's content description. */
  title: String,
  /**
   * Where pressing the title leads, and null for a drawing of the whole heap dump.
   *
   * The only way back out, which is why it is drawn at all: a player has no history and no chrome of ours
   * to put a button in, so up is a click area like every other and the title is what carries it.
   */
  parentObjectId: Long?,
  coloring: CellColoring,
  /** Which of the objects drawn are leaking, for the colouring that shades them. See [LeakShading]. */
  shading: LeakShading,
  width: Int,
  height: Int
): ByteArray {
  val writer = RemoteComposeWriter(width, height, title, JvmRcPlatformServices())
  val colors = CellColors.of(coloring, presentation.cells, shading)

  writer.fill(BACKGROUND_COLOR)
  writer.drawRect(0f, 0f, width.toFloat(), height.toFloat())

  // The same passes in the same order as TreemapView, and for the same reasons: children cover every pixel
  // of their parent, so the fills go in first and the outlines afterwards are what draws the nesting.
  presentation.cells.forEach { presented -> writer.drawFill(presented, colors) }
  presentation.cells.forEach { presented -> writer.drawOutline(presented, colors) }
  presentation.cells.filter { it.isRootChild }.forEach { presented -> writer.drawSeparator(presented) }

  // Last, over everything nested inside them, and the same level the window names. The click areas go on
  // with them: what a person can read is what they can press.
  var clickAreaId = 0
  presentation.cells.filter { it.isRootChild }.forEach { presented ->
    writer.drawLabel(presented, colors)
    presented.clickAreaTargetOrNull()?.let { target ->
      val rect = presented.cell.rect
      writer.addClickArea(
        ++clickAreaId,
        presented.label,
        rect.left.toFloat(),
        rect.top.toFloat(),
        rect.right.toFloat(),
        rect.bottom.toFloat(),
        target
      )
    }
  }

  val titleText = if (parentObjectId == null) title else "$UP_ARROW $title"
  writer.drawTitle(titleText, width)
  parentObjectId?.let { parent ->
    writer.addClickArea(
      ++clickAreaId,
      titleText,
      0f,
      0f,
      width.toFloat(),
      DRAWING_TITLE_HEIGHT,
      exactHexObjectId(parent)
    )
  }
  // Rather than buffer(), which hands back the whole megabyte the writer allocated up front.
  return writer.encodeToByteArray()
}

/**
 * How much of the top of a drawing its title takes, which is space the treemap cannot have.
 *
 * Read by whoever lays the presentation out, since that is where the viewport is chosen and a map laid out
 * into the whole height would be drawn under its own title with its top row unpressable.
 */
internal const val DRAWING_TITLE_HEIGHT = 24f

/**
 * Whether this is one of the children of the node the view is rooted at, which are the named, pressable
 * level. The same [ROOT_CHILD_DEPTH] rule the window draws by.
 */
private val PresentedCell<TreemapCell<Long>>.isRootChild: Boolean
  get() = cell.depth == ROOT_CHILD_DEPTH

/**
 * The address a press on this rectangle should lead to, or null for a cell that leads nowhere nameable.
 *
 * The address rather than a place, because that is what an object *is* on the agent surface — the same
 * `0x…` spelling `shark.dive.agent.AgentPlace` reads back — and it is what the client hands to
 * `draw_treemap` to walk in. A pile of siblings that didn't fit leads to the object they were left out of,
 * which is where the window puts you too: an agent has no way to name the pile itself, since which objects
 * are in it follows from how wide the view is.
 */
private fun PresentedCell<TreemapCell<Long>>.clickAreaTargetOrNull(): String? =
  Place.of(cell).viewRootObjectId?.let { exactHexObjectId(it) }

private fun RemoteComposeWriter.drawFill(
  presented: PresentedCell<TreemapCell<Long>>,
  colors: CellColors
) {
  val rect = presented.cell.rect
  fill(colors.colorOf(presented))
  drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
  if (presented.content is CellContent.Leftover) {
    hatch(presented)
  }
}

/**
 * What the siblings a rectangle had no room for are textured with, so that a pile doesn't read as one
 * enormous object — which on this map only ever means a bitmap.
 *
 * Diagonal lines rather than the window's dots, and that is a deliberate difference: `pileDots` is one
 * repeated tile a `Brush` fills any area with for the same cost, and a document has no brushes, so dotting a
 * pile that fills the view would be tens of thousands of operations on the wire. Lines are bounded by
 * [MAX_HATCH_LINES] and say the same thing — many small things, not one big one.
 */
private fun RemoteComposeWriter.hatch(presented: PresentedCell<TreemapCell<Long>>) {
  val rect = presented.cell.rect
  val left = rect.left.toFloat()
  val top = rect.top.toFloat()
  val right = rect.right.toFloat()
  val bottom = rect.bottom.toFloat()
  val span = rect.width + rect.height
  // Widened rather than truncated when a pile is large enough to need more lines than it may have: a
  // texture that stops half way across the rectangle reads as a rendering fault.
  val spacing = maxOf(HATCH_SPACING, span / MAX_HATCH_LINES).toFloat()
  stroke(HATCH_COLOR, HATCH_WIDTH)
  save()
  clipRect(left, top, right, bottom)
  var offset = 0f
  while (offset < span) {
    drawLine(left + offset, top, left, top + offset)
    offset += spacing
  }
  restore()
}

private fun RemoteComposeWriter.drawOutline(
  presented: PresentedCell<TreemapCell<Long>>,
  colors: CellColors
) {
  val rect = presented.cell.rect
  // The window dashes the border of a pile and leaves an object's solid. A document's paint has no path
  // effect, so the two are told apart by weight instead, which is the other half of what `outlineOf` says.
  val isPile = presented.content is CellContent.Leftover ||
    (presented.content as? CellContent.ObjectGroup)?.kind == ObjectGroupKind.CLASS
  stroke(colors.borderOf(presented), if (isPile) PILE_BORDER_WIDTH else BORDER_WIDTH)
  drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
}

/** The heavier line around one of the named children, which is the boundary the map is read by. */
private fun RemoteComposeWriter.drawSeparator(presented: PresentedCell<TreemapCell<Long>>) {
  val rect = presented.cell.rect
  stroke(ROOT_CHILD_BORDER_COLOR, ROOT_CHILD_BORDER_WIDTH)
  drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
}

/** What a named child is, on the translucent plate that makes it readable over whatever it covers. */
private fun RemoteComposeWriter.drawLabel(
  presented: PresentedCell<TreemapCell<Long>>,
  colors: CellColors
) {
  val label = presented.labelOf() ?: return
  val rect = presented.cell.rect
  val left = rect.left.toFloat() + LABEL_MARGIN
  val top = rect.top.toFloat() + LABEL_MARGIN
  fill(LABEL_PLATE_COLOR)
  drawRect(left, top, left + label.width, top + LABEL_LINE_HEIGHT)
  fill(colors.label, LABEL_TEXT_SIZE)
  drawTextRun(
    label.text,
    0,
    label.text.length,
    0,
    label.text.length,
    left + LABEL_PLATE_PADDING,
    top + LABEL_BASELINE,
    false
  )
}

/** The drawing's own name along the top, over the background rather than over any rectangle. */
private fun RemoteComposeWriter.drawTitle(
  title: String,
  width: Int
) {
  fill(BACKGROUND_COLOR)
  drawRect(0f, 0f, width.toFloat(), DRAWING_TITLE_HEIGHT)
  fill(TITLE_COLOR, TITLE_TEXT_SIZE)
  drawTextRun(title, 0, title.length, 0, title.length, LABEL_MARGIN, TITLE_BASELINE, false)
}

/** A name to draw on this rectangle and how wide it will come out, or null where there is no room. */
private fun PresentedCell<TreemapCell<Long>>.labelOf(): DocumentLabel? {
  val rect = cell.rect
  if (rect.height < MIN_LABEL_HEIGHT_UNITS) {
    return null
  }
  val room = rect.width - 2 * LABEL_MARGIN
  if (room < MIN_LABEL_WIDTH_UNITS) {
    return null
  }
  // Estimated rather than measured, which is what a document written with no font on hand can do: the
  // player picks the font, and `RemoteComposeWriter.textLength` is a value the *player* computes when it
  // draws rather than something readable here. So the count is conservative — a name cut a character early
  // is a name that reads; one cut a character late is one that overhangs its rectangle onto a sibling.
  val fits = ((room - 2 * LABEL_PLATE_PADDING) / LABEL_GLYPH_WIDTH).toInt()
  if (fits < MIN_LABEL_CHARACTERS) {
    return null
  }
  val text = if (label.length <= fits) label else label.take(fits - 1) + ELLIPSIS
  return DocumentLabel(
    text = text,
    width = text.length * LABEL_GLYPH_WIDTH + 2 * LABEL_PLATE_PADDING
  )
}

/** A name as it will be drawn: already cut to fit, with the width of the plate to draw under it. */
private class DocumentLabel(
  val text: String,
  val width: Float
)

/**
 * Sets the paint to fill in [color], with [textSize] for the runs of text that follow.
 *
 * A paint is document state rather than an argument to a draw, so every draw here sets its own: the
 * alternative is an operation whose colour depends on which operation ran before it, which is exactly the
 * bug a treemap of a thousand rectangles makes impossible to spot.
 */
private fun RemoteComposeWriter.fill(
  color: Color,
  textSize: Float = LABEL_TEXT_SIZE
) {
  val paint: RcPaint = getRcPaint()
  paint.setColor(color.toArgb()).setStyle(PaintBundle.STYLE_FILL).setTextSize(textSize).commit()
}

private fun RemoteComposeWriter.stroke(
  color: Color,
  strokeWidth: Float
) {
  val paint: RcPaint = getRcPaint()
  paint.setColor(color.toArgb()).setStyle(PaintBundle.STYLE_STROKE).setStrokeWidth(strokeWidth).commit()
}

/** Behind the whole document, and behind the title strip: a client's own page colour is not ours to assume. */
private val BACKGROUND_COLOR = Color(0xFF101418)
private val TITLE_COLOR = Color(0xFFE6EDF3)

private const val TITLE_TEXT_SIZE = 14f
private const val TITLE_BASELINE = 17f

/** What a title that leads somewhere starts with, since a strip that can be pressed has to look like one. */
private const val UP_ARROW = "\u2191"

/**
 * How wide a character of the label font is, as a fraction of its size.
 *
 * 0.55 is a little over the average for the sans-serif faces a browser falls back to, which is the direction
 * to be wrong in: see [labelOf].
 */
private const val LABEL_GLYPH_RATIO = 0.55f

private const val LABEL_TEXT_SIZE = 11f
private const val LABEL_GLYPH_WIDTH = LABEL_TEXT_SIZE * LABEL_GLYPH_RATIO
private const val LABEL_LINE_HEIGHT = 14f
private const val LABEL_BASELINE = 11f

/** Between a rectangle's edge and the plate its name sits on, matching the window's `LABEL_PADDING`. */
private const val LABEL_MARGIN = 3f

/** Fewer than this and the name is an ellipsis and a letter, which names nothing. */
private const val MIN_LABEL_CHARACTERS = 4

private const val MIN_LABEL_WIDTH_UNITS = 24.0
private const val MIN_LABEL_HEIGHT_UNITS = 13.0

private const val ELLIPSIS = "…"

private val HATCH_COLOR = Color(0x33000000)
private const val HATCH_WIDTH = 1f
private const val HATCH_SPACING = 7.0

/** How many lines one pile is textured with at most, whatever its size. See [hatch]. */
private const val MAX_HATCH_LINES = 60
