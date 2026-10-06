package shark.dive

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.dive.Verdict.STUCK
import shark.dive.Verdict.EXPECTED
import shark.dive.Verdict.UNKNOWN

class VerdictTest {

  @Test fun `an object nothing knows either way about is unknown`() {
    val verdicts = verdictsOf(listOf(unknown("Holder")))

    assertThat(verdicts.single().verdict).isEqualTo(UNKNOWN)
    assertThat(verdicts.single().reason).isNull()
  }

  @Test fun `everything holding an object that is still needed is still needed too`() {
    val verdicts = verdictsOf(
      listOf(unknown("Thread"), unknown("Holder"), notLeaking("Activity"), unknown("Payload"))
    )

    assertThat(verdicts.map { it.verdict })
      .containsExactly(EXPECTED, EXPECTED, EXPECTED, UNKNOWN)
    // Named after the object that decided it, and which way along the path it is.
    assertThat(verdicts[0].reason).isEqualTo("Activity↓ is expected")
    assertThat(verdicts[2].reason).isEqualTo("Activity#mDestroyed is false")
  }

  @Test fun `everything a leaking object holds is only there because it is`() {
    val verdicts = verdictsOf(
      listOf(unknown("Holder"), leaking("Activity"), unknown("View"), unknown("Payload"))
    )

    assertThat(verdicts.map { it.verdict }).containsExactly(UNKNOWN, STUCK, STUCK, STUCK)
    assertThat(verdicts[2].reason).isEqualTo("Activity↑ is stuck")
  }

  @Test fun `what is left between the two is where the leak is`() {
    val verdicts = verdictsOf(
      listOf(notLeaking("Thread"), unknown("Holder"), unknown("Cache"), leaking("Activity"))
    )

    assertThat(verdicts.map { it.verdict }).containsExactly(EXPECTED, UNKNOWN, UNKNOWN, STUCK)
  }

  @Test fun `the object a path ends at is not made to be leaking`() {
    // Which is the one rule of shark's leak trace deliberately left out: a leak trace ends where the leak
    // is, and a path here ends wherever the reader clicked.
    val verdicts = verdictsOf(listOf(unknown("Holder"), unknown("Payload")))

    assertThat(verdicts.map { it.verdict }).containsExactly(UNKNOWN, UNKNOWN)
  }

  @Test fun `an object both sides recognize is taken to be still needed`() {
    val verdicts = verdictsOf(listOf(conflicted("Activity"), unknown("Payload")))

    assertThat(verdicts.first().verdict).isEqualTo(EXPECTED)
    assertThat(verdicts.first().reason)
      .isEqualTo("Activity#mDestroyed is false. Conflicts with Activity#mDestroyed is true")
  }

  @Test fun `except at the end of the path, where it is the object being asked about`() {
    val verdicts = verdictsOf(listOf(unknown("Holder"), conflicted("Activity")))

    assertThat(verdicts.last().verdict).isEqualTo(STUCK)
    assertThat(verdicts.last().reason)
      .isEqualTo("Activity#mDestroyed is true. Conflicts with Activity#mDestroyed is false")
  }

  @Test fun `a leak below an object that is still needed starts below it`() {
    // The leaking object is held by one that is known to be needed, so the two disagree: the object that
    // is needed wins, and what it holds carries on being read from there.
    val verdicts = verdictsOf(
      listOf(leaking("Cache", reason = "Cache#entry is stale"), notLeaking("Activity"), unknown("Payload"))
    )

    assertThat(verdicts.map { it.verdict }).containsExactly(EXPECTED, EXPECTED, UNKNOWN)
    assertThat(verdicts.first().reason)
      .isEqualTo("Activity↓ is expected. Conflicts with Cache#entry is stale")
  }

  @Test fun `a path with no objects has no verdicts`() {
    assertThat(verdictsOf(emptyList())).isEmpty()
  }

