#!/bin/bash
#
# Measures whether an agent can solve a leak through Shark Dive's agent surface, and scores it by string
# comparison and counting. No model marks anything: see shark/shark-dive/notes/agent-eval.md.
#
# One run is one scenario, one model, one repetition, and one session file. The heap dumps and the scoring
# come from `shark-dive-eval`; everything here is process handling — launching a client per run, finding
# the session it produced, and writing down which run that session belongs to.
#
#   ./run-eval.sh                                        every scenario, the default model, once each
#   ./run-eval.sh --scenarios two-apart --repetitions 5   one scenario, five times
#   ./run-eval.sh --models opus,sonnet                    two models over the same dumps, in one table
#
# **A run reaches Shark Dive over the command line**, which is what somebody who installed the app has: a
# shell, the launcher, and the skill this repository ships to find it by. There was a second arm that
# configured the client with the MCP server instead, and it is gone — see `notes/agent-eval.md` for what
# comparing the two measured and why keeping the arm was not worth what it cost to keep it honest.
#
# Costs money and needs the network, so it is not in CI. Run it before and after a change to the method or a
# refusal and commit the table it prints, or the change is a prompt change nobody reviewed.
#
# **Nothing here opens the heap dump.** The agent is told where the file is and how to reach Shark Dive, and
# opening it is the first thing it has to get right — which is the first thing a person has to get right too,
# and the part `.claude/skills/shark-dive/SKILL.md` exists to carry. An eval that handed over a dump already
# open measured every step of an investigation except the one that starts it. See `prompt_for`.
#
# **Seven things here are about keeping a run from being told the answer**, and all of them were found by
# running it rather than by thinking about it: six about the shape of a run's directory, in `set_up_run`, and
# one about what the client inherits from this script, in `run_client`. A run that leaks its own answer scores
# well and measures nothing, which is the one failure of an eval that doesn't announce itself. An eighth is in
# the product rather than in here — a worked example in `AgentMethod` that named a real reference — so the
# notes count eight and this script can only close seven of them.

set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../.." && pwd)"
readonly APP_PATH="shark/shark-dive/shark-dive-app/build/compose/binaries/main/app/Shark Dive.app"
readonly SKILL_PATH=".claude/skills/shark-dive"
readonly TEMPORARY_DIRECTORY="${TMPDIR:-/tmp}"
readonly EVAL_DIRECTORY="${SHARK_EVAL_DIR:-${TEMPORARY_DIRECTORY%/}/shark-dive-eval}"
# Every invocation gets a directory of its own, named for when it started, because notes and verdicts are kept
# per heap dump path: `runs/3/heap-dump.hprof` reused a week later is the same heap dump as far as they are
# concerned, and the second agent to be given it opens a dump somebody else already solved. Which is not a
# hypothetical — see `set_up_run`.
readonly RUN_SET="$EVAL_DIRECTORY/$(date +%Y-%m-%d_%H-%M-%S)"
# Where Shark Dive keeps what it writes, for this eval only: the runs it publishes, the sessions, the notes,
# the verdicts, the record of where each heap dump was. Every process an agent starts inherits it, which is
# the whole reason it is an environment variable — see `sharkDiveDirectory` in `DiveLogging.kt`.
readonly SHARK_DIVE_DIR="$RUN_SET/shark-dive"
export SHARK_DIVE_DIR
readonly SESSIONS_DIRECTORY="$SHARK_DIVE_DIR/agents/sessions"
readonly PUBLISHED_RUNS_DIRECTORY="$SHARK_DIVE_DIR/agents"
# And where the client keeps its own configuration, for the same reason: without this the agent is handed
# every skill and every CLAUDE.md of whoever ran the eval. Measured: 71 skills on this machine, one of them
# about investigating memory leaks. See `run_client`.
readonly CLIENT_CONFIG_DIRECTORY="$RUN_SET/claude"
readonly EVAL_MODULE=":shark:shark-dive:shark-dive-eval"
# Long enough for a real dump to be indexed and an investigation to run, short enough that a client which
# hung on a refusal doesn't hold the whole eval. A run that hits it is scored for what it did before it.
readonly RUN_TIMEOUT_SECONDS="${SHARK_EVAL_TIMEOUT:-900}"
readonly CLOSING_WAIT_SECONDS=15

