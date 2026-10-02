package shark.dive.agent

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import shark.dive.AndroidDevice
import shark.dive.DeviceProcess

/**
 * The whole way in: `--cli <command> name=value …`, one command typed at a run that is already open.
 *
 * **Argument translation and nothing else.** It turns a command line into the one line [AgentWire] describes,
 * prints what came back and exits with what happened, so a refusal met here was thrown by the command's own
 * handler in [AgentTools]. Everything that decides anything is on the other end of the socket, which is what
 * keeps this from being a second surface with rules of its own. See `notes/agent-surface.md`.
 *
 * **[CLI_OPTION] is on every command**, rather than being implied by a command name arriving, so that a command
 * line either says it is a command or is a run of the app opening windows and there is no third reading. It is
 * the word that used to be `--agent`, and the rename is the whole of what it says better: this surface is
 * written for agents and a person types it as often, so what the option names is the way in and not who is
 * using it.
 *
 * **It talks to the run that is already open**, over the loopback socket every run publishes. So being a
 * process per command costs the connect and nothing else: the heap dump was parsed and indexed once, in the run
 * somebody is watching, and this queues on that run's own reading thread. What it does cost is that a process
 * cannot be what gathers an investigation, which is what [SESSION_OPTION] is for.
 *
 * **And only to a run of its own build**, which is what [AgentServer.PublishedRun.buildSha] is for: a machine
 * with this surface being worked on has last week's run still up, and a command renamed since is otherwise a
 * refusal that reads as a command that never existed.
 *
 * Why a command line rather than something an agent's client is configured with: it is what an agent reaches
 * for without being configured at all, it costs nothing until it is run, it pipes into `jq`, and a person
 * watching can type the same command the agent just typed.
 */
object AgentCommandLine {

  /**
   * Makes one call, prints the answer, and returns the exit code the process should end with.
   *
   * [words] is what was typed after [CLI_OPTION]: the name of a command, then its arguments as `name=value`.
   */
  @Suppress("ReturnCount")
  fun run(
    /** Where the runs of the app publish themselves. See [AgentServer]. */
    directory: File,
    /** What to type to run this app, which is what every message here tells a caller to type. */
    command: String,
    words: List<String>,
    /** Which build this is, which is the only kind of run it talks to. See [AgentServer.PublishedRun]. */
    buildSha: String,
    /** Which run, by process id, or null for the one run there had better be. See [runToTalkTo]. */
    pid: String? = null,
    /** Whether [NO_UI_OPTION] was passed, which only the commands in [STARTS_A_RUN] take. */
    noWindow: Boolean = false,
    /**
     * What [SESSION_OPTION] said, and null for a command line that named no session. See [defaultSessionName].
     *
     * **Null is also what says a person typed this**, which is the one thing this command line knows about who
     * is calling and is why `reason` is asked for here rather than on every tool: naming a session is what an
     * agent does and what a shell has no need of, so the pair is "a session and a reason" against "neither".
     * See [whyIsMissing].
     *
     * Refused rather than sanitised when it is no name: it becomes part of a file name, and a caller that
     * got it wrong wants to hear so on the command it got wrong.
     */
    sessionName: String? = null,
    /**
     * How to start a run to investigate in when there is none, and null for a caller that cannot.
     *
     * Called with whether that run should draw no window, since [NO_UI_OPTION] is a property of a run rather
     * than of one heap dump. See [runToTalkTo].
     */
    openARun: ((noWindow: Boolean) -> Unit)? = null
  ): Int {
    val commands = described()
    val commandName = words.firstOrNull()
    if (commandName == null || isCallArgument(commandName)) {
      say("$CLI_OPTION needs the name of a command. `$command $HELP_OPTION` prints the ones there are.")
      return NOTHING_ANSWERED
    }
    // Locally, which it can be because a command line only talks to a run of its own build: the list here is
    // the list that would answer, so a typo costs a message rather than a connect and a refusal.
    if (commands.none { it.name == commandName }) {
      say(
        "There is no command called \"$commandName\". This build has " +
          commands.joinToString(", ") { it.name } + ". `$command $HELP_OPTION <command>` is what one does."
      )
      return NOTHING_ANSWERED
    }
    if (noWindow && commandName !in STARTS_A_RUN) {
      say(
        "$NO_UI_OPTION says what kind of run to start, so it goes with the commands that start one — " +
          STARTS_A_RUN.joinToString(", ") + " — and with no other: $commandName reads a dump that is open " +
          "already, in whichever run has it."
      )
      return NOTHING_ANSWERED
    }
    if (sessionName != null && !AgentSessionFile.isSessionName(sessionName)) {
      say(
        "\"$sessionName\" is no session name: it becomes part of a file name, so it is letters and digits, " +
          "up to ${AgentSessionFile.MAX_SESSION_NAME_LENGTH} of them. Strip the rest out of yours rather " +
          "than shortening it — an id with dashes in it is still an id without them."
      )
      return NOTHING_ANSWERED
    }
    val arguments = try {
      argumentsOf(commands, commandName, words.drop(1))
    } catch (unreadable: IllegalArgumentException) {
      say(unreadable.message.orEmpty())
      return NOTHING_ANSWERED
    }
    if (sessionName != null && !arguments.saysWhy()) {
      say(whyIsMissing(command, commandName, sessionName))
      return NOTHING_ANSWERED
    }
    val run = runToTalkTo(
      directory = directory,
      command = command,
      commandName = commandName,
      pid = pid,
      buildSha = buildSha,
      noWindow = noWindow,
      openARun = openARun
    ) ?: return NOTHING_ANSWERED
    val socket = try {
      Socket().apply {
        connect(InetSocketAddress(InetAddress.getLoopbackAddress(), run.port), CONNECT_TIMEOUT_MILLIS)
      }
    } catch (throwable: Throwable) {
      // Which is a run that was killed: the file is still there and nothing is on the port.
      say("Shark Dive run ${run.pid} does not answer on port ${run.port}: $throwable")
      run.file.delete()
      return NOTHING_ANSWERED
    }
    return socket.use { call(it, run, commandName, arguments, sessionName ?: defaultSessionName()) }
  }

