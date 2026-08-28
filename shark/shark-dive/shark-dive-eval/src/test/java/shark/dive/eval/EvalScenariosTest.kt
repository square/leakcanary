package shark.dive.eval

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.dive.HeapDive
import shark.dive.LeakStatus
import shark.dive.LeakStatusOverride
import shark.dive.LeakStatusOverrides
import shark.dive.RootPath
import shark.dive.faultyReference
import shark.dive.leakLabel

/**
 * That every scenario is one an agent could actually solve, and that the key names the reference it would end
 * on.
 *
 * **This is not a check that the answer is right** — the answer is right by construction, since the fixture
 * writes the leak — it is a check that the dump can be *investigated* to it. A scenario whose key is nowhere
 * on the chain, or one whose chain names it before anybody has read anything, is a scenario every model fails
 * or passes for the wrong reason, and the eval would report that as a fact about the models.
 *
 * So each case here does the one thing the method asks an agent to do and no more: find the leak, get the
 * chain, set the one verdict that closes the unknown zone, and read off what the chain then names. Which is
 * also why it is the test to run after touching the tools — it is the shortest thing in this repository that
 * says the surface can be finished.
 */
class EvalScenariosTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun `every scenario names its key once the unknown zone is closed`() {
    EvalScenarios.all(repositoryRoot).forEach { scenario ->
      val file = scenario.writeHeapDumpIn(temporaryFolder.newFolder(scenario.name))
      HeapDive.open(file).use { dive ->
        val chain = dive.chainToTheFirstLeak()
        val keyIndex = chain.steps.indexOfFirst { it.step.reference?.leakLabel() == scenario.key }
        assertThat(keyIndex)
          .describedAs(
            "${scenario.name} has no reference called ${scenario.key} on the chain to its first leak. " +
              "What it does have: ${chain.references()}"
          )
          .isGreaterThan(0)
        // Everything above the object that owns the key is meant to be in memory, which one verdict says:
        // an EXPECTED spreads upwards. What is below is stuck already, because the dump itself says so.
        val owner = chain.steps[keyIndex - 1].step.objectId
        val solved = dive.tree.rootPathTo(
          objectId = chain.steps.last().step.objectId,
          overrides = LeakStatusOverrides.of(
            listOf(
              LeakStatusOverride(owner, LeakStatus.EXPECTED, "The scenario says this belongs in memory.")
            )
          )
        )
        assertThat(solved.faultyReference()?.leakLabel())
          .describedAs("${scenario.name} was not solved by one verdict on the owner of ${scenario.key}")
          .isEqualTo(scenario.key)
      }
    }
  }

  @Test
  fun `no scenario names its key before anybody has read anything`() {
    EvalScenarios.all(repositoryRoot).forEach { scenario ->
      val file = scenario.writeHeapDumpIn(temporaryFolder.newFolder("unread-${scenario.name}"))
      HeapDive.open(file).use { dive ->
        // Because a chain that names the faulty reference with no verdict set is a scenario an agent finishes
        // by reading one answer, and a run of it measures nothing about the method. `conclude` would allow it.
        assertThat(dive.chainToTheFirstLeak().faultyReference())
          .describedAs("${scenario.name} names a faulty reference before anybody has set a verdict")
          .isNull()
      }
    }
  }

  @Test
  fun `every scenario is a leak the heap dump finds on its own`() {
    EvalScenarios.all(repositoryRoot).forEach { scenario ->
      val file = scenario.writeHeapDumpIn(temporaryFolder.newFolder("leaks-${scenario.name}"))
      HeapDive.open(file).use { dive ->
        // The first step of the method is `list_leaks`, so a scenario whose leak isn't in it is one an agent
        // has to go looking for by other means — a different investigation from the one being measured.
        assertThat(dive.tree.findLeaks().leakingObjectCount)
          .describedAs("${scenario.name} has no leak of the app's own for list_leaks to answer with")
          .isGreaterThan(0)
      }
    }
  }

  /** The chain to the first leaking object the dump reports, which is where the method starts. */
  private fun HeapDive.chainToTheFirstLeak(): RootPath {
    val leaks = tree.findLeaks()
    val leaking = leaks.leakSections.flatMap { it.groups }.flatMap { it.objects }
    assertThat(leaking).describedAs("nothing is leaking in this dump").isNotEmpty
    return tree.rootPathTo(leaking.first().objectId)
  }

  /** Every reference the chain names, for a failure message that says what the key should have been. */
  private fun RootPath.references(): String =
    steps.mapNotNull { it.step.reference?.leakLabel() }.joinToString(", ")

  /**
   * Where the real dumps are read from.
   *
   * Four directories up from this module, since a JVM test runs with the module as its working directory and
   * the real dumps are test resources of another one.
   */
  private val repositoryRoot: File get() = File("../../..").canonicalFile
}
