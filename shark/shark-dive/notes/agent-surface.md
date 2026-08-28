# The shape of the agent surface

Why Shark Dive is talked to over MCP today, what that costs, and what the other shapes would buy. Numbers
measured on this branch, not estimated.

## What MCP costs here

Measured off `AgentTools.all` and `AgentMethod.INSTRUCTIONS`, one `tools/list` entry per tool:

| | Characters | ≈ tokens | Paid |
| --- | --- | --- | --- |
| Seventeen tool definitions | 21,123 | 5,280 | Every turn, while the server is connected |
| The method | 7,845 | 1,960 | Handshake, and again with `open_heap_dumps` |

So the standing cost of this surface is **7 to 8 k tokens**, around 3.5% of a 200 k window. Parity took the
tool count from eleven to seventeen and the definitions from 13,116 characters to 21,123 — **a fifth of the
window's budget for the six tools that mean an agent never has to ask its human to click something**, which
is the trade this surface exists to make. The sixth is `agent_log`, 1,237 characters of the total, and the
900 the other sixteen grew by are the two agent-log places added to the sentence naming every place, which
`show`, `read_notes` and `take_note` all repeat. The method then grew by half again for the section on reading the
code at the version the dump is of, which is the one part of the method the tools cannot enforce at all and
the part that decides whether an answer is a root cause or a reference. The published horror stories are still an order of magnitude worse:
GitHub's server is ~17.6 k tokens of definitions, and three servers together have been measured at 143 k. The
mitigations that shipped in 2026 (Anthropic's tool search, code execution over MCP) are aimed at that scale.
**This surface is not where a context window goes to die**, and a per-tool cost of ~300 tokens is what buys
descriptions that say when to reach for a tool. Re-measure it if the count doubles again.

## What the command line costs, now that there is one

`--agent <tool> name=value …` is a process per call, and the thing to know is what that *doesn't* cost.
Measured against a packaged build with one window open on `leak_asynctask_o.hprof`:

| | Measured | Paid |
| --- | --- | --- |
| One call, JVM start to JSON on stdout | 160–180 ms | Per call |
| `--agent-help`, all seventeen tools | 14,594 characters, ≈3,650 tokens | Only when read |
| `--agent-help <tool>`, one of them | 500–1,250 characters, ≈125–310 tokens | Only when read |

So the standing cost is nothing, and the whole surface as text is *smaller* than the `tools/list` definitions
of it (14,594 against 21,123) because `reason` is explained once rather than seventeen times. Both
`--agent-help` figures include the invocation path twice, since what it prints is the command to type on this
machine; a shorter install path is a slightly shorter help.

**A call from a shell is not a slower call.** It reaches the same window over the loopback socket the run
already publishes, so the heap dump is the one that was parsed and indexed once and the read queues on that
window's own thread — the 170 ms is a JVM starting and a socket, not a heap dump being reopened. The
process-per-call shape costs exactly one thing, and it isn't speed: **a connection can no longer be what
gathers an investigation**, which is what `--agent-session=` and `AgentSessionFile.continuing` exist for. A
call says which session it is one of, defaulting to `cli<the shell's pid>`, so a conversation's calls are one
row of the *Agent logs* screen the way one held-open MCP connection is.

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
  ~2 k-token body loaded only for a session that is actually investigating a heap dump. Today every session
  pays for it at the handshake whether it is investigating anything or not.

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