  /**
   * Whether a call says why it was made, which is [REASON] with something in it.
   *
   * Read here rather than in [AgentTool.call], where every tool read it: the rule is for the sessions somebody
   * reviews, and this is the only place that knows whether this command line is one of those — see
   * [SESSION_OPTION]. Blank counts as missing, since `reason=""` is a caller satisfying the letter of it.
   */
  private fun JsonObject.saysWhy(): Boolean = (this[REASON] as? JsonPrimitive)?.content?.isNotBlank() == true

  /** What to say to a command line that named its session and said nothing about why it is calling. */
  private fun whyIsMissing(
    command: String,
    commandName: String,
    sessionName: String
  ): String = "$commandName needs `$REASON`: $SESSION_OPTION$sessionName says these calls are one " +
    "investigation somebody will read afterwards, and a call with no sentence beside it is a read they " +
    "cannot follow. Write what you are trying to learn, or what you concluded from the last answer: " +
    "`$command $CLI_OPTION $commandName … $REASON=\"what I am asking and why\"`. " +
    "`$command $HELP_OPTION $commandName` has the rest of what it takes."

  /**
   * Every command of this build, one line each, and the rest of what a command line takes.
   *
   * Generated from [AgentTools.all], which is the same list every call is answered out of, so a command cannot
   * be in this text and missing from the surface or the other way round. [AgentTool.summary] is the line, and it
   * is written rather than cut out of the description for the reason that field records.
   *
   * Answered with no run of the app and no heap dump anywhere, since it describes a build rather than anything
   * open: this is what an agent reads *before* there is something to read, so reaching for a run to print it
   * would be help that fails on the machine where it is needed. [NoHeapDumpToDescribe] is what makes that
   * literal.
   */
  fun commandsHelp(
    /** What to type to run this app, which is what the examples are written with. */
    command: String
  ): String = """
    |COMMANDS
    |
    |**Start with $OPEN_HEAP_DUMP on the heap dump you were given.** It opens that file, or joins the run
    |that already has it, and answers with the key every command below names that dump by — along with its
    |size and whatever verdicts somebody has already recorded about it. If you were given no heap dump,
    |$LIST_HEAP_DUMPS says which are open. Every other command needs that key, so that each one says which
    |heap dump it is about.
    |
    |  $command $CLI_OPTION $OPEN_HEAP_DUMP path=/tmp/crash.hprof reason="Starting on the dump I was given"
    |  $command $CLI_OPTION list_leaks heapDumpKey=crash.hprof reason="What this dump says shouldn't be here"
    |
    |${commandColumn()}
    |
    |Every command takes `reason`: why you are making it, or what you concluded from the last answer. It is
    |logged beside the reads it causes and read afterwards on the *Agent logs* screen, so write the sentence
    |you would say to the person watching — and it is required of every command of a session named with
    |$SESSION_OPTION, which is every agent's. Addresses are `0x…`, exactly as this surface writes them, and
    |never decimal.
    |
    |$SURFACE_METHOD_OPTION is how to work here, read once per session, and $LEAK_METHOD_OPTION is how to find
    |a faulty reference, read once per investigation. Both are text this build prints with nothing open.
    |
    |Opening a heap dump is the one command with a wait worth planning for — minutes, on a large dump, and it
    |does not answer until the dump can be read. $DUMP_HEAP is the other, since it takes one off a device
    |first.
    |
    |Exit code $ANSWERED when the answer is on stdout, $REFUSED when the command was refused and the refusal
    |is on stderr, $NOTHING_ANSWERED when there was nothing to answer it.
  """.trimMargin()

