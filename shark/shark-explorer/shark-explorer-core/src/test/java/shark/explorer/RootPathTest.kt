package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.explorer.HeapDominatorTreemap.Companion.ROOT_OBJECT_ID
import shark.explorer.HeapDominatorTreemap.Companion.UNREACHABLE_NODE_ID

class RootPathTest {

  @Test fun `a chain is cut at the rectangle the map draws the object inside`() {
    // Rooted at 1, which draws 2 as one of its own rectangles, with 5 pointed at somewhere inside it.
    val path = chain(1L to DOMINATES, 2L to DOMINATES, 3L to ON_THE_WAY, 5L to ON_THE_WAY)

    assertThat(path.stepsBelow(rootNodeId = 1L).objectIds()).containsExactly(2L, 3L, 5L)
  }

  @Test fun `a step only on the way is cut with the rest of the chain above`() {
    // 3 holds 5 and doesn't dominate it, so the map draws no rectangle for it around 5: what it draws is 4.
    val path = chain(1L to DOMINATES, 3L to ON_THE_WAY, 4L to DOMINATES, 5L to ON_THE_WAY)

    assertThat(path.stepsBelow(rootNodeId = 1L).objectIds()).containsExactly(4L, 5L)
  }

  @Test fun `a chain on the whole heap dump starts at the top level dominator`() {
    // The GC rooted object holds 2 without dominating it, so 2 is the top level rectangle 5 is inside of.
    val path = chain(9L to ON_THE_WAY, 2L to DOMINATES, 5L to ON_THE_WAY)

    assertThat(path.stepsBelow(ROOT_OBJECT_ID).objectIds()).containsExactly(2L, 5L)
  }

  @Test fun `an object nothing below the root dominates is the whole of its own chain`() {
    // Which is what pointing at one of the root's own rectangles is: there is nothing between the two.
    val path = chain(9L to ON_THE_WAY, 5L to ON_THE_WAY)

    assertThat(path.stepsBelow(ROOT_OBJECT_ID).objectIds()).containsExactly(5L)
  }

  @Test fun `a chain is left whole when the map is rooted at a pile of objects`() {
    val path = chain(1L to DOMINATES, 2L to DOMINATES, 5L to ON_THE_WAY)

    assertThat(path.stepsBelow(UNREACHABLE_NODE_ID).objectIds()).containsExactly(1L, 2L, 5L)
  }

  @Test fun `a chain with no steps has none to cut`() {
    assertThat(RootPath.NONE.stepsBelow(ROOT_OBJECT_ID)).isEmpty()
  }

  @Test fun `what a chain adds below an object is the steps under it`() {
    val path = chain(1L to DOMINATES, 2L to DOMINATES, 3L to ON_THE_WAY, 5L to ON_THE_WAY)

    assertThat(path.stepsAfter(objectId = 2L)!!.objectIds()).containsExactly(3L, 5L)
  }

  @Test fun `a chain adds nothing below the object it leads to`() {
    val path = chain(1L to DOMINATES, 5L to ON_THE_WAY)

    assertThat(path.stepsAfter(objectId = 5L)).isEmpty()
  }

  @Test fun `a chain that doesn't run through an object says so rather than adding all of itself`() {
    // The pointer on a rectangle held some other way entirely: the two chains have no object in common, so
    // there is no telling where the one on screen would have to be cut for the other to carry on from it.
    val path = chain(1L to DOMINATES, 5L to ON_THE_WAY)

    assertThat(path.stepsAfter(objectId = 4L)).isNull()
  }
}
