package shark.explorer.eval

import java.io.File
import kotlin.system.exitProcess
import shark.explorer.agent.AgentServer
import shark.explorer.agent.AgentSession
import shark.explorer.agent.AgentSessionFile

/**
 * The two halves of an eval run that are code rather than shell: writing the heap dumps, and scoring the
 * sessions the agents left behind.
 *
 * Everything between them — launching a client, per model, per repetition — is
 * `shark-explorer-agent/harness/eval/run-eval.sh`, because it is process handling and nothing else, and
 * because the adapter for each client is a line of its own arguments. This module never runs a model.
 *
 * Two commands rather than one that does everything, since they happen at different times: the dumps are
 * written once and read by every run, and the scoring happens after the last one has finished. A run that
 * crashed halfway is still scored for what it did.
 */
fun main(args: Array<String>) {
  val command = args.firstOrNull()
  val rest = args.drop(1)
  when (command) {
    "scenarios" -> writeScenarios(rest)
    "score" -> scoreRuns(rest)
    else -> {
      System.err.println(USAGE)
      exitProcess(1)
    }
  }
}

/**
 * Writes every scenario's heap dump into a directory, and says on stdout what it wrote.
 *
 * One line per scenario, tab separated, because the caller is a shell script: the name, the file, the key and
 * what the scenario is about. The key is printed for whoever is reading the run rather than for the script —
 * scoring reads it back out of this module, so a key can never drift between the two halves.
 *
 * A numbered directory each, so that **no path an agent could reach spells the scenario's name**: the name says
 * what the leak is, and this is the eval's answer key. The number is this listing's order and nothing else, and
 * the mapping from it to a name is on stdout, where only the script reads it.
 */
private fun writeScenarios(args: List<String>) {
  val directory = File(args.firstOrNull() ?: fail("`scenarios` needs a directory to write the dumps into."))
  val repositoryRoot = File(args.getOrNull(1) ?: ".")
  EvalScenarios.all(repositoryRoot).forEachIndexed { index, scenario ->
    val file = scenario.writeHeapDumpIn(File(directory, "${index + 1}"))
    println(listOf(scenario.name, file.absolutePath, scenario.key, scenario.about).joinToString("\t"))
  }
}

/**
 * Scores the runs listed in a file, and prints the table to commit and the line per run under it.
 *
 * The runs file is written by the script as it goes, a line per finished run: the scenario, the model, the name
 * of the session file that run's server wrote, and the heap dump it was pointed at. Written as it goes rather
 * than at the end so that an eval
 * somebody stopped halfway is still an eval — thirty runs is an hour, and the reason to stop one is usually
 * that the first few already answered the question.
 */
private fun scoreRuns(args: List<String>) {
  val runsFile = File(args.firstOrNull() ?: fail("`score` needs the file the runs were recorded in."))
  if (!runsFile.isFile) {
    fail("There is no runs file at ${runsFile.absolutePath}.")
  }
  val repositoryRoot = File(args.getOrNull(1) ?: ".")
  val sessionsDirectory = args.getOrNull(2)?.let { File(it) } ?: DEFAULT_SESSIONS_DIRECTORY
  // Read once and indexed, rather than once per run: the whole point of a session being a small file is that
  // a hundred of them is one cheap directory read, and a run names one of them.
  val sessions = AgentSessionFile.sessionsIn(sessionsDirectory).associateBy { it.file.name }
  val results = runsFile.readLines()
    .filter { it.isNotBlank() }
    .mapNotNull { line -> scoreRun(line, repositoryRoot, sessions) }
  if (results.isEmpty()) {
    fail("None of the runs in ${runsFile.absolutePath} could be scored.")
  }
  println(results.asMarkdownTable())
  println()
  println(results.asRunLines())
}

/**
 * One line of the runs file as a result, or null with a line on stderr saying why not.
 *
 * Skipped rather than fatal, because the runs a script has already paid for are worth scoring even when one
 * of them names a session that isn't there — which is what a client that failed to start the server looks
 * like, and it is a finding of its own.
 */
private fun scoreRun(
  line: String,
  repositoryRoot: File,
  sessions: Map<String, AgentSession>
): EvalResult? {
  val fields = line.split("\t")
  if (fields.size < 4) {
    System.err.println(
      "Not a run: \"$line\". Each line is scenario, model, session file and heap dump, tab separated."
    )
    return null
  }
  val (scenarioName, model, sessionName, heapDumpPath) = fields
  val scenario = EvalScenarios.byName(scenarioName, repositoryRoot)
  if (scenario == null) {
    System.err.println("There is no scenario called \"$scenarioName\".")
    return null
  }
  val session = sessions[sessionName]
  if (session == null) {
    System.err.println(
      "No session called \"$sessionName\" was written. ${sessions.size} session(s) are there, so this " +
        "run is one whose server never got as far as a handshake."
    )
    return null
  }
  // The path as the run's server was pointed at it, which is what its session calls the dump: a run that
  // investigated a different file is a run this eval measured nothing with. See [EvalOutcome.WANDERED].
  return EvalResult.of(scenario, model, session, heapDumpPath)
}

private fun fail(message: String): Nothing {
  System.err.println(message)
  exitProcess(1)
}

/** Where a run with no window writes its sessions, which is where every other one writes them too. */
private val DEFAULT_SESSIONS_DIRECTORY: File
  get() = AgentServer.sessionsDirectory(
    File(File(System.getProperty("user.home")), ".shark-explorer/agents")
  )

private val USAGE = """
  Shark Explorer's agent eval, the half of it that isn't process handling.

    scenarios <directory> [repository root]
        Writes every scenario's heap dump into <directory>. Prints one tab separated line per scenario:
        name, heap dump, answer key, what it is about.

    score <runs file> [repository root] [sessions directory]
        Scores the runs recorded in <runs file>, one tab separated line each: scenario, model, session file
        name, heap dump. Prints the markdown table to commit, and a line per run under it.

  Run it through shark-explorer-agent/harness/eval/run-eval.sh rather than by hand.
""".trimIndent()
