package shark.dive.eval

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
  /** The reference the agent concluded on, and null for a run that concluded nothing. */
  val concluded: String?,
  /** What it should have been, repeated here so that a result is readable without the scenario beside it. */
  val key: String,
  /** The heap dump a wandering run concluded about instead, and null for every run that stayed. */
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
  /** How many times it tried to finish, which is how a run that was refused into giving up reads. */
  val concludeCount: Int,
  /** Time the heap dump spent being read for it, summed over every call. */
  val readMillis: Long,
  /** Which session file this was read from, so that a row of a table leads back to what the agent did. */
  val sessionId: String
) {

  companion object {

    /**
     * Scores [session] against [scenario], which is a walk over the calls and no more than that.
     *
     * [model] is what ran it, which the session file has no idea about: an MCP server is told the name of the
     * client and never the name of the model behind it.
     */
    fun of(
      scenario: EvalScenario,
      model: String,
      session: AgentSession,
      heapDumpPath: String
    ): EvalResult {
      // The calls, not every message: a session holds the protocol around them too, and a run scored on how
      // many handshakes it sent is a number that changes with the transport rather than with the agent. See
      // [AgentSession.toolCalls].
      val toolCalls = session.toolCalls
      val concludes = toolCalls.filter { it.tool == CONCLUDE }
      val concluded = concludes.firstNotNullOfOrNull { it.outcome }
      return EvalResult(
        scenario = scenario.name,
        model = model,
        outcome = outcomeOf(concludes, concluded, scenario.key, heapDumpPath),
        concluded = concluded,
        key = scenario.key,
        wanderedTo = concludes.mapNotNull { it.heapDumpPath }.firstOrNull { it != heapDumpPath },
        callCount = toolCalls.size,
        refusalCount = session.refusedCount,
        concludeCount = concludes.size,
        readMillis = toolCalls.sumOf { it.millis },
        sessionId = session.sessionId
      )
    }

    private fun outcomeOf(
      concludes: List<AgentSessionCall>,
      concluded: String?,
      key: String,
      heapDumpPath: String
    ): EvalOutcome = when {
      // Before the answer is compared to anything, because a conclusion about another heap dump is not an
      // answer to this scenario however right it reads.
      concludes.any { it.heapDumpPath != null && it.heapDumpPath != heapDumpPath } -> EvalOutcome.WANDERED
      // The reference and nothing else, because that is what the answer key is: a run that named it and
      // explained it badly still found it, and a run that explained the wrong reference beautifully didn't.
      concluded == key -> EvalOutcome.RIGHT
      concluded != null -> EvalOutcome.WRONG
      concludes.isNotEmpty() -> EvalOutcome.REFUSED
      else -> EvalOutcome.NOT_CONCLUDED
    }

    private const val CONCLUDE = "conclude"
  }
}

/**
 * The five ways a run ends, which are five different things to do about it.
 *
 * [WRONG] is the one that matters most, and the reason a pass rate alone is not enough: an agent that
 * concluded the wrong reference produced a confident answer somebody would have acted on, while [REFUSED] and
 * [NOT_CONCLUDED] left the question open. A surface that turns wrong answers into refusals has got better
 * even if its pass rate hasn't moved.
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
  /** Concluded, and on the reference the key names. */
  RIGHT("right"),

  /** Concluded on another reference: the confident wrong answer. */
  WRONG("wrong"),

  /** Tried to conclude and was refused every time, so it never claimed a root cause. */
  REFUSED("refused"),

  /** Never tried, which is the failure mode of a surface an agent answers around rather than through. */
  NOT_CONCLUDED("no conclusion"),

  /** Concluded about a heap dump this run was not given, so the run measured nothing and is not the model's. */
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
    "| Scenario | Model | Right | Wrong | Refused | No conclusion | Wandered | Calls | Refusals |"
  val rule = "| --- | --- | --- | --- | --- | --- | --- | --- | --- |"
  val rows = groupBy { it.scenario to it.model }.map { (key, results) ->
    val (scenario, model) = key
    val count = results.size
    "| $scenario | $model " +
      "| ${results.count { it.outcome == EvalOutcome.RIGHT }}/$count " +
      "| ${results.count { it.outcome == EvalOutcome.WRONG }}/$count " +
      "| ${results.count { it.outcome == EvalOutcome.REFUSED }}/$count " +
      "| ${results.count { it.outcome == EvalOutcome.NOT_CONCLUDED }}/$count " +
      // In the table rather than only in the run lines, because a column of zeroes is the claim that these
      // numbers are about the models — and a column that isn't zero says to fix the harness before reading
      // the rest of the row.
      "| ${results.count { it.outcome == EvalOutcome.WANDERED }}/$count " +
      "| ${results.map { it.callCount }.median()} " +
      "| ${results.map { it.refusalCount }.median()} |"
  }
  return (listOf(header, rule) + rows).joinToString("\n")
}

/**
 * Every run, one line each, in the order they were scored.
 *
 * Under the table because the table is what a change is argued from and this is what an argument about one
 * row goes to: which reference was concluded, and which session file to open to see how.
 */
fun List<EvalResult>.asRunLines(): String = joinToString("\n") { result ->
  listOfNotNull(
    result.scenario,
    result.model,
    result.outcome.label,
    result.wanderedTo?.let { "concluded about $it, not the dump it was given" },
    result.concluded?.takeIf { it != result.key }?.let { "concluded $it, key ${result.key}" },
    "${result.callCount} call(s)",
    "${result.refusalCount} refused".takeIf { result.refusalCount > 0 },
    "${result.concludeCount} conclude attempt(s)".takeIf { result.concludeCount > 1 },
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
