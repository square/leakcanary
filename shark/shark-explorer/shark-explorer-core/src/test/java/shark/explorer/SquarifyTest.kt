package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.Test

class SquarifyTest {

  private val rect = TreemapRect(0.0, 0.0, 100.0, 100.0)

  @Test fun `rectangle areas are proportional to weights`() {
    val weights = longArrayOf(50, 30, 15, 5)

    val rects = squarify(weights, rect)

    val totalWeight = weights.sum().toDouble()
    rects.forEachIndexed { index, childRect ->
      val expectedArea = rect.area * weights[index] / totalWeight
      assertThat(childRect.area).isCloseTo(expectedArea, within(EPSILON))
    }
  }

  @Test fun `rectangles fill the whole rectangle`() {
    val rects = squarify(longArrayOf(7, 6, 5, 4, 3, 2, 1), rect)

    assertThat(rects.sumOf { it.area }).isCloseTo(rect.area, within(EPSILON))
  }

  @Test fun `rectangles do not overlap`() {
    val rects = squarify(longArrayOf(40, 25, 20, 10, 5), rect)

    for (i in rects.indices) {
      for (j in i + 1 until rects.size) {
        assertThat(overlaps(rects[i], rects[j]))
          .describedAs("rect $i ${rects[i]} overlaps rect $j ${rects[j]}")
          .isFalse()
      }
    }
  }

  @Test fun `rectangles stay within the laid out rectangle`() {
    val offsetRect = TreemapRect(10.0, 20.0, 110.0, 220.0)

    val rects = squarify(longArrayOf(9, 5, 3, 2, 1), offsetRect)

    rects.forEach { childRect ->
      assertThat(childRect.left).isGreaterThanOrEqualTo(offsetRect.left - EPSILON)
      assertThat(childRect.top).isGreaterThanOrEqualTo(offsetRect.top - EPSILON)
      assertThat(childRect.right).isLessThanOrEqualTo(offsetRect.right + EPSILON)
      assertThat(childRect.bottom).isLessThanOrEqualTo(offsetRect.bottom + EPSILON)
    }
  }

  /**
   * Weights the size of real retained heap sizes overflow the `Int` arithmetic that
   * `leakcanary-app`'s treemap uses: `sumValue * sumValue` exceeds [Int.MAX_VALUE] above ~46 341,
   * which corrupts every row break decision. Proportionality is the property that breaks.
   */
  @Test fun `byte sized weights stay proportional`() {
    val weights = longArrayOf(180_000_000, 60_000_000, 40_000_000, 20_000_000)

    val rects = squarify(weights, rect)

    val totalWeight = weights.sum().toDouble()
    rects.forEachIndexed { index, childRect ->
      val expectedArea = rect.area * weights[index] / totalWeight
      assertThat(childRect.area).isCloseTo(expectedArea, within(EPSILON))
    }
  }

  @Test fun `single weight fills the rectangle`() {
    val rects = squarify(longArrayOf(42), rect)

    assertThat(rects).hasSize(1)
    assertThat(rects.single()).isEqualTo(rect)
  }

  @Test fun `no weights produces no rectangles`() {
    assertThat(squarify(longArrayOf(), rect)).isEmpty()
  }

  @Test fun `zero weights produce empty rectangles`() {
    val rects = squarify(longArrayOf(10, 0, 0), rect)

    assertThat(rects[0].area).isCloseTo(rect.area, within(EPSILON))
    assertThat(rects[1].area).isCloseTo(0.0, within(EPSILON))
    assertThat(rects[2].area).isCloseTo(0.0, within(EPSILON))
  }

  @Test fun `all zero weights produce empty rectangles`() {
    val rects = squarify(longArrayOf(0, 0), rect)

    rects.forEach { assertThat(it.area).isCloseTo(0.0, within(EPSILON)) }
  }

  @Test fun `empty rectangle produces empty rectangles`() {
    val rects = squarify(longArrayOf(3, 2, 1), TreemapRect(5.0, 5.0, 5.0, 5.0))

    rects.forEach { assertThat(it.area).isCloseTo(0.0, within(EPSILON)) }
  }

  @Test fun `aspect ratios stay reasonable for evenly sized weights`() {
    // Squarifying doesn't promise squares, but it does promise to avoid the slivers a naive layout
    // produces: 16 equal weights sliced across a square would each be 16:1, so anything under 3:1
    // shows the row breaking is doing its job.
    val rects = squarify(LongArray(16) { 10 }, rect)

    rects.forEach { childRect ->
      val ratio = maxOf(
        childRect.width / childRect.height,
        childRect.height / childRect.width
      )
      assertThat(ratio).describedAs("aspect ratio of $childRect").isLessThan(3.0)
    }
  }

  private fun overlaps(
    first: TreemapRect,
    second: TreemapRect
  ): Boolean {
    if (first.area <= EPSILON || second.area <= EPSILON) return false
    return first.left < second.right - EPSILON && second.left < first.right - EPSILON &&
      first.top < second.bottom - EPSILON && second.top < first.bottom - EPSILON
  }

  companion object {
    private const val EPSILON = 1e-6
  }
}
