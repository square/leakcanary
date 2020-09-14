package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.internal.hppc.HHPC.mixPhi

class HhpcTest {

  @Test fun `11 and 14_723_950_898 has same hash`() {
    assertThat(mixPhi(11))
        .isEqualTo(mixPhi(14_723_950_898))
  }
}