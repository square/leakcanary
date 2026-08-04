package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.LeakKind.APPLICATION
import shark.explorer.LeakKind.LIBRARY
import shark.explorer.LeakKind.UNREACHABLE

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

  @Test fun `a leak held another way as well is listed even though a leak is on its chain`() {
    val heapDump = testFolder.leakAlsoHeldAnotherWayHeapDump()
    HeapExplorer.open(heapDump.file).use { explorer ->
      val tree = explorer.tree
      val leaking = tree.findLeaks().objectsOf(APPLICATION)

      // The chain drawn for the window runs through the activity, since that is the shortest way to it, and
      // folding it under the activity would claim that letting go of the activity takes the window with it.
      // It wouldn't: something that isn't leaking holds the window too, the long way round.
      assertThat(tree.rootPathTo(heapDump.windowObjectId).steps.map { it.step.objectId })
        .contains(heapDump.activityObjectId)
      assertThat(leaking.map { it.objectId })
        .containsExactlyInAnyOrder(heapDump.activityObjectId, heapDump.windowObjectId)
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

  @Test fun `two leaks of one kind have two signatures, and one leak has one`() {
    HeapExplorer.open(testFolder.destroyedActivitiesHeapDump()).use { explorer ->
      val group = explorer.tree.findLeaks().sectionOf(APPLICATION).groups.single()

      // A hash of what makes it that leak, so the two activities of one leak are one signature, and it says
      // nothing about which heap dump this is: 40 characters of hex and no address among them.
      assertThat(group.signature).matches("[0-9a-f]{40}")
      assertThat(group.objects).hasSize(2)
    }
  }

  @Test fun `a heap dump with nothing wrong in it has all three sections and no leak`() {
    testFolder.openTestHeapDump().use { explorer ->
      val leaks = explorer.tree.findLeaks()

      // All three of them, so that the screen says which kinds of leak were looked for and not found.
      assertThat(leaks.sections.map { it.kind }).containsExactly(APPLICATION, LIBRARY, UNREACHABLE)
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