main() {
  local scenarios="all" models="opus" repetitions=1
  while (($#)); do
    case "$1" in
      --scenarios) scenarios="$2"; shift 2 ;;
      --models | --model) models="$2"; shift 2 ;;
      --repetitions) repetitions="$2"; shift 2 ;;
      --help | -h) usage; exit 0 ;;
      *) echo "Unknown option $1" >&2; usage >&2; exit 1 ;;
    esac
  done

  require_client

  # The sets before this one, because each is a directory of heap dumps and one of them is 8 MB. Which means a
  # run is readable in a window until the next eval starts and not after it, so read a failure before rerunning.
  rm -rf "$EVAL_DIRECTORY"
  mkdir -p "$RUN_SET/dumps" "$RUN_SET/runs" "$SESSIONS_DIRECTORY" "$CLIENT_CONFIG_DIRECTORY"
  local app
  app="$(bundle_for_the_eval)"

  echo "Writing the scenario heap dumps into $RUN_SET/dumps."
  local scenario_lines
  scenario_lines="$(eval_module scenarios "$RUN_SET/dumps" "$REPO_ROOT")"

  echo "One process per call, against a window each run opens for itself, with the skill to find it by."

  local runs="$RUN_SET/runs.tsv"
  : >"$runs"
  local run_number=0
  local name dump about
  # On descriptor 3 rather than standard input, because **a child inherits standard input** and what this
  # streams is about the answer. The client read it, and the answer key used to be on it: see `run_client`, and
  # `writeScenarios` for why the key is no longer printed at all. A descriptor a child knows nothing about
  # cannot be read by one, and cannot be drained by one either — which is the other half of what happened,
  # since a loop whose input has been swallowed ends after the run that swallowed it.
  while IFS=$'\t' read -r -u 3 name dump about; do
    if [[ "$scenarios" != "all" && ",$scenarios," != *",$name,"* ]]; then
      # Said rather than skipped silently: a table of one scenario looks exactly like a table of all of them
      # that only one of them passed.
      echo "Skipping $name."
      continue
    fi
    echo
    echo "$name — $about"
    local model repetition
    for model in ${models//,/ }; do
      for ((repetition = 1; repetition <= repetitions; repetition++)); do
        run_number=$((run_number + 1))
        run_once "$app" "$name" "$dump" "$model" "$repetition" "$run_number" "$runs"
      done
    done
  done 3<<<"$scenario_lines"

  echo
  echo "Scoring."
  echo
  eval_module score "$runs" "$REPO_ROOT" "$SESSIONS_DIRECTORY"
  cat <<END

Every run has a directory of its own under $RUN_SET/runs: what it was, what it was asked, what the client
reported, the client's own transcript, and the heap dump as that run saw it. What the agent did call by call
is on the *Agent logs* screen of a window opened on that dump, with the notes and the verdicts it left:

  SHARK_DIVE_DIR="$SHARK_DIVE_DIR" \\
    "$app/Contents/MacOS/Shark Dive" --title="Eval run 1" $RUN_SET/runs/1/heap-dump.hprof

That variable is not optional: everything these runs wrote is under it rather than in ~/.shark-dive, so a
window started without it opens the same dump with none of the investigation on it.
END
}

