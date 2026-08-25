#!/bin/bash
#
# Measures whether an agent can solve a leak through Shark Explorer's MCP surface, and scores it by string
# comparison and counting. No model marks anything: see shark/shark-explorer/notes/agent-eval.md.
#
# One run is one scenario, one model, one repetition, and one session file. The heap dumps and the scoring
# come from `shark-explorer-eval`; everything here is process handling — launching a client per run, finding
# the session it produced, and writing down which run that session belongs to.
#
#   ./run-eval.sh                                        every scenario, the default model, once each
#   ./run-eval.sh --scenarios two-apart --repetitions 5   one scenario, five times
#   ./run-eval.sh --models opus,sonnet                    two models over the same dumps, in one table
#
# Costs money and needs the network, so it is not in CI. Run it before and after a change to the method or a
# refusal and commit the table it prints, or the change is a prompt change nobody reviewed.
#
# **Four things here are about keeping a run from being told the answer**, and all four were found by running
# it rather than by thinking about it — see `set_up_run`. A run that leaks its own answer scores well and
# measures nothing, which is the one failure of an eval that doesn't announce itself.

set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../../.." && pwd)"
readonly APP_PATH="shark/shark-explorer/shark-explorer-app/build/compose/binaries/main/app/Shark Explorer.app"
readonly SESSIONS_DIRECTORY="$HOME/.shark-explorer/agents/sessions"
readonly TEMPORARY_DIRECTORY="${TMPDIR:-/tmp}"
readonly EVAL_DIRECTORY="${SHARK_EVAL_DIR:-${TEMPORARY_DIRECTORY%/}/shark-explorer-eval}"
# Every invocation gets a directory of its own, named for when it started, because notes and verdicts are kept
# per heap dump path: `runs/3/heap-dump.hprof` reused a week later is the same heap dump as far as they are
# concerned, and the second agent to be given it opens a dump somebody else already solved. Which is not a
# hypothetical — see `set_up_run`.
readonly RUN_SET="$EVAL_DIRECTORY/$(date +%Y-%m-%d_%H-%M-%S)"
readonly EVAL_MODULE=":shark:shark-explorer:shark-explorer-eval"
# Long enough for a real dump to be indexed and an investigation to run, short enough that a client which
# hung on a refusal doesn't hold the whole eval. A run that hits it is scored for what it did before it.
readonly RUN_TIMEOUT_SECONDS="${SHARK_EVAL_TIMEOUT:-900}"

# What every run of every scenario is asked, and it says nothing about how to investigate. What the agent
# follows has to come from the server, or the eval is measuring this file.
readonly PROMPT="A heap dump is open in Shark Explorer, which you can reach through its MCP tools. Something \
in it is leaking. Find the root cause."

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
  mkdir -p "$RUN_SET/dumps" "$RUN_SET/runs"
  local app
  app="$(built_app)"

  echo "Writing the scenario heap dumps into $RUN_SET/dumps."
  local scenario_lines
  scenario_lines="$(eval_module scenarios "$RUN_SET/dumps" "$REPO_ROOT")"

  local runs="$RUN_SET/runs.tsv"
  : >"$runs"
  local run_number=0
  local name dump key about
  while IFS=$'\t' read -r name dump key about; do
    if [[ "$scenarios" != "all" && ",$scenarios," != *",$name,"* ]]; then
      # Said rather than skipped silently: a table of one scenario looks exactly like a table of all of them
      # that only one of them passed.
      echo "Skipping $name."
      continue
    fi
    echo
    echo "$name — $about"
    echo "  the answer is $key, and nothing the agent is told mentions it"
    local model repetition
    for model in ${models//,/ }; do
      for ((repetition = 1; repetition <= repetitions; repetition++)); do
        run_number=$((run_number + 1))
        run_once "$app" "$name" "$dump" "$model" "$repetition" "$run_number" "$runs"
      done
    done
  done <<<"$scenario_lines"

  echo
  echo "Scoring."
  echo
  eval_module score "$runs" "$REPO_ROOT" "$SESSIONS_DIRECTORY"
  cat <<END

Every run has a directory of its own under $RUN_SET/runs: what it was, what the client reported, and
the heap dump as that run saw it. What the agent did call by call is on the *Agent logs* screen of a window
opened on that dump, with the notes and the verdicts it left:

  ./gradlew :shark:shark-explorer:shark-explorer-app:runNamed \\
    --args="--title=\\"Eval\\" $RUN_SET/runs/1/heap-dump.hprof"
END
}

