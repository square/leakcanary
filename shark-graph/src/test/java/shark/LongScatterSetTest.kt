package shark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import shark.internal.hppc.HHPC
import shark.internal.hppc.LongScatterSet

class LongScatterSetTest {

  @Test fun `verify add operation`() {
    val set = LongScatterSet()
    verifyAddOperation(set)
  }

  @Test fun `verify remove operation`() {
    val set = LongScatterSet()
    verifyRemoveOperation(set)
  }

  @Test fun `verify release operation`() {
    val set = LongScatterSet()
    set.add(42)
    set.release()

    assertFalse(42 in set)
    assertEquals(0, set.size())
  }

  @Test fun `verify ensureCapacity does not break operations`() {
    // Ensure capacity before adding/removing elements
    verifyAddOperation(LongScatterSet().apply { ensureCapacity(10) })
    verifyRemoveOperation(LongScatterSet().apply { ensureCapacity(10) })

    // Ensure capacity after adding/removing elements
    val set = LongScatterSet()
    set.add(42)
    set.add(10)
    set.ensureCapacity(100)

    assertTrue(42 in set)
    assertTrue(10 in set)
    assertEquals(2, set.size())
  }

  @Ignore(value = "Takes ~20 seconds on MacBook Pro 2019, disabled so it doesn't waste CI time.")
  @Test(expected = RuntimeException::class)
  fun `verify out of memory is handled`() {
    val set = LongScatterSet()
    for (i in Long.MIN_VALUE..Long.MAX_VALUE) {
      set += i
    }
  }

  private fun verifyRemoveOperation(set: LongScatterSet) {
    val testValues = listOf(42, 0, Long.MIN_VALUE, Long.MAX_VALUE, -1)

    // First, check removing unique values on empty list
    testValues.forEach { value ->
      set.remove(value)
      assertFalse(value in set)
      assertEquals(0, set.size())
    }

    // Check removing unique values on filled list
    testValues.forEach { set += it }

    testValues.forEachIndexed { index: Int, value: Long ->
      // Values is in the set at first
      assertTrue(value in set)

      set.remove(value)

      // Value is removed, size decreased, other elements are still there
      assertFalse(value in set)
      assertEquals(testValues.size - index - 1, set.size())
      for (i in index + 1 until testValues.size) {
        assertTrue(testValues[i] in set)
      }
    }

    // Check removing same element twice
    set.add(42)
    set.remove(42)
    set.remove(42)
    assertFalse(42 in set)
    assertEquals(0, set.size())

    // Check removing elements with same hash. Remove in reverse order than inserting
    set += 11
    set += 14723950898

    set.remove(14723950898)
    set.remove(11)
    assertFalse(11 in set)
    assertFalse(14723950898 in set)
  }

  private fun verifyAddOperation(set: LongScatterSet) {
    // First, check adding unique values + having matching hashKeys
    // Values 11 and 14723950898 give same hash when using HHPC.mixPhi()
    assertEquals(HHPC.mixPhi(11), HHPC.mixPhi(14723950898))

    val testValues = listOf(42, 0, Long.MIN_VALUE, Long.MAX_VALUE, -1, 11, 14723950898)

    testValues.forEachIndexed { index: Int, value: Long ->
      // Values is not yet in the set
      assertFalse(value in set)
      assertEquals(index, set.size())

      set.add(value)

      // Size increases by one, element and all previous elements should be in the set
      assertEquals(index + 1, set.size())
      for (i in 0 until index + 1) {
        assertTrue(testValues[i] in set)
      }
    }

    // Check the += operator
    set += 30
    assertTrue(30 in set)

    // Check adding element that was already there
    val currentSize = set.size()
    set.add(testValues.first())
    assertEquals(currentSize, set.size())
  }
}