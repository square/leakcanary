package shark.explorer.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

/**
 * Where the graph's picture ends up as it is dragged and zoomed, which is the one part of that view
 * worth testing without drawing it: a wheel notch is two lines of vector arithmetic, and getting them
 * wrong is a zoom that walks the picture off the screen.
 */
class GraphTransformTest {

  @Test fun `a picture opens with its root against the left edge, half way down`() {
    val transform = GraphTransform.rootedIn(IntSize(width = 800, height = 600), margin = 48f)

    assertThat(transform.pan).isEqualTo(Offset(48f, 300f))
    assertThat(transform.zoom).isEqualTo(1f)
  }

  @Test fun `dragging moves the picture by as much as the pointer moved`() {
    val dragged = start().pannedBy(Offset(30f, -20f))

    assertThat(dragged.pan).isEqualTo(Offset(78f, 280f))
    assertThat(dragged.zoom).isEqualTo(1f)
  }

  @Test fun `zooming leaves whatever is under the pointer under it`() {
    val pointer = Offset(500f, 120f)
    val start = start()
    val under = start.pointsAt(pointer)

    val zoomed = start.zoomedAt(pointer, scrollDelta = -3f)

    assertThat(zoomed.zoom).isGreaterThan(start.zoom)
    assertThat(zoomed.pointsAt(pointer).x).isCloseTo(under.x, within(0.01f))
    assertThat(zoomed.pointsAt(pointer).y).isCloseTo(under.y, within(0.01f))
  }

  @Test fun `zooming out leaves it under the pointer too`() {
    val pointer = Offset(200f, 400f)
    val start = start()
    val under = start.pointsAt(pointer)

    val zoomed = start.zoomedAt(pointer, scrollDelta = 5f)

    assertThat(zoomed.zoom).isLessThan(start.zoom)
    assertThat(zoomed.pointsAt(pointer).x).isCloseTo(under.x, within(0.01f))
    assertThat(zoomed.pointsAt(pointer).y).isCloseTo(under.y, within(0.01f))
  }

  @Test fun `a wheel spun far enough stops rather than shrinking to nothing`() {
    val pointer = Offset(400f, 300f)

    val out = generateSequence(start()) { it.zoomedAt(pointer, scrollDelta = 10f) }.elementAt(20)

    assertThat(out.zoom).isGreaterThan(0f)
    // And is where it stays, which is what keeps a picture zoomed all the way out still there to drag.
    assertThat(out.zoomedAt(pointer, scrollDelta = 10f).zoom).isEqualTo(out.zoom)
  }

  @Test fun `a wheel spun the other way stops as well`() {
    val pointer = Offset(400f, 300f)

    val into = generateSequence(start()) { it.zoomedAt(pointer, scrollDelta = -10f) }.elementAt(20)

    assertThat(into.zoomedAt(pointer, scrollDelta = -10f).zoom).isEqualTo(into.zoom)
  }

  private fun start() = GraphTransform.rootedIn(IntSize(width = 800, height = 600), margin = 48f)

  /** Which point of the picture is drawn at [offset] of the view, which is what a click is read as. */
  private fun GraphTransform.pointsAt(offset: Offset): Offset = (offset - pan) / zoom
}
