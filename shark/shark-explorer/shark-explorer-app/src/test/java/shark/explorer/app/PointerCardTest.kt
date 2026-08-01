package shark.explorer.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Where the card naming what the pointer is on goes, as a pure function: the card itself is drawn into the
 * window and nothing outside the JVM can look at it, but [placeCard] is arithmetic.
 */
class PointerCardTest {

  @Test fun `a card goes below and right of the pointer`() {
    val placed = placeCard(pointer = Offset(100f, 100f), cardSize = CARD, viewSize = VIEW, gap = GAP)

    assertThat(placed).isEqualTo(IntOffset(120, 120))
  }

  @Test fun `a card that would run past the right edge goes left of the pointer instead`() {
    val placed = placeCard(pointer = Offset(700f, 100f), cardSize = CARD, viewSize = VIEW, gap = GAP)

    // Flipped rather than slid along the edge, which is what keeps the gap: a card that touches the pointer
    // takes the hover off the map, and closing itself is the last thing it does with it.
    assertThat(placed).isEqualTo(IntOffset(700 - GAP.toInt() - CARD.width, 120))
  }

  @Test fun `a card that would run past the bottom goes above the pointer`() {
    val placed = placeCard(pointer = Offset(100f, 550f), cardSize = CARD, viewSize = VIEW, gap = GAP)

    assertThat(placed).isEqualTo(IntOffset(120, 550 - GAP.toInt() - CARD.height))
  }

  @Test fun `a card in the far corner flips on both axes at once`() {
    val placed = placeCard(pointer = Offset(700f, 550f), cardSize = CARD, viewSize = VIEW, gap = GAP)

    assertThat(placed).isEqualTo(
      IntOffset(700 - GAP.toInt() - CARD.width, 550 - GAP.toInt() - CARD.height)
    )
  }

  @Test fun `a card with no room on either side of the pointer is kept inside the view`() {
    // A window narrower than the card, which is the one case where there is nowhere beside the pointer to
    // put it. Outside the view is not a place the window has, so it lands against the far edge.
    val placed = placeCard(
      pointer = Offset(150f, 100f),
      cardSize = CARD,
      viewSize = IntSize(width = 300, height = 600),
      gap = GAP
    )

    assertThat(placed).isEqualTo(IntOffset(300 - CARD.width, 120))
  }

  @Test fun `a card larger than the view starts at its edge rather than outside it`() {
    val placed = placeCard(
      pointer = Offset(50f, 50f),
      cardSize = CARD,
      viewSize = IntSize(width = 100, height = 100),
      gap = GAP
    )

    assertThat(placed).isEqualTo(IntOffset.Zero)
  }

  companion object {
    private val VIEW = IntSize(width = 800, height = 600)
    private val CARD = IntSize(width = 280, height = 200)
    private const val GAP = 20f
  }
}
