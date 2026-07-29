package shark

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import shark.ValueHolder.Companion.NULL_REFERENCE

class ReferenceTest {

  @Test
  fun `creating a Reference to a null reference fails`() {
    assertThatThrownBy {
      Reference(
        valueObjectId = NULL_REFERENCE,
        isLowPriority = false,
        lazyDetailsResolver = { error("Details should not be resolved") }
      )
    }.isInstanceOf(IllegalArgumentException::class.java)
      .hasMessageContaining("HeapValue.asNonNullObjectId")
  }
}
