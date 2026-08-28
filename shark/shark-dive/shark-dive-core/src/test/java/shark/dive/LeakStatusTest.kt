package shark.dive

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.dive.LeakStatus.STUCK
import shark.dive.LeakStatus.EXPECTED
import shark.dive.LeakStatus.UNKNOWN

class LeakStatusTest {

  @Test fun `an object nothing knows either way about is unknown`() {
    val statuses = leakStatusesOf(listOf(unknown("Holder")))

    assertThat(statuses.single().status).isEqualTo(UNKNOWN)
    assertThat(statuses.single().reason).isNull()
  }

  @Test fun `everything holding an object that is still needed is still needed too`() {
    val statuses = leakStatusesOf(
      listOf(unknown("Thread"), unknown("Holder"), notLeaking("Activity"), unknown("Payload"))
    )

    assertThat(statuses.map { it.status })
      .containsExactly(EXPECTED, EXPECTED, EXPECTED, UNKNOWN)
    // Named after the object that decided it, and which way along the path it is.
    assertThat(statuses[0].reason).isEqualTo("Activity↓ is expected")
    assertThat(statuses[2].reason).isEqualTo("Activity#mDestroyed is false")
  }

  @Test fun `everything a leaking object holds is only there because it is`() {
    val statuses = leakStatusesOf(
      listOf(unknown("Holder"), leaking("Activity"), unknown("View"), unknown("Payload"))
    )

    assertThat(statuses.map { it.status }).containsExactly(UNKNOWN, STUCK, STUCK, STUCK)
    assertThat(statuses[2].reason).isEqualTo("Activity↑ is stuck")
  }

  @Test fun `what is left between the two is where the leak is`() {
    val statuses = leakStatusesOf(
      listOf(notLeaking("Thread"), unknown("Holder"), unknown("Cache"), leaking("Activity"))
    )

    assertThat(statuses.map { it.status }).containsExactly(EXPECTED, UNKNOWN, UNKNOWN, STUCK)
  }

  @Test fun `the object a path ends at is not made to be leaking`() {
    // Which is the one rule of shark's leak trace deliberately left out: a leak trace ends where the leak
    // is, and a path here ends wherever the reader clicked.
    val statuses = leakStatusesOf(listOf(unknown("Holder"), unknown("Payload")))

    assertThat(statuses.map { it.status }).containsExactly(UNKNOWN, UNKNOWN)
  }

  @Test fun `an object both sides recognize is taken to be still needed`() {
    val statuses = leakStatusesOf(listOf(conflicted("Activity"), unknown("Payload")))

    assertThat(statuses.first().status).isEqualTo(EXPECTED)
    assertThat(statuses.first().reason)
      .isEqualTo("Activity#mDestroyed is false. Conflicts with Activity#mDestroyed is true")
  }

  @Test fun `except at the end of the path, where it is the object being asked about`() {
    val statuses = leakStatusesOf(listOf(unknown("Holder"), conflicted("Activity")))

    assertThat(statuses.last().status).isEqualTo(STUCK)
    assertThat(statuses.last().reason)
      .isEqualTo("Activity#mDestroyed is true. Conflicts with Activity#mDestroyed is false")
  }

  @Test fun `a leak below an object that is still needed starts below it`() {
    // The leaking object is held by one that is known to be needed, so the two disagree: the object that
    // is needed wins, and what it holds carries on being read from there.
    val statuses = leakStatusesOf(
      listOf(leaking("Cache", reason = "Cache#entry is stale"), notLeaking("Activity"), unknown("Payload"))
    )

    assertThat(statuses.map { it.status }).containsExactly(EXPECTED, EXPECTED, UNKNOWN)
    assertThat(statuses.first().reason)
      .isEqualTo("Activity↓ is expected. Conflicts with Cache#entry is stale")
  }

  @Test fun `a path with no objects has no statuses`() {
    assertThat(leakStatusesOf(emptyList())).isEmpty()
  }