# One agent, one scenario, one repetition. Appends a line to the runs file naming the session it produced.
run_once() {
  local app="$1" scenario="$2" dump="$3" model="$4" repetition="$5" run_number="$6" runs="$7"
  local directory
  directory="$(set_up_run "$scenario" "$dump" "$model" "$repetition" "$run_number")"
  install_the_skill "$directory"
  prompt_for "$app" "$directory" >"$directory/prompt.txt"

  # Nothing of the run before this one, because a run left up is the one the next scenario's `open_heap_dump`
  # finds and joins: the new dump opens as a second tab of the previous agent's window, and `list_heap_dumps`
  # answers with both. Which is this eval offering the previous scenario to the next agent.
  close_the_runs

  # Which session files existed before, because the server names its own session file and the new one is the
  # difference. The alternative is parsing timestamps out of file names, which two runs a second apart would
  # get wrong.
  local before
  before="$(session_files)"

  echo "  run $run_number: $scenario/$model/$repetition"
  local started ended
  started="$(date +%s)"
  # Deliberately not `set -e`'s business: a client that exits non-zero — a timeout, a refusal it gave up on,
  # a crash — is a run to score for what it did rather than an eval to abandon. The session file is written
  # per call, so whatever it managed is on disk.
  if ! run_client "$directory" "$model"; then
    echo "    the client exited non-zero, which the session still says what happened up to"
  fi
  ended="$(date +%s)"

  local session
  session="$(comm -13 <(echo "$before") <(session_files) | head -1)"
  if [[ -z "$session" ]]; then
    echo "    no session was written: nothing ever reached Shark Dive. See $directory/client.stderr"
    return 0
  fi
  # The dump this run was set up around, because scoring checks that the conclusion was about it: a run that
  # ended up in another heap dump measured nothing, and is not a wrong answer. See EvalOutcome.WANDERED.
  printf '%s\t%s\t%s\t%s\n' "$scenario" "$model" "$session" "$directory/heap-dump.hprof" >>"$runs"
  echo "    $((ended - started))s, session $session"
}

# One run's own directory, and it prints where it is.
#
# Six things about the shape of it are what keep a run from being handed its own answer. Each was a run that
# scored well and measured nothing, or — for the last two — a way in that is open because the agent has a
# shell:
#
# **The heap dump is called `heap-dump.hprof`, whatever the scenario is**, and the scenario's own dump sits in a
# numbered directory rather than a named one. An agent is answered with the path of the dump it is reading, so a
# file called `cache-never-evicts.hprof` tells it where to look before it has read anything — and one run
# reached a *sibling* scenario's dump by path, so the name has to be off the filesystem and not merely off this
# run's copy of it.
#
# **A run's dump is in a directory of its own, and every invocation's runs are under [RUN_SET].** Notes and
# verdicts are kept per heap dump, keyed by the file name and the directory it is in — so five runs over one
# path would be one run and four agents reading the conclusion of the first, and `runs/3` reused by the next
# eval a week later is that same agent again. A **hard link** rather than a copy, so the 8 MB is not written
# five times, and rather than a symlink because a symlink has a resolved path and an agent with a shell
# resolves things: `realpath` on one of those lands in the shared `dumps` directory, which is one identity for
# every run of that scenario and the wrong file to be scored against.
#
# **The client's working directory holds nothing but its configuration and the skill.** Its own environment
# lists that directory in what it is told, so the other scenarios' dumps being in it is the eval naming every
# answer at once. Which is exactly what the first run of this script did: it opened all three and solved all
# three.
#
# **Shark Dive's own state directory is this eval's, not the person's.** [SHARK_DIVE_DIR], inherited by every
# process a run starts. Without it an agent asking what is open is shown whatever dives are up on the machine,
# `open_heap_dump` on a file somebody already investigated hands back their verdicts, and the eval writes its
# notes into their notes.
#
# **And the client's configuration directory is this eval's too**, so what a run has to work with is the
# surface and not this machine: 71 skills are installed here, one of them about investigating memory leaks in
# an iOS app, and every one of them would have been in the system prompt of every run.
#
# **A run that wandered anyway is scored as having wandered**, not as having answered wrongly. Two did, before
# [RUN_SET] existed: given a dump the previous eval had already solved — same path, so the same notes and
# verdicts — an agent that has nothing left to investigate goes looking for a dump that does, and both of them
# guessed a path in this eval's own directory and investigated that instead. **This is the only one of the six
# that is a guarantee**, and it has to be, because the agent has a shell: no arrangement of paths hides a file
# from a process that can run `find`. What the others buy is that nothing *invites* a wrong dump; what this
# buys is that taking the invitation can never look like a pass.
set_up_run() {
  local scenario="$1" dump="$2" model="$3" repetition="$4" run_number="$5"
  local directory="$RUN_SET/runs/$run_number"
  mkdir -p "$directory/cwd"
  ln -f "$dump" "$directory/heap-dump.hprof"
  # Beside the run rather than in it, so that a directory of numbers is still readable afterwards.
  printf '%s\t%s\t%s\n' "$scenario" "$model" "$repetition" >"$directory/what.txt"
  echo "$directory"
}

