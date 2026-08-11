package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.LeakKind.APPLICATION
import shark.explorer.LeakKind.FINALIZER
import shark.explorer.LeakKind.LIBRARY
import shark.explorer.LeakKind.PHANTOM
import shark.explorer.LeakKind.SOFT
import shark.explorer.LeakKind.UNREACHABLE
import shark.explorer.LeakKind.WEAK

class HeapLeaksTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `an object the app said should be gone is a leak`() {
    HeapExplorer.open(testFolder.watchedLeakHeapDump()).use { explorer ->
      val leaks = explorer.tree.findLeaks()

      val leaking = leaks.objectsOf(APPLICATION).single()
      assertThat(leaking.className).isEqualTo(WATCHED_CLASS_NAME)
    }
  }

  @Test fun `a watched object comes with what the watcher wrote down about it`() {
    HeapExplorer.open(testFolder.watchedLeakHeapDump()).use { explorer ->
      val watcher = explorer.tree.findLeaks().objectsOf(APPLICATION).single().watcher!!

      // The durations the DSL writes: watched 25 seconds before the dump, retained for the last 10 of them.
      assertThat(watcher.key).isNotEmpty()
      assertThat(watcher.description).isEqualTo("its lifecycle has ended")
      assertThat(watcher.watchDurationMillis).isEqualTo(25_000L)
      assertThat(watcher.retainedDurationMillis).isEqualTo(10_000L)
      assertThat(watcher.isRetained).isTrue()
    }
  }

  @Test fun `the weak reference a watched object came from is an object of the tree`() {
    HeapExplorer.open(testFolder.watchedLeakHeapDump()).use { explorer ->
      val tree = explorer.tree
      val watcher = tree.findLeaks().objectsOf(APPLICATION).single().watcher!!

      // Which is what makes the row about it clickable: it opens like any other object.
      assertThat(tree.summarize(watcher.weakReferenceObjectId).className)
        .isEqualTo("leakcanary.KeyedWeakReference")
    }
  }

  @Test fun `an object the inspectors recognize is a leak with nothing watching it`() {
    HeapExplorer.open(testFolder.destroyedActivitiesHeapDump()).use { explorer ->
      val leaking = explorer.tree.findLeaks().objectsOf(APPLICATION).first()

      assertThat(leaking.className).isEqualTo(ACTIVITY_CLASS_NAME)
      assertThat(leaking.watcher).isNull()
      assertThat(leaking.leakingReason).contains("mDestroyed")
    }
  }

  @Test fun `objects leaking the same way are one leak with both of them in it`() {
    HeapExplorer.open(testFolder.destroyedActivitiesHeapDump()).use { explorer ->
      val leaks = explorer.tree.findLeaks()

      // The third activity of that dump is not destroyed, so it is not one of these.
      val group = leaks.sectionOf(APPLICATION).groups.single()
      assertThat(group.objects).hasSize(2)
      assertThat(leaks.objectCount).isEqualTo(2)
    }
  }

  @Test fun `a leak is named after the reference that shouldn't be holding`() {
    HeapExplorer.open(testFolder.destroyedActivitiesHeapDump()).use { explorer ->
      val group = explorer.tree.findLeaks().sectionOf(APPLICATION).groups.single()

      // Which is the leak itself. The two activities under it are what it left behind, and each of those
      // rows says what it is, so the row above them is free to say why they are still there.
      assertThat(group.title).isEqualTo("Holder.activity")
    }
  }

  @Test fun `a leak is the whole stretch of references, not only the one it is named after`() {
    val heapDump = testFolder.leakTwoLeaksHoldHeapDump()
    HeapExplorer.open(heapDump.file).use { explorer ->
      val group = explorer.tree.findLeaks().sectionOf(APPLICATION).groups
        .single { it.title == "Further.holder" }

      // The name is the first of them, which is the reference that shouldn't be holding and is what
      // LeakCanary calls the leak. The last is where on the chain to find what it left behind, and the two
      // are different references as soon as the stretch is longer than one.
      assertThat(group.suspectPath).containsExactly("Further.holder", "Holder.activity")
    }
  }

  @Test fun `objects in two slots of one array are instances of one leak`() {
    HeapExplorer.open(testFolder.leaksInOneArrayHeapDump()).use { explorer ->
      val group = explorer.tree.findLeaks().sectionOf(APPLICATION).groups.single()

      // Which slot an object landed in changes from one heap dump of an app to the next, so a leak that
      // was named after one would be a different leak in every dump. LeakCanary erases them for the same
      // reason, and this is what makes its report and this list line up.
      assertThat(group.title).isEqualTo("$HOLDER_SIMPLE_CLASS_NAME.$LEAK_ARRAY_FIELD_NAME")
      assertThat(group.objects.filter { it.className == ACTIVITY_CLASS_NAME }).hasSize(2)
    }
  }

  @Test fun `objects of two classes held the same way are instances of one leak`() {
    HeapExplorer.open(testFolder.leaksInOneArrayHeapDump()).use { explorer ->
      val group = explorer.tree.findLeaks().sectionOf(APPLICATION).groups.single()

      // A leak is the reference that shouldn't be holding rather than the class of what it holds: letting
      // go of that array is the one thing to do, and it is not two things because a window is in it too.
      assertThat(group.objects.map { it.className })
        .containsExactlyInAnyOrder(ACTIVITY_CLASS_NAME, ACTIVITY_CLASS_NAME, WINDOW_CLASS_NAME)
    }
  }

  @Test fun `a leak nothing reaches any more is listed as already collected`() {
    HeapExplorer.open(testFolder.collectedActivityHeapDump()).use { explorer ->
      val leaks = explorer.tree.findLeaks()

      val leaking = leaks.objectsOf(UNREACHABLE).single()
      assertThat(leaking.className).isEqualTo(ACTIVITY_CLASS_NAME)
      assertThat(leaking.strength).isEqualTo(ReachabilityStrength.UNREACHABLE)
      assertThat(leaks.objectsOf(APPLICATION)).isEmpty()
    }
  }

  @Test fun `an object held only by a cleaner is listed as phantom reachable`() {
    HeapExplorer.open(testFolder.cleanerHeldActivityHeapDump()).use { explorer ->
      val leaks = explorer.tree.findLeaks()

      // Destroyed, so the inspectors say it shouldn't be here, and phantom reachable, so it is on its way
      // out and none of the app's business. Which is a section of its own rather than an app leak.
      val leaking = leaks.objectsOf(PHANTOM).single()
      assertThat(leaking.className).isEqualTo(ACTIVITY_CLASS_NAME)
      assertThat(leaking.strength).isEqualTo(ReachabilityStrength.PHANTOM)
      assertThat(leaks.objectsOf(APPLICATION)).isEmpty()
    }
  }

  @Test fun `a leak on its way out is named after its class rather than after a cleaner list`() {
    HeapExplorer.open(testFolder.cleanerHeldActivityHeapDump()).use { explorer ->
      val group = explorer.tree.findLeaks().sectionOf(PHANTOM).groups.single()

      // What holds it is a Cleaner on a static list, which is where it lives rather than why it is still
      // here, so there is no chain to name it after and the section says what being in it means.
      assertThat(group.title).isEqualTo(ACTIVITY_CLASS_NAME.substringAfterLast('.'))
      assertThat(group.suspectPath).isEmpty()
      assertThat(group.subtitle).isEqualTo(PHANTOM.subtitle)
    }
  }

  @Test fun `the same class in two of the sections on their way out is two leaks`() {
    HeapExplorer.open(testFolder.cleanerHeldActivityHeapDump()).use { explorer ->
      val phantom = explorer.tree.findLeaks().sectionOf(PHANTOM).groups.single()

      // The fingerprint is what tells one leak from another, and a class name alone would make a phantom
      // reachable activity and an unreachable one the same leak found twice.
      HeapExplorer.open(testFolder.collectedActivityHeapDump()).use { collected ->
        val unreachable = collected.tree.findLeaks().sectionOf(UNREACHABLE).groups.single()

        assertThat(phantom.title).isEqualTo(unreachable.title)
        assertThat(phantom.leakFingerprint).isNotEqualTo(unreachable.leakFingerprint)
      }
    }
  }

  @Test fun `an object on its way out is still on the map`() {
    HeapExplorer.open(testFolder.cleanerHeldActivityHeapDump()).use { explorer ->
      val tree = explorer.tree
      val activity = tree.findByLabel(ACTIVITY_CLASS_NAME.substringAfterLast('.')).objectId

      // Left where it was: which bytes haven't come back yet is what the map is for, and which section of
      // the list an object is in says nothing about where the tree hangs it.
      assertThat(tree.strengthOf(activity)).isEqualTo(ReachabilityStrength.PHANTOM)
      assertThat(tree.dominatorOf(activity)!!.label).isEqualTo("Cleaner")
    }
  }

  @Test fun `a leak held by a reference Shark knows is somebody else's`() {
    HeapExplorer.open(testFolder.libraryLeakHeapDump()).use { explorer ->
      val leaks = explorer.tree.findLeaks()

      val group = leaks.sectionOf(LIBRARY).groups.single()
      assertThat(group.title).contains(TEXT_LINE_CLASS_NAME, TEXT_LINE_CACHE_FIELD_NAME)
      assertThat(group.subtitle).contains("TextLine.sCached is a pool")
      assertThat(group.objects.single().className).isEqualTo(ACTIVITY_CLASS_NAME)
      assertThat(leaks.objectsOf(APPLICATION)).isEmpty()
    }
  }

  @Test fun `a leak is classified by the very chain the explorer draws for it`() {
    HeapExplorer.open(testFolder.libraryLeakHeapDump()).use { explorer ->
      val tree = explorer.tree
      val leaking = tree.findLeaks().objectsOf(LIBRARY).single()

      // A list that sorted leaks by one path and then showed another when a row is clicked would be two
      // answers to one question, so the known reference is matched on the steps of this path and no other.
      val steps = tree.rootPathTo(leaking.objectId).steps.map { it.step }
      assertThat(steps.last().objectId).isEqualTo(leaking.objectId)
      assertThat(steps.mapNotNull { it.reference?.libraryLeak?.pattern })
        .anySatisfy { assertThat(it).contains(TEXT_LINE_CACHE_FIELD_NAME) }
    }
  }

  @Test fun `a chain says which of its objects shouldn't be there, whatever it was built for`() {
    HeapExplorer.open(testFolder.destroyedActivitiesHeapDump()).use { explorer ->
      val tree = explorer.tree
      val leaking = tree.findLeaks().objectsOf(APPLICATION).first()

      val steps = tree.rootPathTo(leaking.objectId).steps.map { it.step }
      assertThat(steps.last().leakStatus).isEqualTo(LeakStatus.LEAKING)
      assertThat(steps.last().leakStatusReason).contains("mDestroyed")
    }
  }

  @Test fun `a leak the chain of another leak runs through is listed under that one`() {
    val heapDump = testFolder.nestedLeaksHeapDump()
    HeapExplorer.open(heapDump.file).use { explorer ->
      val leaking = explorer.tree.findLeaks().objectsOf(APPLICATION)

      // Both the activity and the window it holds are objects that shouldn't be in memory, and there is one
      // thing to fix: let go of the activity and the window goes with it.
      assertThat(leaking.map { it.objectId }).containsExactly(heapDump.activityObjectId)
    }
  }

  @Test fun `a leak held another way as well is a leak of its own, held that other way`() {
    val heapDump = testFolder.leakAlsoHeldAnotherWayHeapDump()
    HeapExplorer.open(heapDump.file).use { explorer ->
      val tree = explorer.tree

      // Something that isn't leaking holds the window, one step further round than the destroyed activity
      // does. So letting go of the activity leaves the window where it is, and the chain says the thing
      // that would still be holding it rather than the shortest thing that is.
      assertThat(tree.rootPathTo(heapDump.windowObjectId).steps.map { it.step.objectId })
        .doesNotContain(heapDump.activityObjectId)
      assertThat(tree.findLeaks().objectsOf(APPLICATION).map { it.objectId })
        .containsExactlyInAnyOrder(heapDump.activityObjectId, heapDump.windowObjectId)
      // And two leaks rather than one, which is what listing them separately is for: they are two things to
      // fix, and grouping goes by the chain, so a chain through the activity would have made them one.
      assertThat(tree.findLeaks().sectionOf(APPLICATION).groups).hasSize(2)
    }
  }

  @Test fun `a leak every way to it runs through a leak is left off the list`() {
    val heapDump = testFolder.leakTwoLeaksHoldHeapDump()
    HeapExplorer.open(heapDump.file).use { explorer ->
      val tree = explorer.tree

      // Two destroyed activities hold this window, so there is no way to it that avoids a leak and the chain
      // runs through the nearer of the two — which doesn't dominate it, since letting go of that one leaves
      // the other one holding it. Neither of them does, and it still goes: the two references that shouldn't
      // be there are both on the list, and fixing both takes the window with them.
      assertThat(tree.rootPathTo(heapDump.windowObjectId).steps.map { it.step.objectId })
        .contains(heapDump.nearerActivityObjectId)
      assertThat(tree.findLeaks().objectsOf(APPLICATION).map { it.objectId })
        .doesNotContain(heapDump.windowObjectId)
        .contains(heapDump.nearerActivityObjectId)
    }
  }

  @Test fun `the leak it was listed under is the one whose chain runs through it`() {
    val heapDump = testFolder.nestedLeaksHeapDump()
    HeapExplorer.open(heapDump.file).use { explorer ->
      val tree = explorer.tree

      // Nothing is lost by leaving it off the list: the chain drawn for the leak that stayed runs through
      // it and says it is leaking, which is that chain being read as a leak trace.
      val steps = tree.rootPathTo(heapDump.windowObjectId).steps.map { it.step }
      assertThat(steps.map { it.objectId }).contains(heapDump.activityObjectId)
      assertThat(steps.last().leakStatus).isEqualTo(LeakStatus.LEAKING)
    }
  }

  @Test fun `two leaks of one kind have two leak fingerprints, and one leak has one`() {
    HeapExplorer.open(testFolder.destroyedActivitiesHeapDump()).use { explorer ->
      val group = explorer.tree.findLeaks().sectionOf(APPLICATION).groups.single()

      // A hash of what makes it that leak, so the two activities of one leak are one fingerprint, and it says
      // nothing about which heap dump this is: 40 characters of hex and no address among them.
      assertThat(group.leakFingerprint).matches("[0-9a-f]{40}")
      assertThat(group.objects).hasSize(2)
    }
  }

  @Test fun `a heap dump with nothing wrong in it has every section and no leak`() {
    testFolder.openTestHeapDump().use { explorer ->
      val leaks = explorer.tree.findLeaks()

      // Every one of them, so that the screen says which kinds of leak were looked for and not found: the
      // two to do something about first, then the ways of being on the way out, weakest last.
      assertThat(leaks.sections.map { it.kind })
        .containsExactly(APPLICATION, LIBRARY, SOFT, WEAK, FINALIZER, PHANTOM, UNREACHABLE)
      assertThat(leaks.objectCount).isZero()
      assertThat(leaks.leakingObjectIds).isEmpty()
    }
  }

  @Test fun `what a leaking object holds is leaking with it`() {
    HeapExplorer.open(testFolder.watchedLeakHeapDump()).use { explorer ->
      val tree = explorer.tree
      val leaking = tree.findLeaks().objectsOf(APPLICATION).single()
      val payload = tree.children(leaking.objectId).single()

      // What the treemap shades by: the payload is only still in memory because the leak is.
      assertThat(tree.isBelowLeakingObject(payload)).isTrue()
      assertThat(tree.isBelowLeakingObject(leaking.objectId)).isFalse()
      assertThat(tree.isBelowLeakingObject(tree.findByLabel("Holder").objectId)).isFalse()
    }
  }

  @Test fun `nothing is below a leak in a heap dump that has none`() {
    testFolder.openTestHeapDump().use { explorer ->
      val tree = explorer.tree

      assertThat(tree.findByLabel("Object[]").objectId).matches { !tree.isBelowLeakingObject(it) }
    }
  }
}

private fun HeapLeaks.sectionOf(kind: LeakKind): LeakSection = sections.single { it.kind == kind }

private fun HeapLeaks.objectsOf(kind: LeakKind): List<LeakingObject> =
  sectionOf(kind).groups.flatMap { it.objects }
