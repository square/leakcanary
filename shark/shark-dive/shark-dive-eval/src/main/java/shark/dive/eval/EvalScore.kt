package shark.dive.eval

import java.io.File
import java.io.IOException
import shark.dive.agent.AgentSession
import shark.dive.agent.AgentSessionCall

/**
 * What one agent's session came to, measured against the scenario's answer key.
 *
 * **Nothing here is a judgement.** Every field is a string comparison or a count over the session file the
 * server wrote while the agent worked, which is the rule this eval exists under: a model scoring another
 * model's answer is a second unverified opinion, and the reason to have numbers at all is to be able to
 * review a change to a tool description. See `notes/agent-eval.md`.
 */
class EvalResult(
  val scenario: String,
  val model: String,
  val outcome: EvalOutcome,
  /**
   * The reference this heap dump named once the run's verdicts narrowed to one, and null for a run that
   * never got there.
   *
   * **Not what the agent said**, which is the point of scoring this at all: it is derived by Shark Dive from
   * the verdicts the run recorded, so a run gets this right by setting verdicts that are right, and there is
   * no way to type it. See `AgentSessionFile.outcomeOfTool`.
   */
  val solved: String?,
  /** What it should have been, repeated here so that a result is readable without the scenario beside it. */
  val key: String,
  /** The heap dump a wandering run solved a leak in instead, and null for every run that stayed. */
  val wanderedTo: String?,
  /** How many tools calls it took, refusals included: the number a better surface lowers. */
  val callCount: Int,
  /**
   * How many of those were refused.
   *
   * The secondary number worth the most: refusals rising while the pass rate holds still says a refusal
   * message is not telling an agent what to do next, and refusals falling to zero says the refusals stopped
   * biting. Neither shows up in pass or fail.
   */
  val refusalCount: Int,
  /**
   * How many verdicts it recorded, which is the work this surface is made of.
   *
   * The number to read beside a wrong answer: a run that solved the wrong reference in two verdicts guessed,
   * and one that did it in nine was wrong about something it had looked at. Refusals are counted separately
   * and a refused `set_verdict` is not one of these.
   */
  val verdictCount: Int,
  /** Time the heap dump spent being read for it, summed over every call. */
  val readMillis: Long,
  /** Which session file this was read from, so that a row of a table leads back to what the agent did. */
  val sessionId: String
) {

  companion object {

    /**
     * Scores [session] against [scenario], which is a walk over the calls and no more than that.
     *
     * [model] is what ran it, which the session file has no idea about: a call arrives as a token, a session
     * name and a line of JSON, and nothing on that surface names the model behind it. So it is the eval's own
     * knowledge of which model it launched, passed in here rather than read off the session.
     */
    fun of(
      scenario: EvalScenario,
      model: String,
      session: AgentSession,
      heapDumpPath: String
    ): EvalResult {
      // The calls, not every line: a session records what it could not read as well as what it answered, and
      // a run scored on lines sent would count a typo against the agent's work. See [AgentSession.toolCalls].
      val toolCalls = session.toolCalls
      // Every call that solved a leak, which is every call whose answer carried the heap dump's own
      // `leakSolved`. The first of them, because that is where the run's verdicts first added up to an
      // answer; reading one again later says the same thing. See `AgentSessionFile.outcomeOfTool`.
      val solvedCalls = toolCalls.filter { it.outcome != null }
      val solved = solvedCalls.firstNotNullOfOrNull { it.outcome }
      val dumpGiven = sameFileAs(heapDumpPath)
      return EvalResult(
        scenario = scenario.name,
        model = model,
        outcome = outcomeOf(solvedCalls, solved, scenario.key, dumpGiven),
        solved = solved,
        key = scenario.key,
        wanderedTo = solvedCalls.mapNotNull { it.heapDumpPath }.firstOrNull { sameFileAs(it) != dumpGiven },
        callCount = toolCalls.size,
        refusalCount = session.refusedCount,
        verdictCount = toolCalls.count { it.tool == SET_VERDICT && it.refusal == null },
        readMillis = toolCalls.sumOf { it.millis },
        sessionId = session.sessionId
      )
    }

    private fun outcomeOf(
      solvedCalls: List<AgentSessionCall>,
      solved: String?,
      key: String,
      dumpGiven: String
    ): EvalOutcome = when {
      // Before the answer is compared to anything, because a leak solved in another heap dump is not an
      // answer to this scenario however right it reads.
      solvedCalls.mapNotNull { it.heapDumpPath }.any { sameFileAs(it) != dumpGiven } ->
        EvalOutcome.WANDERED
      // The reference and nothing else, because that is what the answer key is. What it measures now is the
      // verdicts: this string is derived from them by Shark Dive, so a run reaches RIGHT by recording
      // verdicts that are right about the objects, and cannot reach it by writing anything.
      solved == key -> EvalOutcome.RIGHT
      solved != null -> EvalOutcome.WRONG
      else -> EvalOutcome.NOT_SOLVED
    }

    /**
     * One spelling of a path, for the comparison that decides [EvalOutcome.WANDERED].
     *
     * **Which has to be about the file and not about the string**, because the two paths come from different
     * processes: one is the path this eval set a run up around and the other is whatever the run resolved it
     * to. An A/B with `SHARK_EVAL_DIR` set from a `$TMPDIR` that ends in a separator put a doubled one in the
     * middle of every path in `runs.tsv`, and all thirty runs of it scored [EvalOutcome.WANDERED] — each
     * "concluded about" the very heap dump it had been given. The outcome that exists to say this eval
     * measured nothing is the one that must not be reachable by typing a path two ways.
     *
     * Unresolvable is left as it arrived: this scores runs that have already finished, and a dump deleted
     * since is still a path two sessions can be compared on.
     */
    private fun sameFileAs(path: String): String = try {
      File(path).canonicalPath
    } catch (unresolvable: IOException) {
      path
    }

    private const val SET_VERDICT = "set_verdict"
  }
}

