package shark.explorer

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.GcRoot.JniGlobal
import shark.ReferenceLocationType.ARRAY_ENTRY
import shark.ReferenceLocationType.INSTANCE_FIELD
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
      //
      // The window rather than the activity, whose mDecor points at this decor view as well: two owners
      // would put it under whatever dominates both, and a window is the one that always has it.
      assertThat(tree.dominatorOf(decor.objectId)!!.label).isEqualTo("PhoneWindow")
      assertThat(tree.independentPathsBelowDominator(decor.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("mDecor → DecorView"))
      // And the window under the activity it is the window of, so the hierarchy still reads under the screen
      // it belongs to — one step further down than it used to.
      assertThat(tree.dominatorOf(tree.findByLabel("PhoneWindow").objectId)!!.label)
        .isEqualTo("MainActivity")
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

  @Test fun `the window of a screen the framework is done with is held by whatever leaks it`() {
    HeapExplorer.open(destroyedActivityWindowHeapDump()).use { explorer ->
      val tree = explorer.tree

      // A destroyed activity still points at its window — mWindow is set in attach and never cleared, unlike
      // the mDecor this rule used to be about — and nothing else here does, so the window lands under it all
      // the same. What the rule saves is the case below, where something else points at the window too.
      assertThat(tree.dominatorOf(tree.findByLabel("PhoneWindow").objectId)!!.label)
        .isEqualTo("MainActivity")
      assertThat(tree.dominatorOf(tree.findByLabel("MainActivity").objectId)!!.label)
        .isEqualTo("Leaker")
      // And the hierarchy under the window nests as it always did, each layer under its own parent, with the
      // bytes at the bottom counted all the way up. Declaring a view owned when nothing owns it used to cost
      // exactly this: every rival counted, and a window's hierarchy was drawn as a flat pile under whatever
      // dominated all of them.
      assertThat(tree.dominatorOf(tree.findByLabel("DecorView").objectId)!!.label)
        .isEqualTo("PhoneWindow")
      assertThat(tree.dominatorOf(tree.findByLabel("ContentFrame").objectId)!!.label)
        .isEqualTo("DecorView")
      assertThat(tree.dominatorOf(tree.findByLabel("ContentView").objectId)!!.label)
        .isEqualTo("ContentFrame")
      assertThat(tree.weight(tree.findByLabel("PhoneWindow").objectId))
        .isGreaterThan(tree.weight(tree.findByLabel("ContentView").objectId))
    }
  }

  @Test fun `a dialog that is up holds its window and one that has been dismissed doesn't`() {
    HeapExplorer.open(dialogWindowsHeapDump()).use { explorer ->
      val tree = explorer.tree

      // A dialog's window is final, so the field that says whether the dialog is still up is its own mDecor:
      // show sets it, dismiss nulls it. Which is the field this rule used to be about, and the one place it
      // was a reliable signal.
      assertThat(tree.dominatorOf(tree.findByLabel("ShowingDialogWindow").objectId)!!.label)
        .isEqualTo("ShowingDialog")
      // Nothing owns the dismissed one's window, so the jank monitor holding it counts and the tree says
      // there are two ways to it rather than attributing it to the dialog that is done with it. Which is the
      // whole point: that other reference is the reason the window is still in memory, and a rule that owned
      // it anyway would have drawn it as the dialog's own bytes and hidden the one thing to fix.
      val dismissed = tree.findByLabel("DismissedDialogWindow").objectId
      assertThat(tree.independentPathsBelowDominator(dismissed).paths.map { it.stepLabels() })
        .containsExactlyInAnyOrder(
          listOf("JankMonitor", "dismissedWindow → DismissedDialogWindow"),
          listOf("DismissedDialog", "mWindow → DismissedDialogWindow")
        )
    }
  }

  @Test fun `an activity the app is running holds its window and one it has let go of doesn't`() {
    HeapExplorer.open(activityWindowsHeapDump()).use { explorer ->
      val tree = explorer.tree

      // An activity's window is never taken off it, so what says whether the screen is still up is the
      // record in ActivityThread.mActivities, which handleDestroyActivity removes. The activity that still
      // has one owns its window, and the jank monitor pointing at it as well costs it nothing.
      assertThat(tree.dominatorOf(tree.findByLabel("RunningActivityWindow").objectId)!!.label)
        .isEqualTo("RunningActivity")
      // Nothing owns the window of the activity the framework has finished with, so the tree says there are
      // two ways to it rather than drawing it as the bytes of an activity that has nothing left to do with
      // it — and one of those two ways is why it is still in memory.
      val destroyed = tree.findByLabel("DestroyedActivityWindow").objectId
      assertThat(tree.independentPathsBelowDominator(destroyed).paths.map { it.stepLabels() })
        .containsExactlyInAnyOrder(
          listOf("JankMonitor", "destroyedWindow → DestroyedActivityWindow"),
          listOf("Leaker", "activity → DestroyedActivity", "mWindow → DestroyedActivityWindow")
        )
    }
  }

  @Test fun `an activity the framework hasn't destroyed is held by the thread running it`() {
    HeapExplorer.open(viewHierarchyHeapDump()).use { explorer ->
      val tree = explorer.tree
      val activity = tree.findByLabel("MainActivity")

      // Something else holding a live activity is holding what the framework is running, so the thread
      // running it is where its bytes belong. Straight from the thread rather than through the ArrayMap it
      // keeps its activities in — see [RunningActivityReferenceReader] — so the map, its array and the
      // record are no steps on the way down to a screen, and the one step there is names the thread's own
      // class.
      assertThat(tree.dominatorOf(activity.objectId)!!.label).isEqualTo("ActivityThread")
      assertThat(tree.independentPathsBelowDominator(activity.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("activities → MainActivity"))
      val activityReference = tree.independentPathsBelowDominator(activity.objectId)
        .paths
        .single()
        .steps
        .single()
        .reference!!
      assertThat(activityReference.ownerClassName).isEqualTo("ActivityThread")
      assertThat(activityReference.locationType).isEqualTo(INSTANCE_FIELD)
    }
  }

  @Test fun `an activity in a slot the map doesn't count is not one the app is running`() {
    HeapExplorer.open(viewHierarchyHeapDump()).use { explorer ->
      val tree = explorer.tree
      val uncounted = tree.findByLabel("UncountedActivity")

      // What the thread runs is what mSize covers, not what its array has room for. A pair past the count
      // is an entry the map has let go of or hasn't taken yet, so nothing owns the activity in it and the
      // record that points at it holds it as a last resort.
      assertThat(tree.dominatorOf(uncounted.objectId)!!.label)
        .isEqualTo("ActivityThread\$ActivityClientRecord")
    }
  }

  @Test fun `an activity the framework has destroyed is held by whatever leaks it`() {
    HeapExplorer.open(viewHierarchyHeapDump()).use { explorer ->
      val tree = explorer.tree
      val destroyed = tree.findByLabel("DestroyedActivity")

      // handleDestroyActivity takes the record out of mActivities, so the reference the ownership rule
      // waits on never turns up and the rival is how the activity is held. Which is the leak, and it's the
      // one thing you'd want its bytes drawn under.
      assertThat(destroyed.strength).isEqualTo(STRONG)
      assertThat(tree.dominatorOf(destroyed.objectId)!!.label).isEqualTo("Leaker")
      assertThat(tree.independentPathsBelowDominator(destroyed.objectId).paths.map { it.stepLabels() })
        .containsExactly(listOf("destroyedActivity → DestroyedActivity"))
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
   * through the `ArrayMap` of records it keeps them in, the activity holding its window, the window holding
   * its decor view, the decor view holding a child through the array a `ViewGroup` keeps its children in —
   * plus a view in a slot of that array it doesn't count — and two things pointing at views they don't hold,
   * a `ViewRootImpl` and an `InputMethodManager`, each a GC root of its own so that both are closer to a root
   * than the activity is.
   *
   * Plus the two shapes of something the framework isn't running: a destroyed activity only a leaker keeps
   * in memory, and an activity in a pair of the map past the count it keeps.
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
      val windowClassId = clazz(
        className = "com.android.internal.policy.PhoneWindow",
        // Declared by the internal subclass rather than by android.view.Window, which is where the framework
        // declares it and so what makes this cover a rule that has to find the field without naming the class
        // that has it.
        superclassId = clazz(
          className = "android.view.Window",
          fields = listOf("mDestroyed" to BooleanHolder::class)
        ),
        fields = listOf("mDecor" to ReferenceHolder::class)
      )
      val activityClassId = clazz(
        className = "android.app.Activity",
        fields = listOf(
          "mDecor" to ReferenceHolder::class,
          "mDestroyed" to BooleanHolder::class,
          "mWindow" to ReferenceHolder::class
        )
      )
      // The owner field is declared by android.app.Activity and the instance is a subclass of it, which is
      // how a real dump looks and what makes this cover the class hierarchy walk.
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
      // The window the decor view really hangs off. The activity's own mDecor is left pointing at it too,
      // because the framework sets that field for the first activity of a record and this is what says the
      // decor view is drawn under the window all the same.
      val window = instance(windowClassId, listOf(decor, BooleanHolder(false)))
      instance(mainActivityClassId, listOf(decor, BooleanHolder(false), window), objectId = activityId)
      // The activity the framework has destroyed, and one in a pair of the map past the count it keeps —
      // neither of them something the app is running, reached by two different ways of not being in it.
      val destroyedActivity = instance(
        clazz(className = "com.example.DestroyedActivity", superclassId = activityClassId),
        listOf(NO_REFERENCE, BooleanHolder(true), NO_REFERENCE)
      )
      val uncountedActivity = instance(
        clazz(className = "com.example.UncountedActivity", superclassId = activityClassId),
        listOf(NO_REFERENCE, BooleanHolder(false), NO_REFERENCE)
      )
      // What the framework runs each activity from. One class for both records, because the shorthand that
      // declares a class per instance would put two ActivityClientRecord classes in a dump that no real one
      // has.
      val activityRecordClassId = clazz(
        className = "android.app.ActivityThread\$ActivityClientRecord",
        fields = listOf("activity" to ReferenceHolder::class)
      )
      // A token per record, which is what the map is keyed by.
      val tokenClassId = clazz(className = "android.os.BinderProxy")
      val activityThread = "android.app.ActivityThread" instance {
        field["mActivities"] = "android.util.ArrayMap" instance {
          // An ArrayMap holds a key at every even slot and its value at the odd one after it, in an array
          // with room to spare: two pairs of capacity, and mSize says one of them is an entry.
          field["mArray"] = objectArray(
            instance(tokenClassId),
            instance(activityRecordClassId, listOf(activityId)),
            instance(tokenClassId),
            instance(activityRecordClassId, listOf(uncountedActivity))
          )
          field["mSize"] = IntHolder(1)
        }
      }
      // Something outside the framework pointing at the activity it's running, and something pointing at
      // the one it has destroyed, which is a leak.
      val leaker = "com.example.Leaker" instance {
        field["activity"] = activityId
        field["destroyedActivity"] = destroyedActivity
      }
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

  /**
   * A destroyed activity that a leaker keeps in memory, still pointing at its window: three views deep, with
   * something pointing at the middle of the hierarchy the way an app's own field does.
   */
  private fun destroyedActivityWindowHeapDump(): File {
    val file = testFolder.newFile("destroyed-activity-window.hprof")
    file.dump {
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
      val activityClassId = clazz(
        className = "android.app.Activity",
        fields = listOf(
          "mDestroyed" to BooleanHolder::class,
          "mWindow" to ReferenceHolder::class
        )
      )
      val decorId = reserveObjectId()
      val activityId = reserveObjectId()
      val attachInfo = "android.view.View\$AttachInfo" instance { field["mRootView"] = decorId }
      val contentView = instance(
        clazz(
          className = "com.example.ContentView",
          superclassId = viewClassId,
          fields = listOf("payload" to ReferenceHolder::class)
        ),
        listOf(
          ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT))),
          NO_REFERENCE,
          IntHolder(1),
          attachInfo,
          activityId
        )
      )
      val contentFrame = instance(
        clazz(className = "com.example.ContentFrame", superclassId = viewGroupClassId),
        listOf(
          ReferenceHolder(objectArray(viewArrayClassId, longArrayOf(contentView.value))),
          IntHolder(1),
          decorId,
          IntHolder(1),
          attachInfo,
          activityId
        )
      )
      instance(
        clazz(className = "com.android.internal.policy.DecorView", superclassId = viewGroupClassId),
        listOf(
          ReferenceHolder(objectArray(viewArrayClassId, longArrayOf(contentFrame.value))),
          IntHolder(1),
          NO_REFERENCE,
          IntHolder(1),
          attachInfo,
          activityId
        ),
        objectId = decorId
      )
      // The window holding the decor view, and the destroyed activity still pointing at the window.
      val window = instance(
        clazz(
          className = "com.android.internal.policy.PhoneWindow",
          superclassId = clazz(
            className = "android.view.Window",
            fields = listOf("mDestroyed" to BooleanHolder::class)
          ),
          fields = listOf("mDecor" to ReferenceHolder::class)
        ),
        listOf(decorId, BooleanHolder(true))
      )
      instance(
        clazz(className = "com.example.MainActivity", superclassId = activityClassId),
        listOf(BooleanHolder(true), window),
        objectId = activityId
      )
      // What keeps the destroyed screen in memory, plus a rival into the middle of the hierarchy, one step
      // from a GC root while the decor view is three.
      val leaker = "com.example.Leaker" instance {
        field["activity"] = activityId
        field["frame"] = contentFrame
      }
      gcRoot(JniGlobal(id = leaker.value, jniGlobalRefId = 0))
    }
    return file
  }

  /**
   * Two dialogs, each holding a window of its own, one of them dismissed — and a jank monitor holding both
   * windows from a GC root of its own, which is what makes where each of them lands a question.
   */
  private fun dialogWindowsHeapDump(): File {
    val file = testFolder.newFile("dialog-windows.hprof")
    file.dump {
      val windowClassId = clazz(
        className = "android.view.Window",
        // mDestroyed because the object inspector for a window reads it and would throw without it. False
        // for both: the framework only sets it on the window of an activity it destroys.
        fields = listOf(
          "mDestroyed" to BooleanHolder::class,
          "payload" to ReferenceHolder::class
        )
      )
      val dialogClassId = clazz(
        className = "android.app.Dialog",
        fields = listOf(
          "mDecor" to ReferenceHolder::class,
          "mWindow" to ReferenceHolder::class
        )
      )
      // Named for what each of them is, since a window is told from another by its label in the tree.
      val showingWindow = instance(
        clazz(className = "com.example.ShowingDialogWindow", superclassId = windowClassId),
        listOf(
          BooleanHolder(false),
          ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT)))
        )
      )
      val dismissedWindow = instance(
        clazz(className = "com.example.DismissedDialogWindow", superclassId = windowClassId),
        listOf(
          BooleanHolder(false),
          ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT)))
        )
      )
      // Dialog.show reads the decor view off the window and keeps it; Dialog.dismiss puts the field back to
      // null and leaves mWindow where it was.
      val showingDialog = instance(
        clazz(className = "com.example.ShowingDialog", superclassId = dialogClassId),
        listOf(
          instance(clazz(className = "com.android.internal.policy.DecorView")),
          showingWindow
        )
      )
      val dismissedDialog = instance(
        clazz(className = "com.example.DismissedDialog", superclassId = dialogClassId),
        listOf(NO_REFERENCE, dismissedWindow)
      )
      val jankMonitor = "com.example.JankMonitor" instance {
        field["showingWindow"] = showingWindow
        field["dismissedWindow"] = dismissedWindow
      }
      gcRoot(JniGlobal(id = showingDialog.value, jniGlobalRefId = 0))
      gcRoot(JniGlobal(id = dismissedDialog.value, jniGlobalRefId = 1))
      gcRoot(JniGlobal(id = jankMonitor.value, jniGlobalRefId = 2))
    }
    return file
  }

  /**
   * Two activities, each holding a window of its own, one of them gone from the `ActivityThread` running the
   * app — and a jank monitor holding both windows from a GC root of its own, which is what makes where each
   * of them lands a question.
   */
  private fun activityWindowsHeapDump(): File {
    val file = testFolder.newFile("activity-windows.hprof")
    file.dump {
      val windowClassId = clazz(
        className = "android.view.Window",
        // mDestroyed because the object inspector for a window reads it and would throw without it.
        fields = listOf(
          "mDestroyed" to BooleanHolder::class,
          "payload" to ReferenceHolder::class
        )
      )
      val activityClassId = clazz(
        className = "android.app.Activity",
        fields = listOf(
          "mDestroyed" to BooleanHolder::class,
          "mWindow" to ReferenceHolder::class
        )
      )
      // Named for what each of them is, since a window is told from another by its label in the tree.
      val runningWindow = instance(
        clazz(className = "com.example.RunningActivityWindow", superclassId = windowClassId),
        listOf(
          BooleanHolder(false),
          ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT)))
        )
      )
      val destroyedWindow = instance(
        clazz(className = "com.example.DestroyedActivityWindow", superclassId = windowClassId),
        listOf(
          BooleanHolder(true),
          ReferenceHolder(objectArray(arrayClass("java.lang.Object"), LongArray(PAYLOAD_ELEMENT_COUNT)))
        )
      )
      // Activity.mWindow is set in attach and never cleared, so both of them point at their window and the
      // difference between the two is only in what the thread below still runs.
      val runningActivity = instance(
        clazz(className = "com.example.RunningActivity", superclassId = activityClassId),
        listOf(BooleanHolder(false), runningWindow)
      )
      val destroyedActivity = instance(
        clazz(className = "com.example.DestroyedActivity", superclassId = activityClassId),
        listOf(BooleanHolder(true), destroyedWindow)
      )
      val activityThread = "android.app.ActivityThread" instance {
        field["mActivities"] = "android.util.ArrayMap" instance {
          // A key at the even slot and its record at the odd one after it, for the one activity left.
          field["mArray"] = objectArray(
            instance(clazz(className = "android.os.BinderProxy")),
            instance(
              clazz(
                className = "android.app.ActivityThread\$ActivityClientRecord",
                fields = listOf("activity" to ReferenceHolder::class)
              ),
              listOf(runningActivity)
            )
          )
          field["mSize"] = IntHolder(1)
        }
      }
      val jankMonitor = "com.example.JankMonitor" instance {
        field["runningWindow"] = runningWindow
        field["destroyedWindow"] = destroyedWindow
      }
      // What keeps the destroyed screen in memory, the thread being what keeps the other one.
      val leaker = "com.example.Leaker" instance { field["activity"] = destroyedActivity }
      gcRoot(JniGlobal(id = activityThread.value, jniGlobalRefId = 0))
      gcRoot(JniGlobal(id = leaker.value, jniGlobalRefId = 1))
      gcRoot(JniGlobal(id = jankMonitor.value, jniGlobalRefId = 2))
    }
    return file
  }

  companion object {
    private const val PAYLOAD_ELEMENT_COUNT = 1024

    /** A field holding nothing, which is what a detached view's attach info and a root view's parent is. */
    private val NO_REFERENCE = ReferenceHolder(ValueHolder.NULL_REFERENCE)
  }
}