  /** All of one command: what it answers, and every argument it takes. See [commandsHelp]. */
  fun commandHelp(
    command: String,
    commandName: String
  ): String {
    val commands = described()
    val asked = commands.firstOrNull { it.name == commandName }
      ?: return "There is no command called \"$commandName\". This build has " +
        commands.joinToString(", ") { it.name } + "."
    return asked.helpText(command)
  }

  /** The options a command line takes beside a command, for the one place that lays them out. */
  fun cliOptions(): List<Pair<String, String>> = listOf(
    "$CLI_OPTION <command> name=value" to
      "Makes one call and prints the answer as JSON. Required on every command.",
    "$RUN_OPTION<pid>" to "Which run to talk to, for a machine with more than one open.",
    "$SESSION_OPTION<name>" to
      "Which session these commands are one of, letters and digits. An agent passes something naming its " +
      "own session, so that a reviewer reading its logs can find the investigation beside them. Every " +
      "command of a named session needs its `$REASON`.",
    NO_UI_OPTION to
      "With a command that starts a run: have it draw no window, for a machine with no screen.",
    "$HELP_OPTION <command>" to "All of one command: what it answers, and every argument it takes.",
    SURFACE_METHOD_OPTION to
      "How to work here: what every call records, what to put on screen, what to put in your reply. Read it " +
      "once per session.",
    LEAK_METHOD_OPTION to
      "How to investigate leaks of objects that reached their lifecycle end. Read it once per investigation."
  )

  /** How to work on this surface at all, which is text this build carries rather than an answer. */
  fun surfaceMethod(): String = AgentMethod.SURFACE

  /** What to do with a leak, which is text this build carries rather than an answer. See [AgentMethod]. */
  fun leakMethod(): String = AgentMethod.LEAK

  /**
   * Whether a word of a command line is one argument of a call, `name=value`.
   *
   * The one rule both ends of [CLI_OPTION] read a command line by: whatever this says is an argument is sent to
   * the command, and whatever it doesn't is the command line of the run this may have to start. Two definitions
   * of that would be a heap dump path quietly sent as an argument, or an argument quietly opened as a heap dump.
   *
   * A name of letters and digits, which is what every argument on this surface is called, so that a path is
   * still a path — `/tmp/a=b.hprof` has a slash in its name and is therefore no argument. The one it gets
   * wrong is a relative path with an `=` in it and no directory, which is a file nobody has.
   */
  fun isCallArgument(word: String): Boolean {
    val name = word.substringBefore('=', missingDelimiterValue = "")
    return name.isNotEmpty() && name.first().isAsciiLetter() && name.all { it.isAsciiLetterOrDigit() }
  }

  /**
   * What a command joins when nothing said: the nearest process above this one that outlives a single command,
   * which is the shell an investigation is typed in, or the agent driving that shell.
   *
   * A connection held open for a whole investigation would gather its own calls, so a process per command has to
   * find something that plays that part, and the obvious candidate is the parent — a person's shell lives as
   * long as their conversation does. **It is the wrong answer for the agent this exists for.** Claude Code runs
   * every command it issues in a `zsh -c` of its own, so the parent's id is a session per command again:
   * measured, one investigation of nine commands wrote nine session files and drew nine rows of the *Agent
   * logs* screen.
   *
   * So a shell that was handed one command to run is walked past — it ends with that command, which is the
   * definition of what cannot gather calls — and its parent is the session instead. For an agent that is the
   * agent's own process, whose life is the conversation; for a person it is still their own shell, which has no
   * command on its command line, and a shell per terminal tab is what they would expect. Falls back to this
   * process, which is a session per command: an ancestry that cannot be read is calls that cannot be gathered,
   * and a row each is better than landing in somebody else's session.
   *
   * **An agent should pass [SESSION_OPTION] rather than rely on this**, with its own session id in the name:
   * this can only ever name a process, and what a reviewer of an agent's logs has in front of them is the
   * agent's session. A process id is what turns that into a search.
   */
  fun defaultSessionName(): String = sessionName(ProcessHandle.current())

  /**
   * [defaultSessionName] from anywhere in a process tree, which is how it is tested: a test can start a shell
   * and find what it ran, and cannot be the thing the shell ran.
   */
  internal fun sessionName(start: ProcessHandle): String {
    var session = start
    // Bounded because this walks a tree read one handle at a time, and a cycle in it would be a hang in
    // something every command goes through.
    repeat(MAX_COMMAND_SHELLS_WALKED_PAST + 1) {
      val parent = session.parent().orElse(null) ?: return "$SESSION_NAME_PREFIX${session.pid()}"
      session = parent
      val information = parent.info()
      val ranOneCommand = ranOneCommand(
        command = information.command().orElse(null),
        arguments = information.arguments().orElse(emptyArray()).asList()
      )
      if (!ranOneCommand) return "$SESSION_NAME_PREFIX${parent.pid()}"
    }
    return "$SESSION_NAME_PREFIX${session.pid()}"
  }

