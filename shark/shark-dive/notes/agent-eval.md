# Measuring whether an agent can solve a leak

An eval of the agent surface. `shark-dive-eval` is the heap dumps and the scoring;
`shark-dive-agent/harness/eval/run-eval.sh` is the process handling between them.

```bash
shark/shark-dive/shark-dive-agent/harness/eval/run-eval.sh --models opus,sonnet --repetitions 5
shark/shark-dive/shark-dive-agent/harness/eval/run-eval.sh --scenarios two-apart --repetitions 5
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
key is on the path and **one verdict on the owner of it solves the dump**, the path names **nothing** before
a verdict has been set, and the leak is one `list_leak_groups` finds on its own. Which is not a check that the
answer is right — it is right by construction — but that the dump can be *investigated* to it. A scenario an
agent can't finish, or one that hands over the answer with no work, is a scenario whose score is a fact about
nothing.

## What one run is scored on

| Signal | How it is read |
| --- | --- |
| `RIGHT` | The `OwnerClass.field` the heap dump named at `leakSolved` equals the key |
| `WRONG` | It named another reference — the failure that matters most, since it is a confident wrong answer somebody would have acted on |
| `NOT_SOLVED` | `leakSolved` never went true, whatever the run said in its reply |
| `WANDERED` | Solved a leak in a heap dump this run was not given, so the run measured nothing — see below |
| Calls, refusals | Counted off the session, median over the repetitions |
| Verdicts | How many `set_verdict` calls it took, which is the work the answer is made of |
| Cost | The client's own report, in `<run>/client.json` |

**The scored string is the tool's, not the model's**, and that is the whole of why this measures anything.
`outcomeOfTool` reads the faulty reference off an answer that also says `leakSolved`, so a run reaches
`RIGHT` by recording verdicts that are right about the objects and cannot reach it by writing a reference
anywhere. The version before this had a `conclude` tool that took the reference as an argument, and scoring
compared it against the key — but `conclude` only accepted once the path had narrowed to one candidate and
its refusal *named that candidate*, so the thing being compared was a string the tool had already handed
over. A model that copies well scored the same as a model that investigated well. `REFUSED` was a fifth
outcome counting the runs that tried and were told no, and it went with the tool: there is nothing left to
report a root cause *to*, so what it measured is now `NOT_SOLVED` plus the refusal count beside it.

Rounds and refusals are the interesting secondary numbers rather than pass/fail: a change that keeps the pass
rate and halves the calls is a better surface, and a rise in refusals with the same pass rate says a refusal
message is not telling an agent what to do next. **A surface that turns wrong answers into unsolved ones has
got better even if its pass rate hasn't moved**, which is why those are two columns and not one.

Not scored, deliberately: whether a verdict contradicts the key. It would take resolving the addresses in the
arguments against an open dump, and a verdict that was wrong and then corrected is not a worse run.

## Getting to the heap dump is part of what is measured

**The eval opens nothing.** A run is given the skill, `.claude/skills/shark-dive/SKILL.md` copied into its
working directory, and a prompt that says where the file is and where the launcher is — and then it is on its
own. Which is how anybody meets this app: a dump arrives with a bug report, and the first question is what to
run. Earlier versions of this script started the app on the run's dump and handed the agent a live session, so
every number was about a surface reached halfway through; `open_heap_dump` is the most-called tool here and it
was the one the eval could say nothing about.

**The calls arrive over the command line, which is the only way they arrive anywhere.** The client is given
`Bash` and `Skill` and nothing else, and it runs `"…/Shark Dive" --cli <command> name=value …`. A window per
run, since a call reaches the commands by connecting to one — which is why a run closes its windows before the
next one starts, and why every run of this eval is one an agent's own `open_heap_dump` started.

**There was a `--transport mcp` arm, and both it and the server it scored are gone.** It configured the client
with `["--mcp-stdio", "--no-ui"]` — a process the client launched itself, answering over its stdio pipe with no
window, `--no-ui` being what suppressed the window rather than the run mode of the same name today — and gave
it the MCP tools instead of a shell. The arm went first, before the server did, and for a reason worth keeping: **the command line is how an agent actually arrives** — a shell
and a skill, no client configuration and nothing to restart — so the numbers that decide whether a description
or a refusal got better have to come from that arm, and a second arm scored beside it is a second set of
numbers nobody acts on. The arms were not comparable in the way a table implies either. They differed in the
tools the client has, in whether the surface is in band or discovered, and in whether a window exists — three
variables at once, which is a demonstration and not a measurement.

Which is also the argument that decided the surface: see `notes/agent-surface.md` for what each shape cost,
measured, and why one way in is what is left.

**A shell is a hole, and it is a bounded one.** An agent given `Bash` can `find` the dumps directory, or read
the hprof with `strings`. What stops that mattering is that nothing about a *score* is on the filesystem: the
key is in `shark-dive-eval`, and an answer only counts once the heap dump itself names a reference, which
takes verdicts recorded through the surface. So a shell buys a faster guess at where to look and cannot make
a skipped method look like a followed one. `WANDERED` is what catches it reading the wrong dump.

### The eval's own state has to be somewhere else, and one bug says why

The `cli` arm is several processes per run, so what they agree on has to be an environment variable —
`SHARK_DIVE_DIR`, `sharkDiveDirectory` in `DiveLogging.kt`. Which is also the setting worth having for reasons
that have nothing to do with the eval: a second dive with its own notes is a thing to want.

And the third product bug this eval found is one only the `cli` arm could have found.
`AgentCommandLine.defaultSessionName` keyed a session on the parent process's id, documented as a shell
living as long as the conversation does. **Claude Code runs every command it issues in a `zsh -c` of its
own**, so one nine-call investigation wrote nine session files and drew nine rows of the *Agent logs* screen —
the artefact this eval is scored off, and the screen a person reads over an agent's shoulder. The fix walks up
past a shell that was handed one command, and it needs both halves of that test: a name alone walks past
`claude -c`, which merges two conversations, and a `-c` alone walks past an interactive shell, which merges
somebody's terminal tabs.

## Eight ways a run gets handed its own answer

Every one of these was a run that scored well or failed for the wrong reason, and every one was found by
running the script rather than by reading it. Five are about the shape of a run's directory, one is about what
the client inherits from the script and lives in `run_client`, one is what an agent does when a run leaves it
nothing to investigate, and the last is not in the harness at all — it was in the method this surface hands
every agent. They are the part of this worth knowing before changing anything:

- **The script's own standard input.** The client reads the standard input it was started with and appends it
  to the prompt, and what this script had on standard input was the scenario table — every scenario's name,
  dump path and **answer key**. So every run was handed every key, its own included. Worth reading in full
  below: it is the one that voided the numbers in this file.
- **The heap dump's file name.** An agent is answered with the path of what it is reading, so a dump called
  `cache-never-evicts.hprof` names the answer before it has read a byte. Every run's dump is
  `heap-dump.hprof`, and the scenario's own copy sits in a numbered directory rather than a named one — the
  fourth item below is why the name has to be off the filesystem entirely and not merely off this run's copy.
- **The client's working directory.** Its own environment lists that directory in what the model is told. With
  the three dumps in it, the first run of this script opened all three and solved all three — one session,
  three conclusions, and a score that meant nothing. A run's working directory now holds its configuration and
  the skill, and no heap dump at all.
- **The notes and the verdicts of the run before, and of the eval before that one.** They are kept per heap
  dump, keyed by file name and directory, so five repetitions over one path are one investigation and four
  agents reading the first one's conclusion — which the very first run demonstrated by calling `read_notes`
  third. Each run gets a directory of its own with a **hard link** in it, and every invocation puts its runs
  under a directory named for when it started. That second half was missing for a day, and the item below is
  what it cost. The link was a symlink until the agent got a shell: a symlink has a resolved path and an
  agent with a shell resolves things, so `realpath` lands in the shared `dumps` directory — one identity for
  every repetition of that scenario, and the wrong file to be scored against.
- **Shark Dive's own state directory.** `SHARK_DIVE_DIR`, so the runs it publishes, the sessions, the notes,
  the verdicts and the record of where each dump was are this eval's and not the person's. Without it an agent
  asking what is open is shown whatever dives are up on the machine, and the eval writes its notes into
  theirs. It is an environment variable because every process a run starts has to agree on it, and an `--cli`
  call starts one: `open_heap_dump` with nothing running opens a window by running the app again. See `sharkDiveDirectory` in `DiveLogging.kt`,
  which has the rest of why it is a variable rather than an option.
- **The client's own configuration directory.** `CLAUDE_CONFIG_DIR`, for the same reason one step out: what a
  run has to work with is the surface and not this machine. Measured here, 71 installed skills, one of them
  about investigating memory leaks in an iOS app, and every one of them would have been in the system prompt
  of every run — along with `~/.claude/CLAUDE.md`. **It needs `CLAUDE_SECURESTORAGE_CONFIG_DIR=` beside it**,
  or every run is `Not logged in` rather than isolated; `run_the_agent` in `harness/start-harness.sh` has the
  keychain naming that makes that so, and why it only shows up for a person at a terminal.
- **An agent with nothing left to investigate goes and finds something.** Worth reading in full: it is the one
  that would have been written up as a model failing.
- **The method's own worked example.** The paragraph asking for an answer that links the reference showed what
  that looks like with `Holder.activity` — which is `com.example.Holder`, this
  repository's fixture name everywhere, and was also the answer key of `two-apart`. So the first tool result of
  every run of that scenario contained its key, in the one string on this surface that arrives before the agent
  has asked the heap dump anything. Nobody planted it; the example was written from the same fixture the tests
  are written from, which is exactly how this kind of thing gets in. The example is now `Owner.field`, a shape
  rather than a reference, and `AgentMethod`'s own KDoc says why — on the object rather than on either of the
  two texts, since the rule is about both of them, so that the next concrete example doesn't go back in
  whichever half it would have been written into.

**Only `WANDERED` is a guarantee**, and it has to be, because the agent has a shell: no arrangement of paths
hides a file from a process that can run `find`. What the other seven buy is that nothing *invites* a wrong
dump; what `WANDERED` buys is that taking the invitation can never look like a pass.

**And the last one generalises past the harness.** Six of these are paths and environment variables, which is
to say things a script controls; that one was a sentence in the product, and no arrangement of directories
would ever have caught it. Anything a run reads before it reads the heap dump — the method, a tool description,
a refusal, the skill — is a channel, so a concrete class name written into any of them is worth checking
against `EvalScenarios` before it lands.

**The same question, asked of the scoring rather than of the prompt, is what removed `conclude`.** None of
the eight is what that was: a run still had to record the verdicts that narrowed the path, so it could not
reach `RIGHT` without investigating. What it could not do is get the last step *wrong* — the tool refused
until one candidate was left and its refusal named the candidates, so the reference the run typed into
`conclude` was a string it had been handed, and comparing it to the key measured transcription on top of a
result the surface had already produced. **So ask of every scored string where it came from**, not only of
every string a run reads: an eval that scores an agent's restatement of a tool's answer has an extra column
that moves with nothing. What is scored now is the reference the heap dump derived at `leakSolved`, read off
the answer by `outcomeOfTool` — see *What one run is scored on* above.

### Standard input, and why every number below it is void

The loop over scenarios was `while IFS=$'\t' read -r name dump key about; do … done <<<"$scenario_lines"`, and
the client was started inside it with no redirection of standard input. Claude Code reads its standard input
and appends it to the prompt — and reads a seekable one **from offset 0**, not from wherever the loop's `read`
had got to. So the first user message of every run was the two sentences of `prompt_for` followed by the whole
table: five names, five dump paths, five answer keys, five descriptions.

Every version of this script has done that, from the commit that introduced it (`fb7aa3e18`, 2026-08-25)
onwards, so **every table in this file was produced by runs that were shown the answer**, and so was every
number in the pull request that added the stub scenarios.

Nothing failed. It was found by rendering a run's `client-transcript.jsonl` as a page and reading the first
user message, which is where the table had been in plain text all along — so the argument for keeping that
artefact and actually looking at it is this section.

Two things kept it hidden for a month:

- **The obvious probe understates it.** `cat` in the loop body reads from where `read` left off, so it shows
  the scenarios the loop *hasn't reached yet* and not the one the run is about — which reads like a harness
  leaking its other scenarios, a lesser bug, rather than one handing over the key in play. Only a test with
  the real client shows the whole table, because only the real client seeks to 0. Reproduced with a
  one-word prompt in a loop of the same shape: the run's first user message was `Reply with the single word
  OK.` and then all three lines of a three-line fixture.
- **The failure looked like a filter.** The client *consumed* the input, so the loop ended after the first
  scenario it ran. `./run-eval.sh` with no arguments printed one scenario and then `Scoring.`, which is a
  table missing four rows — indistinguishable from having asked for one scenario.

Fixed three times over, any one of which would have done it: `</dev/null` on the client, the table moved to
file descriptor 3, and the answer key no longer printed by `scenarios` at all. The third is the one worth
having on its own, since an answer that is never written to a stream cannot leak down a path nobody thought
of — scoring reads each key out of `EvalScenarios` and prints it in the line per run, which is where anybody
watching should have been reading it.

**The lesson for the next thing that gets added to this script: a child process inherits more than its
arguments.** Everything else in this section is about paths, because paths are what an agent asks about — and
the leak that actually mattered was a file descriptor nobody had thought of as an input at all. Anything this
script has open when it starts a client is part of the prompt.

### The two runs that wandered

`runs/3/heap-dump.hprof` was the third run of *every* eval, so the second eval's third agent opened a heap dump
the first eval's third agent had already solved — same path, same notes, same verdicts, four of them, with the
faulty reference already named. Its own words for what it did next, in the reason it gave for the call:

> The dump open in the window is already concluded (CacheEntry.activity). Opening the real 8 MB dump for this
> run, which has no verdicts on it yet, to investigate it.

The path it opened, `…/dumps/N/heap-dump.hprof`, was read rather than guessed — that is the standard input bug
again, and it was written up here as a guess for a month — and it landed on another scenario's dump, which it
then investigated properly and concluded correctly about. Scored against the scenario it had been given, that
is a confidently wrong answer. It is nothing of the kind, and the day before,
the same thing had been written down as sonnet getting a leak wrong.

Three things came out of it:

- **A directory per invocation**, one line of the script, which took away the *motive* — an agent handed a
  dump somebody else had already solved. It was read as the whole fix, and it wasn't: the path this run
  opened instead was being printed into its prompt, and that took another month to find.
- **`WANDERED`.** Scoring compares the heap dump each conclusion was recorded against with the one the run was
  given, and a mismatch is its own outcome rather than a wrong answer. Keeping it after the motive was taken
  away is what caught the next two — 2026-09-18, the same `…/dumps/N` path, one of them on its first call —
  which is the evidence the standard input bug was sitting in the whole time, unread. So the rule holds
  generally: an eval whose failures look like model failures is worse than no eval.
- **`AgentHeapDumps.openingHeapDumpPaths`.** Not the cause, but the reason the first of the two had nothing
  better to do: its first call asked what was open 2.6 seconds in, the dump it had been started on was still
  indexing, and the answer said nothing was open without naming the path the run had been pointed at. An agent
  told that has one move left, which is to guess a path. That hole is in the *product* rather than in the eval —
  an agent connecting to a window that is still indexing falls into exactly the same one — and it is the first
  thing this eval found that was worth fixing in the app.

## The `--cli` redesign, 2026-10-01 — the first table that isn't void

The first run of this script since the standard input bug above was fixed, so the first table here whose runs
were not shown the answer. Two arms, and they are exactly a pull request against the commit it branched from:
`6d4a192c4`, which is the merge base, against the six commits on top of it that redesigned the command line
around `--cli`, one run, and a heap dump named by a key. The before build still had the MCP server in it and
neither arm launched one — both reach the surface over `--cli`, which is what keeps this from being the
two-variable comparison the deleted `--transport mcp` arm was. Five scenarios, opus, three repetitions, 15 runs
an arm. Shark Dive 1.0.0, `claude` 2.1.280, $6.11 before and $7.24 after. The two arms ran at the same time on
one machine, so neither one's wall clock — 44 minutes and 51 — is a number about the surface.

**Both arms are a build with `conclude` in it**, which is also why the columns here are `Refused` and `No
conclusion` rather than today's `Not solved`: the tool went on 2026-10-03, with `list_leaks` renamed to
`list_leak_groups` and `chain_from_gc_root` to `path_from_gc_root`. So read the tool names in this section
and the two below as the build's, not as commands to type — the numbers stand, the surface they were taken
on is not the one in the checkout.

Before, `6d4a192c4`:

| Scenario | Model | Right | Wrong | Refused | No conclusion | Wandered | Calls | Refusals |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| two-apart | opus | 3/3 | 0/3 | 0/3 | 0/3 | 0/3 | 12 | 0 |
| cache-never-evicts | opus | 0/3 | 3/3 | 0/3 | 0/3 | 0/3 | 20 | 0 |
| stub-outlives-its-work | opus | 3/3 | 0/3 | 0/3 | 0/3 | 0/3 | 17 | 0 |
| stub-holds-no-state | opus | 3/3 | 0/3 | 0/3 | 0/3 | 0/3 | 24 | 0 |
| real-asynctask | opus | 3/3 | 0/3 | 0/3 | 0/3 | 0/3 | 22 | 0 |

After, `d1802373e`:

| Scenario | Model | Right | Wrong | Refused | No conclusion | Wandered | Calls | Refusals |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| two-apart | opus | 3/3 | 0/3 | 0/3 | 0/3 | 0/3 | 15 | 0 |
| cache-never-evicts | opus | 0/3 | 3/3 | 0/3 | 0/3 | 0/3 | 20 | 0 |
| stub-outlives-its-work | opus | 3/3 | 0/3 | 0/3 | 0/3 | 0/3 | 20 | 0 |
| stub-holds-no-state | opus | 3/3 | 0/3 | 0/3 | 0/3 | 0/3 | 26 | 0 |
| real-asynctask | opus | 3/3 | 0/3 | 0/3 | 0/3 | 0/3 | 19 | 0 |

**12/15 either side, the same four scenarios right and the same one wrong.** Which is the result to want
rather than a disappointment: nothing in the redesign was meant to make an investigation go better, and a
surface rebuilt around one command, one run and a key is a surface an agent could have stopped being able to
reach at all. The calls say the same thing — 285 over the before arm's fifteen runs against 297 over the
after's, a median moving by two or three either way on four of the five — and the twelve are accounted for
below rather than being noise.

**The one thing only the after arm did is the story the refusals are for**: two of its runs read `1 refused ·
2 conclude attempt(s)` — refused, a verdict, then concluded — and no run on main did. Two runs is not
evidence, it is the shape to watch in the next table, since a refusal that says what to do next is the whole
argument for this module.

**And neither arm covers the method text this same round changed.** The paragraph of `AgentMethod.LEAK` saying
`list_leaks` is where an investigation starts rather than somewhere to come back to went in after these runs,
so this table is its baseline and not a measurement of it.

### The twelve extra calls are one `close_heap_dump` a run, less a `show` nobody needed

Per tool, over all fifteen runs of each arm, the deltas sum to exactly the twelve:

| Tool | `6d4a192c4` | `cli-only` | Δ |
| --- | --- | --- | --- |
| `close_heap_dump` | 0 | 15 | **+15** |
| `show` | 12 | 4 | **−8** |
| `describe_object` | 142 | 150 | +8 |
| `find_objects` | 39 | 35 | −4 |
| `conclude` | 15 | 17 | +2 |
| `set_verdict` | 28 | 29 | +1 |
| `path_from_gc_root` | 17 | 16 | −1 |
| `list_devices` | 1 | 0 | −1 |
| `open_heap_dump`, `list_leaks`, `take_note` | 15, 15, 1 | 15, 15, 1 | 0 |

**`close_heap_dump` is one call a run and it is the last call of all fifteen** after-arm sessions: a command
this round adds — `shark/shark-dive/shark-dive-agent/src/main/java/shark/dive/agent/AgentTools.kt:147` — which
`.claude/skills/shark-dive/SKILL.md:151` tells an agent to make for a dump it opened. So the whole rise is a
command that did not exist to be called, called once each time, which is the number going up for the reason it
was supposed to.

**And `show` paying two thirds of it back is the method leaving the answers, measurable.** At `6d4a192c4`,
[`McpSession.withTheSurface`](https://github.com/square/leakcanary/blob/6d4a192c484437cb31299d756156f672834bcd82/shark/shark-dive/shark-dive-agent/src/main/java/shark/dive/agent/McpSession.kt#L302)
prepended `AgentMethod.SURFACE` to the first *answered* call of every session, so every run was handed
"**`show` puts what you are looking at on screen.** Use it when you reach something that matters"
(`shark/shark-dive/shark-dive-agent/src/main/java/shark/dive/agent/AgentMethod.kt:67`) whether it had asked for
it or not. On this branch that text is only printed by
`--investigation-help`. What that cost is not investigation: **nine of the before arm's twelve `show` calls
came after the run's last `conclude`**, and eight of its fifteen sessions ended on one. Mid-investigation
`show` was three calls before and two after. So a closing flourish went away and `close_heap_dump` took its
place at the end of every run, which is the same ritual costing the same call.

Per scenario the arithmetic is only that swap: two-apart 12 → 15 and stub-outlives-its-work 17 → 20 are the one
close with no `show` to give back, stub-holds-no-state 24 → 26 is nearly it, and cache-never-evicts 20 → 20 and
real-asynctask 22 → 19 are flat or down because the before arm spent its closing `show` calls there.

**The cost this table cannot see is the help, and it is the number to watch.** A help text is not a `--cli`
command, so none of it reaches a call count: client round trips went 171 → 238 while help invocations went
31 → 90. `--agent-help` was read 31 times before — 8 of them bare, 23 naming a tool. After: `--help` 60 times
(10 bare, 50 naming a command — `conclude` 15, `set_verdict` 14, `find_objects` 9, `show` 6), plus
`--investigation-help` 15 and `--leak-investigation-help` 15. Two readings of that. **Both method texts were
read by fifteen of fifteen runs, exactly once each**, which is the risk of moving them out of the answers not
materialising. And per-command help is now a round trip per command
(`shark/shark-dive/shark-dive-agent/src/main/java/shark/dive/agent/AgentCommandLine.kt:207`), where the before
build's bare `--agent-help` printed the preamble and every tool's full help in one
([`AgentCommandLine.help`](https://github.com/square/leakcanary/blob/6d4a192c484437cb31299d756156f672834bcd82/shark/shark-dive/shark-dive-agent/src/main/java/shark/dive/agent/AgentCommandLine.kt#L109),
`(asked ?: tools).forEach`). Cheap calls — no window, no heap dump read — but that is where the 39% went, and
a surface that grows another ten commands pays it again.

### `cache-never-evicts` fails the way `two-apart` used to, and the fixture is why

0/3 in both arms, and all six runs of it — across two builds — concluded `Object[][x]` against a key of
`CacheEntry.activity`. That is not six wrong answers. It is one reference above the key, `conclude` accepted
it, and `conclude` accepts only when the path has exactly one candidate left, so the heap dump agreed with the
verdicts that got them there. One run's own `why` for the `STUCK` it set on the entry
(`dive4a7c2e19`, after arm):

> Its only fields are key = "screen:main" and activity = the MainActivity 0x24, which has
> Activity#mDestroyed = true

Which is the reading that got the old `two-apart` fixture replaced: **an object whose only fields are a dead
activity and a label for it reads as done with its work.** A `CacheEntry` for a destroyed screen should have
been evicted, and nothing in the dump says otherwise — `aCacheThatNeverEvicts` writes the entry as a key and
the activity and no evidence of its own, so "the entry belongs here and the field is wrong" and "the entry
should be gone" are both defensible off what is there, and the key picks one of them. Three steps of that
path carry an argument an agent can point at: the static `INSTANCE`, and `MemoryCache.size = 1` matching its
one element, both of which the runs cited. The fourth, the one the answer turns on, carries none.

So this is a scenario to fix rather than a number to act on, and the fix is the one `twoApart` already got:
write something onto the entry that says its own work is not finished. Until then its 0/3 is a fact about the
fixture and no part of a before and after.

### Thirty runs scored `WANDERED`, and it was the eval holding a path two ways

Both arms came back 15/15 `WANDERED` the first time they were scored, every run "concluding about" the very
heap dump it had been given. The wrapper running the two arms built `SHARK_EVAL_DIR` from `${TMPDIR:-/tmp}`,
`$TMPDIR` on macOS ends in a separator, and so every path in `runs.tsv` carried a doubled one in the middle of
it while Shark Dive had recorded the single-separator path it resolved. Scoring compared the two as strings.

Rescored, nothing wandered — and the fix is in the scorer rather than in the wrapper. `EvalResult.sameFileAs`
canonicalises both sides, and `EvalScoreTest` pins one dump spelled two ways as one dump. **The outcome that
exists to say this eval measured nothing is the one that must not be reachable by typing a path two ways**,
which is the rule above — an eval whose failures look like model failures is worse than no eval — turned on
the scorer's own comparison.

Worth knowing what it cost, which was nothing: what each run concluded is in its session file, so rescoring
thirty runs was a re-read rather than a re-run.

## Baseline, 2026-08-25 — void, kept as history

**Every run in this table was shown the answer key**, its own and the other four, appended to its prompt by
the standard input bug above. So a `RIGHT` here says nothing about whether the surface can be investigated to
the answer, which is the only thing this eval exists to measure. Read it as the shape of a table and not as a
number to beat; the first honest baseline is whatever is run after the fix.

**And `two-apart` is not the same dump any more**, so even its call counts are about a fixture that no longer
exists — see the scenario's entry below for what it was and why it had to change.

Shark Dive 1.0.0, `claude` 2.1.223, one repetition each, $3.33 and 13 minutes for the six. One repetition
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

Six for six was read at the time as the scenarios being at their ceiling, and the argument still holds as far
as it goes — **these three scenarios cannot show a change to the method or a refusal making anything better**,
only worse. But an eval that hands over the key is at a ceiling whatever it measures, and that is the better
explanation of a perfect column: six for six was never evidence about these dumps. What the numbers to beat are is the
call counts, and the one row worth pointing at is sonnet on `two-apart` — refused once, set a verdict, then
concluded, in 9 calls against opus's 15. That is the surface working as designed on the weaker model, which is
the model a surface is measured on. Harder scenarios are what the families below are for, and the cost per run
($0.23 to $1.07) is what says how many repetitions of them are affordable.

## `stub-outlives-its-work`, before and after the stub work, 2026-09-18 — void, kept as history

**Void for the same reason as the baseline**: all ten runs had `UploadCallbacks$ResultStub.this$0`, the key
for this very scenario, in their prompts. Both arms of a two-arm comparison were contaminated equally, so the
*difference* between them is not obviously wrong — but neither arm measures an investigation, and 5/5 in the
before arm is what the argument below is built on. It is kept because the section under it is about a real
dump investigated by hand, which the bug never touched.

The first two-arm run of this script: the same scenario, five repetitions, opus, against two builds of the
app — `65f9ac898`, the commit before any of the binder-stub work, and `a133e42ab`, with
`AndroidObjectInspectors.STUB` reporting a stub as not leaking and saying what to do about what it holds.
Each arm needs its own `SHARK_EVAL_DIR`, since the script starts by deleting it.

| Arm | Right | Wrong | Refused | No conclusion | Wandered | Calls (median) | Cost | Seconds |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `65f9ac898`, before | 5/5 | 0/5 | 0/5 | 0/5 | 0/5 | 18 | $4.02 | 173 |
| `a133e42ab`, after | 3/5 | 0/5 | 0/5 | 0/5 | 2/5 | 18 | $4.26 | 194 |

**The scenario cannot show this change working, because opus was already at 5/5 without it**, and that is
the result rather than a caveat on it. The 5/5 is void, so that sentence now rests on the fixture rather than
on the runs — which is where it always had its force, and it was foreseeable from the fixture alone: nine
objects with `delivered = true` written into the one that matters, so the evidence is *on screen* the moment
the path is read. What the change is for is a 327 MB dump of a real
app where the same reasoning has to be found among thousands of objects, and that is where the headroom is.
So a synthetic scenario is the wrong instrument for an inspector that supplies an argument rather than a
fact — it measures whether the argument is *reachable*, and here it always was.

Neither is the 3/5 a regression, and the two `WANDERED` runs are worth reading before anyone treats it as
one — see below. Calls, turns and cost are the same in both arms to within the noise of five runs.

### The real dump is where it showed, and this is what a scenario can't measure

The same 327 MB dump, the same prompt, the same model, with the session data cleared so that nothing of the
earlier run was there to read — twice, a day apart, either side of the change. Both runs named the leak;
what changed is where they cut the path, and the whole path is four objects long:

| | 2026-09-17, before | 2026-09-18, after |
| --- | --- | --- |
| `ResultReceiver$MyResultReceiver` (the stub) | `EXPECTED`, by hand | `EXPECTED`, by the inspector |
| `GetCredentialController$resultReceiver$1` | `EXPECTED` | `EXPECTED` |
| `GetCredentialController` | **`EXPECTED`** | **`STUCK`** |
| `MainActivity` | `STUCK` | `STUCK` |
| named the leak | `GetCredentialController.context` | `…$resultReceiver$1.this$0` |

The before run reasoned that the two `this$0`s above the controller are compiler generated and unclearable,
carried that down to the controller as well, and landed on the last reference of the path — which accounts
for 1.32 MB of the 4.37 MB, since the other 3.06 MB hangs off the controller's `callback`. The after run
argued the controller *on its own evidence*: its continuation is `CompletedExceptionally` with a
`NoCredentialException`, its `cancellationSignal` has `mIsCanceled = false`, so the request it exists for was
delivered and it should be garbage. That is the object-by-object reading the label now asks for, and it moves
the cut one step up, onto a reference that accounts for all of it.

Two things it also got that the run before didn't: the upstream fix is in **1.7.0-alpha01**, not the
alpha03 the earlier run named — verified here by diffing the two published sources jars, `val context` →
`context`, a `WeakReference`, `callback = emptyCallback()`, and both base controllers dropping their own
`private val context` — and it made **no `ways_held` call** at all, where the run before spent one on 4.6 KB
of path it already had. $7.01 and 16 minutes against $6.10 and 13.

So the measurement that answered the question was one run on a real dump, not five on a fixture, and that is
worth remembering the next time a scenario is written to catch something a real dump did: a scenario pins the
*shape* against regression, and it is at ceiling from the day it is written.

#### The after run is still one object short, and the reason is the row the table repeats

Read the `EXPECTED` on `GetCredentialController$resultReceiver$1` again: it is the same in both columns, and
it is wrong in both. That receiver exists to receive one result, the result arrived — which is what
`CompletedExceptionally` on the continuation below it says — so its work is done and it should not still be
reachable. The verdict on it is `STUCK`, and the leak is then
`ResultReceiver$MyResultReceiver.this$0`, one step above what the after run named.

Which matters more than one row of a table, because **the after run reached that `EXPECTED` by the before
run's argument**, on a smaller scale: the receiver is a 24-byte dispatcher whose only field is a `this$0` a
compiler wrote, so there is nothing on it to read and nothing about it anybody can clear, and an
investigation concludes it cannot be the defect and moves down. The before run made that inference twice and
lost 3.06 MB by it; the after run made it once and lost the reference. So the change fixed the deference to
the framework — "the stub is not leaking, therefore what it holds is not either" — and left the harder half
standing: **a reference you cannot clear is still a reference that shouldn't be held**, and whether anybody
can clear it is a question about the fix rather than about the verdict.

Both sentences are now in the method's rules and in `AndroidObjectInspectors.STUB`, and
`stub-holds-no-state` is the scenario that can fail a run for the second one, which
`stub-outlives-its-work` cannot: it writes `delivered = true` onto the very object the verdict goes on, so a
run scores there by reading one field and never has to ask what the object is for. 5/5 on it said nothing
about the reading above.

### The two wanders were not a guess: the path was in the prompt

Both of them opened `…/dumps/N/heap-dump.hprof`, the same `runs` → `dumps` swap as the 2026-08-25 pair, and
one of them, `edcc4aa3`, did it **as its very first tool call** — before reading anything. It then
investigated its own dump correctly, set the `STUCK` on `UploadCallbacks` citing `delivered == true`,
concluded `UploadCallbacks$ResultStub.this$0` — the key — and then repeated the conclusion against the other
path, which is the one scoring read. `215cbfc0` opened `dumps/4` first, never touched its own, and concluded
correctly about the scenario that dump belongs to.

**They read those paths, they didn't derive them.** The scenario table is exactly `<name> <RUN_SET>/dumps/N/
heap-dump.hprof <key> <description>` per line, and the standard input bug put all five lines in every prompt,
so `dumps/N` was in front of both of them — in the first run's case before it had called anything. An agent
opening one of five heap dumps it was handed the paths of is not wandering, it is doing as it was told.

That replaces what was written here, and the probe it was written from is worth keeping as the mistake:
a `claude --print` asked from a run's working directory to list every path in its context named the cwd and
`~/.claude/CLAUDE.md` and nothing else, which was read as the model deriving `dumps/N` from `runs/N`. The
probe was run by hand, from a terminal, so it had a terminal on standard input — **the one thing about a run
that mattered was the thing the probe changed**. A probe of what a process is handed has to be launched the
way that process is launched.

What survives is the product hole underneath, because it is the reason a path was worth reaching for at all: a
window can be part way through opening a dump, **nothing in the session says so**, and `open_heap_dump` wants
a path. An agent with no path and a tool that needs one goes looking for one. Naming the dump that is opening
in what a session starts with is the fix, `AgentHeapDumps.openingHeapDumpPaths`, and it is in the product
rather than in the eval, exactly like the first one.

## The scenario families

Five exist. The rest are what the synthetic side is *for* — shapes a real dump doesn't happen to contain:

- ✅ **Two apart** (`two-apart`) — one object with no verdict between the verdicts, so **two** candidate
  references, which is what an unsolved leak is made of: the application holding a settings store, and the
  store holding a destroyed activity in a field called `context`. The key is the second. What decides it is on
  the middle object and readable off the dump — the store has three writes outstanding, so it is not done with
  its work and belongs in memory, and the field name says what was wanted there was the application context.
  **It used to be a `Holder` whose only field was the activity, and that was not a fair scenario**: nothing in
  the dump distinguished `ExampleApplication.holder` from `Holder.activity`, and the little evidence there was
  pointed the wrong way, since an object whose one field is a dead activity reads as done with its work. Two
  runs that did everything the method asks answered `ExampleApplication.holder` and were scored `WRONG`. A
  scenario whose answer is the author's intention rather than the dump's content measures nothing, whichever
  way the number comes out — and the old fixture's key was also the class name in `AgentMethod`'s worked
  example, which is the eighth channel above.
- ✅ **A long unknown zone** (`cache-never-evicts`) — four steps of infrastructure with no verdict, rooted at
  a static singleton so that "this belongs in memory" is a fact of the dump rather than an assumption.
- ✅ **A verdict that spreads the wrong way** (`stub-outlives-its-work`) — a binder stub at the top of the
  path, which genuinely belongs in memory, above a request whose work is done. **The only scenario the
  method can be failed on by reading it backwards**, and the first whose answer needs a `STUCK` of the
  agent's own rather than the watcher's: the reference is `UploadCallbacks$ResultStub.this$0`, one step below
  the GC root, and an investigation that lets `EXPECTED` spread *downwards* from the stub comes out with
  `UploadController.activity` at the bottom instead. Measured on the dump: nothing set names no reference, an
  `EXPECTED` on the stub alone still names none, and the two readings name those two different strings — so
  the inversion scores `WRONG` rather than passing for the wrong reason. A second, static stub in the same
  dump is the control, so refusing whatever sits under a stub is not a way to score here either. This is the
  shape a real POS dump turned out to have (`ResultReceiver$MyResultReceiver.this$0`), where an opus run
  read it backwards twice and wrote the inversion down as its reason.
- ✅ **Evidence that isn't on the object being judged** (`stub-holds-no-state`) — the same inversion as
  above, with the flag taken away. The object under the stub is a receiver whose only field is a `this$0` a
  compiler wrote, and what says its work is finished is two steps down: the controller it forwards into is
  already holding the results it was waiting for. So a run has to read past the object it is judging, which
  is what "is this object's work done?" asks for and what reading `delivered` off the object never
  exercised. **The wrong answer is one step down** — `STUCK` on the controller with the receiver left
  `EXPECTED` names `SearchResultReceiver.this$0` — and it is the answer the real POS dump got from both arms
  above, by the argument that a 24-byte object with nothing to clear cannot be the defect. The control is a
  second stub over a stateless receiver whose request hasn't come back, so "the thing under a stub with no
  state of its own is stuck" is not a rule that scores here.
- ✅ **A real dump** (`real-asynctask`) — 8 MB, real framework classes, and a path nobody wrote for this eval.
- **A decoy** — an object that reads like a leak above the real one, where the key is the reference below.
- **Two candidates** — two references that both cross into stuck, so the answer depends on a verdict the agent
  has to defend rather than on the shape of the path.
- **A loop** — objects holding each other, where the path's order is arbitrary and the conflict machinery
  reports nothing (see `LeakStatusOverrides.isAbove`).
- **A library leak** — the fault is in the framework, and the right answer says so rather than naming app
  code.
- **Source to read** — the method sends an agent to the code at the version the dump is of, and no scenario
  here has any code to read. Measuring that means shipping a source tree with the dump and letting the client
  keep its file tools, which the runs above turn off on purpose so that the surface is the only variable.

## What the runs leave behind

Every run is a directory under `$TMPDIR/shark-dive-eval/<when it started>/runs`: the heap dump as that run
saw it, what the client reported, and which scenario it was. **Three artefacts, and they are three different
things** — a run that went wrong usually needs two of them:

- `client.json` — the one object `--output-format json` prints. Cost, turns, token usage including thinking
  tokens, stop reason, and the final answer. Not a transcript, which is what makes it short.
- `client-transcript.jsonl` — the client's own session file, copied in by `copy_client_transcript`. The
  model's turns, and the thinking between two tool calls, which is the only place a run says *why* it did
  what it did. Absent for a run that timed out or died before printing a session id. Readable as a page with
  [`claude-code-log`](https://github.com/daaain/claude-code-log) — `claude-code-log convert <the file> -o
  run.html` — which is worth it for a run somebody is being shown rather than one being grepped.
- The Shark Dive session — every call with its `reason`, arguments, answer and duration, including the
  refused ones. Present for every run that connected at all, which is why it is the one to read when the
  other two are missing.

The sessions go where every session of that invocation goes, which is its own `shark-dive` directory beside
the runs, so **a run is readable in a window afterwards** — with that directory named, since it is the state
Shark Dive was writing at the time:

```bash
SHARK_DIVE_DIR="$TMPDIR/shark-dive-eval/<when it started>/shark-dive" \
  open -a /Applications/"Shark Dive.app" --args --debug-title-prefix="Eval run 3" \
  "$TMPDIR/shark-dive-eval/<when it started>/runs/3/heap-dump.hprof"
