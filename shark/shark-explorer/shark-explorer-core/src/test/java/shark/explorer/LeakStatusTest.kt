package shark.explorer

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.explorer.LeakStatus.LEAKING
import shark.explorer.LeakStatus.NOT_LEAKING
import shark.explorer.LeakStatus.UNKNOWN

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
      .containsExactly(NOT_LEAKING, NOT_LEAKING, NOT_LEAKING, UNKNOWN)
    // Named after the object that decided it, and which way along the path it is.
    assertThat(statuses[0].reason).isEqualTo("Activity↓ is not leaking")
    assertThat(statuses[2].reason).isEqualTo("Activity#mDestroyed is false")
  }

  @Test fun `everything a leaking object holds is only there because it is`() {
    val statuses = leakStatusesOf(
      listOf(unknown("Holder"), leaking("Activity"), unknown("View"), unknown("Payload"))
    )

    assertThat(statuses.map { it.status }).containsExactly(UNKNOWN, LEAKING, LEAKING, LEAKING)
    assertThat(statuses[2].reason).isEqualTo("Activity↑ is leaking")
  }

  @Test fun `what is left between the two is where the leak is`() {
    val statuses = leakStatusesOf(
      listOf(notLeaking("Thread"), unknown("Holder"), unknown("Cache"), leaking("Activity"))
    )

    assertThat(statuses.map { it.status }).containsExactly(NOT_LEAKING, UNKNOWN, UNKNOWN, LEAKING)
  }

  @Test fun `the object a path ends at is not made to be leaking`() {
    // Which is the one rule of shark's leak trace deliberately left out: a leak trace ends where the leak
    // is, and a path here ends wherever the reader clicked.
    val statuses = leakStatusesOf(listOf(unknown("Holder"), unknown("Payload")))

    assertThat(statuses.map { it.status }).containsExactly(UNKNOWN, UNKNOWN)
  }

  @Test fun `an object both sides recognize is taken to be still needed`() {
    val statuses = leakStatusesOf(listOf(conflicted("Activity"), unknown("Payload")))

    assertThat(statuses.first().status).isEqualTo(NOT_LEAKING)
    assertThat(statuses.first().reason)
      .isEqualTo("Activity#mDestroyed is false. Conflicts with Activity#mDestroyed is true")
  }

  @Test fun `except at the end of the path, where it is the object being asked about`() {
    val statuses = leakStatusesOf(listOf(unknown("Holder"), conflicted("Activity")))

    assertThat(statuses.last().status).isEqualTo(LEAKING)
    assertThat(statuses.last().reason)
      .isEqualTo("Activity#mDestroyed is true. Conflicts with Activity#mDestroyed is false")
  }

  @Test fun `a leak below an object that is still needed starts below it`() {
    // The leaking object is held by one that is known to be needed, so the two disagree: the object that
    // is needed wins, and what it holds carries on being read from there.
    val statuses = leakStatusesOf(
      listOf(leaking("Cache", reason = "Cache#entry is stale"), notLeaking("Activity"), unknown("Payload"))
    )

    assertThat(statuses.map { it.status }).containsExactly(NOT_LEAKING, NOT_LEAKING, UNKNOWN)
    assertThat(statuses.first().reason)
      .isEqualTo("Activity↓ is not leaking. Conflicts with Cache#entry is stale")
  }

  @Test fun `a path with no objects has no statuses`() {
    assertThat(leakStatusesOf(emptyList())).isEmpty()
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
