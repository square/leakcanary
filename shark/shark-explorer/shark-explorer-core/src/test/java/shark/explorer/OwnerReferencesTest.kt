package shark.explorer

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ValueHolder
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.ReferenceHolder
import shark.dump
import shark.explorer.ReachabilityStrength.STRONG

class OwnerReferencesTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `a view of a hierarchy is held by its parent rather than by what points at it`() {
    HeapExplorer.open(viewHierarchyHeapDump()).use { explorer ->
      val tree = explorer.tree
      val decor = tree.findByLabel("DecorView")
      val leaf = tree.findByLabel("LeafView")

      // A ViewRootImpl and an InputMethodManager both point at a view they don't hold, and both are one
      // step from a GC root while the activity is three. Counting those as ways of holding a view is what
      // scatters a window's hierarchy across whatever happens to be closest to a root.
      assertThat(tree.dominatorOf(decor.objectId)!!.label).isEqualTo("Activity")
      assertThat(tree.independentPathsTo(decor.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("mDecor → DecorView"))
      assertThat(tree.dominatorOf(leaf.objectId)!!.label).isEqualTo("View[]")
      assertThat(tree.independentPathsTo(leaf.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("0 → LeafView"))
    }
  }

  @Test fun `a view no parent is left to hold is held by what points at it after all`() {
    HeapExplorer.open(viewHierarchyHeapDump()).use { explorer ->
      val tree = explorer.tree
      val detached = tree.findByLabel("DetachedRoot")
      val detachedChild = tree.findByLabel("DetachedChild")

      // Nothing that owns this one is left: it's the root of a hierarchy that was taken off its window,
      // so the reference the ownership rule waits on never turns up and the rival is how it's held. Which
      // is a leak, and dropping the rival outright rather than waiting on it would have made a reachable
      // view read as garbage and hidden it.
      assertThat(detached.strength).isEqualTo(STRONG)
      assertThat(tree.dominatorOf(detached.objectId)!!.label).isEqualTo("InputMethodManager")
      assertThat(tree.independentPathsTo(detached.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("mNextServedView → DetachedRoot"))
      // And the hierarchy under it still nests inside it, because its own children do have a parent.
      assertThat(tree.dominatorOf(detachedChild.objectId)!!.label).isEqualTo("View[]")
    }
  }

  @Test fun `the tree still retains every byte when a reference loses to an owner`() {
    HeapExplorer.open(viewHierarchyHeapDump()).use { explorer ->
      // The rule takes edges out of the graph the tree is built from, so this is what says it takes none
      // of the objects out: every one of them still hangs somewhere under the root.
      assertThat(explorer.tree.weight(explorer.tree.root)).isEqualTo(explorer.sizes.totalByteCount)
      assertThat(explorer.sizes.reachableByteCount + explorer.sizes.unreachableByteCount)
        .isEqualTo(explorer.sizes.totalByteCount)
    }
  }
  /**
   * A heap dump shaped like the view hierarchies of a running app: an activity holding its decor view,
   * the decor view holding a child through the array a `ViewGroup` keeps its children in, and two things
   * pointing at views they don't hold — a `ViewRootImpl` and an `InputMethodManager`, each a GC root of
   * its own so that both are closer to a root than the activity is.
   *
   * Plus a hierarchy that was taken off its window and is only still in memory because the input method
   * manager kept a reference into it, which is the shape of a real view leak.
   */
  private fun viewHierarchyHeapDump(): File {
    val file = testFolder.newFile("view-hierarchy.hprof")
    file.dump {
      // The fields android.view.View really declares, because the object inspectors read them to tell an
      // attached view from a detached one, and a view without them is a view they can't describe.
      val viewClassId = clazz(
        className = "android.view.View",
        fields = listOf(
          "mParent" to ReferenceHolder::class,
          "mWindowAttachCount" to IntHolder::class,
          "mAttachInfo" to ReferenceHolder::class,
          "mContext" to ReferenceHolder::class
        )
      )
      val viewGroupClassId = clazz(
        className = "android.view.ViewGroup",
        superclassId = viewClassId,
        fields = listOf("mChildren" to ReferenceHolder::class)
      )
      val viewArrayClassId = arrayClass("android.view.View")
      val leafClassId = clazz(
        className = "com.example.LeafView",
        superclassId = viewClassId,
        fields = listOf("payload" to ReferenceHolder::class)
      )
      val decorClassId = clazz(
        className = "com.android.internal.policy.DecorView",
        superclassId = viewGroupClassId
      )
      val activityClassId = clazz(
        className = "android.app.Activity",
        fields = listOf(
          "mDecor" to ReferenceHolder::class,
          "mDestroyed" to BooleanHolder::class
        )
      )
      // Views point back at their parent, at their hierarchy's root and at their activity, so their ids
      // have to exist before the objects they point at are written.
      val decorId = reserveObjectId()
      val activityId = reserveObjectId()
      val detachedRootId = reserveObjectId()
      val attachInfo = "android.view.View\$AttachInfo" instance { field["mRootView"] = decorId }
      val payload = ReferenceHolder(
        objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))
      )
      val leaf = instance(
        leafClassId,
        listOf(payload, decorId, IntHolder(1), attachInfo, activityId)
      )
      val decor = instance(
        decorClassId,
        listOf(
          ReferenceHolder(objectArray(viewArrayClassId, longArrayOf(leaf.value))),
          NO_REFERENCE,
          IntHolder(1),
          attachInfo,
          activityId
        ),
        objectId = decorId
      )
      // The hierarchy nothing but the input method manager reaches: detached, so no attach info, and with
      // a child of its own to show that a hierarchy losing its parent doesn't cost its views theirs.
      val detachedChild = instance(
        clazz(className = "com.example.DetachedChild", superclassId = viewClassId),
        listOf(detachedRootId, IntHolder(1), NO_REFERENCE, activityId)
      )
      instance(
        clazz(className = "com.example.DetachedRoot", superclassId = viewGroupClassId),
        listOf(
          ReferenceHolder(objectArray(viewArrayClassId, longArrayOf(detachedChild.value))),
          NO_REFERENCE,
          IntHolder(1),
          NO_REFERENCE,
          activityId
        ),
        objectId = detachedRootId
      )
      instance(activityClassId, listOf(decor, BooleanHolder(false)), objectId = activityId)
      val fallbackHandler = "com.android.internal.policy.PhoneFallbackEventHandler" instance {
        field["mView"] = decor
      }
      val inputMethodManager = "android.view.inputmethod.InputMethodManager" instance {
        field["mServedView"] = leaf
        field["mNextServedView"] = detachedRootId
      }
      gcRoot(JniGlobal(id = activityId.value, jniGlobalRefId = 0))
      gcRoot(JniGlobal(id = fallbackHandler.value, jniGlobalRefId = 1))
      gcRoot(JniGlobal(id = inputMethodManager.value, jniGlobalRefId = 2))
    }
    return file
  }

  companion object {
    private const val PAYLOAD_ELEMENT_COUNT = 1024

    /** A field holding nothing, which is what a detached view's attach info and a root view's parent is. */
    private val NO_REFERENCE = ReferenceHolder(ValueHolder.NULL_REFERENCE)
  }
}
