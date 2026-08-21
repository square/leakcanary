#!/bin/bash
#
# Opens one heap dump in a real Shark Explorer, then prints the command that throws an agent at it.
#
# What this is for: the tools in this module are meant to hold an investigation to a method, and whether
# they do is not a thing a unit test can answer — it takes a model that has never seen this repository,
# reading nothing but what the tools hand back. So this sets the stage and stops: a window someone can watch,
# an MCP config pinned to that window, and a prompt that says no more than "find the root cause".
#
# The packaged app rather than `./gradlew run`, for two reasons. It is what a person has installed, so the
# command in the config is the command they would write; and a Gradle build of any kind kills a window
# launched from source, which here would be every window this harness opened. See shark/shark-explorer/AGENTS.md.

set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
readonly DEFAULT_HEAP_DUMP="shark/shark-android/src/test/resources/leak_asynctask_o.hprof"
readonly APP_PATH="shark/shark-explorer/shark-explorer-app/build/compose/binaries/main/app/Shark Explorer.app"
readonly RUNS_DIRECTORY="$HOME/.shark-explorer/agents"
readonly TEMPORARY_DIRECTORY="${TMPDIR:-/tmp}"
readonly HARNESS_DIRECTORY="${SHARK_HARNESS_DIR:-${TEMPORARY_DIRECTORY%/}/shark-explorer-harness}"
readonly TITLE="${SHARK_HARNESS_TITLE:-Agent harness}"
readonly WAIT_SECONDS=90

main() {
  local heap_dump
  heap_dump="$(absolute_path "${1:-$REPO_ROOT/$DEFAULT_HEAP_DUMP}")"
  if [[ ! -f "$heap_dump" ]]; then
    echo "No heap dump at $heap_dump" >&2
    exit 1
  fi

  # Everything that builds happens before the window opens, because building rewrites the jars a window
  # launched from source is reading. A packaged app is a copy and survives it, but the ordering costs
  # nothing and one day somebody will point this at `run`.
  echo "Building the app. jlink takes about a minute the first time."
  (cd "$REPO_ROOT" && ./gradlew --quiet :shark:shark-explorer:shark-explorer-app:createDistributable)

  local app="$REPO_ROOT/$APP_PATH"
  local before
  before="$(published_runs)"
  echo "Opening $(basename "$heap_dump") in a window called \"$TITLE\"."
  open -n "$app" --args --title="$TITLE" "$heap_dump"

  local pid
  pid="$(wait_for_new_run "$before")"
  local bridge="$app/Contents/MacOS/Shark Explorer"

  mkdir -p "$HARNESS_DIRECTORY"
  write_mcp_config "$bridge" "$pid"
  write_prompt

  cat <<END

The window is open and answering agents as run $pid.

Throw an agent at it:

  cd $HARNESS_DIRECTORY
  claude --mcp-config mcp.json --strict-mcp-config "\$(cat prompt.txt)"

Started from that directory, and with --strict-mcp-config, so the agent has this repository's heap dump and
nothing else of it: no CLAUDE.md, no memory of this project, no other MCP server. Your own ~/.claude/CLAUDE.md
still loads, which is the one thing this cannot keep out.

Watch what it does, in the window and in the log:

  tail -f "$(newest_log)"

Every call is one line saying which tool, with which arguments, and the reason the agent gave for making
it — then the reads that call cost. That log is the point of the exercise as much as the answer is.
END
}

write_mcp_config() {
  local bridge="$1" pid="$2"
  # Pinned to this run rather than left to pick the most recent, because whoever is running this has other
  # explorers open — that is what the app is like — and an agent that wandered into one of them would be
  # investigating a heap dump nobody set up.
  cat >"$HARNESS_DIRECTORY/mcp.json" <<END
{
  "mcpServers": {
    "shark-explorer": {
      "command": "$bridge",
      "args": ["--mcp-stdio", "--agent-run=$pid"]
    }
  }
}
END
}

# Deliberately says nothing about how to investigate. The method the agent follows has to come from the
# server, or the experiment is measuring this file.
write_prompt() {
  cat >"$HARNESS_DIRECTORY/prompt.txt" <<'END'
A heap dump is open in Shark Explorer, which you can reach through its MCP tools. Something in it is
leaking. Find the root cause.
END
}

published_runs() {
  ls "$RUNS_DIRECTORY" 2>/dev/null | sort || true
}

# The process id of the run that just started, which is the file that wasn't there before it did.
wait_for_new_run() {
  local before="$1" waited=0 new
  while ((waited < WAIT_SECONDS)); do
    new="$(comm -13 <(echo "$before") <(published_runs) | head -1)"
    if [[ -n "$new" ]]; then
      echo "${new%.agent}"
      return 0
    fi
    sleep 1
    ((waited++))
  done
  echo "The window did not publish itself within ${WAIT_SECONDS}s. Look at $(newest_log)." >&2
  exit 1
}

newest_log() {
  # shellcheck disable=SC2012
  ls -t "$HOME/.shark-explorer/logs" 2>/dev/null | head -1 | sed "s|^|$HOME/.shark-explorer/logs/|"
}

absolute_path() {
  if [[ "$1" == /* ]]; then echo "$1"; else echo "$PWD/$1"; fi
}

main "$@"
