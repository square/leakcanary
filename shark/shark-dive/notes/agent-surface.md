# The shape of the agent surface

What Shark Dive's agent surface is — one command line, and a skill so that an agent knows it exists — what
that costs, and what the MCP server it replaced cost before it went. Every number measured on this branch
against a packaged build, not estimated.

## There is one way in, and this note is why

`--agent <tool> name=value …`, a process per call, answered by the run that already has the heap dump open.
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

`--agent <tool> name=value …` is a process per call, and the thing to know is what that *doesn't* cost.
Measured against a packaged build with one window open on `leak_asynctask_o.hprof`:

| | Measured | Paid |
| --- | --- | --- |
| One call, JVM start to JSON on stdout | 170 ms | Per call |
| `--agent-help`, all seventeen tools | 17,923 characters, ≈4,480 tokens | Only when read |
| `--agent-help <tool>`, one of them | 424–2,068 characters, ≈105–520 tokens | Only when read |

So **the standing cost is nothing** — no server is running, no definitions are in a context window, and a
session that never reaches for a heap dump never pays for this surface at all. The whole surface as text is
also *smaller* than MCP's `tools/list` definitions of it were, 17,923 characters against 23,877, because
`reason` is explained once rather than seventeen times.

The full help includes the invocation path three times, since what it prints is the command to type on this
machine, so a shorter install path is a shorter help: these were measured from
`…/build/compose/binaries/main/app/Shark Dive.app/Contents/MacOS/Shark Dive`, and the same text off a `.dmg`
install is 17,608 characters. A single tool's help doesn't repeat the path at all. The largest of those is
`set_verdict` at 2,068 characters, which is the tool with two justifications to explain and the one to watch:
the help for one tool is worth being the short answer.

**A call from a shell is not a slower call.** It reaches the same window over the loopback socket the run
already publishes, so the heap dump is the one that was parsed and indexed once and the read queues on that
window's own thread — the 170 ms is a JVM starting and a socket, not a heap dump being reopened, and the wire
trace below puts 23 ms of it between the connect and the answer. The process-per-call shape costs exactly one
thing, and it isn't speed: **a connection can no longer be what gathers an investigation**, which is what
`--agent-session=` and `AgentSessionFile.continuing` exist for. A call says which session it is one of,
defaulting to `cli<the shell's pid>`, so a conversation's calls are one row of the *Agent logs* screen.

**What a call does queue behind is the window.** Reads are confined to the heap dump's own thread so that an
agent sees what the window shows, which means a call costs whatever that window is already doing — and on a
real dump that can be minutes: a leak analysis of a 4 million object Android dump took 683 s here. That is
the intended trade for every tool that reads one dump.

**Listing what is open is not one of them, and used to be.** `open_heap_dumps` is the only call that touches
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
should not pay for a method it isn't following.**

- **`SURFACE`, 1,819 characters**, is how to work on this surface at all: the reason on every call, the window
  somebody is watching, the `shark://` links to hand back, the gap to admit, and one sentence saying that
  anything about a leak starts at `list_leaks`. `AgentConnection.withTheSurface` prepends it to the first
  *answered* call of a session, exactly once, which is read off the session file rather than held in memory
  because a process per call has no memory. It is the one text every session pays, so it stays the short one.
  Measured: a first `open_heap_dumps` answers 3,980 characters where the same call answers 2,132 after.
- **`LEAK`, 7,920 characters**, is what a leak is, how a verdict spreads, the order to work in, and reading
  the code at the version the dump is of. It travels in `list_leaks`'s answer and nowhere else, so the call
  that pays for it is the call it is the method for, and a session that only ever asks where the memory went
  never pays it at all. Measured: `list_leaks` answers 14,477 characters as a session's second call, and
  16,312 as its first — `SURFACE`, a blank line, `LEAK`, 9,741 characters of method in one field.

**An investigation of a leak that never called `list_leaks` therefore never read the leak method**, and that
is the intended consequence rather than a hole to patch. `conclude` is what holds it: an investigation that
skipped the method has not narrowed a chain to one reference, so it cannot finish.

