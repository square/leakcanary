package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class NavigationHistoryTest {

  @Test fun `a fresh history has nowhere to go`() {
    val history = NavigationHistory(TreemapNavigation("root"))

    assertThat(history.current).isEqualTo(TreemapNavigation("root"))
    assertThat(history.canGoBack).isFalse()
    assertThat(history.canGoForward).isFalse()
  }

  @Test fun `going somewhere is a move to come back from`() {
    val history = NavigationHistory(TreemapNavigation("root")).goTo(zoomedInto("a"))

    assertThat(history.current).isEqualTo(zoomedInto("a"))
    assertThat(history.canGoBack).isTrue()
    assertThat(history.goBack().current).isEqualTo(TreemapNavigation("root"))
  }

  @Test fun `coming back and going forward again lands where it was`() {
    val history = NavigationHistory(TreemapNavigation("root"))
      .goTo(zoomedInto("a"))
      .goTo(zoomedInto("b"))

    val back = history.goBack()

    assertThat(back.current).isEqualTo(zoomedInto("a"))
    assertThat(back.canGoForward).isTrue()
    assertThat(back.goForward()).isEqualTo(history)
  }

  @Test fun `going somewhere else drops what the forward arrow led to`() {
    val history = NavigationHistory(TreemapNavigation("root"))
      .goTo(zoomedInto("a"))
      .goTo(zoomedInto("b"))
      .goBack()
      .goTo(zoomedInto("c"))

    // What a browser does: having gone somewhere else, redoing the move that was undone isn't a move.
    assertThat(history.canGoForward).isFalse()
    assertThat(history.entries).containsExactly(TreemapNavigation("root"), zoomedInto("a"), zoomedInto("c"))
  }

  @Test fun `going where it already is is not a move`() {
    val history = NavigationHistory(TreemapNavigation("root")).goTo(zoomedInto("a"))

    // Clicking the rectangle already being shown shouldn't fill the history with it.
    assertThat(history.goTo(zoomedInto("a"))).isEqualTo(history)
  }

  @Test fun `replacing where it is leaves the way back alone`() {
    val history = NavigationHistory(TreemapNavigation("root"))
      .goTo(zoomedInto("a"))
      .replacingCurrent(zoomedInto("b"))

    // For a view that settled on less than it was asked for: that isn't somewhere to come back to, but
    // where it was before still is.
    assertThat(history.current).isEqualTo(zoomedInto("b"))
    assertThat(history.entries).containsExactly(TreemapNavigation("root"), zoomedInto("b"))
    assertThat(history.goBack().current).isEqualTo(TreemapNavigation("root"))
  }

  @Test fun `the ends of the history are as far as it goes`() {
    val history = NavigationHistory(TreemapNavigation("root")).goTo(zoomedInto("a"))

    assertThat(history.goForward()).isEqualTo(history)
    assertThat(history.goBack().goBack().current).isEqualTo(TreemapNavigation("root"))
  }

  @Test fun `a history has to be somewhere`() {
    assertThatThrownBy { NavigationHistory<String>(entries = emptyList(), index = 0) }
      .isInstanceOf(IllegalArgumentException::class.java)
  }

  private fun zoomedInto(node: String): TreemapNavigation<String> =
    TreemapNavigation("root").zoomInto(node)
}
