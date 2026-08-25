package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.explorer.LeakStatus.STUCK
import shark.explorer.LeakStatus.EXPECTED
import shark.explorer.LeakStatus.UNKNOWN

/**
 * What a heap dump says about its objects once somebody has said something about them by hand: the panel,
 * the chain, and which statuses can't both be true. See [LeakStatusOverride].
 */
class HeapLeakStatusTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `an object the inspectors recognize is leaking on its own`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val summary = explorer.tree.summarize(dump.activityObjectId)

      assertThat(summary.leakStatus).isEqualTo(STUCK)
      assertThat(summary.leakStatusReason).contains("mDestroyed")
    }
  }

  @Test fun `a status set by hand is what the panel says instead`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val summary = explorer.tree.summarize(
        objectId = dump.activityObjectId,
        overrides = overrides(dump.activityObjectId, EXPECTED, "kept for one more frame on purpose")
      )

      assertThat(summary.leakStatus).isEqualTo(EXPECTED)
      // What the inspector said is kept as the record of what the hand overruled.
      assertThat(summary.leakStatusReason)
        .isEqualTo("set by hand — kept for one more frame on purpose. Conflicts with Activity#mDestroyed is true")
    }
  }

  /** The panel is about one object, so nothing above or below it changes what it says. */
  @Test fun `a status set on another object is not what the panel says`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val summary = explorer.tree.summarize(
        objectId = dump.windowObjectId,
        overrides = overrides(dump.activityObjectId, EXPECTED, "kept for one more frame on purpose")
      )

      assertThat(summary.leakStatus).isEqualTo(STUCK)
    }
  }

  @Test fun `a chain is read through the statuses set by hand`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val path = explorer.tree.rootPathTo(
        objectId = dump.windowObjectId,
        overrides = overrides(dump.activityObjectId, EXPECTED, "kept for one more frame on purpose")
      )

      val activity = path.steps.single { it.step.objectId == dump.activityObjectId }.step
      assertThat(activity.leakStatus).isEqualTo(EXPECTED)
      assertThat(activity.leakStatusReason).contains(SET_BY_HAND)
      // And what a chain reads off it: the object above the activity is holding something still needed.
      assertThat(path.steps.first().step.leakStatus).isEqualTo(EXPECTED)
    }
  }

  /** Which is the whole point of setting one: the objects below stop being read as leaking. */
  @Test fun `what a hand set decides the objects below it on the chain`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val plain = explorer.tree.rootPathTo(dump.windowObjectId)
      // The holder above the destroyed activity is where the leak starts, so it is left unknown.
      assertThat(plain.steps.first().step.leakStatus).isEqualTo(UNKNOWN)
      assertThat(plain.steps.single { it.step.objectId == dump.activityObjectId }.step.leakStatus)
        .isEqualTo(STUCK)

      val read = explorer.tree.rootPathTo(
        objectId = dump.windowObjectId,
        overrides = overrides(dump.activityObjectId, UNKNOWN, "this activity is not the problem")
      )

      // The window is still leaking on its own account — an inspector recognized it — so what the activity
      // no longer being leaking changes is the activity, not the object the chain leads to.
      assertThat(read.steps.last().step.leakStatus).isEqualTo(STUCK)
      assertThat(read.steps.single { it.step.objectId == dump.activityObjectId }.step.leakStatus)
        .isEqualTo(UNKNOWN)
    }
  }

  /**
   * The other half of what a status decides: which reference the leak is, which is read off the objects
   * either side of it and so is a hand's to change. See [PathReference.isFaulty].
   */
  @Test fun `a chain marks a faulty reference once a hand says an object is expected`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      // Nothing on this chain is known to belong in memory — a holder nothing knows either way about, then
      // two destroyed objects — so the fault is at one of two steps and the chain marks neither.
      assertThat(explorer.tree.rootPathTo(dump.windowObjectId).faultyReferences()).isEmpty()
      assertThat(explorer.tree.rootPathTo(dump.windowObjectId).faultyReference()).isNull()

      val path = explorer.tree.rootPathTo(
        objectId = dump.windowObjectId,
        overrides = overrides(dump.activityObjectId, EXPECTED, "kept for one more frame on purpose")
      )

      // And saying the activity belongs there is what leaves one step between the two verdicts: the window
      // it is still holding is the leak, and there is now nothing else it could be. Named after the class
      // that declares the field, which is the framework's `Activity` rather than the app's subclass of it.
      assertThat(path.faultyReferences()).containsExactly("Activity.mWindow")
      // The same reference, through the one call the window's `Leak solved` section and an agent's
      // `faultyReference` both go through: a chain either names the leak or it doesn't.
      assertThat(path.faultyReference()?.leakLabel()).isEqualTo("Activity.mWindow")
    }
  }

  @Test fun `an object holds the ones it reaches and not the other way round`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      assertThat(explorer.tree.reaches(dump.activityObjectId, dump.windowObjectId)).isTrue()
      assertThat(explorer.tree.reaches(dump.windowObjectId, dump.activityObjectId)).isFalse()
    }
  }

  @Test fun `an object does not hold itself`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      assertThat(explorer.tree.reaches(dump.activityObjectId, dump.activityObjectId)).isFalse()
    }
  }

  @Test fun `an address that is no object of this heap dump reaches nothing`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      assertThat(explorer.tree.reaches(NOT_AN_ADDRESS, dump.windowObjectId)).isFalse()
      assertThat(explorer.tree.reaches(dump.activityObjectId, NOT_AN_ADDRESS)).isFalse()
    }
  }

  @Test fun `a leaking object above and one that is still needed below cannot both be true`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val conflicts = explorer.tree.leakStatusConflictsWith(
        override = override(dump.windowObjectId, EXPECTED, "this window is reused deliberately"),
        overrides = overrides(dump.activityObjectId, STUCK, "this screen was closed")
      )

      val conflict = conflicts.single()
      assertThat(conflict.existing.objectId).isEqualTo(dump.activityObjectId)
      assertThat(conflict.objectName).contains(ACTIVITY_CLASS_NAME.substringAfterLast('.'))
      assertThat(conflict.isAbove).isTrue()
      // Solving it flips the one already set, and the reason says that this is why.
      assertThat(conflict.solved.status).isEqualTo(EXPECTED)
      assertThat(conflict.solved.reason)
        .contains("below this can be \"Expected\"", "Was \"Stuck\": this screen was closed")
    }
  }

  /** The same disagreement, set the other way round: the object below is the one already set. */
  @Test fun `a status set below the new one conflicts too, and says it is held by it`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val conflicts = explorer.tree.leakStatusConflictsWith(
        override = override(dump.activityObjectId, STUCK, "this screen was closed"),
        overrides = overrides(dump.windowObjectId, EXPECTED, "this window is reused deliberately")
      )

      val conflict = conflicts.single()
      assertThat(conflict.existing.objectId).isEqualTo(dump.windowObjectId)
      assertThat(conflict.isAbove).isFalse()
      assertThat(conflict.solved.status).isEqualTo(STUCK)
      assertThat(conflict.solved.reason).contains("above this can be \"Stuck\"")
    }
  }

  @Test fun `two statuses that agree do not conflict`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val conflicts = explorer.tree.leakStatusConflictsWith(
        override = override(dump.windowObjectId, STUCK, "and so is the window it holds"),
        overrides = overrides(dump.activityObjectId, STUCK, "this screen was closed")
      )

      assertThat(conflicts).isEmpty()
    }
  }

  /** Neither holds the other, so the two are answers about two things and nothing has to be settled. */
  @Test fun `two statuses on objects neither of which holds the other do not conflict`() {
    // Three activities under three holders of their own, so no chain runs through two of them.
    HeapExplorer.open(testFolder.destroyedActivitiesHeapDump()).use { explorer ->
      val (one, other) = explorer.tree.findLeaks().leakingObjectIds.toList()

      val conflicts = explorer.tree.leakStatusConflictsWith(
        override = override(one, EXPECTED, "this screen is meant to be kept"),
        overrides = overrides(other, STUCK, "and that one is not")
      )

      assertThat(conflicts).isEmpty()
    }
  }

  /**
   * The loop of [taskHoldingItsOwnThreadHeapDump], which is what makes both of the next two questions
   * questions: every pair of objects on it reaches the other, so reaching is not what says which is above.
   */
  @Test fun `objects that hold each other each reach the other`() {
    val dump = testFolder.taskHoldingItsOwnThreadHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      assertThat(explorer.tree.reaches(dump.wrapperObjectId, dump.taskObjectId)).isTrue()
      assertThat(explorer.tree.reaches(dump.taskObjectId, dump.wrapperObjectId)).isTrue()
    }
  }

  /**
   * Which is the shape of the sample app's `AsyncTask` leak, and so the one every reader of it reaches: the
   * runnable the executor is running belongs in memory and the task it holds does not, said in that order,
   * with the faulty reference between them the whole point of saying it.
   */
  @Test fun `two statuses on objects that hold each other do not conflict`() {
    val dump = testFolder.taskHoldingItsOwnThreadHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val conflicts = explorer.tree.leakStatusConflictsWith(
        override = override(dump.taskObjectId, STUCK, "this task should have been cancelled"),
        overrides = overrides(dump.wrapperObjectId, EXPECTED, "the executor is running this")
      )

      assertThat(conflicts).isEmpty()
    }
  }

  /** And set in the other order, since the walk is from whichever of the two is already there. */
  @Test fun `nor do they the other way round`() {
    val dump = testFolder.taskHoldingItsOwnThreadHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val conflicts = explorer.tree.leakStatusConflictsWith(
        override = override(dump.wrapperObjectId, EXPECTED, "the executor is running this"),
        overrides = overrides(dump.taskObjectId, STUCK, "this task should have been cancelled")
      )

      assertThat(conflicts).isEmpty()
    }
  }

  /**
   * The two of them read as a chain, which is why there was nothing to settle: the reference from the object
   * that belongs in memory to the one that doesn't is the fault, and nothing else on the chain can be.
   */
  @Test fun `a status on each of them marks the reference between them instead`() {
    val dump = testFolder.taskHoldingItsOwnThreadHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val path = explorer.tree.rootPathTo(
        objectId = dump.activityObjectId,
        overrides = LeakStatusOverrides.of(
          listOf(
            override(dump.wrapperObjectId, EXPECTED, "the executor is running this"),
            override(dump.taskObjectId, STUCK, "this task should have been cancelled")
          )
        )
      )

      assertThat(path.faultyReferences())
        .containsExactly("${WRAPPER_CLASS_NAME.substringAfterLast('.')}.val\$r")
    }
  }

  /**
   * A loop is not a heap dump where nothing conflicts: an object below one is held by every object of it, so
   * a status on it is settled against them the way any other pair is.
   */
  @Test fun `a status below a loop still conflicts with one on it`() {
    val dump = testFolder.taskHoldingItsOwnThreadHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val conflicts = explorer.tree.leakStatusConflictsWith(
        override = override(dump.activityObjectId, EXPECTED, "this screen is coming back"),
        overrides = overrides(dump.taskObjectId, STUCK, "this task should have been cancelled")
      )

      val conflict = conflicts.single()
      assertThat(conflict.existing.objectId).isEqualTo(dump.taskObjectId)
      assertThat(conflict.isAbove).isTrue()
    }
  }

  @Test fun `setting a status again on the same object disagrees with nothing`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val conflicts = explorer.tree.leakStatusConflictsWith(
        override = override(dump.activityObjectId, EXPECTED, "changed my mind"),
        overrides = overrides(dump.activityObjectId, STUCK, "this screen was closed")
      )

      assertThat(conflicts).isEmpty()
    }
  }

  /** Nobody claiming to know overrules nobody, so it is never one of the statuses to settle against. */
  @Test fun `a status of unknown set by hand conflicts with nothing below or above it`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val conflicts = explorer.tree.leakStatusConflictsWith(
        override = override(dump.windowObjectId, STUCK, "the window is the problem"),
        overrides = overrides(dump.activityObjectId, UNKNOWN, "no idea about this activity")
      )

      assertThat(conflicts).isEmpty()
    }
  }

  @Test fun `an object set to leaking is a leak, and what it holds is folded under it`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val holderObjectId = explorer.tree.findByLabel(HOLDER_CLASS_NAME.substringAfterLast('.')).objectId
      // Nothing is wrong with the holder as far as the heap dump is concerned: the destroyed activity it
      // holds is the leak, and the window under that is folded into it.
      assertThat(explorer.tree.findLeaks().leakingObjectIds).containsExactly(dump.activityObjectId)

      val leaks = explorer.tree.findLeaks(
        overrides(holderObjectId, STUCK, "this cache is never emptied")
      )

      // The activity is now only in memory because of an object that shouldn't be there, which is the one
      // thing to fix — so it is listed under the holder rather than beside it.
      assertThat(leaks.leakingObjectIds).containsExactly(holderObjectId)
    }
  }

  @Test fun `an object set to expected is no longer a leak, and what it held becomes one`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val leaks = explorer.tree.findLeaks(
        overrides(dump.activityObjectId, EXPECTED, "kept for one more frame on purpose")
      )

      // What the activity was holding is still a destroyed window, and nothing above it is a leak any
      // more, so it stops being one leak's collateral and becomes the leak.
      assertThat(leaks.leakingObjectIds).containsExactly(dump.windowObjectId)
    }
  }

  /** The list is read through them, so taking a status off has to give the heap dump's answer back. */
  @Test fun `the leaks are what the heap dump says again once a status is taken off`() {
    val dump = testFolder.nestedLeaksHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      explorer.tree.findLeaks(overrides(dump.activityObjectId, EXPECTED, "kept on purpose"))

      assertThat(explorer.tree.findLeaks().leakingObjectIds).containsExactly(dump.activityObjectId)
    }
  }

  /** A chain goes round what shouldn't be in memory, and a hand saying it should be is what decides that. */
  @Test fun `a chain stops going round an object once a hand says it is expected`() {
    val dump = testFolder.leakAlsoHeldAnotherWayHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      // The window is held another way as well, so the chain to it gives up a step to avoid the destroyed
      // activity.
      assertThat(explorer.tree.rootPathTo(dump.windowObjectId).steps.map { it.step.objectId })
        .doesNotContain(dump.activityObjectId)

      val path = explorer.tree.rootPathTo(
        objectId = dump.windowObjectId,
        overrides = overrides(dump.activityObjectId, EXPECTED, "this screen is coming back")
      )

      // And takes the short way through it once there is nothing to avoid, which is the chain and the
      // statuses drawn on it being the same answer.
      assertThat(path.steps.map { it.step.objectId }).contains(dump.activityObjectId)
    }
  }

  /**
   * The way round a leak is only worth taking while it is a way anybody can read, and a running method's
   * stack frame is not one: it says the object was in use when the dump was taken, which is nothing to fix.
   * So a chain gives up going round rather than take a frame — see [RootPathSearch].
   */
  @Test fun `a chain runs through what shouldn't be in memory rather than through a stack frame`() {
    val dump = testFolder.taskHoldingItsOwnThreadHeapDump()

    HeapExplorer.open(dump.file).use { explorer ->
      val path = explorer.tree.rootPathTo(
        objectId = dump.activityObjectId,
        overrides = overrides(dump.taskObjectId, STUCK, "this task should have been cancelled")
      )

      // The frame holding the activity is two steps from the thread where the executor's field is four, and
      // it is the four that says what holds this activity: the task somebody has just said shouldn't be
      // running.
      assertThat(path.steps.map { it.step.objectId }).contains(dump.taskObjectId)
      assertThat(path.steps.map { it.step.className }).doesNotContain("java.lang.Thread")
    }
  }

  private fun override(
    objectId: Long,
    status: LeakStatus,
    reason: String
  ) = LeakStatusOverride(objectId = objectId, status = status, reason = reason)

  private fun overrides(
    objectId: Long,
    status: LeakStatus,
    reason: String
  ) = LeakStatusOverrides.of(listOf(override(objectId, status, reason)))

  /**
   * The references of a chain marked as the leak, spelled the way a leak of the leaks screen is named.
   *
   * Every step rather than [RootPath.faultyReference], which stops at the first: a chain marking two of them
   * would leave the window and the agent naming one leak each, and this is what would notice.
   */
  private fun RootPath.faultyReferences(): List<String> =
    steps.mapNotNull { it.step.reference }
      .filter { it.isFaulty }
      .map { it.leakLabel() }

  companion object {
    /** An address no dump these tests write has an object at, since they start at 1. */
    private const val NOT_AN_ADDRESS = -0x1234L
  }
}
