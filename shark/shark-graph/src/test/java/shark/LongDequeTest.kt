package shark

import java.util.ArrayDeque
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.internal.hppc.LongDeque

class LongDequeTest {

  @Test fun `new deque is empty`() {
    val deque = LongDeque()

    assertThat(deque.isEmpty()).isTrue()
    assertThat(deque.isNotEmpty()).isFalse()
    assertThat(deque.size).isEqualTo(0)
  }

  @Test fun `polls in insertion order`() {
    val deque = LongDeque()

    (1L..100L).forEach { deque += it }

    assertThat(deque.pollAll()).isEqualTo((1L..100L).toList())
  }

  @Test fun `behaves like an ArrayDeque when interleaving adds and polls`() {
    // Interleaving moves head around the circular buffer, including while the buffer grows.
    val deque = LongDeque(expectedElements = 4)
    val reference = ArrayDeque<Long>()
    val random = Random(42)

    repeat(10_000) { index ->
      if (reference.isEmpty() || random.nextBoolean()) {
        deque += index.toLong()
        reference += index.toLong()
      } else {
        assertThat(deque.poll()).isEqualTo(reference.poll())
      }
      assertThat(deque.size).isEqualTo(reference.size)
    }
    assertThat(deque.pollAll()).isEqualTo(reference.toList())
  }

  @Test fun `clear empties the deque`() {
    val deque = LongDeque()
    deque += 42L
    deque.poll()
    deque += 1L
    deque += 2L

    deque.clear()

    assertThat(deque.isEmpty()).isTrue()
    deque += 3L
    assertThat(deque.pollAll()).isEqualTo(listOf(3L))
  }

  @Test fun `polling an empty deque fails`() {
    val deque = LongDeque()

    assertThat(runCatching { deque.poll() }.exceptionOrNull())
      .isInstanceOf(IllegalStateException::class.java)
  }

  private fun LongDeque.pollAll(): List<Long> {
    val polled = mutableListOf<Long>()
    while (isNotEmpty()) {
      polled += poll()
    }
    return polled
  }
}
