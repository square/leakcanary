package shark

import org.assertj.core.api.Assertions.assertThat
import shark.internal.hppc.LongScatterSet

/**
 * Assertion class for writing checks for [LongScatterSet] in AssertJ style.
 * API is made similar to [org.assertj.core.api.AbstractIterableAssert].
 */
internal class LongScatterSetAssertion(private val actual: LongScatterSet) {

  fun contains(vararg values: Long) = apply {
    values.forEach {
      assertThat(it in actual).isTrue()
    }
  }

  fun doesNotContain(vararg values: Long) = apply {
    values.forEach {
      assertThat(it in actual).isFalse()
    }
  }

  fun isEmpty() = apply {
    assertThat(actual.size()).isZero()
  }

  fun hasSize(expected: Int) = apply {
    assertThat(actual.size()).isEqualTo(expected)
  }

  companion object {
    fun assertThat(actual: LongScatterSet): LongScatterSetAssertion =
      LongScatterSetAssertion(actual)
  }
}
