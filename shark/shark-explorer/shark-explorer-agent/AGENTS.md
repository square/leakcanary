# Shark Explorer's agent surface — agent guide

An [MCP](https://modelcontextprotocol.io) server inside the running app, so that an agent investigates the
heap dump **in the window somebody is looking at** rather than one of its own.

This file is scoped to `shark/shark-explorer/shark-explorer-agent/`. Its parent,
`shark/shark-explorer/AGENTS.md`, has the app-wide rules — the heap dump being read off the UI thread, a
verdict being an argument to every read — and they all apply here. This one only records what is specific to
being talked to by a program that is not this app.

## What the pieces are

| File | What it is |
| --- | --- |
| `AgentHeapDump.kt` | The seam: one open heap dump, as everything here sees it. The app implements it over a window; the tests implement it over a `HeapExplorer` and three fields. |
| `AgentTools.kt` | Every tool, each a name, a schema and one read. Where the refusals are. |
| `AgentMethod.kt` | The method, as prose handed to the model twice. |
| `AgentJson.kt` | The explorer's model as JSON. |
| `AgentTool.kt` | One tool, its arguments read strictly, and `AgentRefusal`. |
| `McpSession.kt` | JSON-RPC, one message per line. |
| `AgentSessionFile.kt` | One session on disk, both ways: what a call is written as, and what it reads back as. |
| `AgentServer.kt` | The loopback socket a run publishes, and the file that says where. |
| `AgentStdioBridge.kt` | `--mcp-stdio`: the pipe an MCP client launches. |
| `harness/start-harness.sh` | Opens a window and prints the command that throws an agent at it. |

Nothing here is public API — the module is in `modulesWithoutPublicApi`, like the rest of the explorer — with
two deliberate exceptions, `AgentServer`/`AgentStdioBridge`/`AgentHeapDump*` because the app calls them, and
`AgentRefusal` because the app throws it.

## The refusals are the feature

The whole point of this being a server rather than a library is that **it can say no**, and it works with any
client because saying no is all it does — nothing here ever calls a model.

- `set_verdict` refuses a blank reason (through `LeakStatusOverride`'s own `require`) and refuses a verdict
  that contradicts one already recorded unless it is told to flip it.
- `conclude` refuses until the heap dump agrees that **one** reference is at fault, and the refusal says which
  of the three reasons it is: nothing `STUCK`, nothing `EXPECTED` above it, or *these* steps in between
  with no verdict. Same rule as `faultyReferenceIndexOrNull`, read off the chain rather than asked of it,
  because the three ways it answers null are three different things to do next.
- Every tool takes a `reason`, and it is enforced in `AgentTool.call` rather than only asked for in the
  schema: a client is free to ignore a schema.

So a change that makes any of these easier to satisfy is a change that removes the reason this module exists.
An agent that has narrowed a chain to three unexplained steps must not be able to report a root cause, however
confident it is. `AgentToolsTest` walks that exact story — refused, then a verdict, then concluded — and it is
the test to keep working.

The `reason` is traceability and not a quality gate. Asking a model to explain itself does not make it right,
and [the research says it can make it worse](https://arxiv.org/abs/2504.09664); what it buys is a session log
someone can follow afterwards instead of a conclusion they have to trust.

## A session file has two readers, and neither is in this process

`AgentSessionFile` writes `~/.shark-explorer/agents/sessions/agent-<when>-<id>.jsonl`, one file per
connection, a JSON object per line, the newest `KEEP_SESSION_COUNT` kept. What reads it back is **the window's
*Agent logs* screen and the eval in `notes/agent-eval.md`** — one artefact, two readers, which is why the
reading half lives here beside the writing half and is tested with it. A field written and never read back is
a row of that screen saying nothing.

Three things follow that reading the code won't tell you.

**A call is described before it is answered, not after.** `McpSession.callTool` asks `AgentTools.target` what
the call is about and only then invokes the handler, so **a refused call still records its place** and its row
is still clickable. That is deliberate: the refusals are the half of a session worth reading afterwards, and
a refusal nobody can follow up on is a dead end on the screen. `target` derives the place from the argument
*names* rather than from a second list of tool names — one exception, `list_leaks`, which takes no argument
saying where it is.

**The verbs are here rather than in the app.** `verbOfTool` is beside the tool names, so that a screen never
spells them itself and drift is one list rather than two. `AgentSessionFileTest` asserts every tool in the
registry has one; a tool added without a verb reads as its own name, which is the protocol showing through on
the screen that exists to not show it.

**Writing a session never throws and never blocks the answer.** A bad line is skipped on read with a
`SharkLog.d` saying which, a file whose header is missing falls back to the id in its name, and a truncated
last line — an app killed mid-write — keeps every call before it. An agent's call must not fail because the
record of it couldn't be written.

## In `--mcp-stdio` mode, stdout is the protocol

`main` answers `agentBridgeExitCode` **before `installLogging()`**, because that logger writes to stdout and
one log line in the middle of a JSON-RPC stream is a session the client reports as broken. So in this module:

- Everything the bridge has to say goes to stderr, which is where an MCP client collects a server's log.
- Nothing in the bridge path may use `SharkLog`, `println`, or anything that ends up on stdout.

The app's own side of it — a window answering an agent — logs through `SharkLog` as usual, so a session log
reads as the reason for each call followed by the reads it caused. That is the artefact to ask for when
somebody reports that an agent got it wrong.

## The transport, and why it is two things

**A run publishes a loopback port and a token** to `~/.shark-explorer/agents/<pid>.agent`, and `--mcp-stdio`
is a mode of the same app binary that pipes stdio to it. Two parts because an MCP client can be configured
with a command and not with a port that changes every run.

Deliberately **not** the socket `DeepLinkPeers` listens on, though it is the same shape. A link is one line
answered in a millisecond; this is a session held open for as long as an investigation takes. One port for
both would mean a link arriving mid-investigation and an investigation ending when a link handler closed.

The token is the whole of the authorization, and it is worth being clear about what that is: enough to keep a
web page or another machine out, and **not** a boundary between programs run by the same person — anything
that can read `~/.shark-explorer` can read any heap dump on the disk anyway.

`AgentServer.serve` sets **no read timeout**, unlike the link socket. An agent thinking is a quiet connection.

## An address is a string, never a JSON number

A heap dump's addresses fill the range of `Long`, and a JSON number is a double to most clients of this
protocol: anything above 2^53 comes back rounded, which for an address means a different object, silently. So
every address on this surface is `exactHexObjectId` — `0x12d368b8`, the same spelling the app's own files use
— and `objectIdOfHex` is the only way back. The refusal for a decimal names that case, because a model that
has seen a numeric address elsewhere will write one here.

## kotlinx-serialization without the plugin

`kotlinx-serialization-json` is a **runtime dependency only**: `buildJsonObject`, `Json.parseToJsonElement`
and friends. There is no `kotlin("plugin.serialization")` on this module and no `@Serializable` anywhere,
because everything crossing this boundary is either the explorer's own model — which is not ours to annotate
— or a JSON-RPC envelope of a dozen fields. Adding the plugin to get `@Serializable` would be a compiler
plugin's worth of build for a saving of nothing.

## It is a Java 8 target that cannot run on Java 8

`AgentServer` uses `ProcessHandle.current().pid()`, which is Java 9. The repo-wide Java 8 target sets
`targetCompatibility` and no `options.release`, so this compiles: the bytecode is Java 8 and the reference to
a Java 9 class is only resolved at runtime. Same trick `shark-explorer-jdwp` gets away with for `com.sun.jdi`,
and it is fine for the same reason — this is desktop-only code, loaded by the desktop app and by nothing on
Android.

So don't "fix" it by moving the module out of the Java 8 target list. Do remember that anything added here is
under the same rule as the rest of the explorer: **no Compose, and nothing that assumes a display**, since
the reads happen on the heap dump's thread and the tests run headless.

## Build and test

```bash
./gradlew :shark:shark-explorer:shark-explorer-agent:check   # test + detekt

# The whole surface end to end, in a real window, with an agent that has never seen this repository.
shark/shark-explorer/shark-explorer-agent/harness/start-harness.sh [heap-dump.hprof]
```

Every test here runs against a heap dump built with the `dump { }` DSL and no window, which is what
`AgentHeapDump` being an interface is for. `AgentStdioBridgeTest` is the one that goes through a real socket
in both directions — it swaps `System.in` and `System.out` around the bridge, over a pipe rather than a string
of input, because a real client keeps stdin open until it has its answer.

**The harness is how the thing this module is for actually gets tested.** It builds the packaged app, opens
one heap dump in it, and writes an MCP config pinned to that run plus a prompt that says nothing but "find the
root cause" — so what the agent follows is the method the server handed it. Then read
`~/.shark-explorer/logs`: a run that went well and a run that guessed look completely different there, and
neither of them looks like anything in a unit test.