  @Test fun `a status set by hand wins over the inspector that disagreed with it`() {
    val statuses = leakStatusesOf(
      listOf(unknown("Holder"), setByHand(leaking("Activity"), EXPECTED, "kept for one more frame"))
    )

    assertThat(statuses.last().status).isEqualTo(EXPECTED)
    assertThat(statuses.last().reason)
      .isEqualTo("set by hand — kept for one more frame. Conflicts with Activity#mDestroyed is true")
  }

  @Test fun `a status set by hand on an object nothing knew about has only its own reason`() {
    val statuses = leakStatusesOf(listOf(setByHand(unknown("Cache"), STUCK, "this cache is unbounded")))

    assertThat(statuses.single().status).isEqualTo(STUCK)
    assertThat(statuses.single().reason).isEqualTo("set by hand — this cache is unbounded")
  }

  @Test fun `an object set to unknown by hand overrules both sides of what was known about it`() {
    val statuses = leakStatusesOf(
      listOf(unknown("Holder"), setByHand(conflicted("Activity"), UNKNOWN, "the inspectors are both wrong"))
    )

    assertThat(statuses.last().status).isEqualTo(UNKNOWN)
    assertThat(statuses.last().reason).isEqualTo(
      "set by hand — the inspectors are both wrong. Conflicts with Activity#mDestroyed is false and " +
        "Activity#mDestroyed is true"
    )
  }

  @Test fun `what a hand set decides the objects above and below it, like any other status`() {
    val statuses = leakStatusesOf(
      listOf(
        unknown("Thread"),
        setByHand(unknown("Presenter"), STUCK, "this screen was closed"),
        unknown("View")
      )
    )

    assertThat(statuses.map { it.status }).containsExactly(UNKNOWN, STUCK, STUCK)
    assertThat(statuses.last().reason).isEqualTo("Presenter↑ is stuck")
  }

  @Test fun `the path overruling a status set by hand says what it overruled`() {
    // Someone said nothing is known about this object, and the path then reads it off the leaking object
    // above: the status is the path's, and what they typed is what the reason records.
    val statuses = leakStatusesOf(
      listOf(leaking("Activity"), setByHand(unknown("View"), UNKNOWN, "no idea what this is"))
    )

    assertThat(statuses.last().status).isEqualTo(STUCK)
    assertThat(statuses.last().reason)
      .isEqualTo("Activity↑ is stuck. Conflicts with set by hand — no idea what this is")
  }
}

private fun unknown(simpleClassName: String) = inspected(simpleClassName)

private fun leaking(
  simpleClassName: String,
  reason: String = "$simpleClassName#mDestroyed is true"
) = inspected(simpleClassName, leakingReasons = setOf(reason))

private fun notLeaking(simpleClassName: String) =
  inspected(simpleClassName, notLeakingReasons = setOf("$simpleClassName#mDestroyed is false"))

/** An object the inspectors say is leaking and say is not, which real ones do disagree about. */
private fun conflicted(simpleClassName: String) = inspected(
  simpleClassName,
  leakingReasons = setOf("$simpleClassName#mDestroyed is true"),
  notLeakingReasons = setOf("$simpleClassName#mDestroyed is false")
)

private fun inspected(
  simpleClassName: String,
  leakingReasons: Set<String> = emptySet(),
  notLeakingReasons: Set<String> = emptySet()
) = InspectedPathObject(simpleClassName, leakingReasons, notLeakingReasons)

/** The same object with someone's own answer on it. The object id is only what the reason is filed under. */
private fun setByHand(
  inspected: InspectedPathObject,
  status: LeakStatus,
  reason: String
) = InspectedPathObject(
  simpleClassName = inspected.simpleClassName,
  leakingReasons = inspected.leakingReasons,
  notLeakingReasons = inspected.notLeakingReasons,
  setByHand = LeakStatusOverride(objectId = 0x42, status = status, reason = reason)
)
