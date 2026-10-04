# The shape of the agent surface

What Shark Dive's agent surface is — one command line, and a skill so that an agent knows it exists — what
that costs, and what the MCP server it replaced cost before it went. Every number measured on this branch
against a packaged build, not estimated.

## There is one way in, and this note is why

`--cli <command> name=value …`, a process per call, answered by the run that already has the heap dump open.
No protocol, no handshake to negotiate, nothing to discover: a socket, a line, a read.

It was three ways in. An [MCP](https://modelcontextprotocol.io) server over a stdio pipe a client launched
(`--mcp-stdio`), the same tools served from that same process with no window and no socket (`--no-ui`), and
this. The measurements below are what decided that the third one is the whole of it — and the ones about MCP
are kept rather than deleted, because what it cost is the argument, and an argument whose numbers have been
thrown away is a preference.

**`--no-ui` is still here and is no longer a way in.** It is a run of this app with nothing drawing it,
publishing the same socket a window's run publishes, so a call reaches it without knowing which kind of run it
is. *So: one core, one adapter* has what that leaves to build.

## What the command line costs

`--cli <command> name=value …` is a process per call, and the thing to know is what that *doesn't* cost.
Measured against a packaged build of `3ff1c42a6`, `SHARK_DIVE_DIR` pointed at a scratch directory, with one
run open on `leak_asynctask_o.hprof` and no window:

| | Measured | Paid |
| --- | --- | --- |
| One call, JVM start to JSON on stdout | 201 ms, median of twenty-four | Per call |
| `--help`, the whole surface: every option and all nineteen commands, one line each | 5,889 characters, ≈1,472 tokens | Only when read |
| `--help <command>`, one of them in full | 885–3,200 characters, ≈221–800 tokens | Only when read |
| `--leak-investigation-help`, the method for solving a leak | 11,855 characters, ≈2,964 tokens | Once per investigation |

So **the standing cost is nothing** — no server is running, no definitions are in a context window, and a
session that never reaches for a heap dump never pays for this surface at all.

**And what the whole surface costs to read is now a third of what it was**, 5,889 characters against 17,923,
because `--help` lists a one-line summary per command and `--help <command>` is where the full description and
every argument are. That is the split worth keeping: the list is what an agent reads to find the command, the
description is what it reads to call one, and the version that printed every description in the list was
paying 4,480 tokens to answer "what is here". MCP's `tools/list` was 23,877 characters of the same thing, every
turn. The largest single command is `set_verdict` at 3,200 characters — two justifications to explain, and
`solvingLeakOf` to say what a verdict is in service of — and it is the one to watch, because the help for one
command is worth being the short answer.

**Part of every figure here is the invocation path**, since what the help prints is the command to type on
this machine: the full help carries it four times and a single command's help once. The path measured from is
128 characters, so **the help itself is 5,154 characters and a command's is 687 to 2,571** — which is the pair
to compare a future build against, and the pair that hasn't moved since `5eaa0d78a` except for the method.
An installed `/Applications/Shark Dive.app/Contents/MacOS/Shark Dive` is 54, which works the full help out at
5,370.

**The per-call figure is a JVM starting, so it moves with what else the machine is doing** rather than with
anything in this module: three batches of eight here ranged 168 to 209 ms, and the same measurement at
`5eaa0d78a` was 178 ms. Read a change of ten milliseconds here as load.

**So a figure here moves when nothing has been written longer.** This row was 4,580 characters and eighteen
commands at `df043f985`, measured from a path that wasn't recorded, which is most of why it isn't the number
above. Measure again when the command count changes rather than scaling the old one.

**`--cli` with nothing after it prints exactly that help**, byte for byte, exit 0 and nothing on stderr —
`diff` says identical, and so do `-h` and `--help`. Which is the point: the one command line that is a
question about this program rather than a call to a run of it reaches no run at all, opens nothing, and
answers immediately.

**A call from a shell is not a slower call.** It reaches the same window over the loopback socket the run
already publishes, so the heap dump is the one that was parsed and indexed once and the read queues on that
window's own thread — the 186 ms is a JVM starting and a socket, not a heap dump being reopened, and the wire
trace below puts 23 ms of a call between the connect and the answer. The process-per-call shape costs exactly one
thing, and it isn't speed: **a connection can no longer be what gathers an investigation**, which is what
`--session=` and `AgentSessionFile.continuing` exist for. A call says which session it is one of,
defaulting to `cli<the shell's pid>`, so a conversation's calls are one row of the *Agent logs* screen.

**Measured, and it holds across runs as well as across processes**: five calls typed as five separate
commands, two of them answered by a different run of the app — `--debug-run=` aimed one at the second — are
**one session file with five rows and a header**, because the sessions directory belongs to `SHARK_DIVE_DIR`
rather than to a run. So an investigation that outlived the run it started in still reads as one thing.

**What a call does queue behind is the window.** Reads are confined to the heap dump's own thread so that an
agent sees what the window shows, which means a call costs whatever that window is already doing — and on a
real dump that can be minutes: a leak analysis of a 4 million object Android dump took 683 s here. That is
the intended trade for every tool that reads one dump.

**Listing what is open is not one of them, and used to be.** `list_heap_dumps` is the only call that touches
every open window, and it was doing it through `read` — so with three real dumps open it took **40.7 s**, all
of it queued behind the leak analysis of a dump the agent had not been asked about, for an answer that is
mostly file names. The read itself measured 0 ms, because the sizes are a constructor val of `HeapDive`:
`reachability.sizes`, worked out by the pass that made the dump openable. So there was nothing to time-box —
what was being waited for was the queue and nothing else. `HeapDive.sizes` is now a property on the seam
(`HeapDumpSession.sizes`, `AgentHeapDump.sizes`, documented on all three as **not** through `read`, for the
reason `origin` isn't), and listing touches no heap dump thread at all.

**Nor does opening one queue behind another opening.** `HeapDumpSession.open` gives each dump its own
single-thread executor named `heap-dump-<file name>` and runs the whole of `HeapDive.open` on it, so three
dumps index on three threads. Measured headless and warm on the 4 M-object dump, no `-Xmx` set, 16 cores:
**32,390 ms as one of three at once against 31,411 ms alone**, a 3% cost, with GC pauses of 337 ms against
155 ms. Three windows taking far longer than that is not the opens colliding — it is each window's own
post-open reads (the referrer index, the treemap layout, the leak analysis) running beside the other two
opens, which is the paragraph above rather than this one.

## One run, and how a command gets one to talk to

**A command expects exactly one run of this build, and every part of this section follows from that.** Several
runs of Shark Dive at once is how the app is used — a window per heap dump — but *several runs of the app*, each
with its own set of dumps open, is a different thing, and it is what made the version before this one answer
about whichever run happened to have started most recently. That is a command whose heap dump depends on what
else is on the machine.

So there are three filters, in this order, and then either one run or a message:

- **The pid is alive.** `AgentServer.isRunning`, asked of the OS rather than by connecting, because this is
  read before anything is sent. A run deletes its own file from a shutdown hook and from the `Closeable`, and
  **neither of those runs for a run that was killed** — a force quit, an out of memory, a `kill -9` — so the
  file outlives the run often enough to matter. Measured before this existed: a window force quit three weeks
  earlier was still being offered to every call beside the live one.
- **The build sha matches.** `PublishedRun.buildSha`, written into the file as the run starts. This is the one
  that makes a machine in the middle of a branch usable: the window still running last week's build refuses a
  command this build renamed, and it refuses it as *there is no command called that* rather than as *that
  window is a different build*. Which is the normal state of this machine while the surface is being worked on.
  A run named by `--debug-run=<pid>` that is a different build gets that sentence explicitly, since a pid
  somebody typed deserves better than reading as no run at all.
- **Then: one, none, or too many.** One is the call. None is either a run being started — see below — or a
  message saying so, with the `open_heap_dump` command line to type and the directory runs publish themselves
  in. Too many is an error naming each of them by pid and by whether it draws windows, and `--debug-run=<pid>`
  is how to mean one. Measured: `2 Shark Dive runs are open, so which heap dumps there are to read depends on
  which of them you meant. Pass --debug-run=<pid> to say: 43049 (with no window), 42481 (with no window).`

**A reader deletes the file of a run that has ended, and of nothing else** — which is the correction the first
filter needed, and it cost a run to find. A `--no-ui` run logged itself as published, the command line that had
just started it never saw the file, the file was gone afterwards, and the run stayed alive and unreachable for
the rest of its life: a JVM holding a nine megabyte heap dump that nothing could ever ask about again, while the
command that started it waited its full sixty seconds and then reported that something had gone wrong opening
it. Nothing but the command line reads that directory, so what deleted the file was the poll that read it in the
moment between its creation and its contents, found no `port` in it, and cleared it out as a file naming no run.

Two changes, because the moment and the deletion are separately wrong. `AgentServer.write` now writes the file
under a name that does not end in `.agent` and **moves it onto the name readers watch**, so a half published run
is not in the list at all. And a file that names a *live* process is left exactly where it is, whatever is in
it: the two reasons one might not parse are being written right now and being written by a build older than one
of these properties, and the second of those is a run its own build's command line can still talk to. Measured
after: five `--no-ui` opens in a row, every one of them answered, at 1.6 to 2.6 seconds each — and a command
against `~/.shark-dive` cleared out the four files of runs that had ended and left the one file a live run of an
older build had published with two properties in it.

**`--debug-run=` and not `--agent-run=`.** The option names a run of Shark Dive, and this surface is designed
for agents and typed by people — an option named after one of its two readers is an option the other one is
entitled to think is not meant for them. `--debug-` names *when* it applies instead, and that is checkable:
two runs at once is a run from source beside the installed one, or two builds being compared, since the OS
hands every heap dump a person opens to the one installed app and a run is many windows. So it is a real
answer to the two-runs refusal and still the wrong thing to meet first, which is why it and
`--debug-title-prefix` are the last two rows of `--help` rather than being mixed in with the surface.

**Four commands start a run**, which is `STARTS_A_RUN` in
`shark/shark-dive/shark-dive-agent/src/main/java/shark/dive/agent/AgentCommandLine.kt:577`: `open_heap_dump`,
`dump_heap`, `list_devices` and `list_processes`. "No run, so start one" narrowed, deliberately, and the rule it
is narrowed by is **what each command's answer is about**. Those four answer the same in a run they just started
as in the run somebody is working in — two of them hand back a heap dump they put there, and the other two ask
`adb` rather than any dump. Every other command is a question *about* a run, and a question answered by a run
started to answer it comes back empty, indistinguishable from the outside from a run that was already there and
had nothing open. So the rest are refused with the command that opens a dump as the message. `list_heap_dumps` is
the one to check that rule against — it needs no heap dump either, and it is excluded because what it answers
*is* what the run has open, so a run started to answer it would be a command answering its own question.

**And the only wait is for a run this command line started**: `OPENING_WAIT_MILLIS`, 60 seconds of it, long
because what it covers is a cold JVM, Compose starting and jlink's runtime being paged in. There was a blind
`DEFAULT_RUN_WAIT_MILLIS` in front of *every* command, measured at **10.4 seconds** for a `list_heap_dumps` with
nothing running, and what those ten seconds bought was saying nothing before saying what was already known. A
run publishes itself as it starts and that file goes away three ways as it ends — a shutdown hook, the
`Closeable`, and whoever reads the file of a process that has gone deleting it — so a run that is up is a run
that is listed, and polling for one to *appear* is only ever a bet that one is in the middle of starting. Which
is a bet worth making when this command line is what started it, and nothing otherwise. The other case, a run
that is listed and does not answer, was never what that wait covered and is handled where it happens: the
connect has a one second timeout, and whoever finds a dead run's file deletes it. One second is enough because
`AgentServer.listen` accepts on a daemon thread of its own rather than the heap dump's, so a run in the middle
of a two minute `dump_heap` answers a connect in milliseconds — it is `AgentTools` that suspends onto the heap
dump's thread, after the connection is up and the call is read.

Nothing waits on the blind wait being there, which is worth saying because the case it looked like it covered —
a window somebody launched by hand a moment ago — has nothing left that does it. `start-harness.sh` launched one
and watched the runs directory for it to publish itself; it now launches nothing at all, and the run an
investigation reads is one the agent's own `open_heap_dump` started, which is the wait that is still here.

### Starting one has to survive the command that started it

The spec asked for forking, and asked to be walked through the alternatives if forking brought problems. It
does, and the problem is not the one forking is usually asked about. A child of this process **already**
survives it exiting; it is reparented to `launchd`. What kills the run is a signal aimed at a process *group*:
closing a terminal sends `SIGHUP` to its foreground process group, a JVM dies on `SIGHUP`, and anything that
kills a command by its group — which is what a coding agent's harness does — takes the run with it.

Three routes measured, against the packaged launcher:

| Route | Starter returns | Run's ppid | Run's pgid | Run's session |
| --- | --- | --- | --- | --- |
| `ProcessBuilder`, a plain child | not waited on | 1 once the caller exits | **the caller's** | the caller's |
| `sh -c 'set -m; "$@" &'` | 0.01 s, rc 0 | 1 | its own | still the caller's |
| `open -n -a <bundle> --args …` | 0.09 s, rc 0 | 1 | its own | **1 (launchd), no tty** |

`detached()` in `shark/shark-dive/shark-dive-app/src/main/java/shark/dive/app/DiveAgents.kt` picks the third
for a packaged install, the second for a run from a JVM classpath where there is no bundle to name, and the
plain child where neither is available. Confirmed on the real thing: a run started by
`--cli open_heap_dump --no-ui` came out `ppid 1, pgid itself, session 0, tty ??`, with the command that started
it returning in **1.566 s** — a heap dump open and answered, not a process handed off and hoped for.

What the `open` route costs, and neither is a surprise once written down: it hands back **no pid**, which is
free here because what waits for the run is the command line watching the directory runs publish themselves in,
exactly as it would for a run somebody else started; and it gives the run **the root directory** to work in, so
every path handed over has to be absolute, which they are — `DiveArguments` holds `File`s and `openAnotherRun`
spells them `absolutePath`.

**And both streams are discarded rather than inherited**, which is the mirror image of the same problem: a run
holding the stderr this process inherited is a command that *appears never to finish*, for anything reading
until end of file. So the diagnostics go where every other diagnostic goes, `~/.shark-dive/logs`, and the one
case a log file cannot cover — a run that died before it could open one — is a run that never published itself,
which is exactly what the caller is told.

### And closing the last heap dump ends the run

A run **is** its heap dumps: one with none left has nothing to come back to, and leaving it up leaves the
two-runs error waiting for the next command. So `close_heap_dump` on the last one ends the run, which needs one
thing that is easy to miss — **the answer has to get out while the run goes away underneath it**. The
connection threads are daemons, so nothing else holds the JVM open for them, and without
`AgentServer.letAnswersOut` the command that did exactly what it said reads as *Shark Dive stopped answering*.
Bounded and short, because what it waits for is a `println` of an answer already built; a call still *working*
is cut off as before, rather than holding a closing app open for the length of a `dump_heap`.

Measured end to end: `close_heap_dump` on the only dump of a run answered `"runEnded": true` with the next step
in it, exit 0, and the run's `.agent` file was gone and its process ended a second later.

## Two texts, because there are two budgets

Nothing on this surface is paid at rest, so the only budget left is **what an answer may be** — and an answer
on a command line is a tool result in the caller's harness, which has a cap of its own. Measured in Claude
Code 2.1.280: a Bash result over **30,000 characters is not handed to the model at all**. It is written to a
file under the session's `tool-results/`, and what the model gets is a 2 KB preview and that path. The cap is
documented in that build's own environment-variable help — "How many characters of a successful Bash or
PowerShell command's output Claude receives inline (default 30000; values clamp to 4000-128000). Output past
this is saved to a file", in the strings of `~/.local/share/claude/versions/2.1.280` — and was confirmed by
printing 35,000 characters at a live session and reading what came back. So the number to keep an answer under
is 30,000 characters, and every answer on this surface is under it except one: see the next section for
`agent_log session=…`, which is 33,035.

`AgentMethod` is split in two against that, and against a second thing the caps make plain: **a session
should not pay for a method it isn't following.** Both halves are **reads rather than answers**, each printed by
an option of its own with no run, no heap dump and nothing open — which is the end of a line this redesign walked
all the way down: a text handed over at a handshake, then a text prepended to an answer, then a text an agent
asks for when it has a use for it.

- **`SURFACE`, 1,969 characters as `--investigation-help` prints it**, is how to work on this surface at all:
  the reason on every call, the window somebody is watching, the `shark://` links to hand back, the gap to
  admit, and one sentence saying that anything about a leak starts by reading `--leak-investigation-help`. It
  used to be prepended to the first *answered* call of a session, exactly once, read off the session file rather
  than held in memory because a process per call has no memory — and exactly once is still every session: a
  `list_heap_dumps` that wanted the name of a dump was answered with the whole of how to work here, and the
  session that only ever wanted that paid for the rest of it.
- **`LEAK`, 11,855 characters as `--leak-investigation-help` prints it**, is what a leak is, how a verdict
  spreads, the order to work in, what to tell a person, and reading the code at the version the dump is of. It was a field of every
  `list_leak_groups` answer, and a `list_leak_groups` answers the same text whether it is the first call of an investigation
  or the fourth. So an investigation of three leaks read the whole method three times, for a text that is about
  the path rather than about the list.

**Measured, and this is the largest single cut in the redesign**: `list_leak_groups` on `leak_asynctask_o.hprof`
answered **6,495 characters**, the same on its second call as on its first and the same in a second session as in
the first, where it answered 14,477 as a second call, 16,312 as a first and 8,435 as another session's first. A
55% cut on the call an investigation makes most, and it is the method that left rather than any of the leaks.
`open_heap_dump` is the other half of the same cut: **1,976 characters** where it was 3,802.

**Re-measured on 2026-10-04, and the leaks answer has grown to 11,040 characters** — still the same on a
second call and in a second session, and `open_heap_dump` is 2,016. What that 70% rise bought is the thing
the answer is read for: `leakTrace` on each group, the trace LeakCanary prints, which is **4,152 of those
characters over three groups** and is what an agent hands a person instead of a trace it retold. The
`representativeObject` beside it is 30 characters for all three. So the shape of the cut held — the method
left the answer and has not come back — and what is in there now is leak traces rather than instructions.
Measured against a `--no-ui` run on a `SHARK_DIVE_DIR` of its own, characters rather than bytes: the
box-drawing of a leak trace is three bytes a glyph, so `wc -c` reads 11,416.

**120 of those characters are the `Retaining … in … objects` line**, one per group, added the same day so
that the trace carries every line a LeakCanary report does — `notes/decisions.md` has what the two still
differ on. Worth knowing which way that trade goes: the whole of what a leak trace is worth paying for is
that it is the artefact somebody can hold beside a report they already have, so a line of it is the last
thing on this surface to cut for size.

**An investigation that never asks for either text never reads it**, and that is the intended consequence rather
than a hole to patch — the same consequence as before, moved one call further out. What holds the leak half is
that nothing else produces an answer: `leakSolved` goes true when the verdicts leave one candidate reference
and at no other time, so an investigation that skipped the method has nothing to show for itself. It is no
longer *stopped*, which is the deliberate part of the 2026-10-03 change — `conclude` refused until the path
named one reference, and the reference it checked was one it had already handed over. See
`notes/agent-eval.md`. What carries the pointer is the answer that opens a heap dump, which is the first call of nearly every
investigation and so the one answer a session that has read nothing is certain to see — `NEXT_WITH_A_NEW_DUMP`
in `AgentTools.kt`, and that one sentence is the whole of what moving the method out of the answers costs. Beside
it: the option column of `--help`, the closing paragraph of the command list, and `list_leak_groups` and the surface
method, each pointing at the leak half. Because a text nothing hands over is a text only a careful reader finds.

**The split was forced by MCP's caps and is kept because it was right anyway.** Claude Code caps every MCP
tool description and every server `instructions` at 2,048 characters — `CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH`
changes it, and the cap is documented in the
[Claude Code changelog](https://github.com/anthropics/claude-code/blob/main/CHANGELOG.md) — so of the one
9,372-character method that used to be handed over at the handshake, **a Claude Code session was given the
first 22%** and the rest was dropped before the model saw anything. What made that survivable was that a tool
*result* was a different budget, `MAX_MCP_OUTPUT_TOKENS`, 25,000 tokens by default, which the whole method was
a tenth of — so `open_heap_dump` carried the lot, and that copy was the only reason a session ever read the
end of it. Two budgets, one text that fitted neither well: the same reason the split still holds now that
neither budget exists and the leak half travels in no answer at all.

## What MCP cost here

Measured off `AgentTools.all` when the server existed, one `tools/list` entry per tool:

| | Characters | ≈ tokens | Paid |
| --- | --- | --- | --- |
| Seventeen tool definitions | 23,877 | 5,970 | **Every turn, while the server was connected** |
| The handshake's `instructions` | 1,819 | 455 | Once per session, plus again on its first answered call |

So the standing cost was **around 6.4 k tokens, about 3% of a 200 k window, for a session that had not yet
asked anything.** That is the number the command line takes to zero, and it is the whole of why one way in is
the command line rather than the server. The published horror stories are an order of magnitude worse — GitHub's
server is ~17.6 k tokens of definitions, and three servers together have been measured at 143 k, which is what
2026's mitigations (tool search, code execution over MCP) are aimed at — so **this surface was never where a
context window went to die**. It was 6.4 k tokens an agent paid before it knew whether it cared about a heap
dump, against nothing.

Worth keeping from that measurement: **the cap never touched the tool definitions**, measured rather than
assumed — the longest description was `path_from_gc_root` at 652 characters, a third of the cap, and none of
the seventeen was within 1,300 of it. So a description can go on saying when to reach for its tool, in
`--help <command>` as it did in a schema. What would silently lose text is the one thing not to write: a
command whose description is a page.

And the shape of the traffic, which is the part the command line fixed rather than made cheaper. `initialize`
answered with the method, and a call was a process per call, so **a session of typed calls carried the
handshake once per call**: 32,976 characters of `initialize` answers in a nine-call trace, 57% of the session
file, handed to a reader that already had the same text. Worse than the size was the drawing — **two rows per
typed command**, so the *Agent logs* screen read as Connected, called, Connected, called.
`McpSession.isTheCommandLineSayingHello` existed to drop exactly that one message. Both are gone with the
protocol: one command typed is one row because nothing crosses the socket but the call.

**So don't reintroduce anything in front of the call.** A capabilities exchange, a `hello`, a version
negotiation: each of them is a row of a screen saying a process started and did the thing the next row already
names, and there is nothing for one to carry. What the commands are is `--help`, text this build prints with
no window and no heap dump. How to work here arrives in the first *answer*, which is the one thing an agent is
certain to read, because it asked for it.

## And what reading somebody else's session costs

`agent_log` without a session id is a line per session and cheap: 1,348 characters for the two sessions of the
trace below. With one, it is every line of that session with what was sent and what came back, which is the
only form that answers "why did it do that next" — and it is the one answer on this surface anywhere near the
caller's own cap. Measured against the `flowtrace` session below, six calls:

| | Measured |
| --- | --- |
| The six rows' answers | 3,801 + 2,098 + 2,098 + 6,480 + 139 + 853 characters |
| The session file on disk | 20,358 characters, seven lines: a header and six calls |
| The whole `agent_log session=…` answer | 20,840 characters, ≈5,210 tokens |

So **a call is one to four thousand characters and the answer is nearly all of it**, the two refusals are the
two cheapest rows in the session, and a thirty-call investigation read in full is most of a small context
window. That is the tool being used for what it is for rather than a leak — it is the one call on this surface
whose subject is somebody else's whole investigation.

**The version of this measurement before the redesign hit Claude Code's 30,000-character cap**: nine calls,
33,035 characters, of which a single `list_leak_groups` carrying the leak method was 14,477. What a Claude Code
session got was a 2 KB preview and a path to read. Moving that method to `--leak-investigation-help` is most of
why this trace's six calls are 20,840 — but the two traces are six calls and nine, so **that is not a
like-for-like number and the cap has not gone away**: a long investigation still passes it, and the shape to
reach for then is a way to ask for one call's exchange rather than a shorter version of every call's. Nothing
truncates it here, deliberately: a session cut to fit is one where the answer that misled an agent is the part
that got cut.

## The flow, end to end, traced

Every number and every line below was read off one run: a packaged build of `df043f985`, one run open on
`shark/shark-android/src/test/resources/leak_asynctask_o.hprof`, and a logging relay in front of that run's
socket so both directions are on the record. `SHARK_DIVE_DIR` pointed at a scratch directory, which is what
keeps a trace out of the notes and logs of whoever is running it — see `sharkDiveDirectory()` in
`shark/shark-dive/shark-dive-app/src/main/java/shark/dive/app/DiveLogging.kt:125`.

The relay is worth describing, because **the two-runs rule is what makes one possible**. It reads the real
run's file, listens on a port of its own, and writes a second `.agent` file naming that port with the same
token, the same `buildSha` and the same `window=` — so it publishes itself as a run of this build. That
makes two, which every command then refuses until one is named, and `--debug-run=<the relay's pid>` is how
the trace was aimed through it. The file has to be called `<a live pid>.agent`, since
`AgentServer.isRunning` deletes one named after a process that has gone.

### The pieces, and what each one is handed

Every path below is under `shark/shark-dive/shark-dive-agent/src/main/java/shark/dive/agent/`, except the
window's own, which is `shark/shark-dive/shark-dive-app/src/main/java/shark/dive/app/`.

| Piece | Process | In | Out |
| --- | --- | --- | --- |
| The window, `Main.kt` | The app: one per run, many windows | A heap dump on its command line | A window, and one `<pid>.agent` file |
| `AgentServer.listen`, `AgentServer.kt:47` | A daemon thread of that process | A loopback connection | A thread per connection, with an `AgentConnection` on it |
| `~/.shark-dive/agents/<pid>.agent` | A file, written as the run starts | — | `port=`, `token=`, `buildSha=`, `window=`, readable by its owner alone |
| `AgentConnection.answer`, `AgentConnection.kt:40` | That thread | One line of JSON per call | One line back, and a line of the session file |
| `AgentWire.kt` | Neither end's, which is the point | A call, or an answer | The two keys each of them is |
| `AgentTools`, `AgentTools.kt:46` | Suspends onto the heap dump's own thread | A command name and its arguments | A `JsonObject`, or an `AgentRefusal` |
| `AgentSessionFile.kt` | The same thread | A line per call | `~/.shark-dive/agents/sessions/agent-<when>-<id>.jsonl` |
| `AgentCommandLine.run`, `AgentCommandLine.kt:56` | **A process per call**, the same binary | The words after `--cli` | Pretty JSON on stdout, a refusal on stderr, an exit code |

### A typed call is a process, a connect, and two lines

```bash
"Shark Dive.app/Contents/MacOS/Shark Dive" --cli list_heap_dumps \
  --session=flowtrace reason="Finding out what is already open"
```

That command starts a JVM, which reads `~/.shark-dive/agents`, keeps the runs built from its own commit,
connects to the one that leaves, and sends this — `->` is the caller talking, `<-` is the run, and the seconds
are from the start of the trace. Captured against a live run by a client written for the purpose rather than by
the app's own command line, which is worth knowing twice over: the timings below it are the run's work with no
JVM start in front of them, and a protocol that twenty lines of Python can speak is a protocol nothing has to be
shipped to speak.

```
0.015 c1 -- connected
0.015 c1 -> 43e96b32f6462fd2b3669cf7c6ee36b0 flowtrace
0.015 c1 <- OK
0.015 c1 -> {"tool":"list_heap_dumps","arguments":{"reason":"Finding out what is already open"}}
0.019 c1 <- {"answer":{"heapDumps":[{"heapDumpKey":"leak_asynctask_o.hprof","heapDumpPath":"/…/leak_asynctask_o.hprof","sizes":{…},"verdictsSetByHand":[],"placesWithANote":0}]}}
0.019 c1 -- closed
```

Six lines, and five things about it that the shape of that trace is the evidence for.

**The first line is not JSON.** `token[ sessionName]`, answered `OK` or `NO`, because the alternative is a
handshake to negotiate before anything can be sent. `flowtrace` is the session these calls join; a connection
that names none gets one of its own. Anything after those two words is dropped rather than refused, which is
what a build talking to a run of a different version needs — a third word used to be there, saying which
transport a line came in over, and that is how it left without a flag day.

**Then one line each way, and that is the whole protocol.** `{"tool":…,"arguments":{…}}` out, and exactly one
of `{"answer":{…}}`, `{"refused":"…"}` or `{"failed":"…"}` back. Three and not two, because a refusal is the
surface working — a tool sending an agent back to the heap dump with the next thing to do — and a caller told
that was a failure is a caller told this app fell over. `AgentCommandLine` maps them to exit 0, 2 and 1.

**The answer names the heap dump by a key that is its file name**, which is what every command after this one
says which dump it is about — `heapDumpKey`, beside the `heapDumpPath` it was opened as. There is no window in
it and there was: a `window` field of a short id per open dump, which the CLI then had no use for — a command
line is about heap dump files, and one file is at most one open per run, so the identifier the answers are
written in is the one an agent already has in front of it. Two readings of one dump being compared is two runs,
and `--debug-run=` is how to say which.

And **`#2` is what two files of one name cost.** `crash.hprof` pulled off two devices
is two dumps a plain name cannot tell apart, and the name resolving to whichever was opened first is a call meant
for one answered about the other with nothing saying so — so the second gets `crash.hprof#2`, from `keyed()` in
`AgentTools.kt`. Readable, and the price of being readable is that a key says what is open *right now*: close the
first of the two and the survivor is `crash.hprof`, so a session holding `#2` across that close is refused rather
than answered about the wrong dump. The path a dump was opened as resolves too, and is the spelling that doesn't
move, which is why an answer hands both back.

**The run does the work on the heap dump's own thread**, so what a call takes is how long that thread takes
to reach it: 4 ms here, `list_heap_dumps` being the one call that touches no heap dump thread at all — against
**393 ms** for the first `list_leak_groups` of that run, which is a leak analysis, and 0 ms for the second, which is
that analysis cached. What the command line adds in front of all of it is a JVM: `list_heap_dumps` typed at the
launcher takes **140 to 160 ms** over three runs, and nearly all of that is the JVM coming up rather than
anything this surface does.

**The socket closes with the process.** Nothing is held open, and nothing has to be: what gathers the calls
is the session name, not the connection.

### Six typed calls are six processes, six connections, and one row each

**A trace of the 2026-10-01 build, and two of its six calls are a command that has since gone.** `conclude`
was removed on 2026-10-03 — `notes/agent-eval.md` has why — so read its two rows, the paragraphs under them
and the `AgentTools.kt` line numbers in them as that build's. What each of its jobs is done by now: which
leak a call is about is `solvingLeakOf` on `set_verdict`, what an investigation worked out is `take_note`,
and which reference the leak is was always the heap dump's own answer. The character counts still stand as a
floor, with `list_leak_groups` since gaining a leak trace and a representative object per group.

The whole trace, as commands typed in a row against the same run:

| Command | Exit | stdout | The same call before this round |
| --- | --- | --- | --- |
| `--cli open_heap_dump path=…` | 0 | 1,976 characters: the dump, its sizes, what is already recorded about it, and one sentence saying where the two method texts are | 3,802 — `SURFACE` prepended, this being the session's first answered call |
| `--cli list_heap_dumps` | 0 | 2,102 characters | 2,099 — the same answer, under `heapDump` rather than `heapDumpKey` |
| `--cli list_heap_dumps` ×2 | 0 | 2,102 characters | 2,099 |
| `--cli list_leak_groups` | 0 | 6,495 characters | 6,495 — the leak half had already left this answer |
| `--cli conclude reference=…` | 2 | empty, refused on stderr | the same, under the old argument list |
| `--cli conclude object=0x12d368b8 reason=…` | 2 | empty, refused on stderr | `rootCause=…` as well, which is the argument this round removed |
| `--cli list_leak_groups --session=flowleak` | 0 | 6,495 characters | 8,435 — `SURFACE` again, that being another session's first answered call |

The two refusals are the pair worth reading. `conclude reference=…` is refused by `AgentArguments.onlyTakes` —
"conclude does not take `reference`. It takes `heapDumpKey`, `howToReproduce`, `notChecked`, `object`, `reason`,
and nothing else." — and the one with the right arguments is refused by the heap dump: *"Not concluded. A root
cause names the one reference a path is the leak of, and this path leaves AsyncTask.SERIAL_EXECUTOR,
AsyncTask$SerialExecutor.mActive, AsyncTask$SerialExecutor$1.val$r, AsyncTask$3.this$0, MainActivity$2.this$0.
The fault is at one of those references, and what settles which is the objects between them that have no
verdict…"*. Both went to stderr with exit code 2 and nothing on stdout, each prefixed `[shark-dive]` so that a
shell's output says which program is talking.

**`rootCause` is the argument that list no longer has**, and it went because it and `reason` were the same
sentence asked for twice: `reason` on every other command is why the call was made, and on `conclude` the call
*is* the conclusion, so what the caller would have written in `rootCause` was already what it had to write in
`reason`. Two fields that want one answer get half an answer in each. So `conclude` takes `reason` and its
description says what belongs in it — "How the faulty reference came to still be set: what assigned it, what
should have cleared it, and why it didn't" — and the command list says `reason` is the answer here rather than a
note beside it.

**`object` stays, and the reason is worth writing down** because the argument for removing it is a good one: the
object at the end of a path is only the *signal*, there is nothing special about it, and objects further up the
path usually shouldn't be in memory either. All true, and none of it is what that argument does here. A heap
dump has as many leaks as it has paths, so `object` is how a conclusion says which of them it is about — and it
is load-bearing twice over in `AgentTools.kt:687` and `:688`: the conclusion is written into *that object's*
notes, where the next reader of that tab finds it, and the window is sent to *that object's* tab. Drop it and a
conclusion has no leak to be about and nowhere to be written. What the description does say is that it needn't be
the end of the path: any object below the faulty reference will do, the one the path was read from being the
obvious one.

And the calls are two session files, not seven:

```
agent-2026-10-01_10-53-20_183-flowtrace.jsonl   header + open_heap_dump + 2 × list_heap_dumps + list_leak_groups + 2 × conclude
agent-2026-10-01_10-53-22_913-flowleak.jsonl    header + list_leak_groups
```

A refused call is a row, with the refusal in `output` as well as in `refused` — 750 and 2,317 characters here,
the two cheapest rows of that session. So is a line that reached no tool at all: three were pushed at that
socket by hand in an earlier trace — `this is not JSON`, a call with no `tool`, and a `solve_the_leak` nothing
answers to — and each was a row answered `{"failed":…}`, with `tool` null for the first two.
`AgentSession.toolCalls` is the subset that got as far as a tool, which is what the `n call(s)` on screen
counts and what the eval scores: a number that moved with whatever a caller happened to send would be a number
nobody could act on.

## So: one core, one adapter

The thing worth protecting is that **the enforcement is not in the transport**. `AgentTools` is a registry of
(name, schema, handler) and every refusal is thrown from a handler, so an adapter is a translation of
arguments in and JSON out, not a second copy of the rules:

- `AgentCommandLine` — `--cli <command> name=value …`, which turns a command line into one call on the socket
  the run publishes and prints what came back. It refuses nothing a run could have answered: every refusal
  about a heap dump that it reports was thrown by a handler, and the four it makes itself are the ones no run
  was reached for — a name this build has no command for, `--no-ui` on a command that starts no run, an unusable
  session name, and a named session that sent no `reason`. `--help` is generated from the
  registry, so a command cannot be on one and
  missing from the other, and it is described through `NoHeapDumpToDescribe` — a heap dump whose every method
  throws — which makes "printed, never called" hold rather than be a habit.
- The skill — `.claude/skills/shark-dive/SKILL.md`. Prose, not generated, and it points at `--help` and
  at the method rather than repeating either, since a list of commands in a file is a list
  that goes stale. See the next section for why it is in `.claude/`.

`McpSession` was the second adapter and is gone, along with `AgentStdioBridge` and `AgentStdioServer`. What
that removed, beyond the tokens above: a JSON-RPC envelope to keep on the right side of, a `tools/list` schema
per tool to keep in step with the help text, a session-file field recording which way a line came in, a
client-shaped row on a screen meant for an investigation, and the one case
(`isTheCommandLineSayingHello`) that existed to undo the handshake's cost. **What it must never become again
is two places that decide what a heap dump says about a leak.**

**`--no-ui` stayed, rebuilt around the socket.** It is worth being explicit about because it was a capability
rather than a transport, and the MCP version of it was a transport as well: the same tools served from the
bridge process, no window, and no `<pid>.agent` file, so its only client was an MCP client and it could have
had no other. What it is now is a run of the app that opens no window and **publishes the socket anyway**, so
`--cli` reaches it exactly as it reaches a window and nothing on the calling side knows which it is talking
to. One surface stays one surface, and a build server or a box over ssh is a machine this works on.

**The one thing the calling side does know is which kind of run it is**, published as `window=true|false` and
checked before connecting. Because whether anything is drawn is decided once, as a run starts — `--no-ui` is a
run with nothing drawing it, and a run on a machine with no display cannot start Compose at all — so there is
no mixed run to open one dump in a window of and another not. Which settles what `--no-ui` means on
`open_heap_dump`: which kind of run to open the dump in, refused either way round if the run that is there is
the other kind, and on no other command because a dump open with no window answers exactly as one in a window
does.

`HeadlessAgentHeapDumps` is the whole of the difference, and it is two answers and one refusal to answer:
which dumps are open, what opening one means, and `show` handing back a link and saying it had nowhere to put
a tab. Everything else — the notes, the verdicts, the sessions — is the same files a window reads, which is
what makes an investigation over ssh today one a window opens tomorrow.

## Where the skill lives, and how an agent finds it

A skill nobody loads is a file. The two things that decide where it goes are that **skills are discovered by
directory, not by search** — every client that reads the
[standard](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview) looks in
`.claude/skills/<name>/SKILL.md` under the project and in `~/.claude/skills/` for the machine — and that
**the people who need it don't have this repository**: they installed a `.dmg`.

So `.claude/skills/shark-dive/` is the one place it can be that is not arbitrary. In this repository it is
the project skill, so an agent working on Shark Dive has it without being told. And it is the directory a
user copies:

```bash
cp -R .claude/skills/shark-dive ~/.claude/skills/
```

Which is what `docs/shark-dive.md` says, and what a release should carry as an asset. **The alternative
worth knowing about and not taking** is having the app write it into `~/.claude/skills` as it starts: it would
need no install step and would always match the build, and it would also be an app that writes into another
program's configuration directory without being asked, which is not a thing to do to somebody's machine.

**A skill is not how an agent finds the binary.** It names the `.dmg` install path and how to look for it,
because there is nothing on `PATH` — the bundle is `/Applications/Shark Dive.app`, the space stays and
gets quoted, and the name was deliberately given that space once Block's signing service could take it (see
`packageName` in the app's build script). What would remove the quoting for good is a launcher shim on `PATH`,
which is a separate decision about writing outside the bundle.

**And `--help` is how the surface is found with no skill at all.** Every other way an agent is told this
exists is outside the build — a skill somebody staged, a line in a README, a person pasting a command — and
the one thing true of all of them is that a program an agent has only been handed the path to gets `--help`
typed at it, and `-h` next. Neither was an option here. Both fell through to `DiveArguments.parse`, which
answered `Unknown option --help` and a usage line naming the window's own options and no agent option at
all, *after* `installLogging()` had printed the JVM, the heap limit and the log path over it — and exited 0.
So the single most likely command an agent can type answered that this surface does not exist, successfully.
`DiveHelp.kt` is both spellings, answered before any logging, naming the window's half of the command line and
the commands, with a one-line summary each and `--help <command>` for all of one.

**There is one help, not one per audience, and that is a correction of this build's predecessor.** It had
`--agent-help` beside `--help`: one of the two was the more likely thing for an agent to type and the other
was the one that listed the commands, which is a program answering "what do you take" with half of what it
takes — and in the half the asker was least likely to type. So the commands moved into `--help`, and the three
smaller dead ends are covered by the same text: `--cli` with nothing after it prints it verbatim (measured
identical, byte for byte), a `--cli` given a name no command answers to lists every command in the build, and
`-h` is the same again.

**And the help points at opening a heap dump, because that is the one thing nothing else can tell an agent.**
The command list opens with `Start with open_heap_dump on the heap dump you were given`, and the two worked
examples under it are that command and one call on what it hands back — since an agent that has read the whole
list still has to find out that the answers name a dump by a key and that every other command takes that key.

Progressive disclosure is why a skill is the right home for the *pointer* and an option is the right home for
the *method*: ~80 tokens of name and description at rest, the body loaded only for a session actually holding a
heap dump, and then neither method text until a session asks for one — ~480 tokens for how to work here and
~2,000 for the leak method, each paid by the session that reads it. Nothing before that, which is the property
the MCP server could not have.

## The judgement, in one line

One command line, one registry, and the method behind an option — so the surface costs an agent nothing until it
is used, nothing an answer carries is anything but the answer, and a person can type the call it just made. The criticism of MCP is about surfaces ten times this
size and about servers whose tools are one HTTP call each, and ours was never that; what decided it here is
simpler than the criticism. A window somebody is watching is reached by a shell, and a shell is what every
agent already has.

Sources worth reading before changing this: the [Milvus comparison of the three
shapes](https://milvus.io/blog/is-mcp-dead-cli-and-skills-for-ai-agents.md), Anthropic's
[skill authoring practices](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview), and
[JProfiler's account of making their MCP server survive weaker
models](https://www.ej-technologies.com/blog/2026/07/making-the-jprofiler-mcp-server-robust-for-weaker-models/),
which is the same problem as ours and is what `agent-eval.md` is about.