# One agent, one scenario, one repetition. Appends a line to the runs file naming the session it produced.
run_once() {
  local app="$1" scenario="$2" dump="$3" model="$4" repetition="$5" run_number="$6" runs="$7"
  local directory
  directory="$(set_up_run "$scenario" "$dump" "$model" "$repetition" "$run_number")"
  write_mcp_config "$app" "$directory"

  # Which session files existed before, because the client starts the server and the server names its own
  # session file: the new one is the difference. The alternative is parsing timestamps out of file names,
  # which two runs a second apart would get wrong.
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
    echo "    no session was written: the server never got as far as a handshake. See $directory/client.stderr"
    return 0
  fi
  # The dump as this run's server was pointed at it, because scoring checks that the conclusion was about it:
  # a run that ended up in another heap dump measured nothing, and is not a wrong answer. See EvalOutcome.
  printf '%s\t%s\t%s\t%s\n' "$scenario" "$model" "$session" "$directory/heap-dump.hprof" >>"$runs"
  echo "    $((ended - started))s, session $session"
}

# One run's own directory, and it prints where it is.
#
# Four things about the shape of it are what keep a run from being handed its own answer. Each was a run that
# scored well and measured nothing:
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
# eval a week later is that same agent again. A symlink rather than a copy: the key is not the resolved path, so
# the identity is fresh and the 8 MB is not copied five times.
#
# **The client's working directory holds nothing but its MCP config.** Its own environment lists that
# directory in what it is told, so the other scenarios' dumps being in it is the eval naming every answer at
# once. Which is exactly what the first run of this script did: it opened all three and solved all three.
#
# **And a run that wandered anyway is scored as having wandered**, not as having answered wrongly. Two did,
# before [RUN_SET] existed: given a dump the previous eval had already solved — same path, so the same notes and
# verdicts — an agent that has nothing left to investigate goes looking for a dump that does, and both of them
# guessed a path in this eval's own directory and investigated that instead. The check stays now that the cause
# is gone, because that is how this eval reports its own failure rather than blaming a model for one.
set_up_run() {
  local scenario="$1" dump="$2" model="$3" repetition="$4" run_number="$5"
  local directory="$RUN_SET/runs/$run_number"
  mkdir -p "$directory/cwd"
  ln -sf "$dump" "$directory/heap-dump.hprof"
  # Beside the run rather than in it, so that a directory of numbers is still readable afterwards.
  printf '%s\t%s\t%s\n' "$scenario" "$model" "$repetition" >"$directory/what.txt"
  echo "$directory"
}

# The client, with nothing of this machine to work with but the heap dump.
#
# `--tools ""` turns off every built-in tool, which is the control that makes the number mean something: the
# heap dump and the tool descriptions are then the whole of what the model has, so a change in the score is a
# change in this surface rather than in what it managed to read off the disk. The method tells an agent to go
# and read the code, and it is right to — but a scenario that measures *that* has to ship the code to read,
# which none of these do yet.
#
# `--strict-mcp-config` for the same reason the interactive harness uses it: no other MCP server, and no
# memory of this project. Your own ~/.claude/CLAUDE.md still loads, which is the one thing this cannot keep
# out.
run_client() {
  local directory="$1" model="$2"
  (
    cd "$directory/cwd"
    timeout_command "$RUN_TIMEOUT_SECONDS" claude \
      --print "$PROMPT" \
      --model "$model" \
      --mcp-config mcp.json \
      --strict-mcp-config \
      --allowedTools "mcp__shark-explorer" \
      --tools "" \
      --output-format json \
      --no-session-persistence \
      >"$directory/client.json" 2>"$directory/client.stderr"
  )
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

# The server the client launches: this app, answering from its own process with no window.
#
# `--no-ui` rather than a window per run, because thirty windows is thirty indexed heap dumps and nobody is
# watching any of them. What an investigation leaves behind is on disk either way, so the run is still
# readable in a window afterwards — which is the last thing this script prints.
write_mcp_config() {
  local app="$1" directory="$2"
  cat >"$directory/cwd/mcp.json" <<END
{
  "mcpServers": {
    "shark-explorer": {
      "command": "$app/Contents/MacOS/Shark Explorer",
      "args": ["--mcp-stdio", "--no-ui", "$directory/heap-dump.hprof"]
    }
  }
}
END
}

built_app() {
  local built="$REPO_ROOT/$APP_PATH"
  echo "Building the app. jlink takes about a minute the first time." >&2
  (cd "$REPO_ROOT" && ./gradlew --quiet :shark:shark-explorer:shark-explorer-app:createDistributable)
  if [[ ! -d "$built" ]]; then
    echo "The app was not built at $built" >&2
    exit 1
  fi
  echo "$built"
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
