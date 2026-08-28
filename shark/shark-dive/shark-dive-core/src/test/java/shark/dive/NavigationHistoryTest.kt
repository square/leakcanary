package shark.dive

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class NavigationHistoryTest {

  @Test fun `a fresh history has nowhere to go`() {
    val history = NavigationHistory("root")

    assertThat(history.current).isEqualTo("root")
    assertThat(history.canGoBack).isFalse()
    assertThat(history.canGoForward).isFalse()
  }

  @Test fun `going somewhere is a move to come back from`() {
    val history = NavigationHistory("root").goTo("a")

    assertThat(history.current).isEqualTo("a")
    assertThat(history.canGoBack).isTrue()
    assertThat(history.goBack().current).isEqualTo("root")
  }

  @Test fun `coming back and going forward again lands where it was`() {
    val history = NavigationHistory("root").goTo("a").goTo("b")

    val back = history.goBack()

    assertThat(back.current).isEqualTo("a")
    assertThat(back.canGoForward).isTrue()
    assertThat(back.goForward()).isEqualTo(history)
  }

  @Test fun `going somewhere else drops what the forward arrow led to`() {
    val history = NavigationHistory("root")
      .goTo("a")
      .goTo("b")
      .goBack()
      .goTo("c")

    // What a browser does: having gone somewhere else, redoing the move that was undone isn't a move.
    assertThat(history.canGoForward).isFalse()
    assertThat(history.entries).containsExactly("root", "a", "c")
  }

  @Test fun `going where it already is is not a move`() {
    val history = NavigationHistory("root").goTo("a")

    // Clicking the rectangle already being shown shouldn't fill the history with it.
    assertThat(history.goTo("a")).isEqualTo(history)
  }

  @Test fun `replacing where it is leaves the way back alone`() {
    val history = NavigationHistory("root").goTo("a").replacingCurrent("b")

    // For a filter typed into the object list: that isn't somewhere to come back to, but where it was
    // before still is.
    assertThat(history.current).isEqualTo("b")
    assertThat(history.entries).containsExactly("root", "b")
    assertThat(history.goBack().current).isEqualTo("root")
  }

  @Test fun `the ends of the history are as far as it goes`() {
    val history = NavigationHistory("root").goTo("a")

    assertThat(history.goForward()).isEqualTo(history)
    assertThat(history.goBack().goBack().current).isEqualTo("root")
  }

  /** What the right click menus on the arrows list, which is why the order they are in is part of it. */
  @Test fun `everywhere it has been is listed nearest first`() {
    val history = NavigationHistory("root").goTo("a").goTo("b").goTo("c").goBack()

    assertThat(history.backEntries).containsExactly("a", "root")
    assertThat(history.forwardEntries).containsExactly("c")
  }

  @Test fun `a history that has been nowhere lists nowhere`() {
    val history = NavigationHistory("root")

    assertThat(history.backEntries).isEmpty()
    assertThat(history.forwardEntries).isEmpty()
  }

  /** Picking the third entry of a back list is going back three moves, which the arrow does one at a time. */
  @Test fun `it goes back and forward several moves at once`() {
    val history = NavigationHistory("root").goTo("a").goTo("b").goTo("c")

    assertThat(history.goBack(3).current).isEqualTo("root")
    assertThat(history.goBack(3).goForward(2).current).isEqualTo("b")
  }

  /** The list a click came from and the history it came from are the same value one recomposition apart. */
  @Test fun `going further than it has been lands at the end of it`() {
    val history = NavigationHistory("root").goTo("a")

    assertThat(history.goBack(9).current).isEqualTo("root")
    assertThat(history.goForward(9).current).isEqualTo("a")
  }

  @Test fun `a history has to be somewhere`() {
    assertThatThrownBy { NavigationHistory<String>(entries = emptyList(), index = 0) }
      .isInstanceOf(IllegalArgumentException::class.java)
  }
}