**The split was forced by MCP's caps and is kept because it was right anyway.** Claude Code caps every MCP
tool description and every server `instructions` at 2,048 characters — `CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH`
changes it, and the cap is documented in the
[Claude Code changelog](https://github.com/anthropics/claude-code/blob/main/CHANGELOG.md) — so of the one
9,372-character method that used to be handed over at the handshake, **a Claude Code session was given the
first 22%** and the rest was dropped before the model saw anything. What made that survivable was that a tool
*result* was a different budget, `MAX_MCP_OUTPUT_TOKENS`, 25,000 tokens by default, which the whole method was
a tenth of — so `open_heap_dump` carried the lot, and that copy was the only reason a session ever read the
end of it. Two budgets, one text that fitted neither well: the same reason the split still holds now that both
halves travel in answers.

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
assumed — the longest description was `chain_from_gc_root` at 652 characters, a third of the cap, and none of
the seventeen was within 1,300 of it. So a description can go on saying when to reach for its tool, in
`--agent-help` as it did in a schema. What would silently lose text is the one thing not to write: a tool whose
description is a page.

And the shape of the traffic, which is the part the command line fixed rather than made cheaper. `initialize`
answered with the method, and `--agent` was a process per call, so **a session of typed calls carried the
handshake once per call**: 32,976 characters of `initialize` answers in a nine-call trace, 57% of the session
file, handed to a reader that already had the same text. Worse than the size was the drawing — **two rows per
typed command**, so the *Agent logs* screen read as Connected, called, Connected, called.
`McpSession.isTheCommandLineSayingHello` existed to drop exactly that one message. Both are gone with the
protocol: one command typed is one row because nothing crosses the socket but the call.

**So don't reintroduce anything in front of the call.** A capabilities exchange, a `hello`, a version
negotiation: each of them is a row of a screen saying a process started and did the thing the next row already
names, and there is nothing for one to carry. What the tools are is `--agent-help`, text this build prints with
no window and no heap dump. How to work here arrives in the first *answer*, which is the one thing an agent is
certain to read, because it asked for it.

## And what reading somebody else's session costs

`agent_log` without a session id is a line per session and cheap: 1,349 characters for the two sessions of the
trace below. With one, it is every line of that session with what was sent and what came back, which is the
only form that answers "why did it do that next" — and it is the one answer on this surface big enough to hit
the caller's own cap. Measured against the `flowtrace` session below, nine calls:

| | Measured |
| --- | --- |
| The six tool-call rows | 3,980 + 2,132 + 2,132 + 14,477 + 139 + 853 characters of answer |
| The three that reached no tool | 28 + 34 + 282 characters — the failure, and nothing else to keep |
| The session file on disk | 30,050 characters, ten lines: a header and nine calls |
| The whole `agent_log session=…` answer | 33,035 characters, ≈8,260 tokens |

So **a tool call is one to four thousand characters and the answer is nearly all of it**, a `list_leaks` that
carried the method is half the session on its own, and a thirty-call investigation read in full is most of a
small context window. That is the tool being used for what it is for rather than a leak — it is the one call
on this surface whose subject is somebody else's whole investigation.

**And at 33,035 characters it is over Claude Code's 30,000**, so what a Claude Code session actually gets is a
2 KB preview and a path to read. Which works, and is worth knowing before concluding that `agent_log` returned
nothing. If it needs to be cheaper, the shape to reach for is a way to ask for one call's exchange rather than
a shorter version of every call's. Nothing truncates it here, deliberately: a session cut to fit is one where
the answer that misled an agent is the part that got cut.

## The flow, end to end, traced

Every number and every line below was read off one run: a packaged build of this branch, one window on
`shark/shark-android/src/test/resources/leak_asynctask_o.hprof`, and a logging relay in front of that run's
socket so both directions are on the record. `SHARK_DIVE_DIR` pointed at a scratch directory, which is what
keeps a trace out of the notes and logs of whoever is running it — see `sharkDiveDirectory()` in
`shark/shark-dive/shark-dive-app/src/main/java/shark/dive/app/DiveLogging.kt`.

### The pieces, and what each one is handed

Every path below is under `shark/shark-dive/shark-dive-agent/src/main/java/shark/dive/agent/`, except the
window's own, which is `shark/shark-dive/shark-dive-app/src/main/java/shark/dive/app/`.

| Piece | Process | In | Out |
| --- | --- | --- | --- |
| The window, `Main.kt` | The app: one per run, many windows | A heap dump on its command line | A window, and one `<pid>.agent` file |
| `AgentServer.listen`, `AgentServer.kt:44` | A daemon thread of that process | A loopback connection | A thread per connection, with an `AgentConnection` on it |
| `~/.shark-dive/agents/<pid>.agent` | A file, written as the run starts | — | `port=`, `token=`, readable by its owner alone |
| `AgentConnection.answer`, `AgentConnection.kt:49` | That thread | One line of JSON per call | One line back, and a line of the session file |
| `AgentWire.kt` | Neither end's, which is the point | A call, or an answer | The two keys each of them is |
| `AgentTools`, `AgentTools.kt:46` | Suspends onto the heap dump's own thread | A tool name and its arguments | A `JsonObject`, or an `AgentRefusal` |
| `AgentSessionFile.kt` | The same thread | A line per call | `~/.shark-dive/agents/sessions/agent-<when>-<id>.jsonl` |
| `AgentCommandLine.run`, `AgentCommandLine.kt:45` | **A process per call**, the same binary | The words after `--agent` | Pretty JSON on stdout, a refusal on stderr, an exit code |

### A typed call is a process, a connect, and two lines

```bash
"Shark Dive.app/Contents/MacOS/Shark Dive" --agent-session=flowtrace \
  --agent open_heap_dumps reason="Finding out what is already open"
```

That command starts a JVM, which reads `~/.shark-dive/agents`, picks the newest run whose pid is still alive,
connects to its port, and sends this — `->` is the command line talking, `<-` is the window, and the seconds
are from the start of the trace:

```
13.003 c2 -- connected
13.004 c2 -> 076984584dad3fb286a88d0a3e81ea12 flowtrace
13.004 c2 <- OK
13.024 c2 -> {"tool":"open_heap_dumps","arguments":{"reason":"Finding out what is already open"}}
13.026 c2 <- {"answer":{"heapDumps":[{"heapDump":"leak_asynctask_o.hprof","window":"p8jku5nd", …}
13.031 c2 -- closed
```

Six lines, and four things about it that the shape of that trace is the evidence for.

**The first line is not JSON.** `token[ sessionName]`, answered `OK` or `NO`, because the alternative is a
handshake to negotiate before anything can be sent. `flowtrace` is the session these calls join; a connection
that names none gets one of its own. Anything after those two words is dropped rather than refused, which is
what a build talking to a run of a different version needs — a third word used to be there, saying which
transport a line came in over, and that is how it left without a flag day.

**Then one line each way, and that is the whole protocol.** `{"tool":…,"arguments":{…}}` out, and exactly one
of `{"answer":{…}}`, `{"refused":"…"}` or `{"failed":"…"}` back. Three and not two, because a refusal is the
surface working — a tool sending an agent back to the heap dump with the next thing to do — and a caller told
that was a failure is a caller told this app fell over. `AgentCommandLine` maps them to exit 0, 2 and 1.

**The window does the work on the heap dump's own thread**, so what a call takes is how long that thread takes
to reach it: 2 ms here, `open_heap_dumps` being the one call that touches no heap dump thread at all. The 20 ms
in front of it is this process encoding its own call, and `time` around the whole command says 170 ms, nearly
all of which is a JVM starting.

**The socket closes with the process.** Nothing is held open, and nothing has to be: what gathers the calls
is the session name, not the connection.

### Six typed calls are six processes, six connections, and one row each

The rest of the trace, as commands typed in a row against the same window:

| Command | Exit | stdout | `method` in the answer |
| --- | --- | --- | --- |
| `--agent open_heap_dumps` | 0 | 3,980 characters: the open dumps, with sizes | `SURFACE`, 1,819 characters — first answered call of the session |
| `--agent open_heap_dumps` ×2 | 0 | 2,132 characters each | none: the surface half is spent |
| `--agent list_leaks` | 0 | 14,477 characters | `LEAK`, 7,920 characters |
| `--agent conclude reference=…` | 2 | empty | none: a refusal carries no answer |
| `--agent conclude object=0x12d368b8 rootCause=…` | 2 | empty | none — the candidate references are named on stderr instead |
| `--agent list_leaks --agent-session=flowleak` | 0 | 16,312 characters | 9,741: `SURFACE`, a blank line, `LEAK` |

The two refusals are the pair worth reading. `conclude reference=…` is refused by `AgentArguments.onlyTakes` —
"conclude does not take `reference`. It takes `heapDump`, `howToReproduce`, `notChecked`, `object`, `reason`,
`rootCause`, and nothing else." — and the one with the right arguments is refused by the heap dump: *"A root
cause names the one reference a chain is the leak of, and this chain leaves AsyncTask.SERIAL_EXECUTOR,
AsyncTask$SerialExecutor.mActive, AsyncTask$SerialExecutor$1.val$r, AsyncTask$3.this$0, MainActivity$2.this$0.
The fault is at one of those references, and what settles which is the objects between them that have no
verdict…"*. Both went to stderr with exit code 2 and nothing on stdout, each prefixed `[shark-dive]` so that a
shell's output says which program is talking.

And the calls are two session files, not six:

```
agent-2026-09-29_21-23-30_220-flowtrace.jsonl   header + 3 × open_heap_dumps + list_leaks + 2 × conclude
agent-2026-09-29_21-23-44_160-flowleak.jsonl    header + list_leaks
```

A refused call is a row, with the refusal in `output` as well as in `refused`. So is a line that reached no
tool at all: three were pushed at that socket by hand — `this is not JSON`, a call with no `tool`, and a
`solve_the_leak` nothing answers to — and each is a row of `flowtrace` answered `{"failed":…}`, with `tool`
null for the first two. `AgentSession.toolCalls` is the subset that got as far as a tool, which is what the
`n call(s)` on screen counts and what the eval scores: a number that moved with whatever a caller happened to
send would be a number nobody could act on.

## So: one core, one adapter

The thing worth protecting is that **the enforcement is not in the transport**. `AgentTools` is a registry of
(name, schema, handler) and every refusal is thrown from a handler, so an adapter is a translation of
arguments in and JSON out, not a second copy of the rules:

- `AgentCommandLine` — `--agent <tool> name=value …`, which turns a command line into one call on the socket
  the window publishes and prints what came back. It refuses nothing itself: every refusal it reports was
  thrown by a handler. `--agent-help` is generated from the registry, so a tool cannot be on one and missing
  from the other, and it is described through `NoHeapDumpToDescribe` — a heap dump whose every method throws —
  which makes "printed, never called" hold rather than be a habit.
- The skill — `.claude/skills/shark-dive/SKILL.md`. Prose, not generated, and it points at `--agent-help` and
  at the method the tools hand over rather than repeating either, since a list of tools in a file is a list
  that goes stale. See the next section for why it is in `.claude/`.

`McpSession` was the second adapter and is gone, along with `AgentStdioBridge` and `AgentStdioServer`. What
that removed, beyond the tokens above: a JSON-RPC envelope to keep on the right side of, a `tools/list` schema
per tool to keep in step with the help text, a session-file field recording which way a line came in, a
client-shaped row on a screen meant for an investigation, and the one case
(`isTheCommandLineSayingHello`) that existed to undo the handshake's cost. **What it must never become again
is two places that decide whether an investigation may conclude.**

**`--no-ui` stayed, rebuilt around the socket.** It is worth being explicit about because it was a capability
rather than a transport, and the MCP version of it was a transport as well: the same tools served from the
bridge process, no window, and no `<pid>.agent` file, so its only client was an MCP client and it could have
had no other. What it is now is a run of the app that opens no window and **publishes the socket anyway**, so
`--agent` reaches it exactly as it reaches a window and nothing on the calling side knows which it is talking
to. One surface stays one surface, and a build server or a box over ssh is a machine this works on.

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
answered `Unknown option --help` and a usage line naming `--title` and the heap dumps and no agent option at
all, *after* `installLogging()` had printed the JVM, the heap limit and the log path over it — and exited 0.
So the single most likely command an agent can type answered that this surface does not exist, successfully.
`DiveHelp.kt` is both spellings, answered before any logging, naming the window's half of the command line and
the agent's, and pointing at `--agent-help` for the tools themselves. The two smaller dead ends were already
covered: a bare `--agent` says it needs a tool name and where the list is, and a tool name nothing answers to
lists every tool in the build.

Progressive disclosure is why a skill is the right home for the *pointer* and the answers are the right home
for the *method*: ~80 tokens of name and description at rest, the body loaded only for a session actually
holding a heap dump, and then 455 tokens of `SURFACE` on the first answer and the leak half only if the leaks
are asked for. Nothing before that, which is the property the MCP server could not have.

## The judgement, in one line

One command line, one registry, and the method in the answers — so the surface costs an agent nothing until it
is used, and a person can type the call it just made. The criticism of MCP is about surfaces ten times this
size and about servers whose tools are one HTTP call each, and ours was never that; what decided it here is
simpler than the criticism. A window somebody is watching is reached by a shell, and a shell is what every
agent already has.

Sources worth reading before changing this: the [Milvus comparison of the three
shapes](https://milvus.io/blog/is-mcp-dead-cli-and-skills-for-ai-agents.md), Anthropic's
[skill authoring practices](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview), and
[JProfiler's account of making their MCP server survive weaker
models](https://www.ej-technologies.com/blog/2026/07/making-the-jprofiler-mcp-server-robust-for-weaker-models/),
which is the same problem as ours and is what `agent-eval.md` is about.
