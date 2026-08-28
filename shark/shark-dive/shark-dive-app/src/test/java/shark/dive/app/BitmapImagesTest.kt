package shark.dive.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.dive.BitmapImage
import shark.dive.CellContent
import shark.dive.CellSubject
import shark.dive.EncodedImageFormat
import shark.dive.PresentedCell
import shark.dive.ReachabilityStrength.STRONG
import shark.dive.TreemapLayout
import shark.dive.TreemapPresentation
import shark.dive.TreemapRect
import shark.dive.TreemapTree

/** Covers turning what a heap dump gave us into something a canvas draws, and where it goes. */
class BitmapImagesTest {

  @Test fun `a compressed image is decoded`() {
    val image = BitmapImage.Encoded(
      width = 8,
      height = 4,
      format = EncodedImageFormat.PNG,
      bytes = pngBytes(width = 8, height = 4)
    )

    assertThat(image.toImageBitmap()?.width).isEqualTo(8)
    assertThat(image.toImageBitmap()?.height).isEqualTo(4)
  }

  @Test fun `raw pixels are drawn as they came out of the buffer`() {
    val image = BitmapImage.Pixels(width = 2, height = 1, argb = intArrayOf(RED, BLUE))

    val decoded = image.toImageBitmap()!!

    assertThat(decoded.width).isEqualTo(2)
    assertThat(pixelAt(decoded, 0, 0)).isEqualTo(RED)
    assertThat(pixelAt(decoded, 1, 0)).isEqualTo(BLUE)
  }

  @Test fun `bytes that turn out not to be an image are skipped rather than thrown`() {
    // One unreadable bitmap out of a hundred is not worth taking the window down for: a device could
    // have compressed to something Skia doesn't read.
    val image = BitmapImage.Encoded(
      width = 8,
      height = 8,
      format = EncodedImageFormat.WEBP,
      bytes = byteArrayOf(1, 2, 3, 4)
    )

    assertThat(image.toImageBitmap()).isNull()
  }

  @Test fun `an image is fitted inside its rectangle and centred, never stretched`() {
    val image = ImageBitmap(width = 10, height = 20)

    val (offset, size) = imageBounds(image, topLeft = Offset(100f, 200f), size = Size(80f, 40f))

    // The rectangle is wider than it is tall, so the height is what the image is limited by, and the
    // half of the width it doesn't need is split either side of it.
    assertThat(size).isEqualTo(IntSize(20, 40))
    assertThat(offset).isEqualTo(IntOffset(130, 200))
  }

  @Test fun `a rectangle too small for a pixel still gets one`() {
    val image = ImageBitmap(width = 100, height = 100)

    val (_, size) = imageBounds(image, topLeft = Offset.Zero, size = Size(0.5f, 0.5f))

    assertThat(size).isEqualTo(IntSize(1, 1))
  }

  @Test fun `only the bitmaps big enough to make out are worth reading the pixels of`() {
    // Every rectangle below that is a smear of one colour, and getting there would be a heap dump read
    // and a decode each.
    val presentation = presentationOf(
      BITMAP_NODE to CellContent.Object(STRONG, isBitmap = true),
      OTHER_NODE to CellContent.Object(STRONG, isBitmap = false)
    )

    assertThat(presentation.bitmapNodeIds(minSize = 1f)).containsExactly(BITMAP_NODE)
    assertThat(presentation.bitmapNodeIds(minSize = Float.MAX_VALUE)).isEmpty()
  }

  /** A presentation of two rectangles filling the view, each with the content it was given. */
  private fun presentationOf(vararg contents: Pair<Long, CellContent>): TreemapPresentation {
    val contentByNode = contents.toMap()
    val tree = object : TreemapTree<Long> {
      override val root = ROOT
      override fun weight(node: Long) = 100L
      override fun children(node: Long) =
        if (node == ROOT) contentByNode.keys.toList() else emptyList()
    }
    val result = TreemapLayout<Long>().layout(tree, VIEWPORT)
    return TreemapPresentation(
      layout = result,
      cells = result.cells.map { cell ->
        val node = (cell.subject as? CellSubject.Node)?.node
        PresentedCell(
          cell = cell,
          label = "node $node",
          content = contentByNode[node] ?: CellContent.Object(STRONG)
        )
      }
    )
  }

  private fun pixelAt(
    image: ImageBitmap,
    x: Int,
    y: Int
  ): Int {
    val pixels = IntArray(image.width * image.height)
    image.readPixels(pixels)
    return pixels[y * image.width + x]
  }

  private fun pngBytes(
    width: Int,
    height: Int
  ): ByteArray {
    val bytes = ByteArrayOutputStream()
    ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", bytes)
    return bytes.toByteArray()
  }

  companion object {
    private const val ROOT = 0L
    private const val BITMAP_NODE = 1L
    private const val OTHER_NODE = 2L

    private const val RED = 0xffff0000.toInt()
    private const val BLUE = 0xff0000ff.toInt()

    private val VIEWPORT = TreemapRect(left = 0.0, top = 0.0, right = 600.0, bottom = 400.0)
  }
}
