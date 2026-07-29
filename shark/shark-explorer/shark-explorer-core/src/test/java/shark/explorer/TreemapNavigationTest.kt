package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class TreemapNavigationTest {

  @Test fun `a fresh navigation is at the root`() {
    val navigation = TreemapNavigation("root")

    assertThat(navigation.current).isEqualTo("root")
    assertThat(navigation.canZoomOut).isFalse()
  }

  @Test fun `zooming in appends to the path`() {
    val navigation = TreemapNavigation("root").zoomInto("a").zoomInto("b")

    assertThat(navigation.path).containsExactly("root", "a", "b")
    assertThat(navigation.current).isEqualTo("b")
  }

  @Test fun `zooming into the current node changes nothing`() {
    val navigation = TreemapNavigation("root").zoomInto("a")

    assertThat(navigation.zoomInto("a")).isEqualTo(navigation)
  }

  @Test fun `zooming into a node already on the path zooms back out to it`() {
    val navigation = TreemapNavigation("root").zoomInto("a").zoomInto("b").zoomInto("c")

    assertThat(navigation.zoomInto("a").path).containsExactly("root", "a")
  }

  @Test fun `zooming out drops the last node`() {
    val navigation = TreemapNavigation("root").zoomInto("a").zoomInto("b")

    assertThat(navigation.zoomOut().path).containsExactly("root", "a")
  }

  @Test fun `zooming out of the root changes nothing`() {
    val navigation = TreemapNavigation("root")

    assertThat(navigation.zoomOut()).isEqualTo(navigation)
  }

  @Test fun `a path is cut where a node is gone`() {
    val navigation = TreemapNavigation("root").zoomInto("a").zoomInto("b").zoomInto("c")

    val retained = navigation.retainingWhere { it != "b" }

    assertThat(retained.path).containsExactly("root", "a")
  }

  @Test fun `a path whose nodes are all still there is unchanged`() {
    val navigation = TreemapNavigation("root").zoomInto("a").zoomInto("b")

    assertThat(navigation.retainingWhere { true }).isSameAs(navigation)
  }

  @Test fun `the root is kept even when it is gone`() {
    val navigation = TreemapNavigation("root").zoomInto("a")

    assertThat(navigation.retainingWhere { false }.path).containsExactly("root")
  }

  @Test fun `an empty path is rejected`() {
    assertThatThrownBy { TreemapNavigation(emptyList<String>()) }
      .isInstanceOf(IllegalArgumentException::class.java)
  }
}
