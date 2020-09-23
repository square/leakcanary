package shark.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

@Suppress("UsePropertyAccessSyntax")
class DominatorTreeTest {

  var latestObjectId: Long = 0
  private fun newObjectId() = ++latestObjectId

  @Suppress("PrivatePropertyName")
  private val `10 bytes per object`: (Long) -> Int = { 10 }

  @Test fun `new object is not already dominated`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }

    val alreadyDominated = tree.updateDominated(newObjectId(), root)

    assertThat(alreadyDominated).isFalse()
  }

  @Test fun `dominated object is already dominated`() {
    val tree = DominatorTree()
    val root1 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val root2 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child = newObjectId().apply { tree.updateDominated(this, root1) }

    val alreadyDominated = tree.updateDominated(child, root2)

    assertThat(alreadyDominated).isTrue()
  }

  @Test fun `only retained objects are returned in sizes map`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child = newObjectId().apply { tree.updateDominated(this, root) }

    val sizes = tree.computeRetainedSizes(setOf(child), `10 bytes per object`)

    assertThat(sizes).containsOnlyKeys(child)
  }

  @Test fun `single root has self size as retained size`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }

    val sizes = tree.computeRetainedSizes(setOf(root), `10 bytes per object`)

    assertThat(sizes[root]).isEqualTo(10)
  }

  @Test fun `size of dominator includes dominated`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    tree.updateDominated(newObjectId(), root)

    val sizes = tree.computeRetainedSizes(setOf(root), `10 bytes per object`)

    assertThat(sizes[root]).isEqualTo(20)
  }

  @Test fun `size of chain of dominators is additive`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child = newObjectId().apply { tree.updateDominated(this, root) }
    tree.updateDominated(newObjectId(), child)

    val sizes = tree.computeRetainedSizes(setOf(root, child), `10 bytes per object`)

    assertThat(sizes[root]).isEqualTo(30)
    assertThat(sizes[child]).isEqualTo(20)
  }

  @Test fun `diamond dominators don't dominate`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child1 = newObjectId().apply { tree.updateDominated(this, root) }
    val child2 = newObjectId().apply { tree.updateDominated(this, root) }
    val grandChild = newObjectId()
    tree.updateDominated(grandChild, child1)
    tree.updateDominated(grandChild, child2)

    val sizes = tree.computeRetainedSizes(setOf(root, child1, child2), `10 bytes per object`)

    assertThat(sizes[child1]).isEqualTo(10)
    assertThat(sizes[child2]).isEqualTo(10)
    assertThat(sizes[root]).isEqualTo(40)
  }

  @Test fun `two dominators dominated by common ancestor`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child1 = newObjectId().apply { tree.updateDominated(this, root) }
    val child2 = newObjectId().apply { tree.updateDominated(this, root) }
    val grandChild = newObjectId()
    tree.updateDominated(grandChild, child1)
    tree.updateDominated(grandChild, child2)

    val sizes = tree.computeRetainedSizes(setOf(root, child1, child2), `10 bytes per object`)

    assertThat(sizes[child1]).isEqualTo(10)
    assertThat(sizes[child2]).isEqualTo(10)
    assertThat(sizes[root]).isEqualTo(40)
  }

  @Test fun `two dominators dominated by lowest common ancestor`() {
    val tree = DominatorTree()
    val root = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child = newObjectId().apply { tree.updateDominated(this, root) }
    val grandChild1 = newObjectId().apply { tree.updateDominated(this, child) }
    val grandChild2 = newObjectId().apply { tree.updateDominated(this, child) }
    val grandGrandChild = newObjectId()
    tree.updateDominated(grandGrandChild, grandChild1)
    tree.updateDominated(grandGrandChild, grandChild2)

    val sizes =
      tree.computeRetainedSizes(setOf(root, child, grandChild1, grandChild2), `10 bytes per object`)

    assertThat(sizes[grandChild1]).isEqualTo(10)
    assertThat(sizes[grandChild1]).isEqualTo(10)
    assertThat(sizes[child]).isEqualTo(40)
    assertThat(sizes[root]).isEqualTo(50)
  }

  @Test fun `two separate trees do not share size`() {
    val tree = DominatorTree()
    val root1 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val root2 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    var descendant1 = root1
    var descendant2 = root2
    for (i in 1..10) {
      descendant1 = newObjectId().apply { tree.updateDominated(this, descendant1) }
      descendant2 = newObjectId().apply { tree.updateDominated(this, descendant2) }
    }

    val sizes =
      tree.computeRetainedSizes(setOf(root1, root2), `10 bytes per object`)

    assertThat(sizes[root1]).isEqualTo(110)
    assertThat(sizes[root2]).isEqualTo(110)
  }

  @Test fun `no common descendant does not include size`() {
    val tree = DominatorTree()
    val root1 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val root2 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    var descendant = root1
    for (i in 1..10) {
      descendant = newObjectId().apply { tree.updateDominated(this, descendant) }
    }
    tree.updateDominated(descendant, root2)

    val sizes =
      tree.computeRetainedSizes(setOf(root1, root2), `10 bytes per object`)

    assertThat(sizes[root1]).isEqualTo(100)
    assertThat(sizes[root2]).isEqualTo(10)
  }

  @Test fun `only compute retained size for retained objects`() {
    val tree = DominatorTree()
    val root1 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val root2 = newObjectId().apply { tree.updateDominatedAsRoot(this) }
    val child = newObjectId().apply { tree.updateDominated(this, root1) }
    val grandChild = newObjectId().apply { tree.updateDominated(this, child) }
    val grandGrandChild = newObjectId().apply { tree.updateDominated(this, grandChild) }
    tree.updateDominated(grandGrandChild, root2)

    val objectsWithComputedSize = mutableSetOf<Long>()
    tree.computeRetainedSizes(setOf(child)) { objectId ->
      objectsWithComputedSize += objectId
      1
    }

    assertThat(objectsWithComputedSize).containsOnly(child, grandChild)
  }
}