  /**
   * Whether a process is a shell that was given one command, `sh -c "…"`, and therefore ends when that
   * command does. See [defaultSessionName].
   *
   * Both halves are needed. Without the name, anything carrying a `-c` is walked past — `claude -c` resumes a
   * conversation — and the session would land on whatever launched *that*, gathering separate conversations
   * into one. Without the `-c`, an interactive shell is walked past too, and a person's session would be
   * their terminal, which merges the tabs and panes they opened precisely to keep work apart.
   *
   * A name this doesn't know is a shell whose commands go back to being a session each, which is what makes the
   * list safe to be incomplete. The option is read a letter at a time because a short option carries
   * others — `-lc` is a login shell running one command — and `--` begins something else entirely.
   */
  internal fun ranOneCommand(
    /** As [ProcessHandle.Info.command] has it, a path, and null for a process this one may not read. */
    command: String?,
    arguments: List<String>
  ): Boolean {
    val name = command?.substringAfterLast('/') ?: return false
    if (name !in COMMAND_SHELLS) {
      return false
    }
    return arguments.any { argument ->
      argument.length > 1 && argument.startsWith("-") && !argument.startsWith("--") && 'c' in argument
    }
  }

  private fun call(
    socket: Socket,
    run: AgentServer.PublishedRun,
    commandName: String,
    arguments: JsonObject,
    sessionName: String
  ): Int {
    val toApp = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
    val fromApp = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    // The token and then which session this call is one of, on one line, because a process per command would
    // otherwise be a session per command and a session is what somebody reads afterwards. See [AgentServer].
    toApp.println("${run.token} $sessionName")
    if (fromApp.readLine() != AgentServer.ACCEPTED) {
      say("Shark Dive run ${run.pid} refused the token in ${run.file}, so it is not the run that wrote it")
      return NOTHING_ANSWERED
    }
    toApp.println(AgentWire.encode(AgentWire.call(commandName, arguments)))
    // No read timeout, because opening a heap dump is minutes and taking one off a device is more: what this
    // is waiting on is the run doing the work, and it says so in its own log while it does.
    val line = fromApp.readLine()
    if (line == null) {
      say("Shark Dive stopped answering during $commandName, so the run it was in has gone")
      return NOTHING_ANSWERED
    }
    val response = AgentWire.decodeOrNull(line)
    if (response == null) {
      say("Shark Dive answered $commandName with something that is no JSON object: $line")
      return NOTHING_ANSWERED
    }
    return printed(commandName, response)
  }

  /**
   * Puts the answer on stdout and anything else on stderr, and says which happened in the exit code.
   *
   * Two streams and three codes because **a refusal is not a failure of the command**: it is what the surface
   * answered, and its message is the next thing to do. So a shell keeping stdout for the JSON still shows the
   * sentence, and a script can tell "it said no" from "nothing answered", which is the difference between
   * going and reading something and going and looking at the app's log.
   */
  private fun printed(
    commandName: String,
    response: JsonObject
  ): Int {
    AgentWire.refusalOf(response)?.let { refusal ->
      say(refusal)
      return REFUSED
    }
    AgentWire.failureOf(response)?.let { failure ->
      say("Shark Dive could not answer $commandName: $failure")
      return NOTHING_ANSWERED
    }
    val answer = AgentWire.answerOf(response)
    if (answer == null) {
      say("Shark Dive answered $commandName with no answer, no refusal and no failure: $response")
      return NOTHING_ANSWERED
    }
    val out = PrintWriter(OutputStreamWriter(System.out, Charsets.UTF_8), true)
    out.println(AgentWire.pretty(answer))
    out.flush()
    return ANSWERED
  }

  /**
   * The arguments as JSON: every value as it was typed, except the ones the schema says are lists.
   *
   * Which works because [AgentArguments] reads a number and a boolean out of text — the commands were written
   * for a model, and a model sends `limit=30` as a string as often as not. So a command line spells
   * everything the way a person types it, and the one shape with no spelling of its own is a list: those are
   * comma separated, a shell having no brackets.
   */
  private fun argumentsOf(
    commands: List<AgentTool>,
    commandName: String,
    words: List<String>
  ): JsonObject {
    val lists = commands.firstOrNull { it.name == commandName }?.listArguments().orEmpty()
    return buildJsonObject {
      words.forEach { word ->
        require(isCallArgument(word)) {
          "\"$word\" is no argument of $commandName. An argument is `name=value`, and a value with spaces " +
            "in it is quoted: reason=\"why I am asking\"."
        }
        val name = word.substringBefore('=')
        val value = word.substringAfter('=')
        if (name in lists) {
          putJsonArray(name) { value.split(LIST_SEPARATOR).forEach { add(it.trim()) } }
        } else {
          put(name, value)
        }
      }
    }
  }

