package shark.explorer.eval

import java.io.File
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.explorer.agent.AgentSession
import shark.explorer.agent.AgentSessionCall

/**
 * What a session is scored as, which is a handful of counts and one string comparison.
 *
 * Worth testing rather than reading because the outcomes are the whole of what this eval reports, and most of
 * them are failures that mean different things: a wrong answer somebody would have acted on, a run the refusals
 * stopped, a run that never tried, and a run that investigated the wrong heap dump — which is this eval's own
 * failure and not a model's. A scorer that folded any two of those together would report a change as neutral
 * that had turned confident wrong answers into refusals, which is the change most worth making.
 */
class EvalScoreTest {

  @Test
  fun `a conclusion on the key is right`() {
    val result = score(calls = listOf(call("list_leaks"), concluded(KEY)))

    assertThat(result.outcome).isEqualTo(EvalOutcome.RIGHT)
    assertThat(result.concluded).isEqualTo(KEY)
    assertThat(result.callCount).isEqualTo(2)
  }

  @Test
  fun `a conclusion on another reference is wrong, not a near miss`() {
    val result = score(calls = listOf(concluded("ExampleApplication.holder")))

    // The failure the whole eval exists to count: the run produced an answer, and somebody would have gone
    // and changed the wrong line. Nothing here scales it by how close the reference was.
    assertThat(result.outcome).isEqualTo(EvalOutcome.WRONG)
    assertThat(result.concluded).isEqualTo("ExampleApplication.holder")
  }

  @Test
  fun `a run the refusals stopped is told from one that never tried`() {
    val refused = score(calls = listOf(call("conclude", refusal = "Not concluded. 1 step(s) have no verdict")))
    val neverTried = score(calls = listOf(call("list_leaks"), call("chain_from_gc_root")))

    assertThat(refused.outcome).isEqualTo(EvalOutcome.REFUSED)
    assertThat(refused.concludeCount).isEqualTo(1)
    assertThat(neverTried.outcome).isEqualTo(EvalOutcome.NOT_CONCLUDED)
    assertThat(neverTried.concludeCount).isZero
  }

  @Test
  fun `a run refused and then right is right, and says how many attempts it took`() {
    val result = score(
      calls = listOf(
        call("conclude", refusal = "Not concluded. 1 step(s) have no verdict"),
        call("set_verdict"),
        concluded(KEY)
      )
    )

    // Which is the story the surface is built for — refused, a verdict, then concluded — so a scorer that
    // called this a refusal would mark the method working as the method failing.
    assertThat(result.outcome).isEqualTo(EvalOutcome.RIGHT)
    assertThat(result.refusalCount).isEqualTo(1)
    assertThat(result.concludeCount).isEqualTo(2)
  }

  @Test
  fun `a conclusion about another heap dump is not an answer to this scenario`() {
    val result = score(
      calls = listOf(call("open_heap_dump"), concluded(KEY, heapDumpPath = "/dumps/2/heap-dump.hprof"))
    )

    // Even though it concluded the key: this run was given another dump, so what it found was a leak in
    // somebody else's scenario. Scored as the harness failing rather than as the model answering.
    assertThat(result.outcome).isEqualTo(EvalOutcome.WANDERED)
    assertThat(result.wanderedTo).isEqualTo("/dumps/2/heap-dump.hprof")
    assertThat(result.asRunLine()).contains("not the dump it was given")
  }

  @Test
  fun `the table is one row per scenario and model, counted out of the repetitions`() {
    val results = listOf(
      score(calls = listOf(concluded(KEY))),
      score(calls = listOf(concluded("Other.field"))),
      score(calls = listOf(call("conclude", refusal = "Not concluded")))
    )

    val table = results.asMarkdownTable()

    // `x/n` rather than a rate, because three runs of a model are three samples: a rate of 33% reads as a
    // measurement and hides that the answer to "does this work" was yes once.
    assertThat(table).contains("| two-apart | opus | 1/3 | 1/3 | 1/3 | 0/3 | 0/3 |")
  }

  @Test
  fun `a run leads back to the session it was read from`() {
    val result = score(calls = listOf(concluded(KEY)))

    // Because every number above is an argument about a session somebody then has to go and read, and the
    // *Agent logs* screen finds one by this id.
    assertThat(result.asRunLine()).contains(SESSION_ID)
  }

  private fun score(calls: List<AgentSessionCall>) = EvalResult.of(
    scenario = EvalScenario(
      name = "two-apart",
      key = KEY,
      about = "A scenario of this test's own, so that nothing here depends on which dumps exist"
    ) { error("This test scores sessions and opens no heap dump") },
    model = "opus",
    session = AgentSession(
      sessionId = SESSION_ID,
      startedAt = AT,
      client = "claude-code 9.9.9",
      serverVersion = "1.2.3",
      file = File("/sessions/agent-$SESSION_ID.jsonl"),
      calls = calls
    ),
    heapDumpPath = HEAP_DUMP_PATH
  )

  private fun concluded(
    reference: String,
    heapDumpPath: String = HEAP_DUMP_PATH
  ) = call("conclude", outcome = reference, heapDumpPath = heapDumpPath)

  private fun call(
    tool: String,
    refusal: String? = null,
    outcome: String? = null,
    heapDumpPath: String = HEAP_DUMP_PATH
  ) = AgentSessionCall(
    at = AT,
    tool = tool,
    reason = "Because.",
    windowId = null,
    heapDumpPath = heapDumpPath,
    place = null,
    arguments = emptyMap(),
    refusal = refusal,
    outcome = outcome,
    millis = 12L
  )

  private fun EvalResult.asRunLine() = listOf(this).asRunLines()

  private companion object {
    const val KEY = "Holder.activity"
    const val SESSION_ID = "1a2b3c4d"

    /** The dump this run was given, named the way every run's is: the scenario is not in the path. */
    const val HEAP_DUMP_PATH = "/runs/1/heap-dump.hprof"
    val AT: Instant = Instant.parse("2026-08-25T18:19:48.035Z")
  }
}
