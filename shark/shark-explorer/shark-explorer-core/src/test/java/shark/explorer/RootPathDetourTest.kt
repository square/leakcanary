package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class RootPathDetourTest {

  @Test fun `a stretch between two dominators could have run some other way`() {
    // 3 and 4 don't dominate 5, so the chain going through them is one of the ways 2 reaches it.
    val path = chain(1L to DOMINATES, 2L to DOMINATES, 3L to ON_THE_WAY, 4L to ON_THE_WAY, 5L to DOMINATES)

    assertThat(path.detours()).containsExactly(
      RootPathDetour(fromIndex = 2, toIndex = 4, fromObjectId = 2L, toObjectId = 5L)
    )
  }

  @Test fun `a stretch running down to the object itself is one of them`() {
    // The object at the end of a chain is held whichever way the chain got there, so it pins the stretch
    // above it the same way a dominator does.
    val path = chain(1L to DOMINATES, 3L to ON_THE_WAY, 5L to ON_THE_WAY)

    assertThat(path.detours()).containsExactly(
      RootPathDetour(fromIndex = 1, toIndex = 2, fromObjectId = 1L, toObjectId = 5L)
    )
  }

  @Test fun `a stretch off the top of a chain is held by a gc root rather than by an object`() {
    val path = chain(1L to ON_THE_WAY, 2L to ON_THE_WAY, 5L to DOMINATES)

    assertThat(path.detours()).containsExactly(
      RootPathDetour(fromIndex = 0, toIndex = 2, fromObjectId = null, toObjectId = 5L)
    )
  }

  @Test fun `a chain every step of which dominates the object could not have run otherwise`() {
    val path = chain(1L to DOMINATES, 2L to DOMINATES, 5L to DOMINATES)

    assertThat(path.detours()).isEmpty()
  }

  @Test fun `a chain with no steps has no stretches`() {
    assertThat(RootPath.NONE.detours()).isEmpty()
  }

  @Test fun `the chain's own way comes first, and the search reporting it again doesn't repeat it`() {
    val path = chain(1L to DOMINATES, 3L to ON_THE_WAY, 5L to DOMINATES)
    val detour = path.detours().single()

    val ways = path.waysOf(
      detour,
      found = IndependentPaths(
        paths = listOf(foundPath(3L, 5L), foundPath(4L, 5L)),
        hasMore = false
      )
    )

    assertThat(ways.map { it.steps.objectIds() }).containsExactly(listOf(3L, 5L), listOf(4L, 5L))
  }

  @Test fun `a way arriving where a dominator is keeps it marked`() {
    // Which way the stretch ran says nothing about what dominates the object: the step it arrives at does,
    // and that step is the same object however the chain got to it.
    val path = chain(1L to DOMINATES, 3L to ON_THE_WAY, 5L to DOMINATES)
    val detour = path.detours().single()

    val ways = path.waysOf(detour, found = IndependentPaths(listOf(foundPath(4L, 5L)), hasMore = false))

    assertThat(ways.map { way -> way.steps.map { it.isDominator } })
      .containsExactly(listOf(false, true), listOf(false, true))
  }

  @Test fun `only a stretch off the top of a chain names a gc root`() {
    val path = chain(1L to ON_THE_WAY, 5L to DOMINATES, gcRootLabel = A_GC_ROOT)
    val detour = path.detours().single()

    val own = path.waysOf(detour, found = IndependentPaths.NONE).single()

    assertThat(own.gcRootLabel).isEqualTo(A_GC_ROOT)
  }

  @Test fun `a stretch below an object has no gc root of its own to name`() {
    val path = chain(1L to DOMINATES, 3L to ON_THE_WAY, 5L to DOMINATES)
    val detour = path.detours().single()

    val own = path.waysOf(detour, found = IndependentPaths.NONE).single()

    assertThat(own.gcRootLabel).isNull()
  }

  @Test fun `a chain drawn with another way of a stretch is one chain of steps`() {
    val path = chain(1L to DOMINATES, 3L to ON_THE_WAY, 5L to DOMINATES, 6L to ON_THE_WAY, 7L to ON_THE_WAY)
    val detour = path.detours().first()
    val other = path.waysOf(detour, found = IndependentPaths(listOf(foundPath(4L, 5L)), hasMore = false))[1]

    val drawn = path.drawnWith(path.detours()) { if (it == detour) other else null }

    assertThat(drawn.path.steps.objectIds()).containsExactly(1L, 4L, 5L, 6L, 7L)
    // Under 1, which is the step the swapped stretch hangs below, and under 5 for the one down to 7. Rows of
    // the chain as drawn: the stretch below 1 is one step shorter than the chain's own was.
    assertThat(drawn.detourByRow.keys).containsExactly(0, 2)
  }

  @Test fun `a stretch off the top of a chain hangs under the head of it`() {
    val path = chain(1L to ON_THE_WAY, 5L to DOMINATES)

    val drawn = path.drawnWith(path.detours()) { null }

    assertThat(drawn.detourByRow.keys).containsExactly(HEAD_INDEX)
  }

  @Test fun `a chain drawn with another way off its top is held by that way's gc root`() {
    val path = chain(1L to ON_THE_WAY, 5L to DOMINATES)
    val detour = path.detours().single()
    val other = RootPathWay(
      gcRootLabel = "GC root: thread object",
      steps = listOf(RootPathStep(pathStep(2L), ON_THE_WAY), RootPathStep(pathStep(5L), DOMINATES))
    )

    val drawn = path.drawnWith(path.detours()) { other }

    assertThat(drawn.path.steps.objectIds()).containsExactly(2L, 5L)
    assertThat(drawn.path.gcRootLabel).isEqualTo("GC root: thread object")
  }

  @Test fun `a chain drawn with its own ways is the chain itself`() {
    val path = chain(1L to DOMINATES, 3L to ON_THE_WAY, 5L to DOMINATES, 7L to ON_THE_WAY)

    val drawn = path.drawnWith(path.detours()) { null }

    assertThat(drawn.path).isEqualTo(path)
  }

  /** One way the search found, as it hands them back: no GC root below an object, and nothing marked. */
  private fun foundPath(vararg objectIds: Long) = IndependentPath(
    gcRootLabel = null,
    steps = objectIds.map { pathStep(it) }
  )
}
