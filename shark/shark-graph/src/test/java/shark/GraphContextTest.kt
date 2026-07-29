package shark

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class GraphContextTest {

  private val context = GraphContext()

  @Test fun `setting a null value removes the key`() {
    context["key"] = "value"

    context["key"] = null

    assertThat("key" in context).isFalse()
    assertThat(context.get<String>("key")).isNull()
  }

  @Test fun `getOrPut that computes null does not store the key`() {
    val firstValue = context.getOrPut<String?>("key") { null }

    assertThat(firstValue).isNull()
    assertThat("key" in context).isFalse()
    assertThat(context.getOrPut("key") { "value" }).isEqualTo("value")
  }

  @Test fun `compute is passed the current value`() {
    context["count"] = 41

    val count = context.compute<Int>("count") { previousCount -> previousCount!! + 1 }

    assertThat(count).isEqualTo(42)
    assertThat(context.get<Int>("count")).isEqualTo(42)
  }

  @Test fun `compute is passed null when there is no value`() {
    val count = context.compute<Int>("count") { previousCount -> (previousCount ?: 0) + 1 }

    assertThat(count).isEqualTo(1)
  }

  @Test fun `compute that returns null removes the key`() {
    context["key"] = "value"

    val value = context.compute<String>("key") { null }

    assertThat(value).isNull()
    assertThat("key" in context).isFalse()
  }
}