```

The *Agent logs* screen then has the whole investigation, call by call, with the verdicts and the note the
agent left on its tabs. That is the artefact to look at when a scenario fails: a score says which runs to
read, and the log says why. It is also what makes an eval leave nothing in `~/.shark-dive` to clear up —
the notes, the `leak-statuses` files and the record of where each dump was are all in there with it.

Until the next eval, which deletes the ones before it: an 8 MB dump per run adds up, and the run that has to be
read is the one that just failed. So read a failure before rerunning.

## Planned: a question that isn't a leak

Everything above scores one question — *which reference is at fault* — and the surface answers others. "What
is using all this memory" is the one people ask most after that, it has a checkable answer, and it exercises a
different half of the tools: `dominator_tree` and `find_objects` rather than the path and the verdicts. What
follows is the design, not something that runs yet.

**The prompt is the whole of the input, as it is for the leak runs**: *"A heap dump is open in Shark Dive.
What is using most of the memory in this app?"* — no mention of a tool.

**The answer key is an object, not a string.** A leak's key is `OwnerClass.field` because that is what a
solved path names; here the scenario builder knows which object it made the biggest, so the key is that
object's identity, and the score resolves what the agent named back to a class in the dump. Which means the
same score works on a real dump with no key written by hand at all: `HeapDominatorTree` says what the biggest
retainer is, and the eval can ask it.

**What is scored, all of it off the session file and the dump, with no model:**

| Signal | How it is read |
| --- | --- |
| `RIGHT` | The agent `show`ed or wrote a note naming the key object, or its class |
| `WRONG` | Named another object as the answer — the confident wrong answer again |
| `TRIVIAL` | Named the class loader's class array, or anything else above the app's own objects: true, useless, and the failure this dump is shaped to provoke |
| `NO_ANSWER` | Never named an object at all |
| Calls to get there | The number worth halving, since this question is a fan-out |

**`take_note` and `show` are what an answer is written in**, because they already are: the window's own way of
saying "this is the thing" is a note on the object and a tab on the screen. So this needs no new tool, and
that is the point — a surface that needs a `report_the_answer` tool per question is a surface that has stopped
being the window.

**The `TRIVIAL` row is the finding that prompted this.** Measured on a 146 MB dump of a real app taken through
`dump_heap`: the top of the dominator tree is `6 × PathClassLoader` at 42 MB, 38% of a 111 MB heap, and under
it one `Object[]` of 79,655 loaded classes. The biggest ten children of that array come to 1 MB — 2% of it —
so an agent that walks down the biggest branch and reports what it finds has reported "the classes", which is
both true and no use to anybody. The interesting answer was two rows further down `find_objects`: a Coil image
cache holding a 4 MB bitmap. A scenario shaped like that is what says whether a description or a refusal can
get an agent past it.

**Two scenarios to start with**, matching how the leak families are split:

- **A synthetic one** where a named static cache retains a known share of the heap under a class loader made
  deliberately fat, so `TRIVIAL` and `RIGHT` are both reachable and the key is exact.
- **A real one**, a dump taken off a device with `dump_heap`, scored against what `HeapDominatorTree` says.
  Which also makes it the first eval scenario whose dump nobody wrote.

## What to do with a result

A scenario that fails the same way across models is a bug in this surface, not in the model, and the fix is
one of the four things that JProfiler's numbers moved: a more prescriptive description, a refusal that says
what to do next, a tool that cannot be called out of order, or a piece of the method that has to be in the
tool's own description because the method was skipped.

**Not in CI.** It costs money and needs the network. Run it before and after a change to the method or a
refusal, and commit the table with the date and the versions, so the next change has a baseline to beat.