  /** The commands of this build, described. Built per call, so nothing here is shared between threads. */
  private fun described(): List<AgentTool> = AgentTools(NoHeapDumpToDescribe) { nothingToDescribeWith() }.all

  /** One line each, in the order an investigation uses them, which is the order [AgentTools.all] is in. */
  private fun commandColumn(): String = described()
    .joinToString("\n") { "  ${it.name.padEnd(COMMAND_WIDTH)}${it.summary}" }

  /** Answered: the command's own JSON is on stdout. */
  const val ANSWERED = 0

  /**
   * Nothing answered: no run to talk to, one that has gone, a command line this could not read, or a command
   * the app could not answer at all.
   *
   * A command that did nothing has to fail, or whatever ran it carries on as though it had an answer. The
   * last of the four is why this is not the same code as a refusal: a read that threw is the app falling over
   * and the next thing to do about it is in `~/.shark-dive/logs`, not in the message. See [REFUSED].
   */
  const val NOTHING_ANSWERED = 1

  /** Refused: the command said no, and stderr says what to do about it. */
  const val REFUSED = 2

  /**
   * What a command line says to make one call, on every one of them.
   *
   * Required rather than implied by a command name, so that a command line is either a command or a run of the
   * app and nothing has to guess which — which is what the word `--agent` got wrong twice over: it read as the
   * option for agents, on a surface a person types as often, and it left `--agent-help` and `--no-ui` looking
   * like options of a different feature. See `shark.dive.app.DiveArguments`.
   */
  const val CLI_OPTION = "--cli"

  /**
   * And to read what the commands are, which needs no run and no heap dump. See [commandsHelp].
   *
   * The same option the app's own command line answers, rather than an `--agent-help` beside it: one of the two
   * was the more likely thing for an agent to type and the other was the one that listed the commands, and a
   * program that answers "what do you take" with half of what it takes is a program that hid the half it was
   * asked about.
   */
  const val HELP_OPTION = "--help"

  /**
   * And how to work on this surface at all, which is one of the two texts this build carries.
   *
   * An option rather than a field of the first answer of a session, which is where it used to be: a text
   * prepended to whatever a session asked first is a text an agent reads *after* making the call it had already
   * decided to make, and the half of it that says what to put in a reply is the half that arrives too late to
   * change the first one. Both halves of the method are a read now, which is also the only shape in which
   * reading one costs a session nothing until it asks. See [AgentMethod].
   */
  const val SURFACE_METHOD_OPTION = "--investigation-help"

  /**
   * And the method for finding a faulty reference, which is the other text this build carries.
   *
   * An option rather than a field of `list_leaks`'s answer, which is where it used to be: an investigation of
   * several leaks called that once per leak and read the whole method again each time, and the method is about
   * the chain rather than about the list. Read once per session, by the session that has a leak to work on.
   * See [AgentMethod].
   *
   * The longer name of the two on purpose: [SURFACE_METHOD_OPTION] is the one every session reads and this is
   * the one an investigation of a leak reads, so the names say which is the special case. A caller that guesses
   * the short one and wanted this is pointed here by its second paragraph.
   */
  const val LEAK_METHOD_OPTION = "--leak-investigation-help"

  /**
   * What a command line says to put its calls in one session, rather than one session per command.
   *
   * **An agent is expected to pass one**, naming the session it is working in — its own session id appended to
   * a word a person would recognise. Optional is for the human case: a shell is a session already, so a person
   * following an investigation of their own gets one row without asking. An agent has no shell of its own that
   * outlives a command, and the thing a reviewer reading its logs wants to find is the session, which is why
   * the recommendation is to put the id where they will search for it. See [defaultSessionName].
   */
  const val SESSION_OPTION = "--session="

  /** Which run to talk to, for a machine with several open. See [runToTalkTo]. */
  const val RUN_OPTION = "--run="

  /**
   * Answer commands and open no window, for a machine that has no screen to open one on.
   *
   * Named after what it does *not* do, rather than `--headless` or `--server`, because every run of this app
   * answers commands and only some of them have a user interface.
   *
   * **Two positions, one meaning.** On a run it is what that run is; on a command that may start one it is
   * which kind to start, and a mismatch with the run that answers is refused rather than papered over — see
   * [kindMatches]. It is on no other command, since every one of those reads a dump that is open already and a
   * dump open with no window reads exactly like one open in a window. See [STARTS_A_RUN].
   */
  const val NO_UI_OPTION = "--no-ui"

  /** How the session of a command from here is named, so that a file says what made it. */
  private const val SESSION_NAME_PREFIX = "cli"

  /**
   * The names [ranOneCommand] will walk past, when one of them was given a command. Every shell an agent or a
   * terminal on a developer machine is likely to start a command with; a name missing from it costs that
   * shell's commands being a session each, which is what they were for every shell before this list existed.
   */
  private val COMMAND_SHELLS = setOf(
    "sh", "bash", "zsh", "dash", "ksh", "mksh", "ash", "fish", "csh", "tcsh", "nu", "pwsh"
  )

