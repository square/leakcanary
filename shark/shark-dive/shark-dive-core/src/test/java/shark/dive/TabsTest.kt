package shark.dive

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class TabsTest {

  @Test fun `a window opens on one tab`() {
    val tabs = Tabs.opening(WHOLE_HEAP_DUMP)

    assertThat(tabs.tabs).hasSize(1)
    assertThat(tabs.place).isEqualTo(WHOLE_HEAP_DUMP)
    assertThat(tabs.canGoBack).isFalse()
  }

  @Test fun `clicking an object moves the tab it was clicked in`() {
    val tabs = Tabs.opening(WHOLE_HEAP_DUMP).goTo(objectAt(1))

    // The whole point of a place being a value: one tab, one move, and nothing else to keep in step.
    assertThat(tabs.tabs).hasSize(1)
    assertThat(tabs.place).isEqualTo(objectAt(1))
    assertThat(tabs.canGoBack).isTrue()
    assertThat(tabs.goBack().place).isEqualTo(WHOLE_HEAP_DUMP)
  }

  @Test fun `a tab opened in the background leaves the reader where they were`() {
    val tabs = Tabs.opening(WHOLE_HEAP_DUMP).open(objectAt(1), inBackground = true)

    assertThat(tabs.tabs).hasSize(2)
    assertThat(tabs.place).isEqualTo(WHOLE_HEAP_DUMP)
    assertThat(tabs.tabs.map { it.place }).containsExactly(WHOLE_HEAP_DUMP, objectAt(1))
  }

  @Test fun `a tab opened in front is the one being read`() {
    val tabs = Tabs.opening(WHOLE_HEAP_DUMP).open(Place.Leaks())

    assertThat(tabs.place).isEqualTo(Place.Leaks())
  }

  @Test fun `a tab opens beside the one it was opened from`() {
    val tabs = Tabs.opening(WHOLE_HEAP_DUMP)
      .open(objectAt(1), inBackground = true)
      .open(objectAt(2), inBackground = true)

    // Beside rather than at the far end, so that what was opened while reading one object stays by it.
    assertThat(tabs.tabs.map { it.place })
      .containsExactly(WHOLE_HEAP_DUMP, objectAt(2), objectAt(1))
  }

  @Test fun `each tab keeps its own history`() {
    val tabs = Tabs.opening(WHOLE_HEAP_DUMP)
      .goTo(objectAt(1))
      .open(objectAt(2))
      .goTo(objectAt(3))

    // Going back in the tab opened walks that tab's own moves, not the ones made in the first tab.
    assertThat(tabs.goBack().place).isEqualTo(objectAt(2))
    assertThat(tabs.goBack().canGoBack).isFalse()
  }

  @Test fun `closing a tab lands on the one after it`() {
    val opened = Tabs.opening(WHOLE_HEAP_DUMP)
      .open(objectAt(1), inBackground = true)
      .open(objectAt(2), inBackground = true)
    val first = opened.tabs[0].id

    val closed = opened.close(first)

    assertThat(closed.tabs.map { it.place }).containsExactly(objectAt(2), objectAt(1))
    assertThat(closed.place).isEqualTo(objectAt(2))
  }

  @Test fun `closing the last tab on the strip lands on the one before it`() {
    val opened = Tabs.opening(WHOLE_HEAP_DUMP).open(objectAt(1))

    val closed = opened.close(opened.selectedId!!)

    assertThat(closed.place).isEqualTo(WHOLE_HEAP_DUMP)
  }

  @Test fun `closing a tab that isn't the one being read leaves the reader alone`() {
    val opened = Tabs.opening(WHOLE_HEAP_DUMP).open(objectAt(1))

    val closed = opened.close(opened.tabs[0].id)

    assertThat(closed.place).isEqualTo(objectAt(1))
  }

  @Test fun `every tab is closeable, including the last`() {
    val opened = Tabs.opening(WHOLE_HEAP_DUMP)

    val closed = opened.close(opened.selectedId!!)

    // A window with no tab keeps the heap dump it spent seconds reading, and is one click from a tab.
    assertThat(closed.tabs).isEmpty()
    assertThat(closed.selectedId).isNull()
    assertThat(closed.place).isNull()
  }

  @Test fun `a click in a window with no tab open gets one`() {
    val empty = Tabs.opening(WHOLE_HEAP_DUMP).let { it.close(it.selectedId!!) }

    val tabs = empty.goTo(objectAt(1))

    // Rather than a click that does nothing, which reads as the window having stopped working.
    assertThat(tabs.place).isEqualTo(objectAt(1))
  }

  @Test fun `a closed tab's id is not handed to the next one`() {
    val opened = Tabs.opening(WHOLE_HEAP_DUMP).open(objectAt(1))
    val reopened = opened.close(opened.selectedId!!).open(objectAt(2))

    // Or the strip would animate the new tab as the old one having changed its mind.
    assertThat(reopened.tabs.map { it.id }).doesNotHaveDuplicates()
    assertThat(reopened.selectedId).isNotEqualTo(opened.selectedId)
  }

  @Test fun `typing a filter is not a move`() {
    val tabs = Tabs.opening(WHOLE_HEAP_DUMP)
      .goTo(Place.Objects())
      .replacingCurrent(Place.Objects(ObjectListFilter(query = "Bitmap")))

    // The back arrow leaves the list rather than walking back through what was typed into it.
    assertThat(tabs.place).isEqualTo(Place.Objects(ObjectListFilter(query = "Bitmap")))
    assertThat(tabs.goBack().place).isEqualTo(WHOLE_HEAP_DUMP)
  }

  @Test fun `selecting a tab that isn't there changes nothing`() {
    val tabs = Tabs.opening(WHOLE_HEAP_DUMP)

    assertThat(tabs.select(404)).isEqualTo(tabs)
    assertThat(tabs.close(404)).isEqualTo(tabs)
  }

  /** What the right click menu on an arrow lists, and what picking one out of it does. */
  @Test fun `the tab on screen says everywhere it has been`() {
    val tabs = Tabs.opening(WHOLE_HEAP_DUMP).goTo(objectAt(1)).goTo(objectAt(2))

    assertThat(tabs.backPlaces).containsExactly(objectAt(1), WHOLE_HEAP_DUMP)
    assertThat(tabs.forwardPlaces).isEmpty()
    assertThat(tabs.goBack(2).place).isEqualTo(WHOLE_HEAP_DUMP)
    assertThat(tabs.goBack(2).goForward(2).place).isEqualTo(objectAt(2))
  }

  /** Each tab has a history of its own, so what the arrows offer is the history of the one on screen. */
  @Test fun `the other tabs histories are not on offer`() {
    val tabs = Tabs.opening(WHOLE_HEAP_DUMP).goTo(objectAt(1)).open(objectAt(2))

    assertThat(tabs.backPlaces).isEmpty()
  }

  @Test fun `a window with no tab open has been nowhere`() {
    val tabs = Tabs.opening(WHOLE_HEAP_DUMP).close(0)

    assertThat(tabs.backPlaces).isEmpty()
    assertThat(tabs.forwardPlaces).isEmpty()
    assertThat(tabs.goBack(1)).isEqualTo(tabs)
  }

  @Test fun `a selected tab has to be one of the tabs`() {
    assertThatThrownBy {
      Tabs(tabs = emptyList(), selectedId = 0, nextId = 1)
    }.isInstanceOf(IllegalArgumentException::class.java)
  }

  private fun objectAt(objectId: Long) = Place.Object(objectId)

  private companion object {
    val WHOLE_HEAP_DUMP = Place.wholeHeapDump()
  }
}
