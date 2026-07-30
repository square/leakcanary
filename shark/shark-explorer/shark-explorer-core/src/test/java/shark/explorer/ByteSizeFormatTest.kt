package shark.explorer

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
}
