# Measuring whether an agent can solve a leak

The plan for an eval of the agent surface. Not built yet; this is what to build and why it is shaped this way.

## What it is for

Every change to a tool description, a refusal or the method is a change to a prompt, and a prompt change is
not something anyone can review by reading it. [JProfiler measured
theirs](https://www.ej-technologies.com/blog/2026/07/making-the-jprofiler-mcp-server-robust-for-weaker-models/)
and found one model going from 38/55 to 55/55 scenarios and from $13.21 to $3.13 a run on the same tools with
better descriptions and harder refusals — a change nobody would have predicted from the diff. That is the
reason to have numbers rather than an opinion, and their headline finding is the one to design for: **the
weak models are where a surface is measured**, since a strong one papers over a bad description.

## The rule: no model scores this

An LLM judging an answer is a second unverified opinion. Everything below is decided by string comparison or
by counting, off artefacts the app already writes.

**The answer key is the faulty reference**, `OwnerClass.field`, per heap dump. Two sources for it, both
independent of what the tools would answer:

- **Synthetic dumps built with the `dump { }` DSL**, where the fixture *writes* the leak, so the key is
  known by construction. `AgentHeapDumps.applicationHoldsActivityThroughHolder` is the first one:
  `Holder.activity`, by construction. This is where the interesting variants live — see the families below.
- **The repository's real Android dumps**, whose key is written down once by hand and checked against
  LeakCanary's own leak trace for the same dump. `leak_asynctask_o.hprof` is `MainActivity$2.this$0`, and
  `LegacyHprofTest` already pins the same dump's leaking object and its 211,038 retained bytes, so a key that
  drifts from the library's reading is a key that fails a test.

## What one run is scored on

| Signal | How it is read |
| --- | --- |
| Concluded at all | A `conclude` that was not refused |
| **Right reference** | Exact match of the concluded `OwnerClass.field` against the key |
| Wrong reference | Concluded, but on another step of the chain — the failure that matters most, since it is a confident wrong answer |
| Stopped short | Text answer produced with no `conclude` — the failure mode of [the shark-cli draft](https://github.com/square/leakcanary/pull/2796) |
| Verdicts against the key | An `EXPECTED` on the object the key says is stuck, or the reverse |
| Rounds | Tool calls, and refusals among them |
| Cost | Wall clock, and the client's own token and dollar report where it has one |

Rounds and refusals are the interesting secondary numbers rather than pass/fail: a change that keeps the pass
rate and halves the calls is a better surface, and a rise in refusals with the same pass rate says a refusal
message is not telling an agent what to do next.

## Where the numbers come from

**A machine-readable session record, one file per agent session**, written beside the human log: the client
that connected, and per call the tool, its arguments, the reason, whether it was refused, and how long the
read took. The eval reads that rather than scraping prose, and the same file is what the window's *Agent
logs* screen draws. One artefact, two readers — build it once.

**That part exists**: `AgentSessionFile` writes `~/.shark-explorer/agents/sessions/*.jsonl` and reads it back,
so a scorer is a walk over `AgentSessionFile.sessionsIn(…)`. Every signal in the table above is on it except
the two the client reports — an answer written with no `conclude` at all, and the cost — which come from the
adapter's own output. Which session belongs to which scenario run is the file the connection was given:
`AgentServer` logs it as the connection opens, and one run of the eval is one connection.

## The scenario families

Start with two dumps to get the harness working, then grow the synthetic side, because the whole point is
cases a real dump doesn't happen to contain:

- **Two apart** — one unexplained step between the verdicts, which is `conclude`'s refusal made real.
- **A long unknown zone** — five or six steps with nothing known, so the agent has to work inwards.
- **A decoy** — an object that reads like a leak (destroyed activity in a cache that is meant to hold it)
  above the real one, where the key is the reference below.
- **Two candidates** — two references that both cross into stuck, so the answer depends on a verdict the
  agent has to defend rather than on the shape of the chain.
- **A loop** — objects holding each other, where the chain's order is arbitrary and the conflict machinery
  reports nothing (see `LeakStatusOverrides.isAbove`).
- **A library leak** — the fault is in the framework, and the right answer says so rather than naming app
  code.

## The runner

```
harness/eval/run-eval.sh --scenarios all --model <name> --repetitions 5
```

Per scenario × model × repetition: open the dump (a window, or headless once that exists), run the client
non-interactively with the same one-line prompt the harness uses today, then score from the session record.
Five repetitions because a model is not deterministic, reported as `x/5` rather than averaged.

**One adapter per client**, each a few lines: `claude -p --output-format json` reports turns and usage,
`codex exec` and `opencode run` have their own. The prompt stays identical across clients — what is being
measured is the surface, and a prompt tuned per client measures the prompt.

**Not in CI.** It costs money and needs the network. Run it before and after a change to the method or a
refusal, and commit the table to this file with the date and the versions, so the next change has a baseline
to beat.

## What to do with a result

A scenario that fails the same way across models is a bug in this surface, not in the model, and the fix is
one of the four things that JProfiler's numbers moved: a more prescriptive description, a refusal that says
what to do next, a tool that cannot be called out of order, or a piece of the method that has to be in the
tool's own description because the method was skipped.
