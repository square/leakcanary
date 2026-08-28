package shark.dive.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import org.jetbrains.skia.Image
import shark.SharkLog
import shark.dive.BitmapImage
import shark.dive.CellContent
import shark.dive.CellSubject
import shark.dive.PresentedCell
import shark.dive.TreemapCell
import shark.dive.TreemapPresentation

/**
 * The pixels a heap dump gave us, as something a canvas draws.
 *
 * Null when the bytes turn out not to be an image, which is a device that compressed to a format Skia
 * doesn't read, or bytes that aren't what the heap dump said they were. One unreadable bitmap is not
 * worth taking the window down for, so it's logged and skipped.
 */
internal fun BitmapImage.toImageBitmap(): ImageBitmap? = try {
  when (this) {
    // Through Skia rather than ImageIO, because it reads the WebP that `am dumpheap -b webp` produces
    // and ImageIO does not.
    is BitmapImage.Encoded -> Image.makeFromEncoded(bytes).toComposeImageBitmap()
    is BitmapImage.Pixels -> BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
      .apply { setRGB(0, 0, width, height, argb, 0, width) }
      .toComposeImageBitmap()
  }
} catch (exception: Exception) {
  SharkLog.d(exception) { "Could not decode a $width × $height bitmap out of the heap dump" }
  null
}

/**
 * The bitmaps of a presentation worth drawing an image on, which is every one big enough to make out.
 *
 * Below that an image is a smear of one colour, and it would be a heap dump read and a decode per
 * rectangle to get there.
 */
internal fun TreemapPresentation.bitmapNodeIds(minSize: Float): Set<Long> = cells
  .filter { it.isDrawableBitmap(minSize) }
  .mapNotNull { (it.cell.subject as? CellSubject.Node)?.node }
  .toSet()

private fun PresentedCell<TreemapCell<Long>>.isDrawableBitmap(minSize: Float): Boolean {
  val content = content
  return content is CellContent.Object && content.isBitmap &&
    cell.rect.width >= minSize && cell.rect.height >= minSize
}

/**
 * Where to draw [image] inside a rectangle: as big as fits, in the middle, and never stretched.
 *
 * A bitmap that came out of a heap dump is being looked at to be recognised, and an icon squashed into
 * whatever aspect ratio its share of the heap happens to be is not recognisable.
 */
internal fun imageBounds(
  image: ImageBitmap,
  topLeft: Offset,
  size: Size
): Pair<IntOffset, IntSize> {
  val scale = minOf(size.width / image.width, size.height / image.height)
  val width = maxOf(1, (image.width * scale).toInt())
  val height = maxOf(1, (image.height * scale).toInt())
  return IntOffset(
    x = (topLeft.x + (size.width - width) / 2).toInt(),
    y = (topLeft.y + (size.height - height) / 2).toInt()
  ) to IntSize(width, height)
}

/**
 * How big a rectangle has to be before an image is drawn on it, and how many pixels of that image are
 * worth reading.
 *
 * The cap is what the raw pixels of a pre API 26 bitmap are scaled down to: a full screen bitmap is
 * megabytes of them, a rectangle on a treemap is a couple of hundred pixels across, and a presentation
 * of a production dump can have a hundred bitmaps on it.
 */
internal val MIN_BITMAP_DRAW_SIZE = 8.dp
internal const val MAX_TREEMAP_BITMAP_PIXELS = 256

/** The same cap for the details panel, where one bitmap is shown as big as the panel is wide. */
internal const val MAX_PANEL_BITMAP_PIXELS = 1024

/** A count of bitmaps, with the noun that belongs on it: everything here counts them by name. */
internal fun bitmapCountText(count: Int): String = if (count == 1) "1 bitmap" else "$count bitmaps"
