package shark

import org.assertj.core.api.Assertions.assertThat
import shark.internal.hppc.LongScatterSet

/**
 * Assertion class for writing checks for [LongScatterSet] in AssertJ style.
 * API is made similar to [org.assertj.core.api.AbstractIterableAssert].
 */
internal class LongScatterSetAssertion(private val actual: LongScatterSet) {

  fun contains(value: Long) = apply {
    assertThat(value in actual).isTrue()
  }

  fun contains(values: List<Long>) = apply {
    values.forEach { contains(it) }
  }

  fun containsExactly(value: Long) = apply {
    hasSize(1)
    contains(value)
  }

  fun containsExactly(values: List<Long>) = apply {
    hasSize(values.size)
    contains(values)
  }

  fun hasSize(expected: Int) = apply {
    assertThat(actual.size()).isEqualTo(expected)
  }

  fun isEmpty() = hasSize(0)

  companion object {
    fun assertThat(actual: LongScatterSet): LongScatterSetAssertion =
      LongScatterSetAssertion(actual)
  }
}
