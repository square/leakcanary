#!/bin/bash
#
# Opens one heap dump in a real Shark Dive, then prints the command that throws an agent at it.
#
# What this is for: the tools in this module are meant to hold an investigation to a method, and whether
# they do is not a thing a unit test can answer — it takes a model that has never seen this repository,
# reading nothing but what the tools hand back. So this sets the stage and stops: a window someone can watch,
# the skill staged where a session started here will find it, and a prompt that says no more than "find the
# root cause" plus which run to say it to.
#
# **The agent reaches the window over the command line** — `--agent <tool> name=value` against the launcher —
# which is what somebody who installed the app has. There is no MCP config here and no `--mcp-config` in the
# command this prints; `--strict-mcp-config` is still passed, and does the opposite job, keeping every MCP
# server on the machine out of the session.
#
# The packaged app rather than `./gradlew run`, for two reasons. It is what a person has installed, so the
# command in the config is the command they would write; and a Gradle build of any kind kills a window
# launched from source, which here would be every window this harness opened. A copy of it, named after the
# title, for two more — see `bundle_named_after_the_title` and shark/shark-dive/AGENTS.md.

set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
readonly DEFAULT_HEAP_DUMP="shark/shark-android/src/test/resources/leak_asynctask_o.hprof"
readonly APP_PATH="shark/shark-dive/shark-dive-app/build/compose/binaries/main/app/Shark Dive.app"
readonly SKILL_PATH=".claude/skills/shark-dive"
readonly RUNS_DIRECTORY="$HOME/.shark-dive/agents"
readonly TEMPORARY_DIRECTORY="${TMPDIR:-/tmp}"
readonly HARNESS_DIRECTORY="${SHARK_HARNESS_DIR:-${TEMPORARY_DIRECTORY%/}/shark-dive-harness}"
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
  (cd "$REPO_ROOT" && ./gradlew --quiet :shark:shark-dive:shark-dive-app:createDistributable)

  mkdir -p "$HARNESS_DIRECTORY"
  local app
  app="$(bundle_named_after_the_title)"
  local before
  before="$(published_runs)"
  echo "Opening $(basename "$heap_dump") in a window called \"$TITLE\"."
  open -n "$app" --args --title="$TITLE" "$heap_dump"

  local pid
  pid="$(wait_for_new_run "$before")"
  local launcher="$app/Contents/MacOS/Shark Dive"

  install_the_skill
  write_prompt "$launcher" "$pid"

  cat <<END

The window is open and answering agents as run $pid.

Throw an agent at it:

  cd $HARNESS_DIRECTORY
  claude --strict-mcp-config "\$(cat prompt.txt)"

Started from that directory, so the skill beside the prompt is the one the session loads, and with
--strict-mcp-config, so the agent has this repository's heap dump and nothing else of it: no CLAUDE.md, no
memory of this project, no MCP server. Your own ~/.claude/CLAUDE.md still loads, which is the one thing this
cannot keep out.

Watch what it does, in the window and in the log:

  tail -f "$(newest_log)"

Every call is one line saying which tool, with which arguments, and the reason the agent gave for making
it — then the reads that call cost. That log is the point of the exercise as much as the answer is.
END
}

# The app to launch: a copy of the packaged one, named after the title, and it prints where it put it.
#
# Two things a copy fixes. **The dock reads the file name of the bundle a process was launched from** and
# nothing else — so every window of the installed app is a tile called "Shark Dive", and several
# harness windows at once are indistinguishable on screen. Renaming the copy names the tile; the two plist
# keys below name the menu bar, which for a real bundle comes from the plist rather than from `--title`.
# Measured, all three names — see shark/shark-dive/AGENTS.md.
#
# **And `build/compose` is not a safe place to launch from**: another Compose task deletes the app image,
# and a window whose bundle has been deleted under it dies the way a window launched from source does. A
# copy outside the build directory survives every build after it.
#
# `cp -c` clones rather than copies, so 240 MB of jlinked runtime costs 80 ms and no disk on APFS.
bundle_named_after_the_title() {
  local built="$REPO_ROOT/$APP_PATH"
  local copy="$HARNESS_DIRECTORY/$TITLE.app"
  rm -rf "$copy"
  cp -Rc "$built" "$copy" 2>/dev/null || cp -R "$built" "$copy"
  local plist="$copy/Contents/Info.plist"
  /usr/libexec/PlistBuddy -c "Set :CFBundleName $TITLE" "$plist" >/dev/null
  /usr/libexec/PlistBuddy -c "Add :CFBundleDisplayName string $TITLE" "$plist" >/dev/null 2>&1 ||
    /usr/libexec/PlistBuddy -c "Set :CFBundleDisplayName $TITLE" "$plist" >/dev/null
  echo "$copy"
}

# The skill this repository ships, beside the prompt, because a session started here has to be able to find
# the launcher and the tool list — over the command line the surface is not in its context. The same copy the
# eval installs, for the same reason: what gets exercised is the skill people get.
install_the_skill() {
  local skill="$REPO_ROOT/$SKILL_PATH"
  if [[ ! -d "$skill" ]]; then
    echo "No shark-dive skill at $skill" >&2
    exit 1
  fi
  mkdir -p "$HARNESS_DIRECTORY/.claude/skills"
  rm -rf "$HARNESS_DIRECTORY/.claude/skills/shark-dive"
  cp -R "$skill" "$HARNESS_DIRECTORY/.claude/skills/"
}

# Deliberately says nothing about how to investigate. The method the agent follows has to come from the
# surface, or the experiment is measuring this file.
#
# It does say which run to talk to, and that part is not a hint: `--agent-run` pins the calls to the window
# this script just opened, and without it they go to whichever run published last. Whoever is running this has
# other dives open — that is what the app is like — so an unpinned session would be investigating a heap dump
# nobody set up. The eval needs no such line because it ends every other run of its own first, which is a
# thing this script must not do to somebody's screen.
write_prompt() {
  local launcher="$1" pid="$2"
  cat >"$HARNESS_DIRECTORY/prompt.txt" <<END
A heap dump is open in Shark Dive. Something in it is leaking. Find the root cause.

Shark Dive is installed on this machine. Its launcher is "$launcher", and the run that has the heap dump open
is $pid, so every call looks like:

    "$launcher" --agent-run=$pid --agent <tool> name=value …
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
  ls -t "$HOME/.shark-dive/logs" 2>/dev/null | head -1 | sed "s|^|$HOME/.shark-dive/logs/|"
}

absolute_path() {
  if [[ "$1" == /* ]]; then echo "$1"; else echo "$PWD/$1"; fi
}

main "$@"