/**
 * The four ways a run ends, which are four different things to do about it.
 *
 * [WRONG] is the one that matters most, and the reason a pass rate alone is not enough: a run that narrowed
 * to the wrong reference produced a confident answer somebody would have acted on, while [NOT_SOLVED] left
 * the question open. A surface that turns wrong answers into unsolved ones has got better even if its pass
 * rate hasn't moved.
 *
 * **There was a fifth, `REFUSED`, and it went with `conclude`.** It counted the runs that tried to report a
 * root cause and were told the path didn't name one — a state that no longer exists, because there is
 * nothing to report a root cause *to*. What it was really measuring is now in [NOT_SOLVED] and in the
 * refusal count beside it, which is where a refusal that didn't say what to do next shows up.
 *
 * [WANDERED] is the one that is not about the model at all. It is this eval failing to measure anything, and it
 * is here because it happened: a run whose first call found nothing open, and which was not told the path it
 * had been started on, guessed one — and the path it guessed was another run's heap dump. Scoring that as a
 * wrong answer would have blamed a model for a hole in the surface. See `notes/agent-eval.md`.
 */
enum class EvalOutcome(
  /** One word for a table, since a column of enum constants is a column nobody reads. */
  val label: String
) {
  /** Solved, and on the reference the key names. */
  RIGHT("right"),

  /** Solved on another reference: the confident wrong answer. */
  WRONG("wrong"),

  /**
   * Never narrowed a path to one reference, whatever the run said in its reply.
   *
   * Which covers the run that gave up, the run that went round in circles, and the run that believed it had
   * the answer and never recorded the verdicts that would have shown it. All three left the heap dump
   * saying nothing, and that is the sense in which none of them solved anything.
   */
  NOT_SOLVED("not solved"),

  /** Solved a leak in a heap dump this run was not given, so the run measured nothing and is not the model's. */
  WANDERED("wandered")
}

/**
 * Every result as a markdown table, ready to be committed to `notes/agent-eval.md`.
 *
 * A table rather than a number, because the number a change is judged by depends on which change it is: a
 * pass rate for a new refusal, the call count for a description that was meant to save a round, the wrong
 * column for anything that touches the method. Grouped by scenario and model, `x/n` rather than averaged,
 * since five runs of a model are five samples and not a measurement of one.
 */
fun List<EvalResult>.asMarkdownTable(): String {
  val header =
    "| Scenario | Model | Right | Wrong | Not solved | Wandered | Calls | Verdicts | Refusals |"
  val rule = "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"
  val rows = groupBy { it.scenario to it.model }.map { (key, results) ->
    val (scenario, model) = key
    val count = results.size
    "| $scenario | $model " +
      "| ${results.count { it.outcome == EvalOutcome.RIGHT }}/$count " +
      "| ${results.count { it.outcome == EvalOutcome.WRONG }}/$count " +
      "| ${results.count { it.outcome == EvalOutcome.NOT_SOLVED }}/$count " +
      // In the table rather than only in the run lines, because a column of zeroes is the claim that these
      // numbers are about the models — and a column that isn't zero says to fix the harness before reading
      // the rest of the row.
      "| ${results.count { it.outcome == EvalOutcome.WANDERED }}/$count " +
      "| ${results.map { it.callCount }.median()} " +
      "| ${results.map { it.verdictCount }.median()} " +
      "| ${results.map { it.refusalCount }.median()} |"
  }
  return (listOf(header, rule) + rows).joinToString("\n")
}

/**
 * Every run, one line each, in the order they were scored.
 *
 * Under the table because the table is what a change is argued from and this is what an argument about one
 * row goes to: which reference the run's verdicts came to, and which session file to open to see how.
 */
fun List<EvalResult>.asRunLines(): String = joinToString("\n") { result ->
  listOfNotNull(
    result.scenario,
    result.model,
    result.outcome.label,
    result.wanderedTo?.let { "solved a leak in $it, not the dump it was given" },
    result.solved?.takeIf { it != result.key }?.let { "solved $it, key ${result.key}" },
    "${result.callCount} call(s)",
    "${result.verdictCount} verdict(s)",
    "${result.refusalCount} refused".takeIf { result.refusalCount > 0 },
    "${result.readMillis}ms reading",
    result.sessionId
  ).joinToString(" · ")
}

/**
 * The middle call count rather than the mean, because a run that went in circles is worth ten that didn't and
 * would drag an average with it. Averaged over two for an even count, which is what a median is.
 */
private fun List<Int>.median(): Int {
  val sorted = sorted()
  val middle = sorted.size / 2
  return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}