# The skill this repository ships, in the working directory of the run, so that what is measured is the skill
# people get rather than a copy of it written for the eval.
#
# It is the whole of how a run is meant to start — which launcher to find, that `--help` is the command
# list, that an address is `0x…`, that a refusal is the next thing to do — and **a client discovers it by its
# description alone**, from the one sentence of frontmatter. So a run that never invokes it is a finding about
# that sentence rather than about the model, and the client transcript beside each run is where that shows.
#
# Copied rather than symlinked because a symlink out of a temporary directory into the checkout is a skill the
# client may decline to load, and because a run has to stay readable after the checkout has moved on.
install_the_skill() {
  local directory="$1"
  mkdir -p "$directory/cwd/.claude/skills"
  cp -R "$REPO_ROOT/$SKILL_PATH" "$directory/cwd/.claude/skills/"
}

# What a run is asked, and it says three things: where the heap dump is, how to reach Shark Dive, and that
# something in it is leaking.
#
# **Not how to investigate.** What the agent follows has to come from the surface — the method arrives with
# whichever call opens the dump — or the eval is measuring this function. And not which tool to call either:
# opening the dump is a step of the investigation, and the one the skill is most of.
#
# The launcher is named rather than left to be found, which is the one concession. The skill says to look in
# /Applications and ~/Applications, and this app is in neither: it is a copy of a build, in a temporary
# directory, so a run told to go and find it would be measuring an `ls` this eval's own shape breaks.
prompt_for() {
  local app="$1" directory="$2"
  cat <<END
Something in the heap dump at $directory/heap-dump.hprof is leaking. Find the root cause.

Shark Dive is installed on this machine. Its launcher is "$app/Contents/MacOS/Shark Dive".
END
}

# The client, with nothing of this machine to work with but the heap dump.
#
# **Two tools, and they are the whole surface.** `Bash`, to run the launcher, and `Skill`, to load the one in
# the working directory. Nothing else: a run that could `Read` the hprof, or reach a second heap dump tool,
# would be scored on something other than what it is here to measure.
#
# **`Bash` is a hole and it is a bounded one.** An agent with a shell can read the hprof by hand, and nothing
# here can stop it. What keeps that from becoming a score is that scoring reads what `conclude` recorded, and
# `conclude` refuses until the chain has been narrowed through the surface — so the shell can make a run
# *faster* at guessing, and cannot make a run that skipped the method look like one that followed it. The
# client transcript beside each run is where a run that reached for `strings` shows up.
#
# `--strict-mcp-config` with no `--mcp-config` beside it is **not** left over from the arm that is gone: it is
# what keeps every MCP server on the machine out of a run, which is the same job it does in
# `start-harness.sh`. It is only about MCP. [CLIENT_CONFIG_DIRECTORY] is what keeps the rest out — it keys
# auto-memory, so a scratch one has no memories, and the person's own skills are not in it either.
#
# **`~/.claude/CLAUDE.md` is loaded anyway**, whatever `CLAUDE_CONFIG_DIR` says, and this comment claimed
# otherwise until 2026-10-02 — measured by probe, which answered with that file's contents from a run whose
# config directory was empty. For this eval that file is not a detail: it tells a reader where leak data and
# heap dumps live, which is a route to something other than the dump a run is scored on. `--bare` is the only
# switch that suppresses it and **it cannot be used here either**, because it turns off skill auto-discovery
# and the skill is what both scripts measure. So a scenario whose runs all answer suspiciously well is worth
# reading that file before believing the table.
#
# `client.json` is the one object `--output-format json` prints — cost, turn count, token usage, stop reason
# and the final answer — and it is **not** a transcript: the model's own turns, and the thinking between two
# tool calls, are only in the client's session file. Which is why **nothing here turns session persistence
# off.** `--print` already starts a fresh session, so the isolation this eval wants is had for free, and
# `--no-session-persistence` bought none of it while throwing away the half of a run that says *why* a call
# was made. It was here for four commits and the run it made unreadable is in `notes/agent-eval.md`.
#
# **`</dev/null`, and it is the difference between measuring an agent and pasting it the answer key.** This
# client reads its standard input and appends it to the prompt, and the standard input it inherited here was
# the scenario table — name, dump, **answer key**, description, one line each — which the scenario loop in
# `main` used to stream in on standard input. Every run of this eval was handed every key, its own included,
# under the two sentences it was asked. Found by reading a rendered transcript rather than by anything
# failing, and reproduced with a one-word prompt in a loop of the same shape.
#
# Two things made it invisible. A shell built-in reads such an input from where the loop's `read` left it, so
# the obvious check — `cat` in the loop body — shows the lines the loop hasn't reached yet and *not* the one
# the run is about, which reads like a harness leaking the other scenarios and not this one. And the client
# consumed what it read, so the loop ended after the first scenario it ran: `./run-eval.sh` with no arguments
# printed one scenario and then `Scoring.`, which is a table missing four rows rather than an error.
#
# Three things close it and any one of them would have: this redirection, the table being on a descriptor of
# its own so that a child inheriting standard input inherits nothing, and the answer key no longer being
# printed by `scenarios` at all. The third is the one that would have made the other two unnecessary, which is
# the argument for it — a stream that never carries the answer cannot leak it down a path nobody thought of.
run_client() {
  local directory="$1" model="$2"
  (
    cd "$directory/cwd"
    export CLAUDE_CONFIG_DIR="$CLIENT_CONFIG_DIRECTORY"
    timeout_command "$RUN_TIMEOUT_SECONDS" claude \
      --print "$(cat "$directory/prompt.txt")" \
      --model "$model" \
      --strict-mcp-config \
      --tools "Bash,Skill" \
      --allowedTools "Bash Skill" \
      --output-format json \
      </dev/null \
      >"$directory/client.json" 2>"$directory/client.stderr"
  )
  copy_client_transcript "$directory"
}

