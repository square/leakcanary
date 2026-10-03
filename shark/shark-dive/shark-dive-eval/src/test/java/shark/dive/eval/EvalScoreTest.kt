package shark.dive.eval

import java.io.File
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import shark.dive.agent.AgentSession
import shark.dive.agent.AgentSessionCall

/**
 * What a session is scored as, which is a handful of counts and one string comparison.
 *
 * Worth testing rather than reading because the outcomes are the whole of what this eval reports, and most of
 * them are failures that mean different things: a wrong answer somebody would have acted on, a run that left
 * the heap dump saying nothing, and a run that investigated the wrong heap dump — which is this eval's own
 * failure and not a model's. A scorer that folded any two of those together would report a change as neutral
 * that had turned confident wrong answers into unsolved ones, which is the change most worth making.
 */
class EvalScoreTest {

  @Test
  fun `a leak solved on the key is right`() {
    val result = score(calls = listOf(call("list_leak_groups"), solved(KEY)))

    assertThat(result.outcome).isEqualTo(EvalOutcome.RIGHT)
    assertThat(result.solved).isEqualTo(KEY)
    assertThat(result.callCount).isEqualTo(2)
  }

  @Test
  fun `a leak solved on another reference is wrong, not a near miss`() {
    val result = score(calls = listOf(solved("ExampleApplication.holder")))

    // The failure the whole eval exists to count: the run's verdicts narrowed to one reference, and somebody
    // would have gone and changed the wrong line. Nothing here scales it by how close the reference was.
    assertThat(result.outcome).isEqualTo(EvalOutcome.WRONG)
    assertThat(result.solved).isEqualTo("ExampleApplication.holder")
  }

  @Test
  fun `a run that solved nothing says so whether or not it was refused along the way`() {
    val refused = score(calls = listOf(call(SET_VERDICT, refusal = "set_verdict needs `why`")))
    val neverTried = score(calls = listOf(call("list_leak_groups"), call("path_from_gc_root")))

    // One outcome for both, because both left the heap dump naming no reference, which is the whole of what
    // solving a leak is. What tells them apart is the refusal count beside it rather than a fifth outcome:
    // a refusal is a step of an investigation that carried on, not an end state of one.
    assertThat(refused.outcome).isEqualTo(EvalOutcome.NOT_SOLVED)
    assertThat(refused.refusalCount).isEqualTo(1)
    assertThat(refused.verdictCount).isZero
    assertThat(neverTried.outcome).isEqualTo(EvalOutcome.NOT_SOLVED)
    assertThat(neverTried.refusalCount).isZero
  }

  @Test
  fun `a run refused and then right is right, and says how many verdicts it took`() {
    val result = score(
      calls = listOf(
        call(SET_VERDICT, refusal = "set_verdict needs `why`"),
        call(SET_VERDICT),
        solved(KEY)
      )
    )

    // Which is the story the surface is built for — a refusal that says what was missing, a verdict, and the
    // verdict that narrows the path to one reference — so a scorer that called this unsolved would mark the
    // method working as the method failing. The refused call is no verdict: two were recorded, not three.
    assertThat(result.outcome).isEqualTo(EvalOutcome.RIGHT)
    assertThat(result.refusalCount).isEqualTo(1)
    assertThat(result.verdictCount).isEqualTo(2)
  }

  @Test
  fun `a leak solved in another heap dump is not an answer to this scenario`() {
    val result = score(
      calls = listOf(call("open_heap_dump"), solved(KEY, heapDumpPath = "/dumps/2/heap-dump.hprof"))
    )

    // Even though the reference is the key: this run was given another dump, so what it solved was a leak in
    // somebody else's scenario. Scored as the harness failing rather than as the model answering.
    assertThat(result.outcome).isEqualTo(EvalOutcome.WANDERED)
    assertThat(result.wanderedTo).isEqualTo("/dumps/2/heap-dump.hprof")
    assertThat(result.asRunLine()).contains("not the dump it was given")
  }

  @Test
  fun `one heap dump spelled two ways is one heap dump`() {
    val result = score(
      calls = listOf(solved(KEY, heapDumpPath = "/runs//1/./heap-dump.hprof"))
    )

    // The eval set this run up around `/runs/1/heap-dump.hprof` and the run was answered about the same file
    // under a path with a doubled separator in it, which is what a `SHARK_EVAL_DIR` built from a `$TMPDIR`
    // that ends in one produces. Compared as strings, that is thirty runs each of which wandered off to the
    // dump it was given. See [EvalResult.sameFileAs].
    assertThat(result.outcome).isEqualTo(EvalOutcome.RIGHT)
    assertThat(result.wanderedTo).isNull()
  }

