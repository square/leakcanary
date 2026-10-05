# Shark Dive's agent surface — agent guide

One command typed at a window: `--cli <command> name=value …`, answered by the run that already has the heap
dump open, so that an agent investigates the heap dump **in the window somebody is looking at** rather than
one of its own.

**`--cli` is required on every one of them**, and it is what tells a command from a run of the app. Nothing
infers it from a command name, because the two things this command line does — run the app, and say one thing
to a run of it — are both ordinary command lines, and a program guessing which one it was handed guesses wrong
on the one that matters. See `shark.dive.app.cliExitCode`.

**And the two forms each open a heap dump their own way, so saying both is unreadable rather than either.**
A command line with no `--cli` opens the files and follows the links named on it as the app starts; a command
line with `--cli` opens a dump by calling `open_heap_dump path=…` and goes to a place by calling `show`, and
both answer that they did. So `--cli list_leak_groups … dump.hprof` says two different things to do, and
`shark.dive.app.twoFormsAtOnce` says so and exits rather than guessing at one of them. The run a command
starts is given a title and nothing else to open — the dump is the command's to open, which is what makes the
answer the thing that says a dump was opened.

**There is one way in, and it is a command line.** This was an [MCP](https://modelcontextprotocol.io) server
as well, over a stdio pipe an MCP client launched, and the two of them were a protocol to maintain, a
handshake to answer, a session file field saying which way a line came in, and a client-shaped row on a
screen meant for an investigation. The command line is what an agent reaches for without being configured at
all, so that is what is left. See `notes/agent-surface.md` for what each of them cost.

**A run with no window is not a second way in.** `--no-ui` is a run of the app with nothing drawing it, and
it publishes the socket below exactly as a window's run does, so the call that reaches it is the same call —
see `shark-dive-app`'s `HeadlessAgentHeapDumps`. What went with MCP was a headless mode with a transport of
its own, not the case of having no screen.

This file is scoped to `shark/shark-dive/shark-dive-agent/`. Its parent,
`shark/shark-dive/AGENTS.md`, has the app-wide rules — the heap dump being read off the UI thread, a
verdict being an argument to every read — and they all apply here. This one only records what is specific to
being talked to by a program that is not this app.

## What the pieces are

| File | What it is |
| --- | --- |
| `AgentHeapDump.kt` | The seam: one open heap dump, as everything here sees it — opened, listed, read and closed. The app implements it over a window; the tests implement it over a `HeapDive` and three fields. |
| `AgentTools.kt` | Every tool, each a name, a schema and one read. The two ways in, `open_heap_dump` and `list_heap_dumps`, and the way out, `close_heap_dump`. Where the refusals are. |
| `AgentPlace.kt` | Where a tab is, as one string an agent can be answered with and hand back. Both directions. |
| `AgentMethod.kt` | The method, as two texts: how to work here at all, and how to find a faulty reference. |
| `AgentJson.kt` | Shark Dive's model as JSON. |
| `AgentTool.kt` | One tool, its arguments read strictly, and `AgentRefusal`. |
| `AgentWire.kt` | What crosses the socket, spelled once: a call out, and one of three things back. |
| `AgentConnection.kt` | One agent being talked to: a call per line in, an answer per line back, and every line written down. |
| `AgentSessionFile.kt` | One session on disk, both ways: what a call is written as, and what it reads back as. |
| `AgentServer.kt` | The loopback socket a run publishes, and the file that says where. |
| `AgentCommandLine.kt` | `--cli <command> name=value …`: one call typed at a window, over that socket. Which run to talk to, and the help, generated from the registry. |
| `harness/start-harness.sh` | Copies the app into a directory of its own and starts an agent on one heap dump, which opens the window itself. |
| `harness/watch-transcript.sh` | Follows that run's client transcript as it is written — every call it made to anything, and what came back. |
| `harness/eval/run-eval.sh` | Throws an agent at a heap dump whose answer is known, and scores what it did. The dumps and the scoring are `shark-dive-eval`. |

Nothing here is public API — the module is in `modulesWithoutPublicApi`, like the rest of Shark Dive — with
two deliberate exceptions, `AgentServer`/`AgentCommandLine`/`AgentHeapDump*`/`AgentSession*` because the app
calls them, and `AgentRefusal` because the app throws it.

## The refusals are the feature

The whole point of this being a server rather than a library is that **it can say no**, and it works with any
client because saying no is all it does — nothing here ever calls a model.

- `set_verdict` refuses a blank `why` (through `LeakStatusOverride`'s own `require`) and refuses a verdict
  that contradicts one already recorded unless it is told to flip it. **`why` is the verdict's own
  justification and `reason` is why the call was made**, which is two arguments for what was one: the `why` is
  kept in the dump's `leak-statuses` file and drawn in the window's *Why* box for as long as anybody reads
  that dump, and the `reason` is a line of this session's log like every other tool's. A model handed the
  single `reason` sent both anyway — see `WHY`'s KDoc for the measurement — so the surface now takes both.
- `set_verdict` also refuses a `solvingLeakOf` that is not a stuck object — `requireStuck`, before anything is
  recorded, so a refused call leaves no verdict behind. That argument names *the leak being solved*, and a
  path ending at an object this dump reads as expected or knows nothing about has no fault on it to narrow, so
  every number the answer carries about it would be about nothing. Left unchecked it reads as a progress of 0
  that never moves, which is the same answer as a verdict that achieved nothing.
- Every tool's schema requires a `reason`, and `AgentCommandLine` refuses a command of a named session that
  sent none — a client being free to ignore a schema. Which is a *pair* with `--session=` rather than a rule of
  its own; see the command line section below for why that is where it is checked.

So a change that makes any of these easier to satisfy is a change that removes the reason this module exists.

**And nothing on this surface asks an agent which reference is at fault.** There was a `conclude` tool that
took one and refused until the heap dump agreed, and the refusal was its whole point: an agent that had
narrowed a path to two candidates could not report a root cause however confident it was. What was wrong with
it is that the reference it checked was one the tool had already derived and answered with — so the check
measured whether a model can copy a string back, and the eval scored a restatement. `shark/shark-dive/notes/decisions.md`
has it in full. **The job is to settle the state of every undecided object until one reference is left**, and
`leakSolved` is the heap dump saying that happened. So a reading an agent cannot reach any other way is one
to answer with, never one to ask it to repeat.

What replaced the refusal is that the answer carries the distance left. `path_from_gc_root` and a
`set_verdict` given `solvingLeakOf` both answer with `whatThePathSays` — `leakSolved`, the candidate
references, the undecided objects, `suspectReferenceCount` and `leakSolvingProgressRatio` — and `PathState`
is which of the six shapes those verdicts are in, which is the field to branch on rather than the prose in
`next`. A verdict answers with `narrowedBy` as well, the counts and the progress on both sides of it, because
a verdict that ruled nothing out and a verdict that halved the search are the same single number afterwards
and are not the same move. **The `Ratio` on all three of those names is load-bearing** — the number is 0 to 1
and the other way to spell a share is 0 to 100, so a name that says neither gets read as whichever the reader
expects; `shark/shark-dive/AGENTS.md` has the convention it comes from. `AgentToolsTest` walks that story — a verdict with `solvingLeakOf`, two candidates
down to one, `leakSolved` true — and it is the test to keep working.

The `reason` is traceability and not a quality gate. Asking a model to explain itself does not make it right,
and [the research says it can make it worse](https://arxiv.org/abs/2504.09664); what it buys is a session log
someone can follow afterwards instead of a conclusion they have to trust.

## A session file has two readers, and neither is in this process

`AgentSessionFile` writes `~/.shark-dive/agents/sessions/agent-<when>-<id>.jsonl`, one file per
connection, a JSON object per line, the newest `KEEP_SESSION_COUNT` kept. What reads it back is **the window's
*Agent logs* screen and the eval in `notes/agent-eval.md`** — one artefact, two readers, which is why the
reading half lives here beside the writing half and is tested with it. A field written and never read back is
a row of that screen saying nothing.

What follows from that, and reading the code won't tell you:

**A call is described before it is answered, not after.** `AgentConnection.callTool` asks `AgentTools.target`
what the call is about and only then invokes the handler, so **a refused call still records its place** and its row
is still clickable. That is deliberate: the refusals are the half of a session worth reading afterwards, and
a refusal nobody can follow up on is a dead end on the screen. `target` derives the place from the argument
*names* rather than from a second list of tool names — except for the four tools that take no argument saying
where they are, which are named in `placeOrNull` because **every call that goes somewhere in the window has to
lead there**: the leaks, the agent log, and `dominator_tree` and `find_objects` given nothing, which are the
tree from its root and the object list unfiltered. Anything left with no place is a call about the app rather
than about a heap dump.

**A session records addresses, and is read in the window of its heap dump.** What an agent types is
`0x12d368b8` and what the screen shows is `MainActivity 0x12d368b8`, so somebody has to resolve it — and
resolving an address means having *that* dump open. Which the reader does: the *Agent logs* screen of a window
lists the sessions that read the dump it has open, `AgentSession.heapDumpPaths`, and the rest are opened in a
window of theirs. So nothing here writes the name down. Recording it was tried and reverted: it put one extra
heap dump read on every call to answer a question the reader already has the dump for.

`heapDumpPath` per call rather than per session is what makes that work, and it is not redundant — an agent
can open a second dump, and a call about one this window hasn't got is a row it leaves as the address, saying
which file, and opens that dump when clicked.

**A call keeps the exchange as well as this app's reading of it.** `input` is the tool's own name and then
the arguments as they arrived, formatted, and `output` is the answer as the text that reached the model — the
same string `AgentWire.pretty` hands the caller to print, formatted once and then both answered with and
written down, so that a session can be compared against a transcript character for character. Everything
else on a call is derived, and a derived field is the one thing that is no use when the question is why an
investigation went wrong: a step made on an answer that said nothing reads exactly like a step made on one
that said everything.

**`output` is what went back, whatever that was.** A refusal is in it as well as in `refused`, and an error as
well as in `error`, and the two are not the same field said twice: `refused` and `error` are this app's
reading — the method said no, this app could not answer — and `output` is the text the agent was handed. The
first version left `output` null for a refusal on the grounds that the refusal was already written down, and
what that looked like from outside was a call that got no answer at all. **Null on `output` means nothing went
back**, which is only what a session whose app was killed mid-call reads as.

**And a line goes down for every line in, not only the ones that reached a tool.** A line that was not JSON, a
line that named no tool, and a name nothing answers to: all of them. `tool` is null for the ones that reached
no tool, `AgentSession.toolCalls` is the subset that got as far as one, and **that distinction is not
cosmetic** — the eval counts calls, and a run scored on lines sent would have a number that moved with
whatever the caller happened to send. `AgentServerTest` and `AgentConnectionTest` each pin one half of that.

**One row per command typed is what there is to record**, and it comes free now: a process connects, makes
its one call and ends, so nothing crosses this socket that isn't the call itself. It was not free before.
MCP's handshake meant a `tools/call` typed at a window arrived behind an `initialize` of its own, so one
typed command was drawn as two rows — Connected, called, Connected, called, which is what a screen reading an
investigation is least able to afford — and `McpSession.isTheCommandLineSayingHello` existed to drop exactly
that one message. **So don't reintroduce anything in front of the call.** A capabilities exchange, a
`hello`, a version negotiation: each of them is a row of that screen saying a process started and did the
thing the next row already names, and there is nothing for one to carry. What the commands are is `--help`,
and how to work here is `--investigation-help`, and both are text this build prints with no window and no heap
dump — so **nothing an answer carries is anything but the answer**. The method was in one: how to work here
prepended to whatever a session asked first, which made a call that only wanted to know which heap dumps are
open the call that handed over the whole of it. See `AgentMethod`.

The name is in `input` even though `tool` has it, and that is not an oversight: this field is read as one
thing, and a set of arguments lifted away from what they are arguments *to* is the one form of a call nobody
can read on its own.

Two things follow. The window's *Agent logs* screen unfolds **every** row onto this, not only the one that
answers with a list, and does it from a mark under the row rather than from the verb, so a row stays a
sentence with one link in it. And `agent_log` with a session id hands the same text over, which makes it the
one expensive call on this surface — `notes/agent-surface.md` has the measurement. Neither of them truncates:
a session cut to fit is one where the answer that misled an agent is the part that got cut.

**Two fields come off the answer instead.** What an agent asked is what it typed, and what it came to is what
the heap dump *derived* — so `outcomeOfTool` reads the faulty reference out of the two answers that can
carry one, `path_from_gc_root` and `set_verdict`, and only when the same answer says `leakSolved`. Both
halves, because a build that changed one of them without the other then reads as null rather than as a
session recording an outcome from a path that has none. Both readers need that field and neither can work it
out: the screen's last row is what a session came to, and the eval has nothing to mark against its answer key
without it. `openHeapDumpsOfTool` is the other, and the reason is the same shape: `list_heap_dumps` is the one
call whose subject is the app, and the dumps it heard about are in the answer alone. Nothing else reads an
answer — a row saying what a read came back with would be the answer printed twice.

**The verbs are here rather than in the app.** `verbOfTool` is beside the tool names, so that a screen never
spells them itself and drift is one list rather than two. `AgentSessionFileTest` asserts every tool in the
registry has one — **which is what makes the fallback mean something**: a name `verbOfTool` has no verb for is
a name this build has no tool for, so a row reading `Called solve_the_leak` is a typo or a tool from a newer
build, and the name is left exactly as it arrived because that string is what somebody is looking for. A line
that named no tool at all has one sentence for all of them, since there is nothing in it to name it after.

**A verb stops where the thing it was about starts**, which is why several of them end mid-sentence: a row of
that screen is prose with one link in it, and the link is the thing. So `list_leak_groups` is "Listed the" and
`screenOfTool` is the *leaks* after it, in lower case because it is inside a sentence rather than a tab title.
Every tool `placeOrNull` names has words there, and only those — `AgentSessionFileTest` fails on either half
of that being added without the other, since a place with no words is a call that went somewhere the reader is
never shown, and words with no place are a link to nothing.

**Writing a session never throws and never blocks the answer.** A bad line is skipped on read with a
`SharkLog.d` saying which, a file whose header is missing falls back to the id in its name, and a truncated
last line — an app killed mid-write — keeps every call before it. An agent's call must not fail because the
record of it couldn't be written.

## In a `--cli` process, stdout is the answer

`main` answers `cliExitCode` **before `installLogging()`**, because that logger writes to stdout and
a log line in the middle of the answer is JSON whoever typed the command cannot parse. So in this module:

- Everything a call has to say about itself goes to stderr — `say()` is the only way to write a line there,
  and it prefixes `[shark-dive]` so that a shell's output says which program is talking.
- Nothing on the command line path may use `SharkLog`, `println`, or anything that ends up on stdout. There is
  no log file for one of these processes either: it prints and exits, and what it was asking about is in the
  log of the run that answered it.

The app's own side of it — a window answering an agent — logs through `SharkLog` as usual, so a session log
reads as the reason for each call followed by the reads it caused. That is the artefact to ask for when
somebody reports that an agent got it wrong.

## The handshake line that lets a shell have a session

`--cli <command> name=value …` is **argument translation and picking a run**, and nothing that decides what an
answer is: it turns `name=value` into the one line `AgentWire` describes, prints what came back and exits, so
a refusal it prints about a heap dump was thrown by the tool's own handler. Everything that reads a heap dump
is on the other end of the socket, which is what keeps a command line from becoming a second surface with
rules of its own. `notes/agent-surface.md` has what a call costs.

**What it refuses without a run is only what could not have reached one**: a name no command in this build has —
answered with the names it does have, since what happened is usually a tool renamed under an agent that had
learned the old name — `--no-ui` on a command that starts no run, a session name that cannot be part of a file
name, and a command of a named session with no `reason`. Keeping that list to four is what makes the surface one
place: a fifth would be a rule to find out about twice. A `--cli` with no command name after it is not one of
them: it prints the help on stdout and exits 0, because a program asked what it takes is being asked a question
rather than making a mistake.

**The `reason` is required of a named session and of nothing else**, which is a pair rather than two rules:
`--session=` is what an agent passes and the `reason` is what makes that session readable afterwards, so
having both or neither is the whole of it. It is checked here because this is the only place that knows which
of the two a command line is — `AgentTool.call` enforced it on every call, which refused a person typing one
command at a window for the sake of a log nobody is going to review. The schemas still require it, since what
a model reads is the schema.

**A process per call would otherwise be a session per call**, and a session is what somebody reads afterwards.
So the handshake is `token[ sessionName]` on one line, `AgentSessionFile.continuing` appends to the
newest file whose name carries that id, and a command line defaults to `cli<a pid above it>` —
`defaultSessionName`, which **walks** rather than taking the parent, because the shell an agent's call arrives
in is one command long. Claude Code runs each of its commands in a `zsh -c` of its own, so the parent is a
session per call again, measured as nine session files for one nine-call investigation; a shell that was handed
a command is walked past and what drove it is the session. Read that KDoc before changing it — the walk tests
both the name and the `-c`, and either half alone merges sessions that have to stay apart. A connection that
names no session gets one of its own.

**`--session=` is the one option an agent is expected to pass, and the default is there for people.** What it
should say is a word a reader would recognise with the agent's own session id after it, because the thing
somebody reviewing an agent's logs is looking for is *which* agent run this was — and a Shark Dive session
named after a pid is a number that names nothing once the process is gone. The walk above is what a shell gets
for free; an agent has no shell that outlives one command, so there is nothing for it to be walked up to.

**Anything after the token and the name is dropped rather than refused.** `AgentServer` reads two words off
that line and ignores the rest, which is what a build talking to a run of a different version needs: a word
added here has to be a word an older run can be handed without the connection failing. One used to be there —
which transport a line came in over — and this is how it left without a flag day.

**The name is checked at both ends**, because it becomes part of a file name: the command line refuses one
that isn't letters and digits before calling anything, and `AgentServer` serves the connection anyway with a
session of its own and a line in the log. Refusing the connection would lose the investigation to protect a
file name; the calls are none the worse for it.

**Exit codes are the second half of the answer.** 0 with JSON on stdout, 2 with the refusal on stderr, 1 when
there was nothing to answer it. A refusal is not a failure of the command — it is what the surface said, and
the message is the next thing to do — so a script can tell "it said no" from "nothing was there", and a shell
keeping stdout for the JSON still shows the sentence.

## The transport

**A run publishes a loopback port, a token, the commit it was built from and whether it draws windows** to
`~/.shark-dive/agents/<pid>.agent`, and a call is a process that reads that file, connects and sends one line.

**A command expects exactly one run, and two is an error rather than a choice.**
`AgentCommandLine.runToTalkTo` is the whole of it. The version before this picked the newest of them and
said so on stderr, which made the heap dump a command was answered about depend on what else was open on the
machine — so now two runs is a message naming each of them by pid and by whether it has windows, and
`--debug-run=<pid>` is how to mean one. **It is not called `--agent-run`**: this surface is designed for
agents and typed by people, and an option naming one of the two readers is an option the other one is
entitled to think is not for them. What `--debug-` says instead is *when* it applies — two runs of Shark
Dive at once is a run from source beside the installed one, or two builds being compared, which is somebody
working on this app rather than somebody reading a heap dump with it. It is last in `--help` for the same
reason, with `--debug-title-prefix`.

**The build sha is what makes a machine in the middle of a branch usable.** A command line only ever sees
runs built from its own commit — `AgentServer.PublishedRun.buildSha`, filtered before anything is sent —
because the window still running last week's build refuses a tool this build renamed, and it refuses it as
"there is no tool called that" rather than as "that window is a different build". Which is the normal state
of this machine while the surface is being worked on. A run named by `--debug-run=<pid>` that is a different
build gets that sentence explicitly, since a pid somebody typed deserves better than reading as no run at
all.

**A call with no run to talk to opens one**, rather than answering "ask your human to launch Shark Dive" — that
being the opposite of the point of this surface being a window at all.

**And the only wait is for a run this command line started**, 60 seconds of it, which covers a cold JVM,
Compose starting and jlink's runtime being paged in. **Nothing is waited for otherwise**, and the reason is a
property of the published file rather than a tuned number: a run takes its file away three ways as it ends —
a shutdown hook, the `Closeable`, and whoever reads the file of a process that has gone deleting it — so a run
that is up is a run that is published, and polling for one to appear is only ever a bet that one is in the
middle of starting. There was a blind 10 seconds before any command, and what it bought was ten seconds of
saying nothing before saying what was already known. The run that is published and does not answer is the
other case and is covered without a wait: the connect has a 1 second timeout, and whoever finds it deletes the
file.

**Four commands start one** — `open_heap_dump`, `dump_heap`, `list_devices` and `list_processes` — which is
a deliberate narrowing of "start one if there is none", and the rule is what each command's answer is about.
Each of those answers the same in a run it just started as in the run somebody is working in: two of them
hand back a heap dump they put there, and the other two ask `adb`. Every other command is a question *about*
a run, and a question answered by a run this command line just started comes back empty — indistinguishable,
to whatever reads the answer, from a run that was already there and had nothing open. `list_heap_dumps` is
the one to check that rule against: it needs no heap dump either, and it starts nothing because what it
answers *is* what the run has open. So the rest are refused, naming the four. `--debug-run=<pid>` starts
nothing whichever command it is on: that names a run, and starting a different one would answer about the
wrong heap dump.

**Whether a run draws windows is checked here, before connecting.** `--no-ui` is a property of the *run* —
`cascadedPosition` asks `GraphicsEnvironment` for the screen, so a run on a machine that has none cannot start
Compose at all — which means there is no opening one heap dump of a run with a window and another without. So
`kindMatches` refuses a mismatch either way with the sentence saying which it is, and it does that **only for
the two commands that open a dump**: `list_devices` and `list_processes` may start a run and open nothing, so
which kind of run answers them is nothing about their answer. `--no-ui` is refused outright on every command
that starts no run, each of which reads a dump that is open already — and a dump open with no window answers
exactly as one open in a window does.

**And the run this starts is left out of this process's process group**, which is `detached` in
`shark.dive.app.DiveAgents`. A child already survives its parent exiting; what kills it is a signal aimed at a
group — closing a terminal `SIGHUP`s its foreground process group, and a JVM dies on `SIGHUP` — so the run an
agent opened used to go with the shell the command was typed in, and with whatever a coding agent's harness
kills. `open -n -a <bundle>` for a packaged install puts the run in a session of its own under `launchd`;
`sh -c 'set -m; "$@" &'` for a run from a classpath gets it a process group of its own and no more.
`notes/agent-surface.md` has all three measured.

Deliberately **not** the socket `DeepLinkPeers` listens on, though it is the same shape. A link is one line
answered in a millisecond; a call here is a connection held for as long as the call takes, which for
`dump_heap` is minutes of `adb`. One port for both would mean a link arriving mid-call and a call ending when
a link handler closed.

The token is the whole of the authorization, and it is worth being clear about what that is: enough to keep a
web page or another machine out, and **not** a boundary between programs run by the same person — anything
that can read `~/.shark-dive` can read any heap dump on the disk anyway.

`AgentServer.serve` sets **no read timeout**, unlike the link socket. A tool waiting on `adb`, or on a heap
dump being indexed, is a quiet connection, and one dropped for being quiet is a call that never comes back.

## Two ways in, and after that every command names a heap dump

`open_heap_dump` and `list_heap_dumps` are the only two commands that take no heap dump, because they are the
two that hand one back: open the dump you were given, or find out what is open already. **Every other command
needs that key**, which is not a rule enforced in one place — it is `heapDumpKey` being a required argument
of each of their schemas and of `AgentArguments.heapDump`, whose refusal lists what is open and points at
`open_heap_dump` for what isn't.

**It is called `heapDumpKey` in every argument and in every answer that hands one back**, which is one word
doing work the old `heapDump` didn't: an argument named after the thing rather than after the identifier of it
reads as somewhere a path, or a file, or a window might go. The path is beside it under `heapDumpPath`, and the
two being plainly a key and a path is the whole reason for the rename.

**A key is the file name**, and `crash.hprof#2` for a second dump open under that name — `keyed()`, which is
also why a key is not simply "the name". Two open dumps of one name is a real case rather than a hypothetical
one, `crash.hprof` pulled off two devices being two files in two directories, and the name alone left the
second one unnameable: it resolved to the first, so a call meant for one was answered about the other and
nothing said so. **A key says what is open right now, and a path is the name that doesn't move**: closing the
first of two makes the survivor `crash.hprof` again, and a call still saying `#2` is refused with the keys
there are rather than answered about the wrong file.

**A path is the other spelling, and `resolvedDump` takes either**, because both are things an agent has in
front of it: somebody says "investigate `/tmp/crash-4821.hprof`", and a surface taking only the last part of
that makes an agent shorten a path it was handed. There is no window identifier, and there was: the CLI is
about heap dump files and a window is where one happens to be drawn. Which is unambiguous because **opening a
dump this run already has open joins that open** rather than making a second one, so one file is at most one
open per run and there is never a second reading of it here to tell apart. Two readings of one dump being
compared is two runs, and `--debug-run=<pid>` is how to say which.

**`close_heap_dump` is the way out, and closing the last one ends the run.** A run *is* its heap dumps: one with
none left has nothing to come back to, and leaving it up would be leaving the two-runs error waiting for the
next command. Its answer says `runEnded` for exactly that reason — the difference between a command another can
follow and a command after which there is nothing to talk to. And the answer has to get out *while* the run
goes away underneath it, which is `AgentServer.letAnswersOut`: without it the command that worked reads as
"Shark Dive stopped answering".

## Everything the window can do, this can do

`AgentTools` covers every screen and every button, `Take heap dump…` included, and that is a rule rather than
how far it happened to get. A surface that can read a heap dump but not open one answers "ask your human to
click something", which is the opposite of the point — so a capability added to the window is a tool added
here, and the same the other way round.

Two consequences worth knowing before adding one.

**A tool that makes a window is answered once the dump is *readable*.** `AgentHeapDumps.open` and `dumpHeap`
hand back an `AgentHeapDump`, not a path or a name, because everything else on this surface is a read: a dump
named back while it is still being indexed is one that refuses every call made with it. The app's
side waits on three outcomes — open, failed to open, window closed — which is why `DiveWindow` publishes
`openProblem` beside `openHeapDump`. Waiting on "opened" alone means a file that was never a heap dump is a
call that never comes back.

**A tool that reaches `adb` is minutes, and says so in the log rather than in the answer.** There is nothing to
stream progress through — an agent is waiting on one JSON object — so `~/.shark-dive/logs` is where a dump
that is still being pulled says how far it has got.

## An address is a string, never a JSON number

A heap dump's addresses fill the range of `Long`, and a JSON number is a double to most clients of this
protocol: anything above 2^53 comes back rounded, which for an address means a different object, silently. So
every address on this surface is `exactHexObjectId` — `0x12d368b8`, the same spelling the app's own files use
— and `objectIdOfHex` is the only way back. The refusal for a decimal names that case, because a model that
has seen a numeric address elsewhere will write one here.

## kotlinx-serialization without the plugin

`kotlinx-serialization-json` is a **runtime dependency only**: `buildJsonObject`, `Json.parseToJsonElement`
and friends. There is no `kotlin("plugin.serialization")` on this module and no `@Serializable` anywhere,
because everything crossing this boundary is either Shark Dive's own model — which is not ours to annotate
— or an envelope of two keys, which is all `AgentWire` is. Adding the plugin to get `@Serializable` would be a
compiler plugin's worth of build for a saving of nothing.

## It targets Java 17, unlike most of the repo

`AgentServer` uses `ProcessHandle.current().pid()`, which is Java 9, so this module is one of the three named
in `modulesTargetingJava17` in the root `build.gradle.kts` rather than taking the repo wide Java 8 target.

It used to take that Java 8 target and get away with it: `targetCompatibility` without `options.release`
compiles a reference to a Java 9 class into Java 8 bytecode and only resolves it at runtime, which is the
same trick `shark-dive-jdwp` still relies on for `com.sun.jdi`. Nothing about that was broken — the build
was green and desktop-only code never meets a Java 8 runtime. What it cost was the editor: IntelliJ checks
API availability against the module's language level, so every `ProcessHandle` call read as an error, on a
module whose declared target was a contract with nobody. Declaring 17 is the honest version of a module that
cannot run on 8, and it keeps that inspection meaningful where the target *is* a contract — a Java 9 call in
`shark-dive-core`, which has to stay loadable from the Android app, is still flagged.

Anything added here is under the same rule as the rest of Shark Dive: **no Compose, and nothing that assumes
a display**, since the reads happen on the heap dump's thread and the tests run headless.

## Build and test

```bash
./gradlew :shark:shark-dive:shark-dive-agent:check   # test + detekt

# What the surface is, from a shell, with nothing open and no Gradle. Then one command, then the method.
"Shark Dive.app/Contents/MacOS/Shark Dive" --help
"Shark Dive.app/Contents/MacOS/Shark Dive" --help open_heap_dump
"Shark Dive.app/Contents/MacOS/Shark Dive" --investigation-help

# Then a heap dump open — which starts a run if none is up — and one call about it.
"Shark Dive.app/Contents/MacOS/Shark Dive" \
  --cli open_heap_dump path=<path> reason="Trying it"
"Shark Dive.app/Contents/MacOS/Shark Dive" \
  --cli list_leak_groups heapDumpKey=<file name> reason="Trying it"

# The whole surface end to end, in a real window, with an agent that has never seen this repository. It
# starts the investigation; --print-command stages everything and hands you the command instead.
shark/shark-dive/shark-dive-agent/harness/start-harness.sh [heap-dump.hprof]

# And what that run is doing, as it does it — every call, and what came back. Both ways of starting a run
# print this with the run's own directory filled in. --html renders it in a browser instead.
shark/shark-dive/shark-dive-agent/harness/watch-transcript.sh <the staged directory>

# And the same surface scored: heap dumps whose faulty reference is known, and a number per run.
shark/shark-dive/shark-dive-agent/harness/eval/run-eval.sh --models opus,sonnet --repetitions 5
```

Every test here runs against a heap dump built with the `dump { }` DSL and no window, which is what
`AgentHeapDump` being an interface is for. `AgentServerTest` is the one that goes through a real socket — a
`Socket` to the published port, the token typed at it by hand — and `AgentConnectionTest` is everything said
once a connection is up, driven as lines of text rather than through the socket so that what a test asserts on
is the answer rather than the plumbing.

**The harness is how the thing this module is for actually gets tested.** It builds the packaged app, copies it
into a directory of its own, and starts an agent on one heap dump with two sentences: which file, and where the
launcher is. **It opens nothing and it stages no skill** — the window is the agent's to open, and what it needs
to know is `--help`, so finding that out is its own first move and the first thing an investigation can get
wrong. Then read `~/.shark-dive/logs`: a run that went well and a run that guessed look completely different
there, and neither of them looks like anything in a unit test.

**And read the client's transcript beside it, which is the only place a run that never arrived is visible.**
The Shark Dive log is the calls that *reached a window*, so a run that opened the app some other way and then
reported an investigation leaves that log empty while its answer reads like a success — measured, on a run that
launched the bundle with `open -a`, made no `--cli` call at all, and described the leak types it would have
found. `watch-transcript.sh` is the reader, live or after the fact, and it is pointed at a run rather than
left to find one because harness directories accumulate and two runs at once is normal. **The reasoning text
is the one thing it hasn't got**: a `--print` run records thinking blocks with their signature and no text —
the API's `display: "omitted"` — and that is the `--print` rather than the model, which takes pinning one to
show, since `--model opus` resolves to `claude-opus-5-5`. That script's header has it measured, along with
the two settings that don't help and the reason a populated block is still only a summary. What stands in for
it is the `reason` every `--cli` call carries, which is what the refusals require it for.

**And `harness/eval` is the measured half of the same idea.** The harness shows how one investigation goes;
the eval runs an agent against a dump whose faulty reference is already known and scores whether it found it,
by string comparison and counting, with no model marking anything. So it is what says whether a change to a
description or a refusal made things better rather than only different. **Neither opens anything for the
agent**, and both name the launcher in the prompt rather than leaving it to be found, since the app they run is
a copy of a build in a temporary directory and an agent sent looking would be measuring an `ls` the scripts'
own shape breaks. Where they differ is that the eval still stages the skill and restricts the run to
`Bash,Skill`, because it is scoring one number; the harness stages nothing and restricts nothing, because
reaching the code the dump came from is part of the job it is showing. **Both scripts reach the surface over
`--cli`**, the way somebody who installed the app would, and
that is now the only way there is: the eval used to have a second arm over MCP, and
`shark/shark-dive/notes/agent-eval.md` has why a run that changed the transport and the method together was
measuring three things at once.
`shark/shark-dive/notes/agent-eval.md` has the answer keys — and the eight ways a run gets handed its own
answer, each of which was a score that meant nothing. One of them voided every number this eval produced up to
2026-10-01, so read that section before quoting a table from it: the tables under it are marked, and the one
headed *the first table that isn't void* is the only one to argue from. **The eighth is in this module**: a worked
example in `AgentMethod` named a real reference, which was a scenario's key, in the text every run is handed
before it has read anything. So a class name written into anything here — a description, a refusal, either
half of the method — is worth checking against `EvalScenarios` first.
