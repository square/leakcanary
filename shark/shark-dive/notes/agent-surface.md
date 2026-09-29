# The shape of the agent surface

Why Shark Dive is talked to over MCP today, what that costs, and what the other shapes would buy. Numbers
measured on this branch, not estimated.

## What MCP costs here

Measured off `AgentTools.all`, `AgentMethod.SURFACE` and `AgentMethod.LEAK`, one `tools/list` entry per tool:

| | Characters | ≈ tokens | Paid |
| --- | --- | --- | --- |
| Seventeen tool definitions | 23,877 | 5,970 | Every turn, while the server is connected |
| How to work here, `SURFACE` | 1,819 | 455 | The handshake, and again on the first *answered* call of a session |
| What to do with a leak, `LEAK` | 7,920 | 1,980 | `list_leaks`'s answer, once, and nowhere else |

So the standing cost of this surface is **around 6.4 k tokens**, about 3% of a 200 k window, and the 2 k that
is the leak method is paid by the one call it is the method for — see the next section for why it is split
that way. Parity took the
tool count from eleven to seventeen and the definitions from 13,116 characters to 23,132 — **a fifth of the
window's budget for the six tools that mean an agent never has to ask its human to click something**, which
is the trade this surface exists to make. The sixth is `agent_log`, 1,237 characters of the total, and the
900 the other sixteen grew by are the two agent-log places added to the sentence naming every place, which
`show`, `read_notes` and `take_note` all repeat. The 719 since are two arguments a measured round of eval runs
said were missing: `show` taking an `object` like every other tool rather than a `place` alone, and
`set_verdict` taking the verdict's own `why` as well as the `reason` the call was made for. The method then
grew by half again for the section on reading the
code at the version the dump is of, which is the one part of the method the tools cannot enforce at all and
the part that decides whether an answer is a root cause or a reference. The published horror stories are still an order of magnitude worse:
GitHub's server is ~17.6 k tokens of definitions, and three servers together have been measured at 143 k. The
mitigations that shipped in 2026 (Anthropic's tool search, code execution over MCP) are aimed at that scale.
**This surface is not where a context window goes to die**, and a per-tool cost of ~300 tokens is what buys
descriptions that say when to reach for a tool. Re-measure it if the count doubles again.

## Two texts, because there are two budgets

Claude Code caps **every MCP tool description and every server `instructions` at 2,048 characters**, for every
server in the session, and the cap is a documented one:
`CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH` is the environment variable that changes it, added in the
[Claude Code changelog](https://github.com/anthropics/claude-code/blob/main/CHANGELOG.md) — "Added
`CLAUDE_CODE_MAX_MCP_DESCRIPTION_LENGTH` to change the 2,048-character cap on MCP tool descriptions and server
instructions for every MCP server in the session". The method was one text of 9,372 characters, so **what a
Claude Code session was handed at the handshake was the first 22% of it** and the rest was dropped before the
model saw anything.

What made that survivable was the second hand-over. A tool *result* is a different budget —
`MAX_MCP_OUTPUT_TOKENS` defaults to 25,000 tokens (`var C=0.5,l=1600,P=25000` in the bundle inside
`~/.local/share/claude/versions/2.1.280`, the `P` that read of the variable falls back to), which the whole
method was a tenth of — so `open_heap_dump` and `open_heap_dumps` each carried the lot, and that copy was the
only reason a Claude Code session ever read the end of it.

Which left the text that says *how to work here* arriving truncated, and the text about *leaks* arriving on a
call that has nothing to do with leaks — paid by every agent asked where the memory had gone. So
`AgentMethod` now splits it where the two budgets do:

- **`SURFACE`, 1,819 characters**, is how to work on this surface at all: the reason on every call, the window
  somebody is watching, the `shark://` links to hand back, the gap to admit, and one sentence saying that
  anything about a leak starts at `list_leaks`. It fits the cap with 229 characters to spare, which is the
  property to keep when editing it. It is the handshake's `instructions`, and `McpSession.withTheSurface`
  prepends it to the first *answered* call of a session as well — for the clients that drop `instructions`,
  and for `--agent`, whose `initialize` result no model ever sees. Measured on a packaged build: 1,819
  characters in the handshake, and the same 1,819 leading the first answer.
- **`LEAK`, 7,920 characters**, is what a leak is, how a verdict spreads, the order to work in, and reading
  the code at the version the dump is of. It travels in `list_leaks`'s answer and nowhere else, so the call
  that pays for it is the call it is the method for, and a session that only ever asks where the memory went
  never pays it at all. Measured: 7,920 characters on the second answered call of a session, and 9,741 —
  `SURFACE`, a blank line, `LEAK` — when `list_leaks` is the first call a session makes.

**An investigation of a leak that never called `list_leaks` therefore never read the leak method**, and that
is the intended consequence rather than a hole to patch. `conclude` is what holds it: an investigation that
skipped the method has not narrowed a chain to one reference, so it cannot finish.

The other thing worth having measured is that **the cap does not touch the tool definitions**, rather than
assumed: the longest description in the registry is `chain_from_gc_root` at 652 characters, a third of the cap,
and none of the seventeen is within 1,300 of it. So a description can go on saying when to reach for its tool.
What would silently lose text is the one thing not to write — a tool whose description is a page.

## And what reading somebody else's session costs

`agent_log` without a session id is a line per session and cheap. With one, it is now every *message* of that
session with what each one sent and read back, which is the only form that answers "why did it do that
next" — and the numbers are worth knowing before reaching for it. Measured against a packaged run on
`leak_asynctask_o.hprof`: four calls typed at `--agent` (two `list_leaks`, a refused `conclude`, a
`solve_the_leak` that is no tool) and four messages pushed at the socket by hand (a line that is not JSON, a
`resources/list`, a `tools/call` with no name, a notification):

| | Measured |
| --- | --- |
| The four tool-call rows | 8,783 + 8,775 + 1,750 + 987 characters — the answer is nearly all of each |
| The protocol rows | 37,313 characters, of which **32,976 was four `initialize` answers** |
| The whole `agent_log session=…` answer | 57,693 characters, ≈14,400 tokens |

So **a tool call is one to two thousand tokens** and a thirty-call investigation read in full is most of a
small context window. That is the tool being used for what it is for rather than a leak — it is the one call
on this surface whose subject is somebody else's whole investigation.

**The handshakes were the surprise, and they were a command line's.** `initialize` answers with the method —
`AgentMethod.SURFACE` today, the whole 9,372-character method when this was measured — and `--agent` is a
process per call, so a session of typed calls carried it once per call: 57% of the measurement above, handed to
a reader that already had the same text in its
own context — and, worse than the size, **two rows per command typed**, so the screen drew a shell's four
calls as eight and read as Connected, called, Connected, called.

`McpSession.isTheCommandLineSayingHello` is the fix, and it is one case and no more: a `--agent` process's own
`initialize` is not recorded. So one typed command is one row, and the same session recorded today is 24,717
characters rather than 57,693 — arithmetic on the numbers above, since nothing but those four rows went. What
that does not do is record less of what a client sent: an MCP session pays for its handshake once and keeps
it, a line that is not JSON is still a row, and a session that kept only what reached a tool could not say
why nothing did. Nothing is hidden either — the CLI's connection is in that run's `~/.shark-dive/logs` file,
and the session still names the client as `shark-dive-cli`.

Splitting the method shrank the same four handshakes to 7,980 characters from 32,976 — an `initialize` answer
is 1,995 characters now, measured — and **that is not a reason to record them again.** What made them worth
dropping was the row per command, which the size only made easier to see.

If `agent_log` needs to be cheaper again, the shape to reach for is a way to ask for one message's exchange
rather than a shorter version of every message's.

Nothing truncates it, deliberately: a session cut to fit is one where the answer that misled an agent is the
part that got cut.

## What the command line costs, now that there is one

`--agent <tool> name=value …` is a process per call, and the thing to know is what that *doesn't* cost.
Measured against a packaged build with one window open on `leak_asynctask_o.hprof`:

| | Measured | Paid |
| --- | --- | --- |
| One call, JVM start to JSON on stdout | 180–200 ms | Per call |
| `--agent-help`, all seventeen tools | 17,485 characters, ≈4,370 tokens | Only when read |
| `--agent-help <tool>`, one of them | 500–2,068 characters, ≈125–520 tokens | Only when read |

So the standing cost is nothing, and the whole surface as text is *smaller* than the `tools/list` definitions
of it (17,485 against 23,877) because `reason` is explained once rather than seventeen times. Both
`--agent-help` figures include the invocation path twice, since what it prints is the command to type on this
machine; a shorter install path is a slightly shorter help — these were measured with
`/Applications/Shark Dive.app/Contents/MacOS/Shark Dive`, the `.dmg` install path. The largest single tool's
help is `set_verdict` at 2,068 characters, which is a tool with two justifications to explain and is the one
to watch: the help for one tool is worth being the short answer.

**A call from a shell is not a slower call.** It reaches the same window over the loopback socket the run
already publishes, so the heap dump is the one that was parsed and indexed once and the read queues on that
window's own thread — the 190 ms is a JVM starting and a socket, not a heap dump being reopened, and the wire
trace in the next section puts 56 ms of it between the connect and the answer. The
process-per-call shape costs exactly one thing, and it isn't speed: **a connection can no longer be what
gathers an investigation**, which is what `--agent-session=` and `AgentSessionFile.continuing` exist for. A
call says which session it is one of, defaulting to `cli<the shell's pid>`, so a conversation's calls are one
row of the *Agent logs* screen the way one held-open MCP connection is.

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
| `AgentServer.listen`, `AgentServer.kt:44` | A daemon thread of that process | A loopback connection | A thread per connection, with an `McpSession` on it |
| `~/.shark-dive/agents/<pid>.agent` | A file, written as the run starts | — | `port=`, `token=`, readable by its owner alone |
| `McpSession.answer`, `McpSession.kt:80` | That thread | One JSON-RPC message per line | One answer per line, and a line of the session file |
| `AgentTools.kt:46` | Suspends onto the heap dump's own thread | A tool name and its arguments | A `JsonObject`, or an `AgentRefusal` |
| `AgentSessionFile.kt` | The same thread | A line per message | `~/.shark-dive/agents/sessions/agent-<when>-<id>.jsonl` |
| `AgentCommandLine.run`, `AgentCommandLine.kt:48` | **A process per call**, the same binary | The words after `--agent` | Pretty JSON on stdout, a refusal on stderr, an exit code |
| `AgentStdioBridge.kt` | One process for a whole `--mcp-stdio` session | JSON-RPC on stdin | The same lines on stdout, its own log on stderr |
| `AgentStdioServer.kt` | One process for `--no-ui`, no window and no socket | JSON-RPC on stdin | The same, answered in that process |

### A typed call is a process, a connect, and two messages — which is the hypothesis, confirmed

```bash
"Shark Dive.app/Contents/MacOS/Shark Dive" --agent-session=flowtrace \
  --agent open_heap_dumps reason="Finding out what is already open"
```

That command starts a JVM, which reads `~/.shark-dive/agents`, picks the newest run whose pid is still alive,
connects to its port, and sends this — `->` is the command line talking, `<-` is the window, and the seconds
are from the start of the trace:

```
8.180 c1 -- connected
8.185 c1 -> e1944e6fe2aae2625905f074a9ca7c58 flowtrace cli
8.186 c1 <- OK
8.207 c1 -> {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18", …}}
8.224 c1 <- {"jsonrpc":"2.0","id":1,"result":{…,"instructions":"You are reading a heap dump through …"}}
8.227 c1 -> {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"open_heap_dumps", …}}
8.233 c1 <- {"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"{\n    \"method\": …
8.236 c1 -- closed
```

So: **one process per command, which connects first and then makes the call**, and four things about it that
the shape of that trace is the evidence for.

**The first line is not JSON.** `token[ sessionName[ over]]`, answered `OK` or `NO`, because the alternative
is a handshake to negotiate before the protocol can start. `flowtrace` is the session these calls join and
`cli` is how they arrived; an MCP client sends the token alone, and that is the whole difference between the
two from here on.

**The `initialize` is sent on every typed command and its answer is read and thrown away.** It exists to say
who is calling — `shark-dive-cli`, which is what the *Agent logs* screen shows connected — and
`AgentCommandLine.kt:231` null-checks the result for liveness and keeps nothing out of it. So the
`instructions` on that line, 1,995 characters of it, crossed the socket five times in this trace and were
read by nothing, which is exactly why `McpSession.withTheSurface` puts the same text on the first *answered*
call instead.

**The window does the work on the heap dump's own thread**, so the 56 ms between connecting and the answer is
a queue, and `time` around the whole command says 180–200 ms — the rest is a JVM starting.

**The socket closes with the process.** Nothing is held open, and nothing has to be: what gathers the calls
is the session name, not the connection.

### Five typed calls are five processes, five connections, and one row each

The rest of the trace, as five commands typed in a row against the same window:

| Command | Exit | stdout | `method` in the answer |
| --- | --- | --- | --- |
| `--agent open_heap_dumps` | 0 | The open dumps, with sizes | `SURFACE`, 1,819 characters — first answered call of the session |
| `--agent list_leaks` | 0 | The leaks | `LEAK`, 7,920 characters — the surface half is spent |
| `--agent conclude reference=…` | 2 | empty | none: a refusal has no `structuredContent` |
| `--agent conclude object=0x12d368b8 rootCause=…` | 2 | empty | none — five candidate references named on stderr instead |
| `--agent list_leaks --agent-session=flowleak` | 0 | The leaks | 9,741 characters: `SURFACE`, a blank line, `LEAK` |

The two refusals are the pair worth reading. `conclude reference=…` is refused by
`AgentArguments.onlyTakes` — "conclude does not take `reference`. It takes `heapDump`, `howToReproduce`,
`notChecked`, `object`, `reason`, `rootCause`, and nothing else." — and the one with the right arguments is
refused by the heap dump: *"this chain leaves AsyncTask.SERIAL_EXECUTOR, AsyncTask$SerialExecutor.mActive,
AsyncTask$SerialExecutor$1.val$r, AsyncTask$3.this$0, MainActivity$2.this$0 … They are 0x12d35068 …, so work
out whether each of them is done with its work"*. Both went to stderr with exit code 2 and nothing on stdout.

And the five commands are two session files, not five:

```
agent-2026-09-29_18-54-42_291-flowtrace.jsonl   header + open_heap_dumps + list_leaks + conclude + conclude
agent-2026-09-29_18-55-15_162-flowleak.jsonl    header + list_leaks
```

Five `initialize`s were sent and none is a row: `McpSession.isTheCommandLineSayingHello`. A refused call is a
row, with the refusal in `output` as well as in `refused`.

### MCP is the same lines, one process, one connection

A client is configured with a command, not a port, so `--mcp-stdio` is a process that pipes stdin to that same
socket and the socket back to stdout. Driven by a 40-line Python client, one session:

```
one server process, pid 92007
  0.116 initialize  answered 1996 characters     instructions: 1819 characters
        notifications/initialized sent, nothing to read back
  0.124 tools/list  answered 23922 characters    17 tools
  0.131 tools/call  answered  6918 characters    open_heap_dumps   method 1819 characters
  0.138 tools/call  answered 26391 characters    list_leaks        method 7920 characters
  0.163 tools/call  answered 20427 characters    describe_object   method none
server exited 0
```

What differs from the command line, and it is four things and not the protocol:

- **One handshake for the session**, and its `instructions` reach the model — which is why `SURFACE` has to
  fit 2,048 characters. The first answered call carries it *again*, on purpose: a client that drops
  `instructions` is indistinguishable from one that shows them.
- **`tools/list` is a real call**, 23,922 characters of schemas, and it is what a client discovers the surface
  with. A command line reads `--agent-help` instead, and never sends this.
- **The connection is the session.** No name is sent, so `AgentSessionFile.starting` gives it a file of its
  own — `agent-2026-09-29_18-55-51_510-c5c0163f.jsonl`, seven lines: the header, the handshake, the
  `notifications/initialized`, the `tools/list` and the three calls. The handshake *is* a row here, because a
  client connects once and what it said is worth keeping.
- **The JVM starts once.** Each call after the first is single-digit milliseconds of socket plus whatever the
  read costs.

`--no-ui` is the same client and the same lines with the socket taken out: `AgentStdioServer` builds
`AgentTools` over `HeadlessAgentHeapDumps` in the bridge process itself, so there is no window, no
`<pid>.agent` file, and `show` answers with the link and says it had nowhere to put a tab.

## What each shape is actually good at

- **MCP** is the shape a client *discovers*: the tools, their schemas and every refusal arrive in band, so
  nothing has to teach a model what this surface is. And a connection is a session for free. What it is not
  is the only way to reach a live window — that was the assumption this note was written under, and it was
  wrong.
- **The command line** is what an agent reaches for without being configured, costs nothing until it is run,
  pipes into `grep`, and is the only one of the two an agent whose client speaks no MCP can use. It pays for
  the discovery MCP gets free: something has to tell it `--agent-help` exists, which is the skill's job.
- **A skill** ([the open standard](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview),
  now read by Claude, Codex, Gemini CLI, Cursor and others) is the right home for *the method*, because
  progressive disclosure is exactly what the method wants: ~80 tokens of name and description at rest, the
  ~2 k-token body loaded only for a session that is actually investigating a heap dump. Splitting the method
  buys most of that inside the protocol — a session pays 455 tokens at the handshake and the leak half only
  when it asks for the leaks — so what a skill still adds is the part no MCP server can, which is being read
  before anything is connected.

## So: one core, several adapters

The thing worth protecting is that **the enforcement is not in the transport**. `AgentTools` is a registry of
(name, schema, handler) and every refusal is thrown from a handler, so a second adapter is a translation of
arguments in and JSON out, not a second copy of the rules:

- `McpSession` — JSON-RPC over the socket. Exists.
- `AgentCommandLine` — `--agent <tool> name=value …`, which turns a command line into one `tools/call` on that
  same socket and prints what came back. Exists. It refuses nothing itself: every refusal it reports was
  thrown by the handler that would have refused an MCP client. `--agent-help` is generated from the registry,
  so a tool cannot be on one and missing from the other, and it is described through `NoHeapDumpToDescribe` —
  a heap dump whose every method throws — which makes "printed, never called" hold rather than be a habit.
- The skill — `.claude/skills/shark-dive/SKILL.md`. Exists. Prose, not generated, and it points at
  `--agent-help` and at the method the tools hand over rather than repeating either, since a list of tools in
  a file is a list that goes stale. See the next section for why it is in `.claude/`.

What that leaves duplicated is argument parsing per adapter, which is tens of lines — and less than that
here, because `AgentArguments` reads a number and a boolean out of text (the tools were written for a model,
which sends `limit=30` as a string as often as not). So a command line sends every value as it was typed and
the only shape needing a spelling of its own is a list, which is comma separated because a shell has no
brackets. What it must never become is two places that decide whether an investigation may conclude.

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

## The judgement, in one line

MCP for a client that can be configured, the command line for everything else, and the method in a skill so
it costs nothing until it is needed — all three over one registry. The criticism of MCP is about surfaces ten
times this size and about servers whose tools are one HTTP call each; ours is a session against a live
process, which is the case that criticism still concedes.

Sources worth reading before changing this: the [Milvus comparison of the three
shapes](https://milvus.io/blog/is-mcp-dead-cli-and-skills-for-ai-agents.md), Anthropic's
[skill authoring practices](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview), and
[JProfiler's account of making their MCP server survive weaker
models](https://www.ej-technologies.com/blog/2026/07/making-the-jprofiler-mcp-server-robust-for-weaker-models/),
which is the same problem as ours and is what `agent-eval.md` is about.
