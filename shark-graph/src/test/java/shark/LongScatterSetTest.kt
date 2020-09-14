package shark

import org.junit.Test
import shark.LongScatterSetAssertion.Companion.assertThat
import shark.internal.hppc.LongScatterSet

class LongScatterSetTest {

  @Test fun `new set is empty`() {
    assertThat(LongScatterSet()).isEmpty()
  }

  @Test fun `adding elements`() {
    val set = LongScatterSet()

    TEST_VALUES_LIST.forEach { set.add(it) }

    assertThat(set)
        .contains(*TEST_VALUES_LIST.toLongArray())
        .hasSize(TEST_VALUES_LIST.size)
  }

  @Test fun `+= operator works as addition`() {
    val set = LongScatterSet()

    set.add(TEST_VALUE)

    assertThat(set)
        .contains(TEST_VALUE)
        .hasSize(1)
  }

  @Test fun `adding element twice doesn't cause duplicates`() {
    val set = LongScatterSet()

    set.add(TEST_VALUE)
    set.add(TEST_VALUE)

    assertThat(set)
        .contains(TEST_VALUE)
        .hasSize(1)
  }

  @Test fun `adding elements with matching hash`() {
    val set = LongScatterSet()

    set += SAME_MIX_PHI_1
    set += SAME_MIX_PHI_2

    assertThat(set)
        .contains(SAME_MIX_PHI_1, SAME_MIX_PHI_2)
        .hasSize(2)
  }

  @Test fun `removing elements`() {
    val set = LongScatterSet()
    TEST_VALUES_LIST.forEach { set.add(it) }

    TEST_VALUES_LIST.forEach { set.remove(it) }

    assertThat(set)
        .doesNotContain(*TEST_VALUES_LIST.toLongArray())
        .isEmpty()
  }

  @Test fun `removing from empty set`() {
    val set = LongScatterSet()

    set.remove(TEST_VALUE)

    assertThat(set)
        .doesNotContain(TEST_VALUE)
        .isEmpty()
  }

  @Test fun `removing elements with matching hash`() {
    val set = LongScatterSet()
    set.add(SAME_MIX_PHI_1)
    set.add(SAME_MIX_PHI_2)

    set.remove(SAME_MIX_PHI_2)
    set.remove(SAME_MIX_PHI_1)

    assertThat(set)
        .doesNotContain(SAME_MIX_PHI_1, SAME_MIX_PHI_2)
        .isEmpty()
  }

  @Test fun `release operation cleans set`() {
    val set = LongScatterSet()
    set.add(TEST_VALUE)

    set.release()

    assertThat(set)
        .doesNotContain(TEST_VALUE)
        .isEmpty()
  }

  @Test fun `setting initial capacity before operations`() {
    val set = LongScatterSet()
    set.ensureCapacity(TEST_CAPACITY)

    set.add(TEST_VALUE)
    set.remove(TEST_VALUE)

    assertThat(set).isEmpty()
  }

  @Test fun `setting initial capacity after operations`() {
    val set = LongScatterSet()
    set.add(TEST_VALUE)

    set.ensureCapacity(TEST_CAPACITY)

    assertThat(set)
        .contains(TEST_VALUE)
        .hasSize(1)
  }

  @Test fun `adding a lot of elements causes resizing`() {
    val set = LongScatterSet()
    (1..100L).forEach { set.add(it) }

    assertThat(set)
        .contains(*(1..100L).toList().toLongArray())
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