  /** See [defaultSessionName]. A shell inside a shell inside a shell, and then some. */
  private const val MAX_COMMAND_SHELLS_WALKED_PAST = 4

  /** Wide enough for the longest command name, since the summaries read as a column or as nothing. */
  private const val COMMAND_WIDTH = 20

  private const val LIST_SEPARATOR = ','

  private const val CONNECT_TIMEOUT_MILLIS = 1_000

  /**
   * How long a run this command line started is given to publish itself, which is the only wait there is.
   *
   * Long because it covers a cold JVM, Compose starting and jlink's runtime being paged in, and because the
   * alternative to waiting is telling an agent there is no run while the one it asked for is appearing.
   */
  private const val OPENING_WAIT_MILLIS = 60_000L

  private const val POLL_MILLIS = 250L

  /**
   * The commands a run may be started for, which are the ones whose answer says nothing about what was open.
   *
   * **A narrowing of "start one if there is none", and the rule is what each command's answer is about.**
   * [OPEN_HEAP_DUMP] and [DUMP_HEAP] each hand back a heap dump they put there, and [LIST_DEVICES] and
   * [LIST_PROCESSES] ask `adb` rather than any dump — so each of them answers the same in a run it just started
   * as in the run somebody is working in. Every other command is a question *about* a run: answered by one
   * started to answer it, it comes back empty, and an empty answer from a fresh run reads exactly like an empty
   * answer from the run that was already there. [LIST_HEAP_DUMPS] is the one to check that rule against — it
   * needs no heap dump either, and it is not here because what it answers *is* what the run has open.
   *
   * [RUN_OPTION] starts nothing whichever command it is on: it names a run, and starting a different one would
   * answer about the wrong heap dump.
   */
  private val STARTS_A_RUN = setOf(OPEN_HEAP_DUMP, DUMP_HEAP, LIST_DEVICES, LIST_PROCESSES)

  /**
   * The commands that open a heap dump, which are the ones a run's kind is checked against. See [kindMatches].
   *
   * [LIST_DEVICES] and [LIST_PROCESSES] may start a run and are deliberately not here: they open nothing, so
   * whether the run that answers draws windows is nothing about their answers, and refusing one over that would
   * be a refusal with no consequence behind it. What [NO_UI_OPTION] still does there is say what kind of run to
   * start if one has to be.
   */
  private val OPENS_A_HEAP_DUMP = setOf(OPEN_HEAP_DUMP, DUMP_HEAP)

  /**
   * The one run to talk to, starting one if there is none, and null for every reason there is no one run.
   *
   * **Two runs is an error rather than a choice**, which is the rule the rest of this reads out of: a command
   * that picked the newest of them would be a command whose heap dump depends on what else is on the machine,
   * and the one before this did exactly that — it said which run it had picked on stderr and answered about
   * whatever that one had open. So the ones this build can see are filtered by [buildSha] first, which on a
   * machine in the middle of a branch is usually what leaves one, and what is left is either one run or a
   * message naming them.
   *
   * **And only the commands in [STARTS_A_RUN] start one**, which is a deliberate narrowing of "no run, so start
   * one": the rest are refused, with the command that opens a dump as the message.
   *
   * **Looked for once, and waited for only when this command is starting one.** A run publishes itself as it
   * starts and takes that file away three ways as it ends — a shutdown hook, the `Closeable`, and whoever reads
   * the file of a process that has gone deleting it — so a run that is up is a run that is published, and
   * polling for one to appear is only ever a bet that one is in the middle of starting. Which is a bet worth
   * making when this command line is what started it, and otherwise ten seconds of saying nothing before saying
   * what was already known. A run that is published and does not answer is the other case and is covered
   * elsewhere: the connect has [CONNECT_TIMEOUT_MILLIS] and whoever finds it deletes the file. See
   * [AgentServer].
   */
  @Suppress("ReturnCount")
  private fun runToTalkTo(
    directory: File,
    command: String,
    commandName: String,
    pid: String?,
    buildSha: String,
    noWindow: Boolean,
    openARun: ((noWindow: Boolean) -> Unit)?
  ): AgentServer.PublishedRun? {
    var waited = 0L
    // Nothing to wait for until something is being started, which is the paragraph above.
    var deadline = 0L
    var started = false
    // Naming a run names the heap dumps it has open, so starting a different one would be answering about the
    // wrong dump: for that command line there is nothing to start, only something to look for.
    val startsARun = openARun != null && pid == null && commandName in STARTS_A_RUN
    while (true) {
      val published = AgentServer.publishedRuns(directory)
      val runs = published.filter { it.buildSha == buildSha }
      if (runs.size > 1 && pid == null) {
        say(severalRuns(command, runs))
        return null
      }
      val run = if (pid == null) runs.singleOrNull() else runs.firstOrNull { it.pid == pid }
      if (run != null) {
        return run.takeIf { kindMatches(it, command, commandName, noWindow) }
      }
      otherBuildRun(published, pid, buildSha)?.let { other ->
        say(
          "Shark Dive run $pid was built from ${other.buildSha}, and this command line is $buildSha, so the " +
            "commands it has are not the commands this one knows about. Its own build is what to call it with."
        )
        return null
      }
      if (!started && startsARun) {
        say("No Shark Dive is running, so one is being started to investigate in.")
        requireNotNull(openARun).invoke(noWindow)
        started = true
        // The whole of the waiting, and only from here: what there is to wait for is a JVM starting, Compose
        // coming up and a run publishing itself, which is a thing this command line knows is on its way.
        deadline = waited + OPENING_WAIT_MILLIS
      }
      if (waited >= deadline) {
        say(nothingToTalkTo(directory, command, pid, buildSha, started))
        return null
      }
      Thread.sleep(POLL_MILLIS)
      waited += POLL_MILLIS
    }
  }

