package shark.dive

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ByteSizeFormatTest {

  @Test fun `bytes are exact`() {
    assertThat(formatByteSize(0)).isEqualTo("0 B")
    assertThat(formatByteSize(1023)).isEqualTo("1023 B")
  }

  @Test fun `sizes below ten carry one decimal`() {
    assertThat(formatByteSize(1024)).isEqualTo("1.0 KB")
    assertThat(formatByteSize(1024 * 1024 * 3 / 2)).isEqualTo("1.5 MB")
  }

  @Test fun `sizes above ten are rounded`() {
    assertThat(formatByteSize(42L * 1024 * 1024)).isEqualTo("42 MB")
  }

  @Test fun `the largest unit is terabytes`() {
    val fourPetabytes = 4L * 1024 * 1024 * 1024 * 1024 * 1024

    assertThat(formatByteSize(fourPetabytes)).isEqualTo("4096 TB")
  }

  @Test fun `a share of the total rounds to two significant digits`() {
    assertThat(percentOf(42.3)).isEqualTo("42%")
    assertThat(percentOf(9.64)).isEqualTo("9.6%")
    assertThat(percentOf(1.04)).isEqualTo("1%")
    assertThat(percentOf(0.96)).isEqualTo("0.96%")
  }

  @Test fun `a share of a thousandth of a percent keeps its digits`() {
    // The usual size of one object: rounded to whole percents this would read as nothing at all.
    assertThat(percentOf(0.001)).isEqualTo("0.001%")
    assertThat(percentOf(0.0012)).isEqualTo("0.0012%")
  }

  @Test fun `a share smaller than a ten thousandth of a percent is reported as under it`() {
    assertThat(percentOf(0.00004)).isEqualTo("<0.0001%")
  }

  @Test fun `a size that is the whole total is all of it`() {
    assertThat(formatPercentOfTotal(byteCount = 100_000, totalByteCount = 100_000)).isEqualTo("100%")
  }

  @Test fun `a share of nothing is no share rather than a division by zero`() {
    assertThat(formatPercentOfTotal(byteCount = 42, totalByteCount = 0)).isEqualTo("0%")
  }

  @Test fun `a size is formatted with its share`() {
    val total = 100L * 1024 * 1024

    assertThat(formatByteSizeOfTotal(byteCount = 1024 * 1024, totalByteCount = total))
      .isEqualTo("1.0 MB (1% total)")
  }

  /** [formatPercentOfTotal] of a size that works out to [percent] percent of a 10 GB heap dump. */
  private fun percentOf(percent: Double): String {
    val totalByteCount = 10_000_000_000
    return formatPercentOfTotal(
      byteCount = (totalByteCount * percent / 100).toLong(),
      totalByteCount = totalByteCount
    )
  }
}
