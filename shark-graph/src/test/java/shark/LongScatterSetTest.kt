package shark

import org.assertj.core.api.Assertions
import org.junit.Test
import shark.LongScatterSetAssertion.Companion.assertThat
import shark.internal.hppc.HHPC.mixPhi
import shark.internal.hppc.LongScatterSet

class LongScatterSetTest {

  @Test fun `new set is empty`() {
    assertThat(LongScatterSet())
        .isEmpty()
  }

  @Test fun `LongScatterSet#add() adds elements`() {
    val set = LongScatterSet()

    TEST_VALUES_LIST.forEach { set.add(it) }

    assertThat(set)
        .containsExactly(TEST_VALUES_LIST)
  }

  @Test fun `+= operator works as addition`() {
    val set = LongScatterSet()

    set += TEST_VALUE

    assertThat(set)
        .containsExactly(TEST_VALUE)
  }

  @Test fun `when adding element twice, it is only added once`() {
    val set = LongScatterSet()

    set.add(TEST_VALUE)
    set.add(TEST_VALUE)

    assertThat(set)
        .containsExactly(TEST_VALUE)
  }

  /**
   * [LongScatterSet] calculates hash for its values using [mixPhi] function.
   * Inevitably, there can be collisions when two different values have same hash value;
   * [LongScatterSet] should handle such collisions properly.
   * There are two tests that verify adding and removing operations for values with matching hash value;
   * current test verifies that values we use in those tests actually do have matching hashes.
   */
  @Test fun `11 and 14_723_950_898 have same hash`() {
    Assertions.assertThat(mixPhi(11))
        .isEqualTo(mixPhi(14_723_950_898))
  }

  @Test fun `elements with equal hash can be added`() {
    val set = LongScatterSet()

    set.add(SAME_MIX_PHI_1)
    set.add(SAME_MIX_PHI_2)

    assertThat(set)
        .containsExactly(listOf(SAME_MIX_PHI_1, SAME_MIX_PHI_2))
  }

  @Test fun `LongScatterSet#remove() removes elements`() {
    val set = LongScatterSet()
    TEST_VALUES_LIST.forEach { set.add(it) }

    TEST_VALUES_LIST.forEach { set.remove(it) }

    assertThat(set)
        .isEmpty()
  }

  @Test fun `removing from empty set`() {
    val set = LongScatterSet()

    set.remove(TEST_VALUE)

    assertThat(set)
        .isEmpty()
  }

  @Test fun `elements with equal hash can be removed`() {
    val set = LongScatterSet()
    set.add(SAME_MIX_PHI_1)
    set.add(SAME_MIX_PHI_2)

    set.remove(SAME_MIX_PHI_2)

    assertThat(set)
        .containsExactly(SAME_MIX_PHI_1)
  }

  @Test fun `LongScatterSet#release() empties set`() {
    val set = LongScatterSet()
    set.add(TEST_VALUE)

    set.release()

    assertThat(set)
        .isEmpty()
  }

  /**
   * Verifies that calling [LongScatterSet.ensureCapacity] after elements has been added to set
   * does not damage the data in set
   */
  @Test fun `setting initial capacity after operations`() {
    val set = LongScatterSet()
    set.add(TEST_VALUE)

    set.ensureCapacity(TEST_CAPACITY)

    assertThat(set)
        .containsExactly(TEST_VALUE)
  }

  @Test fun `adding a lot of elements causes resizing`() {
    val set = LongScatterSet()
    (1..100L).forEach { set.add(it) }

    assertThat(set)
        .containsExactly((1..100L).toList())
  }

  companion object {
    // Values SAME_MIX_PHI_1 and SAME_MIX_PHI_2 have same hash when calculated via HHPC.mixPhi()
    const val SAME_MIX_PHI_1 = 11L
    const val SAME_MIX_PHI_2 = 14_723_950_898L
    val TEST_VALUES_LIST = listOf(42, 0, Long.MIN_VALUE, Long.MAX_VALUE, -1)
    const val TEST_VALUE = 12L
    const val TEST_CAPACITY = 10
  }
}