  /**
   * Whether this run draws windows if [NO_UI_OPTION] said it should, and a message either way that it doesn't.
   *
   * **Checked against the run rather than against the heap dump**, which is a reading of "open it with no
   * window" worth being explicit about: whether anything is drawn is decided once, as a run starts — a run with
   * no window cannot start Compose at all, and on the machine [NO_UI_OPTION] exists for there is no display to
   * start it on. So there is no opening one dump of a run with a window and one without, and the two questions
   * coincide wherever the dump asked about is open in the run being talked to.
   *
   * Only for the commands that open one — [OPENS_A_HEAP_DUMP] — since every other command reads a dump that is
   * open already, and a dump open with no window answers exactly as one open in a window does.
   */
  private fun kindMatches(
    run: AgentServer.PublishedRun,
    command: String,
    commandName: String,
    noWindow: Boolean
  ): Boolean {
    if (commandName !in OPENS_A_HEAP_DUMP || run.hasWindow == !noWindow) {
      return true
    }
    say(
      if (noWindow) {
        "Shark Dive run ${run.pid} draws windows, and $NO_UI_OPTION asks for a run that draws none — which " +
          "is what a run is rather than what one heap dump is, so there is no opening a dump without a " +
          "window inside it. Leave $NO_UI_OPTION off to open this dump in a window of that run."
      } else {
        "Shark Dive run ${run.pid} was started with $NO_UI_OPTION, so it draws no window and a heap dump " +
          "opened in it has none either. Pass $NO_UI_OPTION to open it there anyway, which is what a " +
          "machine with no screen does."
      }
    )
    return false
  }

  /** A run named by [pid] that this command line has no business talking to. See [kindMatches]. */
  private fun otherBuildRun(
    published: List<AgentServer.PublishedRun>,
    pid: String?,
    buildSha: String
  ): AgentServer.PublishedRun? {
    if (pid == null) {
      return null
    }
    return published.firstOrNull { it.pid == pid && it.buildSha != buildSha }
  }

  private fun severalRuns(
    command: String,
    runs: List<AgentServer.PublishedRun>
  ): String = "${runs.size} Shark Dive runs are open, so which heap dumps there are to read depends on which " +
    "of them you meant. Pass $RUN_OPTION<pid> to say: " +
    runs.joinToString(", ") { "${it.pid} (${it.kindText()})" } + ". `$command $CLI_OPTION $LIST_HEAP_DUMPS " +
    "$RUN_OPTION<pid> reason=…` is what each has open."

  private fun AgentServer.PublishedRun.kindText(): String = if (hasWindow) "with windows" else "with no window"

  private fun nothingToTalkTo(
    directory: File,
    command: String,
    pid: String?,
    buildSha: String,
    started: Boolean
  ): String = when {
    started ->
      "A Shark Dive run was started and has not published itself in ${OPENING_WAIT_MILLIS / 1000} seconds, " +
        "so something went wrong opening it. Its log is in the newest file under ~/.shark-dive/logs."
    pid != null ->
      "No Shark Dive run of this build is $pid. Open: " +
        AgentServer.publishedRuns(directory).joinToString(", ") { "${it.pid} (${it.buildSha})" }
          .ifEmpty { "none" }
    else ->
      "No Shark Dive run of this build ($buildSha) is open, so there is no heap dump to read. " +
        "`$command $CLI_OPTION $OPEN_HEAP_DUMP path=<heap dump> reason=…` opens one, and starts a run when " +
        "there is none. Every run publishes itself in $directory."
  }
}

/**
 * On stderr, always: where a shell shows what a command is doing, and where whatever ran it collects that.
 *
 * Not through `SharkLog`: this process installs none of the app's logging, since that writes to stdout and
 * stdout is where the answer goes.
 */
internal fun say(message: String) {
  System.err.println("[shark-dive] $message")
}

internal fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