# The client's own record of the run, beside the two this eval writes.
#
# Found by session id rather than by rebuilding the path, because where Claude Code keeps a transcript is its
# business: it is under the client's configuration directory in a directory named after the working directory,
# and a rule for turning one into the other is a rule that breaks silently on the next release. The id is in
# `client.json`, a run has a working directory of its own, and `find` is the whole of what that costs.
#
# A run that timed out or died has no id and so no transcript, which is exactly when the Shark Dive session
# log is the one to read: it has every call that was made before the client stopped.
#
# `claude-code-log` renders one of these as a page to read: `claude-code-log convert <the file> -o run.html`.
copy_client_transcript() {
  local directory="$1"
  local session_id
  session_id="$(sed -n 's/.*"session_id":"\([^"]*\)".*/\1/p' "$directory/client.json" 2>/dev/null)"
  if [[ -z "$session_id" ]]; then
    echo "  no session id in client.json, so no client transcript — read the Shark Dive session instead" >&2
    return 0
  fi
  local transcript
  transcript="$(find "$CLIENT_CONFIG_DIRECTORY/projects" -name "$session_id.jsonl" -print -quit 2>/dev/null)"
  if [[ -z "$transcript" ]]; then
    echo "  no transcript found for session $session_id under $CLIENT_CONFIG_DIRECTORY/projects" >&2
    return 0
  fi
  cp "$transcript" "$directory/client-transcript.jsonl"
}

# Ends every Shark Dive this eval has published, and only those: [PUBLISHED_RUNS_DIRECTORY] is under
# [SHARK_DIVE_DIR], so a person's own dives are not in it and cannot be killed from here.
#
# One window per run is the cost of reaching a run over its socket, and thirty windows left open would be
# thirty indexed heap dumps and a machine nobody can use. Killed between runs rather than at the end so that
# each agent's `open_heap_dump` starts a run of its own, with its dump the only one in it.
close_the_runs() {
  local file pid waited=0
  local -a pids=()
  for file in "$PUBLISHED_RUNS_DIRECTORY"/*.agent; do
    [[ -e "$file" ]] || continue
    pid="$(basename "$file" .agent)"
    if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      pids+=("$pid")
    fi
    rm -f "$file"
  done
  ((${#pids[@]})) || return 0
  while ((waited < CLOSING_WAIT_SECONDS)); do
    local alive=0
    for pid in "${pids[@]}"; do
      kill -0 "$pid" 2>/dev/null && alive=1
    done
    ((alive)) || return 0
    sleep 1
    ((waited++))
  done
  echo "  ${#pids[@]} Shark Dive run(s) did not end within ${CLOSING_WAIT_SECONDS}s: ${pids[*]}" >&2
}

# `timeout` is GNU, and macOS has it only if coreutils is installed. Without one, the run is unbounded and
# says so rather than silently having no limit.
timeout_command() {
  local seconds="$1"
  shift
  if command -v timeout >/dev/null; then
    timeout "$seconds" "$@"
  elif command -v gtimeout >/dev/null; then
    gtimeout "$seconds" "$@"
  else
    echo "    no timeout command, so this run is unbounded (brew install coreutils)" >&2
    "$@"
  fi
}

# The app the runs launch: a copy of the packaged one, and it prints where it put it.
#
# A copy for the two reasons `start-harness.sh` copies — `build/compose` is deleted by the next Compose task,
# and a window whose bundle went out from under it dies; and the dock reads the file name of the bundle, so a
# name of its own is how somebody tells the eval's windows from their own — plus one that is this script's:
# it is under [RUN_SET], so the app a run was measured with is deleted with the run set and a table can never
# be read against a build that has moved on.
#
# `cp -c` clones rather than copies, so 240 MB of jlinked runtime costs 80 ms and no disk on APFS.
bundle_for_the_eval() {
  local built="$REPO_ROOT/$APP_PATH"
  echo "Building the app. jlink takes about a minute the first time." >&2
  (cd "$REPO_ROOT" && ./gradlew --quiet :shark:shark-dive:shark-dive-app:createDistributable)
  if [[ ! -d "$built" ]]; then
    echo "The app was not built at $built" >&2
    exit 1
  fi
  local copy="$RUN_SET/Shark Dive Eval.app"
  cp -Rc "$built" "$copy" 2>/dev/null || cp -R "$built" "$copy"
  local plist="$copy/Contents/Info.plist"
  /usr/libexec/PlistBuddy -c "Set :CFBundleName Shark Dive Eval" "$plist" >/dev/null
  /usr/libexec/PlistBuddy -c "Add :CFBundleDisplayName string Shark Dive Eval" "$plist" >/dev/null 2>&1 ||
    /usr/libexec/PlistBuddy -c "Set :CFBundleDisplayName Shark Dive Eval" "$plist" >/dev/null
  echo "$copy"
}

# The eval module, on stdout, with Gradle's own noise on stderr where it belongs.
eval_module() {
  (cd "$REPO_ROOT" && ./gradlew --quiet "$EVAL_MODULE:run" --args="$*" 2>/dev/null)
}

session_files() {
  ls "$SESSIONS_DIRECTORY" 2>/dev/null | sort || true
}

require_client() {
  if ! command -v claude >/dev/null; then
    cat >&2 <<END
There is no \`claude\` on the PATH, and it is the only client this script has an adapter for.

One adapter is a few lines — the arguments that make a client run once and print what it did — so add
\`codex exec\` or \`opencode run\` beside \`run_client\` rather than working around this. The prompt has to
stay identical across clients: what is being measured is the surface, and a prompt tuned per client measures
the prompt.
END
    exit 1
  fi
}

usage() {
  cat <<END
Usage: run-eval.sh [--scenarios all|<name>,<name>] [--models <name>,<name>] [--repetitions <n>]

  --scenarios    Which to run, comma separated. Default: all.
  --models       What to pass the client as its model, comma separated. Default: opus. A weak model is
                 where a surface is measured: a strong one papers over a bad description.
  --repetitions  Runs per scenario, reported as x/n rather than averaged, because a model is not
                 deterministic. Default: 1, and 5 is what a result worth committing takes.
END
}

main "$@"