  @Test fun `a verdict set by hand wins over the inspector that disagreed with it`() {
    val verdicts = verdictsOf(
      listOf(unknown("Holder"), setByHand(leaking("Activity"), EXPECTED, "kept for one more frame"))
    )

    assertThat(verdicts.last().verdict).isEqualTo(EXPECTED)
    assertThat(verdicts.last().reason)
      .isEqualTo("set by hand — kept for one more frame. Conflicts with Activity#mDestroyed is true")
  }

  @Test fun `a verdict set by hand on an object nothing knew about has only its own reason`() {
    val verdicts = verdictsOf(listOf(setByHand(unknown("Cache"), STUCK, "this cache is unbounded")))

    assertThat(verdicts.single().verdict).isEqualTo(STUCK)
    assertThat(verdicts.single().reason).isEqualTo("set by hand — this cache is unbounded")
  }

  @Test fun `an object set to unknown by hand overrules both sides of what was known about it`() {
    val verdicts = verdictsOf(
      listOf(unknown("Holder"), setByHand(conflicted("Activity"), UNKNOWN, "the inspectors are both wrong"))
    )

    assertThat(verdicts.last().verdict).isEqualTo(UNKNOWN)
    assertThat(verdicts.last().reason).isEqualTo(
      "set by hand — the inspectors are both wrong. Conflicts with Activity#mDestroyed is false and " +
        "Activity#mDestroyed is true"
    )
  }

  @Test fun `what a hand set decides the objects above and below it, like any other verdict`() {
    val verdicts = verdictsOf(
      listOf(
        unknown("Thread"),
        setByHand(unknown("Presenter"), STUCK, "this screen was closed"),
        unknown("View")
      )
    )

    assertThat(verdicts.map { it.verdict }).containsExactly(UNKNOWN, STUCK, STUCK)
    assertThat(verdicts.last().reason).isEqualTo("Presenter↑ is stuck")
  }

  @Test fun `the path overruling a verdict set by hand says what it overruled`() {
    // Someone said nothing is known about this object, and the path then reads it off the leaking object
    // above: the verdict is the path's, and what they typed is what the reason records.
    val verdicts = verdictsOf(
      listOf(leaking("Activity"), setByHand(unknown("View"), UNKNOWN, "no idea what this is"))
    )

    assertThat(verdicts.last().verdict).isEqualTo(STUCK)
    assertThat(verdicts.last().reason)
      .isEqualTo("Activity↑ is stuck. Conflicts with set by hand — no idea what this is")
  }
}

private fun unknown(simpleClassName: String) = inspected(simpleClassName)

private fun leaking(
  simpleClassName: String,
  reason: String = "$simpleClassName#mDestroyed is true"
) = inspected(simpleClassName, stuckReasons = setOf(reason))

private fun notLeaking(simpleClassName: String) =
  inspected(simpleClassName, expectedReasons = setOf("$simpleClassName#mDestroyed is false"))

/** An object the inspectors say is leaking and say is not, which real ones do disagree about. */
private fun conflicted(simpleClassName: String) = inspected(
  simpleClassName,
  stuckReasons = setOf("$simpleClassName#mDestroyed is true"),
  expectedReasons = setOf("$simpleClassName#mDestroyed is false")
)

private fun inspected(
  simpleClassName: String,
  stuckReasons: Set<String> = emptySet(),
  expectedReasons: Set<String> = emptySet()
) = InspectedPathObject(simpleClassName, stuckReasons, expectedReasons)

/** The same object with someone's own answer on it. The object id is only what the reason is filed under. */
private fun setByHand(
  inspected: InspectedPathObject,
  verdict: Verdict,
  reason: String
) = InspectedPathObject(
  simpleClassName = inspected.simpleClassName,
  stuckReasons = inspected.stuckReasons,
  expectedReasons = inspected.expectedReasons,
  setByHand = VerdictOverride(objectId = 0x42, verdict = verdict, reason = reason)
)