internal fun Char.isAsciiLetterOrDigit(): Boolean = isAsciiLetter() || this in '0'..'9'

/** One argument of a command, as the help prints it. */
private class HelpArgument(
  val name: String,
  val schema: JsonObject,
  val isRequired: Boolean
)

/**
 * All of one command, which is what `--help <command>` prints.
 *
 * The summary above the description rather than only the description, because this is also what somebody reads
 * after picking the command out of the list: the line they picked it by is the line that says whether they
 * picked the right one. And `reason` is listed here, unlike in the list, since there is no preamble beside this
 * to say it is on every command.
 */
private fun AgentTool.helpText(command: String): String = buildString {
  appendLine("$name — $summary")
  appendLine()
  appendLine("  ${AgentCommandLine.CLI_OPTION.let { "$command $it $name" }} " +
    arguments().joinToString(" ") { if (it.isRequired) "${it.name}=…" else "[${it.name}=…]" })
  appendLine()
  appendLine("  $description")
  appendLine()
  arguments().forEach { appendLine("  ${it.helpLine()}") }
}

/** Which of a command's arguments are lists, which is the one thing a command line has to spell specially. */
private fun AgentTool.listArguments(): Set<String> =
  arguments().filter { it.schema.type() == ARRAY_TYPE }.map { it.name }.toSet()

private fun AgentTool.arguments(): List<HelpArgument> {
  val properties = schema[PROPERTIES_KEY] as? JsonObject ?: return emptyList()
  val required = (schema[REQUIRED_KEY] as? JsonArray).orEmpty()
    .mapNotNull { (it as? JsonPrimitive)?.content }
  return properties.mapNotNull { (name, element) ->
    (element as? JsonObject)?.let { HelpArgument(name, it, name in required) }
  }
}

private fun HelpArgument.helpLine(): String {
  val kind = listOfNotNull(schema.kind(), "optional".takeIf { !isRequired }).joinToString(", ")
  return "$name ($kind) — ${schema.description()}"
}

/**
 * How a value of this argument is written on a command line, which is not its JSON type.
 *
 * Every value is typed as text and read as whatever the command asks for — see `AgentCommandLine.argumentsOf` —
 * so what a reader needs here is what to type, rather than that JSON has numbers in it.
 */
private fun JsonObject.kind(): String {
  val values = (this[ENUM_KEY] as? JsonArray)?.contents()
  val itemValues = ((this[ITEMS_KEY] as? JsonObject)?.get(ENUM_KEY) as? JsonArray)?.contents()
  return when {
    values != null -> values.joinToString(" or ")
    type() == ARRAY_TYPE ->
      "comma separated" + itemValues?.let { ", from ${it.joinToString(", ")}" }.orEmpty()
    type() == INTEGER_TYPE -> "a whole number"
    type() == BOOLEAN_TYPE -> "true or false"
    else -> "text"
  }
}

private fun JsonArray.contents(): List<String> = mapNotNull { (it as? JsonPrimitive)?.content }

private fun JsonObject.type(): String? = (this["type"] as? JsonPrimitive)?.content

private fun JsonObject.description(): String = (this["description"] as? JsonPrimitive)?.content.orEmpty()

/**
 * The heap dumps of a run that is answering nobody, which is every method throwing.
 *
 * [AgentTools] holds these so that its handlers can read a heap dump, and **describing a command never calls
 * its handler** — so a registry built on this can be printed and cannot be used. Throwing rather than
 * answering with nothing, because "no heap dump is open" is an answer an agent would act on and this is not
 * that: it is a description of a build, with nowhere for a call to go.
 */
private object NoHeapDumpToDescribe : AgentHeapDumps {

  override fun openHeapDumps(): List<AgentHeapDump> = nothing()

  override fun openingHeapDumpPaths(): List<String> = nothing()

  override suspend fun open(file: File): AgentHeapDump = nothing()

  override suspend fun close(dump: AgentHeapDump): Unit = nothing()

  override suspend fun devices(): List<AndroidDevice> = nothing()

  override suspend fun processesOf(serialNumber: String): List<DeviceProcess> = nothing()

  override suspend fun dumpHeap(
    serialNumber: String,
    processName: String
  ): AgentHeapDump = nothing()

  private fun nothing(): Nothing = nothingToDescribeWith()
}

/** The same for the sessions the log command reads, which a build being described has no directory for. */
private fun nothingToDescribeWith(): Nothing = throw IllegalStateException(
  "These commands are only being described, so there is no heap dump here and nothing to call: a call goes " +
    "to the run of the app that has one open. See AgentCommandLine."
)

private const val PROPERTIES_KEY = "properties"
private const val REQUIRED_KEY = "required"
private const val ENUM_KEY = "enum"
private const val ITEMS_KEY = "items"
private const val ARRAY_TYPE = "array"
private const val INTEGER_TYPE = "integer"
private const val BOOLEAN_TYPE = "boolean"