  @Test
  fun `a path read back after the verdicts added up is the same answer, not a second one`() {
    val result = score(
      calls = listOf(solved(KEY, tool = "set_verdict"), solved(KEY, tool = "path_from_gc_root"))
    )

    // Both tools answer with the heap dump's own `leakSolved`, so a run that sets the last verdict and then
    // reads the path has solved one leak twice over. Scored on the first of them, since that is where the
    // verdicts added up — and counted as one answer, or every re-read would read as a fresh conclusion.
    assertThat(result.outcome).isEqualTo(EvalOutcome.RIGHT)
    assertThat(result.solved).isEqualTo(KEY)
  }

  @Test
  fun `the table is one row per scenario and model, counted out of the repetitions`() {
    val results = listOf(
      score(calls = listOf(solved(KEY))),
      score(calls = listOf(solved("Other.field"))),
      score(calls = listOf(call(SET_VERDICT, refusal = "set_verdict needs `why`")))
    )

    val table = results.asMarkdownTable()

    // `x/n` rather than a rate, because three runs of a model are three samples: a rate of 33% reads as a
    // measurement and hides that the answer to "does this work" was yes once.
    assertThat(table).contains("| two-apart | opus | 1/3 | 1/3 | 1/3 | 0/3 |")
  }

  @Test
  fun `the lines a session also records that reached no tool are no part of what a run is scored on`() {
    val result = score(
      calls = listOf(unreadable(), unreadable(), call("list_leak_groups"), solved(KEY))
    )

    // Two calls, not four. A session holds the full traffic on purpose, and a run measured on the lines it
    // sent would count a typo against the agent's work. The time is the calls' too, a line that reached no
    // tool being no read of a heap dump.
    assertThat(result.outcome).isEqualTo(EvalOutcome.RIGHT)
    assertThat(result.callCount).isEqualTo(2)
    assertThat(result.readMillis).isEqualTo(24L)
  }

  @Test
  fun `a run leads back to the session it was read from`() {
    val result = score(calls = listOf(solved(KEY)))

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
      serverVersion = "1.2.3",
      file = File("/sessions/agent-$SESSION_ID.jsonl"),
      calls = calls
    ),
    heapDumpPath = HEAP_DUMP_PATH
  )

  /**
   * A call whose answer carried the reference the heap dump itself named, which is what solving a leak looks
   * like in a session file.
   *
   * Nothing the agent wrote: [AgentSessionCall.outcome] is lifted off the answer by
   * `AgentSessionFile.outcomeOfTool`, from the two tools that can report `leakSolved`. So the reference this
   * test passes in stands for a path Shark Dive narrowed, and a run reaches it by recording verdicts.
   */
  private fun solved(
    reference: String,
    tool: String = SET_VERDICT,
    heapDumpPath: String = HEAP_DUMP_PATH
  ) = call(tool, outcome = reference, heapDumpPath = heapDumpPath)

  private fun call(
    tool: String,
    refusal: String? = null,
    outcome: String? = null,
    heapDumpPath: String = HEAP_DUMP_PATH
  ) = AgentSessionCall(
    at = AT,
    tool = tool,
    reason = "Because.",
    heapDumpPath = heapDumpPath,
    place = null,
    arguments = emptyMap(),
    // What a run scores on is the reference its verdicts narrowed to and how many calls it took, so the
    // exchange every call also carries is nothing this reads. See [AgentSessionCall.input].
    input = null,
    output = null,
    refusal = refusal,
    error = null,
    outcome = outcome,
    millis = 12L
  )

  /**
   * A line this app could make no call of, which a session holds as well as the calls.
   *
   * Nothing here scores one, and that is what the test using this is about: a run measured on the lines it
   * sent rather than on the calls it made is a run whose number moves with a mistyped command.
   * See [AgentSession.toolCalls].
   */
  private fun unreadable() = AgentSessionCall(
    at = AT,
    tool = null,
    reason = null,
    heapDumpPath = null,
    place = null,
    arguments = emptyMap(),
    input = "describe_object object=0x12d368b8",
    output = FAILURE,
    refusal = null,
    error = FAILURE,
    outcome = null,
    millis = 40L
  )

  private fun EvalResult.asRunLine() = listOf(this).asRunLines()

  private companion object {
    const val KEY = "Holder.activity"
    const val SESSION_ID = "1a2b3c4d"

    /** The one tool a run records work with, and so the one [EvalResult.verdictCount] counts. */
    const val SET_VERDICT = "set_verdict"

    /** What a line that reached no tool was answered with, which is the whole of what it records. */
    const val FAILURE = "That is not one JSON object."

    /** The dump this run was given, named the way every run's is: the scenario is not in the path. */
    const val HEAP_DUMP_PATH = "/runs/1/heap-dump.hprof"
    val AT: Instant = Instant.parse("2026-08-25T18:19:48.035Z")
  }
}
