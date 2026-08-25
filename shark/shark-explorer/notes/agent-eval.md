# Measuring whether an agent can solve a leak

An eval of the agent surface. `shark-explorer-eval` is the heap dumps and the scoring;
`shark-explorer-agent/harness/eval/run-eval.sh` is the process handling between them.

```bash
shark/shark-explorer/shark-explorer-agent/harness/eval/run-eval.sh --models opus,sonnet --repetitions 5
```

## What it is for

Every change to a tool description, a refusal or the method is a change to a prompt, and a prompt change is
not something anyone can review by reading it. [JProfiler measured
theirs](https://www.ej-technologies.com/blog/2026/07/making-the-jprofiler-mcp-server-robust-for-weaker-models/)
and found one model going from 38/55 to 55/55 scenarios and from $13.21 to $3.13 a run on the same tools with
better descriptions and harder refusals — a change nobody would have predicted from the diff. That is the
reason to have numbers rather than an opinion, and their headline finding is the one to design for: **the
weak models are where a surface is measured**, since a strong one papers over a bad description.

## The rule: no model scores this

An LLM judging an answer is a second unverified opinion. Every number is a string comparison or a count over
the session file the server wrote while the agent worked — `EvalResult`, and nothing in it is a judgement.

**The answer key is the faulty reference**, `OwnerClass.field`, per heap dump, and it is known before the tools
are asked anything:

- **Synthetic dumps built with the `dump { }` DSL**, where the fixture *writes* the leak, so the key is true
  by construction.
- **This repository's real Android dumps**, whose key is what LeakCanary's own analysis names.
  `leak_asynctask_o.hprof` is `MainActivity$2.this$0`, and `LegacyHprofTest` already pins the same dump's
  leaking object and its 211,038 retained bytes, so a key that drifts from the library's reading fails a test.

`EvalScenariosTest` is what keeps a scenario honest, and it checks three things about every one of them: the
key is on the chain and **one verdict on the owner of it solves the dump**, the chain names **nothing** before
a verdict has been set, and the leak is one `list_leaks` finds on its own. Which is not a check that the
answer is right — it is right by construction — but that the dump can be *investigated* to it. A scenario an
agent can't finish, or one that hands over the answer with no work, is a scenario whose score is a fact about
nothing.

## What one run is scored on

| Signal | How it is read |
| --- | --- |
| `RIGHT` | The concluded `OwnerClass.field` equals the key |
| `WRONG` | Concluded another reference — the failure that matters most, since it is a confident wrong answer somebody would have acted on |
| `REFUSED` | Tried to conclude and was refused every time, so it never claimed a root cause |
| `NOT_CONCLUDED` | Never tried, which is a surface an agent answered around rather than through |
| `WANDERED` | Concluded about a heap dump this run was not given, so the run measured nothing — see below |
| Calls, refusals | Counted off the session, median over the repetitions |
| Conclude attempts | More than one is the refused-then-verdict-then-concluded story, working |
| Cost | The client's own report, in `<run>/client.json` |

Rounds and refusals are the interesting secondary numbers rather than pass/fail: a change that keeps the pass
rate and halves the calls is a better surface, and a rise in refusals with the same pass rate says a refusal
message is not telling an agent what to do next. **A surface that turns wrong answers into refusals has got
better even if its pass rate hasn't moved**, which is why those are two columns and not one.

Not scored, deliberately: whether a verdict contradicts the key. It would take resolving the addresses in the
arguments against an open dump, and a verdict that was wrong and then corrected is not a worse run.

## Four ways a run gets handed its own answer

All four of these were runs that scored well or failed for the wrong reason, and every one was found by running
the script rather than by reading it. They live in `set_up_run`, and they are the part of this worth knowing
before changing anything:

- **The heap dump's file name.** An agent is answered with the path of what it is reading, so a dump called
  `cache-never-evicts.hprof` names the answer before it has read a byte. Every run's dump is
  `heap-dump.hprof`, and the scenario's own copy sits in a numbered directory rather than a named one — the
  fourth item below is why the name has to be off the filesystem entirely and not merely off this run's copy.
- **The client's working directory.** Its own environment lists that directory in what the model is told. With
  the three dumps in it, the first run of this script opened all three and solved all three — one session,
  three conclusions, and a score that meant nothing. A run's working directory now holds one file: its MCP
  config.
- **The notes and the verdicts of the run before, and of the eval before that one.** They are kept per heap
  dump, keyed by file name and directory, so five repetitions over one path are one investigation and four
  agents reading the first one's conclusion — which the very first run demonstrated by calling `read_notes`
  third. Each run gets a directory of its own with a symlink in it, since the key doesn't resolve symlinks, and
  every invocation puts its runs under a directory named for when it started. That second half was missing for
  a day, and the next item is what it cost.
- **An agent with nothing left to investigate goes and finds something.** Worth reading in full: it is the one
  that would have been written up as a model failing.

### The two runs that wandered

`runs/3/heap-dump.hprof` was the third run of *every* eval, so the second eval's third agent opened a heap dump
the first eval's third agent had already solved — same path, same notes, same verdicts, four of them, with the
faulty reference already named. Its own words for what it did next, in the reason it gave for the call:

> The dump open in the window is already concluded (CacheEntry.activity). Opening the real 8 MB dump for this
> run, which has no verdicts on it yet, to investigate it.

The path it opened was a guess — `runs/3/heap-dump.hprof` with `runs` swapped for `dumps` — and it landed on
another scenario's dump, which it then investigated properly and concluded correctly about. Scored against the
scenario it had been given, that is a confidently wrong answer. It is nothing of the kind, and the day before,
the same thing had been written down as sonnet getting a leak wrong.

Three things came out of it:

- **A directory per invocation**, which is the actual fix and is one line of the script.
- **`WANDERED`.** Scoring compares the heap dump each conclusion was recorded against with the one the run was
  given, and a mismatch is its own outcome rather than a wrong answer. It is not being removed now that the
  cause is gone: an eval whose failures look like model failures is worse than no eval.
- **`AgentHeapDumps.openingHeapDumpPaths`.** Not the cause, but the reason the first of the two had nothing
  better to do: its first call asked what was open 2.6 seconds in, the dump it had been started on was still
  indexing, and the answer said nothing was open without naming the path the run had been pointed at. An agent
  told that has one move left, which is to guess a path. That hole is in the *product* rather than in the eval —
  an agent connecting to a window that is still indexing falls into exactly the same one — and it is the first
  thing this eval found that was worth fixing in the app.

## Baseline, 2026-08-25

Shark Explorer 1.0.0, `claude` 2.1.223, one repetition each, $3.33 and 13 minutes for the six. One repetition
is a smoke test and not a measurement — five is what a result worth arguing from takes — but it is the number
this table is honest about.

| Scenario | Model | Right | Wrong | Refused | No conclusion | Wandered | Calls | Refusals |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| two-apart | opus | 1/1 | 0/1 | 0/1 | 0/1 | 0/1 | 15 | 0 |
| two-apart | sonnet | 1/1 | 0/1 | 0/1 | 0/1 | 0/1 | 9 | 1 |
| cache-never-evicts | opus | 1/1 | 0/1 | 0/1 | 0/1 | 0/1 | 19 | 0 |
| cache-never-evicts | sonnet | 1/1 | 0/1 | 0/1 | 0/1 | 0/1 | 13 | 0 |
| real-asynctask | opus | 1/1 | 0/1 | 0/1 | 0/1 | 0/1 | 28 | 0 |
| real-asynctask | sonnet | 1/1 | 0/1 | 0/1 | 0/1 | 0/1 | 16 | 0 |

Six for six, which is a ceiling and therefore not much of a baseline: **these three scenarios cannot show a
change to the method or a refusal making anything better**, only worse. What the numbers to beat are is the
call counts, and the one row worth pointing at is sonnet on `two-apart` — refused once, set a verdict, then
concluded, in 9 calls against opus's 15. That is the surface working as designed on the weaker model, which is
the model a surface is measured on. Harder scenarios are what the families below are for, and the cost per run
($0.23 to $1.07) is what says how many repetitions of them are affordable.

## The scenario families

Three exist. The rest are what the synthetic side is *for* — shapes a real dump doesn't happen to contain:

- ✅ **Two apart** (`two-apart`) — one unexplained step between the verdicts, which is `conclude`'s refusal
  made real.
- ✅ **A long unknown zone** (`cache-never-evicts`) — four steps of infrastructure with no verdict, rooted at
  a static singleton so that "this belongs in memory" is a fact of the dump rather than an assumption.
- ✅ **A real dump** (`real-asynctask`) — 8 MB, real framework classes, and a chain nobody wrote for this eval.
- **A decoy** — an object that reads like a leak above the real one, where the key is the reference below.
- **Two candidates** — two references that both cross into stuck, so the answer depends on a verdict the agent
  has to defend rather than on the shape of the chain.
- **A loop** — objects holding each other, where the chain's order is arbitrary and the conflict machinery
  reports nothing (see `LeakStatusOverrides.isAbove`).
- **A library leak** — the fault is in the framework, and the right answer says so rather than naming app
  code.
- **Source to read** — the method sends an agent to the code at the version the dump is of, and no scenario
  here has any code to read. Measuring that means shipping a source tree with the dump and letting the client
  keep its file tools, which the runs above turn off on purpose so that the surface is the only variable.

## What the runs leave behind

Every run is a directory under `$TMPDIR/shark-explorer-eval/<when it started>/runs`: the heap dump as that run
saw it, what the client reported, and which scenario it was. The session goes where every other session goes,
so **a run is readable in a window afterwards** — open that run's `heap-dump.hprof` and the *Agent logs* screen
has the whole investigation, call by call, with the verdicts and the note the agent wrote on the tabs it left.
That is the artefact to look at when a scenario fails: a score says which runs to read, and the log says why.

Until the next eval, which deletes the ones before it: an 8 MB dump per run adds up, and the run that has to be
read is the one that just failed. So read a failure before rerunning.

An eval also leaves one `~/.shark-explorer/notes` directory and one `leak-statuses` file per run, which is what
makes the above work. They can go once the runs have been read, and nothing depends on them going: the paths
they are keyed to belong to an eval that has already been deleted.

## What to do with a result

A scenario that fails the same way across models is a bug in this surface, not in the model, and the fix is
one of the four things that JProfiler's numbers moved: a more prescriptive description, a refusal that says
what to do next, a tool that cannot be called out of order, or a piece of the method that has to be in the
tool's own description because the method was skipped.

**Not in CI.** It costs money and needs the network. Run it before and after a change to the method or a
refusal, and commit the table with the date and the versions, so the next change has a baseline to beat.
