package shark

import org.assertj.core.api.Assertions
import org.junit.Test
import shark.LongScatterSetAssertion.Companion.assertThat
import shark.internal.hppc.HHPC
import shark.internal.hppc.LongScatterSet

class LongScatterSetTest {

  @Test fun `verify LongScatterSet#add operation works correctly`() {
    val set = LongScatterSet()
    runAssertionsForAddOperation(set)
  }

  @Test fun `verify LongScatterSet#remove operation works correctly`() {
    val set = LongScatterSet()
    runAssertionsForRemoveOperation(set)
  }

  @Test fun `verify LongScatterSet#release operation works correctly`() {
    val set = LongScatterSet()
    set.add(42)
    set.release()

    assertThat(set)
        .isEmpty()
        .doesNotContain(42)
  }

  @Test fun `verify LongScatterSet#ensureCapacity does not break Set when called before those operations`() {
    val setForAddOperation = LongScatterSet().apply { ensureCapacity(10) }
    val setForRemoveOperation = LongScatterSet().apply { ensureCapacity(10) }

    // Verify that calling ensureCapacity doesn't affect add/remove operations
    runAssertionsForAddOperation(setForAddOperation)
    runAssertionsForRemoveOperation(setForRemoveOperation)
  }

  @Test fun `verify LongScatterSet#ensureCapacity does not break Set when called after those operations`() {
    val set = LongScatterSet().apply {
      add(42)
      add(10)
      ensureCapacity(100)
    }

    // Verify that calling ensureCapacity didn't change any data in set
    assertThat(set)
        .contains(10, 42)
        .hasSize(2)
  }

  private fun runAssertionsForRemoveOperation(set: LongScatterSet) {
    require(set.size() == 0) { "Set should be empty in order to run the assertions on it" }

    val testValues = listOf(42, 0, Long.MIN_VALUE, Long.MAX_VALUE, -1)

    // First, verify removing unique values from an empty set
    testValues.forEach { value ->
      set.remove(value)
      assertThat(set)
          .isEmpty()
          .doesNotContain(value)
    }

    // Add test values to the set
    testValues.forEach { set += it }

    // Verify removing unique values from non-empty set
    testValues.forEachIndexed { index: Int, value: Long ->
      // Value should be in the set at first
      assertThat(set).contains(value)

      // Now remove the value from set
      set.remove(value)

      // Value is removed -> size should decrease, other elements should still be in set
      assertThat(set)
          .doesNotContain(value)
          .hasSize(testValues.size - index - 1)
      for (i in index + 1 until testValues.size) {
        assertThat(set).contains(testValues[i])
      }
    }

    // Verify removing same element twice works correctly.
    set.add(42)
    set.remove(42)
    set.remove(42)

    assertThat(set)
        .isEmpty()
        .doesNotContain(42)

    // Verify removing elements with matching hash. Remove in reverse order than inserting
    set += 11
    set += 14723950898

    set.remove(14723950898)
    set.remove(11)
    assertThat(set)
        .isEmpty()
        .doesNotContain(11, 14723950898)
  }

  private fun runAssertionsForAddOperation(set: LongScatterSet) {
    require(set.size() == 0) { "Set should be empty in order to run the assertions on it" }

    // First, verify adding unique values, as well as values that have matching hashKeys
    // Values 11 and 14723950898 have same hash when it's calculated via HHPC.mixPhi()
    Assertions.assertThat(HHPC.mixPhi(14723950898)).isEqualTo(HHPC.mixPhi(11))

    val testValues = listOf(42, 0, Long.MIN_VALUE, Long.MAX_VALUE, -1, 11, 14723950898)

    // Verify that adding elements to set actually adds them
    testValues.forEachIndexed { index: Int, value: Long ->
      // Verify that value is not yet in the set
      assertThat(set)
          .doesNotContain(value)
          .hasSize(index)

      // Add element to the set
      set.add(value)

      // Size should increase by one, element and all previous elements should still be in the set
      assertThat(set).hasSize(index + 1)
      for (i in 0 until index + 1) {
        assertThat(set).contains(testValues[i])
      }
    }

    // Verify that += operator works exactly like add()
    set += 30
    assertThat(set).contains(30)

    // Verify that adding same element twice - or element that already is in set - doesn't duplicate it
    val currentSize = set.size()
    set.add(testValues.first())
    assertThat(set)
        .hasSize(currentSize)
  }
}
