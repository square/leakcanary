# The shape of the agent surface

Why the explorer is talked to over MCP today, what that costs, and what the other shapes would buy. Numbers
measured on this branch, not estimated.

## What MCP costs here

Measured off `AgentTools.all` and `AgentMethod.INSTRUCTIONS`, one `tools/list` entry per tool:

| | Characters | ≈ tokens | Paid |
| --- | --- | --- | --- |
| Eleven tool definitions | 13,116 | 3,300 | Every turn, while the server is connected |
| The method | 4,970 | 1,240 | Handshake, and again with `open_heap_dumps` |

So the standing cost of this surface is **4 to 6 k tokens**, 2 to 3% of a 200 k window. The published
horror stories are an order of magnitude worse — GitHub's server is ~17.6 k tokens of definitions, three
servers together have been measured at 143 k — and the mitigations that shipped in 2026 (Anthropic's tool
search, code execution over MCP) are aimed at that scale. **This surface is not where a context window goes
to die**, and a per-tool cost of ~300 tokens is what buys descriptions that say when to reach for a tool.
Worth re-measuring when the tool count doubles, which the parity work will do.

## What each shape is actually good at

- **MCP** is the only one of the three that gets a *session*: a process already holding a parsed heap dump,
  its indexes, the window a person is watching, and the verdicts set so far. Reopening `large-dump.hprof`
  costs seconds and hundreds of megabytes, so a stateless call per question is not a smaller version of
  this, it is a different and much slower tool. It is also the only shape a client discovers on its own.
- **A CLI** is what an agent reaches for without being told, costs nothing until it is run, and pipes into
  `grep`. Two things it would buy that MCP can't: the **no window open** case, and clients that speak no
  MCP. What it must not be is a second implementation — see below.
- **A skill** ([the open standard](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview),
  now read by Claude, Codex, Gemini CLI, Cursor and others) is the right home for *the method*, because
  progressive disclosure is exactly what the method wants: ~80 tokens of name and description at rest, the
  1,240-token body loaded only for a session that is actually investigating a heap dump. Today every session
  pays for it at the handshake whether it is investigating anything or not.

## So: one core, several adapters

The thing worth protecting is that **the enforcement is not in the transport**. `AgentTools` is a registry of
(name, schema, handler) and every refusal is thrown from a handler, so a second adapter is a translation of
arguments in and JSON out, not a second copy of the rules:

- `McpSession` — JSON-RPC over the socket. Exists.
- A CLI adapter — one subcommand that names a tool and its arguments, printing the answer or the refusal, and
  a `--agent-help` that prints the same descriptions the schema carries so nothing has to be written twice.
  Talks to a published run when there is one, and opens a heap dump itself when there isn't.
- The skill — the method as `SKILL.md`, plus how to reach either adapter. Prose, not generated, and it points
  at `--agent-help` rather than listing tools that would go stale.

What that leaves duplicated is argument parsing per adapter, which is tens of lines. What it must never
become is two places that decide whether an investigation may conclude.

## The judgement, in one line

Keep MCP for the window somebody is watching, add the CLI for the window that isn't open yet, and move the
method into a skill so it costs nothing until it is needed. The criticism of MCP is about surfaces ten times
this size and about servers whose tools are one HTTP call each; ours is a session against a live process,
which is the case that criticism still concedes.

Sources worth reading before changing this: the [Milvus comparison of the three
shapes](https://milvus.io/blog/is-mcp-dead-cli-and-skills-for-ai-agents.md), Anthropic's
[skill authoring practices](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview), and
[JProfiler's account of making their MCP server survive weaker
models](https://www.ej-technologies.com/blog/2026/07/making-the-jprofiler-mcp-server-robust-for-weaker-models/),
which is the same problem as ours and is what `agent-eval.md` is about.
