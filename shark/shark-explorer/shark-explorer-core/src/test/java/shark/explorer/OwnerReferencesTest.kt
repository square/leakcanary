package shark.explorer

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ReferenceLocationType.ARRAY_ENTRY
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

      // A fallback event handler and an InputMethodManager both point at a view they don't hold, and both
      // are one step from a GC root while the activity is two. Counting those as ways of holding a view is
      // what scatters a window's hierarchy across whatever happens to be closest to a root.
      assertThat(tree.dominatorOf(decor.objectId)!!.label).isEqualTo("MainActivity")
      assertThat(tree.independentPathsBelowDominator(decor.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("mDecor → DecorView"))
      // Its parent, not the View[] the parent keeps it in: the parent points at each of its children
      // itself — see [ViewChildReferenceReader] — so the array is no step on the way down to a view, and
      // the one step there is names the child's index on the parent's own class.
      assertThat(tree.dominatorOf(leaf.objectId)!!.label).isEqualTo("DecorView")
      assertThat(tree.independentPathsBelowDominator(leaf.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("0 → LeafView"))
      val leafReference = tree.independentPathsBelowDominator(leaf.objectId)
        .paths
        .single()
        .steps
        .single()
        .reference!!
      assertThat(leafReference.ownerClassName).isEqualTo("DecorView")
      assertThat(leafReference.locationType).isEqualTo(ARRAY_ENTRY)
    }
  }

  @Test fun `a view in a slot its parent doesn't count is not one of its children`() {
    HeapExplorer.open(viewHierarchyHeapDump()).use { explorer ->
      val tree = explorer.tree
      val uncounted = tree.findByLabel("UncountedView")

      // What a parent holds is what mChildrenCount covers, not what its array has room for. A slot past
      // the count is a view the parent has let go of or hasn't taken yet, and calling it a child would
      // attribute a removed view to the parent that removed it — which is the leak you'd be looking for.
      // So nothing owns this one, and the array that points at it holds it as a last resort.
      assertThat(tree.dominatorOf(uncounted.objectId)!!.label).isEqualTo("View[]")
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
      assertThat(tree.independentPathsBelowDominator(detached.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("mNextServedView → DetachedRoot"))
      // And the hierarchy under it still nests inside it, because its own children do have a parent.
      assertThat(tree.dominatorOf(detachedChild.objectId)!!.label).isEqualTo("DetachedRoot")
    }
  }

  @Test fun `an activity the framework hasn't destroyed is held by the framework`() {
    HeapExplorer.open(viewHierarchyHeapDump()).use { explorer ->
      val tree = explorer.tree
      val activity = tree.findByLabel("MainActivity")

      // Something else holding a live activity is holding what the framework is running, so the thread
      // running it is where its bytes belong. Once the framework destroys it the record is out of
      // mActivities, and then the thing still pointing at it is both its owner and its leak.
      //
      // The ActivityThread, not the ActivityClientRecord the map keeps it in: the thread points at each
      // activity itself — see [ActivityThreadReferenceReader] — so the map, its array and the record are no
      // step on the way down to an activity, and the one step there is names the field on the thread.
      assertThat(tree.dominatorOf(activity.objectId)!!.label).isEqualTo("ActivityThread")
      assertThat(tree.independentPathsBelowDominator(activity.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("mActivities → MainActivity"))
    }
  }

  @Test fun `an activity in a slot the map doesn't count is not one the process is running`() {
    HeapExplorer.open(viewHierarchyHeapDump()).use { explorer ->
      val tree = explorer.tree
      val stale = tree.findByLabel("StaleActivity")

      // What the thread runs is what mSize covers, not what its map has room for. An ArrayMap leaves the
      // slots it gives up for the next put rather than nulling them, so past the count is where the record
      // of a destroyed activity is still written down — and calling it a running activity attributes a
      // leaked screen to the framework, which is the opposite of what you'd want to read.
      assertThat(tree.dominatorOf(stale.objectId)!!.label).isEqualTo("ActivityThread\$ActivityClientRecord")
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
   * A heap dump shaped like the view hierarchies of a running app: an `ActivityThread` running an activity
   * through the `ArrayMap` of client records it keeps them in, the activity holding its decor view, the
   * decor view holding a child through the array a `ViewGroup` keeps its children in — plus a view in a
   * slot of that array it doesn't count — and two things pointing at views they don't hold, a
   * `ViewRootImpl` and an `InputMethodManager`, each a GC root of its own so that both are closer to a
   * root than the activity is.
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
        fields = listOf(
          "mChildren" to ReferenceHolder::class,
          "mChildrenCount" to IntHolder::class
        )
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
      // The owner field is declared by android.app.Activity and the instance is a subclass of it, which is
      // how a real dump looks and what makes this cover the class hierarchy walk.
      val activityClassId = clazz(
        className = "android.app.Activity",
        fields = listOf(
          "mDecor" to ReferenceHolder::class,
          "mDestroyed" to BooleanHolder::class
        )
      )
      val mainActivityClassId = clazz(
        className = "com.example.MainActivity",
        superclassId = activityClassId
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
      // A view in a slot of the array the parent's mChildrenCount doesn't cover, which is what a heap dump
      // records when it suspends addViewInner between filling the slot and counting it.
      val uncounted = instance(
        clazz(className = "com.example.UncountedView", superclassId = viewClassId),
        listOf(decorId, IntHolder(1), attachInfo, activityId)
      )
      val decor = instance(
        decorClassId,
        listOf(
          // mChildren holds two views; mChildrenCount says one of them is a child.
          ReferenceHolder(objectArray(viewArrayClassId, longArrayOf(leaf.value, uncounted.value))),
          IntHolder(1),
          // Then what android.view.View declares: mParent, mWindowAttachCount, mAttachInfo, mContext.
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
          IntHolder(1),
          NO_REFERENCE,
          IntHolder(1),
          NO_REFERENCE,
          activityId
        ),
        objectId = detachedRootId
      )
      instance(mainActivityClassId, listOf(decor, BooleanHolder(false)), objectId = activityId)
      // What the framework runs the activity from: an ArrayMap of binder token to client record, keys at the
      // even slots and records at the odd ones. Written out the way a real dump has it, because the
      // reference the ownership rule waits on is the one the explorer reads through all of it.
      //
      // One class declaration for both records rather than the `"a.b.C" instance { }` shorthand, which
      // declares a class per instance and would put two ActivityClientRecord classes in a dump that has one.
      val activityRecordClassId = clazz(
        className = "android.app.ActivityThread\$ActivityClientRecord",
        fields = listOf("activity" to ReferenceHolder::class)
      )
      val binderTokenClassId = clazz(className = "android.os.BinderProxy")
      val activityRecord = instance(activityRecordClassId, listOf(ReferenceHolder(activityId.value)))
      // A record in a slot past mSize, holding the activity the framework has already destroyed — which is
      // what an ArrayMap leaves behind, since it keeps the slots it gives up for the next put.
      val staleRecord = instance(
        activityRecordClassId,
        listOf(
          instance(
            clazz(className = "com.example.StaleActivity", superclassId = activityClassId),
            listOf(NO_REFERENCE, BooleanHolder(true))
          )
        )
      )
      val activityThread = "android.app.ActivityThread" instance {
        field["mActivities"] = "android.util.ArrayMap" instance {
          field["mArray"] = ReferenceHolder(
            objectArray(
              arrayClass("java.lang.Object"),
              longArrayOf(
                instance(binderTokenClassId).value,
                activityRecord.value,
                instance(binderTokenClassId).value,
                staleRecord.value
              )
            )
          )
          field["mSize"] = IntHolder(1)
        }
      }
      val leaker = "com.example.Leaker" instance { field["activity"] = activityId }
      val fallbackHandler = "com.android.internal.policy.PhoneFallbackEventHandler" instance {
        field["mView"] = decor
      }
      val inputMethodManager = "android.view.inputmethod.InputMethodManager" instance {
        field["mServedView"] = leaf
        field["mNextServedView"] = detachedRootId
      }
      gcRoot(JniGlobal(id = activityThread.value, jniGlobalRefId = 0))
      gcRoot(JniGlobal(id = leaker.value, jniGlobalRefId = 1))
      gcRoot(JniGlobal(id = fallbackHandler.value, jniGlobalRefId = 2))
      gcRoot(JniGlobal(id = inputMethodManager.value, jniGlobalRefId = 3))
    }
    return file
  }

  companion object {
    private const val PAYLOAD_ELEMENT_COUNT = 1024

    /** A field holding nothing, which is what a detached view's attach info and a root view's parent is. */
    private val NO_REFERENCE = ReferenceHolder(ValueHolder.NULL_REFERENCE)
  }
}
