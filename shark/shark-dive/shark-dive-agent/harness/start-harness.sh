#!/bin/bash
#
# Throws an agent at one heap dump through Shark Dive's agent surface, in a window it opens for itself.
#
# What this is for: the tools in this module are meant to hold an investigation to a method, and whether
# they do is not a thing a unit test can answer — it takes a model that has never seen this repository,
# reading nothing but what the tools hand back. So this builds the app, copies it somewhere of its own, and
# starts an agent on one heap dump with two sentences: which file, and where the launcher is.
#
# **Nothing here opens the heap dump.** The agent is told where the file is, and opening it is the first
# thing it has to get right — which is the first thing a person has to get right too. A harness that handed
# over a dump already open measured every step of an investigation except the one that starts it. Same reason
# `harness/eval/run-eval.sh` opens nothing. See `write_prompt`.
#
# **The second run on one heap dump is handed the first one's answer, and that is not a bug to fix here.**
# Notes and verdicts are kept per heap dump under `~/.shark-dive`, which this shares with the person watching —
# the whole point being that what the agent leaves is on their screen and outlives the run. So a repeat run on
# the same dump opens it, finds a note, and `read_notes` hands it a conclusion before it has traced anything:
# measured on the second run of this script against `leak_asynctask_o.hprof`, where the note from the first
# named the faulty reference verbatim. It verified it independently over twenty calls, which is the good case
# and still not a blind one. **So a run that is meant to show whether the surface carries an investigation
# wants a dump nothing here has solved, or its own state directory**: `SHARK_DIVE_DIR=$(mktemp -d)` in front of
# this script gives it one, at the cost of the window not appearing among the person's own dives. The eval does
# that permanently, plus a hard-linked copy of the dump per run, and `run-eval.sh` says why.
#
# **And nothing here stages a skill.** What an agent needs to know is `--help`, `--investigation-help` and
# `--leak-investigation-help`, which are text the build carries and therefore cannot go stale — so the prompt
# names the launcher and stops, and finding out what it takes is the agent's own first move. The skill this
# repository ships is one short page saying exactly that much, for somebody who has the app installed and
# wants their client to reach for it unprompted; it is not a thing this script needs a copy of, and staging one
# used to mean rewriting its paths and checking the rewrite had worked.
#
# **The agent reaches the window over the command line** — `--cli <command> name=value` against the launcher —
# which is what somebody who installed the app has. Shark Dive has no MCP server any more; the command line is
# the whole surface.
#
# The packaged app rather than `./gradlew run`, for two reasons. It is what a person has installed, so the
# command the agent types is the command they would write; and a Gradle build of any kind kills a window
# launched from source, which here would be every window an agent opened. A copy of it, named after the
# title, for two more — see `bundle_named_after_the_title` and shark/shark-dive/AGENTS.md.

set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
readonly DEFAULT_HEAP_DUMP="shark/shark-android/src/test/resources/leak_asynctask_o.hprof"
readonly APP_PATH="shark/shark-dive/shark-dive-app/build/compose/binaries/main/app/Shark Dive.app"
readonly TEMPORARY_DIRECTORY="${TMPDIR:-/tmp}"
readonly STARTED="$(date +%Y-%m-%d_%H-%M-%S)"
# A directory per invocation, and the pid because two started in the same second are a real case — a loop
# comparing two models, or two terminals. **Nothing deletes the ones before it**, unlike the eval, and that
# is not an oversight: a window is launched from the bundle in one of these, and deleting it under a window
# kills that window the way a build does. So they accumulate, and `rm -rf` of the parent is somebody's own
# decision to make once their windows are closed.
readonly HARNESS_DIRECTORY="${SHARK_HARNESS_DIR:-${TEMPORARY_DIRECTORY%/}/shark-dive-harness/$STARTED-$$}"
# Which names the bundle, and so the dock tile — see `bundle_named_after_the_title`. It carries the timestamp
# for the same reason the directory does: two harness runs both tiled `Agent harness` are two windows nobody
# at the machine can tell apart, which is the thing a directory per run would otherwise only half fix.
readonly TITLE="${SHARK_HARNESS_TITLE:-Agent harness $STARTED}"
# Where the client keeps its own configuration, which is the whole of what keeps this machine out of the run —
# see `run_the_agent`.
readonly CLIENT_CONFIG_DIRECTORY="$HARNESS_DIRECTORY/claude"
readonly LOGS_DIRECTORY="${SHARK_DIVE_DIR:-$HOME/.shark-dive}/logs"
# The client's session, named here rather than left to the client, which is what makes its transcript findable
# before it exists — see `WHERE_THE_TRANSCRIPT_IS`. Lower case because the option takes a UUID and `uuidgen`
# on macOS prints upper case.
readonly SESSION_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
readonly WATCH_TRANSCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/watch-transcript.sh"
# Said twice, once by the run that starts an agent and once by the command printed for driving one yourself,
# and it is the same sentence both times on purpose: that log is what this exercise is for as much as the
# answer is, so neither way of starting a run should be the one that forgets to say where it is.
readonly WHERE_THE_LOG_IS="Once a heap dump is open, the Shark Dive agent logs (list of commands + reasons) are in the newest file in $LOGS_DIRECTORY:

  tail -f \"\$(ls -t $LOGS_DIRECTORY/*.log | head -1)\""
# And the other half of what a run did: every call it made to everything that isn't Shark Dive, and what came
# back. **Neither of the two things this script prints carries any of that** — the agent's output is its answer
# and the Shark Dive log is the commands that reached a window — so a run that went wrong somewhere else is a
# run with nothing written down about where. The client writes it all to a transcript as it goes, and
# `watch-transcript.sh` is the reader; this is said by both ways of starting a run for the same reason the log
# above is.
#
# **It names this run rather than letting the default find one**, which matters more here than it looks:
# harness directories are never deleted and two runs at once is the normal case, so the newest is the right
# answer only until somebody starts a second one — and then the command printed by the first quietly follows
# the second. The reasoning text is the one thing the transcript hasn't got; `watch-transcript.sh` has what was
# measured about that and why no option brings it back.
readonly WHERE_THE_TRANSCRIPT_IS="The detailed LLM logs can be watched with:

  $WATCH_TRANSCRIPT \\
    $HARNESS_DIRECTORY

Add --html for the same thing rendered in a browser, updating as the run goes."

main() {
  local heap_dump="" model="${SHARK_HARNESS_MODEL:-}" start_the_agent=true
  while (($#)); do
    case "$1" in
      --print-command) start_the_agent=false; shift ;;
      --model) model="$2"; shift 2 ;;
      --help | -h) usage; exit 0 ;;
      -*) echo "Unknown option $1" >&2; usage >&2; exit 1 ;;
      *) heap_dump="$1"; shift ;;
    esac
  done

  heap_dump="$(absolute_path "${heap_dump:-$REPO_ROOT/$DEFAULT_HEAP_DUMP}")"
  if [[ ! -f "$heap_dump" ]]; then
    echo "No heap dump at $heap_dump" >&2
    exit 1
  fi
  if [[ "$start_the_agent" == true ]]; then
    require_client
  fi

  # Everything that builds happens before anything is staged, because building rewrites the jars a window
  # launched from source is reading. A packaged app is a copy and survives it, but the ordering costs
  # nothing and one day somebody will point this at `run`.
  echo "Building the app. jlink takes about a minute the first time."
  (cd "$REPO_ROOT" && ./gradlew --quiet :shark:shark-dive:shark-dive-app:createDistributable)

  mkdir -p "$HARNESS_DIRECTORY" "$CLIENT_CONFIG_DIRECTORY"
  local app
  app="$(bundle_named_after_the_title)"
  local launcher="$app/Contents/MacOS/Shark Dive"

  write_prompt "$heap_dump" "$launcher" >"$HARNESS_DIRECTORY/prompt.txt"
  # Which transcript belongs to this run, written down rather than worked out: the client keys its projects
  # directory on the *physical* working directory, and `$TMPDIR` here is a symlink into `/private/var`, so a
  # path built from `$HARNESS_DIRECTORY` is wrong on this machine and right on Linux. `watch-transcript.sh`
  # reads this and globs for it.
  echo "$SESSION_ID" >"$HARNESS_DIRECTORY/session-id.txt"

  echo "Copied the Shark Dive app prompt.txt to $HARNESS_DIRECTORY."
  if [[ "$start_the_agent" == true ]]; then
    run_the_agent "$model"
  else
    print_the_command "$model"
  fi
}

# The app the agent launches: a copy of the packaged one, named after the title, and it prints where it put it.
#
# Two things a copy fixes. **The dock reads the file name of the bundle a process was launched from** and
# nothing else — so every window of the installed app is a tile called "Shark Dive", and several
# harness windows at once are indistinguishable on screen. Renaming the copy names the tile; the two plist
# keys below name the menu bar, which for a real bundle comes from the plist rather than from
# `--debug-title-prefix`. Measured, all three names — see shark/shark-dive/AGENTS.md.
#
# **And `build/compose` is not a safe place to launch from**: another Compose task deletes the app image,
# and a window whose bundle has been deleted under it dies the way a window launched from source does. A
# copy outside the build directory survives every build after it.
#
# `cp -c` clones rather than copies, so 240 MB of jlinked runtime costs 80 ms and no disk on APFS.
bundle_named_after_the_title() {
  local built="$REPO_ROOT/$APP_PATH"
  if [[ ! -d "$built" ]]; then
    echo "The app was not built at $built" >&2
    exit 1
  fi
  local copy="$HARNESS_DIRECTORY/$TITLE.app"
  rm -rf "$copy"
  cp -Rc "$built" "$copy" 2>/dev/null || cp -R "$built" "$copy"
  local plist="$copy/Contents/Info.plist"
  /usr/libexec/PlistBuddy -c "Set :CFBundleName $TITLE" "$plist" >/dev/null
  /usr/libexec/PlistBuddy -c "Add :CFBundleDisplayName string $TITLE" "$plist" >/dev/null 2>&1 ||
    /usr/libexec/PlistBuddy -c "Set :CFBundleDisplayName $TITLE" "$plist" >/dev/null
  echo "$copy"
}

# What the agent is asked: which file, and where the launcher is. Which is also the whole of what the shipped
# skill says, deliberately — that page exists so a client reaches for Shark Dive unprompted, and once something
# has been prompted there is nothing left in it to carry.
#
# **Not how to investigate, and not which tool to call.** What the agent follows has to come from the surface —
# `--help` is the command list and `--investigation-help` is the method, both text this build prints — or what
# this measures is this function. Opening the dump is itself a step of the investigation.
#
# **The launcher is named rather than left to be found.** The alternative was measuring an `ls` that this
# harness's own shape breaks: the app is a copy of a build in a temporary directory, in neither of the two
# places anything would look.
write_prompt() {
  local heap_dump="$1" launcher="$2"
  cat <<END
Investigate the heap dump at $heap_dump using Shark Dive, which is located at "$launcher".
END
}

# The client, with this machine's memories kept out of it and everything else left alone.
#
# **`CLAUDE_CONFIG_DIR` is the whole of the isolation, and it is doing more than it looks like.** It keys
# auto-memory, so a scratch one has no memories — which is the half that matters, since what is being watched
# is whether the surface carries an investigation and a remembered conclusion about a heap dump in this
# repository would skip it. It also keeps the person's own skills out, and their MCP servers: measured on
# 2026-10-02, a session on the default config directory has 17 `mcp__shark-dive__*` tools, a complete rival
# investigation surface including `open_heap_dump` and `conclude`, and one on a scratch directory has none.
# **Which is why there is no `--strict-mcp-config` here any more** — it was doing a job already done, and a
# second lock whose comment claims to be load-bearing is the kind of thing somebody later reasons from.
#
# **And `CLAUDE_SECURESTORAGE_CONFIG_DIR=` is what keeps that scratch directory logged in**, the one thing
# here that has to cross over. The client's keychain item is named after its configuration directory — read
# with `security find-generic-password -s "Claude Code-credentials-<first 8 of sha256 of the directory>"`, and
# the suffix is dropped only when `CLAUDE_CONFIG_DIR` is unset — so a directory nothing has ever logged into
# has a keychain item nothing has ever written, and the run ends on `Not logged in · Please run /login` having
# opened nothing. Setting secure storage to the empty string points the lookup back at the default item while
# the configuration stays scratch. Measured both ways, and the variable is the client's own.
#
# **It took somebody at a terminal to find that.** A shell inside an agent session has `ANTHROPIC_API_KEY` and
# `ANTHROPIC_BASE_URL` in its environment, nothing in a shell profile here sets either, and a client holding an
# API key never asks the keychain — so this worked every time an agent ran it and failed the first time a person
# did. Which is the rule for anything else here that reads the environment: try it under
# `env -u ANTHROPIC_API_KEY -u ANTHROPIC_BASE_URL`, or what you have measured is your own session.
#
# **The tools are not restricted, deliberately.** An investigation does not end at the faulty reference: it
# ends at the code, so pulling the sources that heap dump was taken from, decompiling something, reading git
# history and compiling a check are all part of the job, and `--tools` naming two of them cut all of that off.
# The eval restricts to `Bash,Skill` because it is scoring one number and a run that reached a second heap dump
# tool would be scored on something else; a harness is not scoring anything. **So this runs a model's shell
# commands on your machine with permissions bypassed**, which is the price of it running to the end
# unattended, and is why it is a script you invoke rather than something that runs itself.
#
# **No model is pinned.** Passing none leaves the client on whatever its own default is, which is the right
# answer for a harness that is about the surface rather than about a model — a name written down here is a name
# that goes stale the next time the default moves. `--model` is there for pinning one deliberately, which is
# what the eval does and says why.
#
# What does not change with any of this is `~/.claude/CLAUDE.md`, which loads whatever `CLAUDE_CONFIG_DIR`
# says — measured by probe. `--bare` is the only switch that suppresses it, at the price of skill
# auto-discovery and the `Skill`, `Grep` and `Glob` tools, which is too much to pay here. So a run whose answer
# looks like it came from somewhere other than the surface is worth reading that file before trusting.
run_the_agent() {
  local model="$1"
  cat <<END

Starting the investigation. The agent opens the window itself, so one appears shortly — tiled "$TITLE",
after the bundle it is launched from.

Its output is below as well as in $HARNESS_DIRECTORY/agent-output.txt.

$WHERE_THE_LOG_IS

$WHERE_THE_TRANSCRIPT_IS

END
  local -a model_option=()
  [[ -n "$model" ]] && model_option=(--model "$model")
  # Not `set -e`'s business: a client that exits non-zero — a refusal it gave up on, a crash, a model that ran
  # out of turns — is a run to read rather than a script to abandon, and everything it did is already in the
  # Shark Dive log and in its own output.
  if ! (
    cd "$HARNESS_DIRECTORY"
    export CLAUDE_CONFIG_DIR="$CLIENT_CONFIG_DIRECTORY"
    export CLAUDE_SECURESTORAGE_CONFIG_DIR=
    claude \
      --print "$(cat "$HARNESS_DIRECTORY/prompt.txt")" \
      "${model_option[@]+"${model_option[@]}"}" \
      --session-id "$SESSION_ID" \
      --permission-mode bypassPermissions \
      </dev/null
  ) | tee "$HARNESS_DIRECTORY/agent-output.txt"; then
    echo
    echo "The client exited non-zero. What it managed is above, and every call it made is in $LOGS_DIRECTORY."
  fi
}

# For driving the client yourself — a particular model, an interactive session, or a second run over the staged
# directory. The isolation is in these arguments rather than in the script, so a command that drops
# `CLAUDE_CONFIG_DIR` is a run with this machine's memories in it, and one that drops the secure storage
# variable beside it is a run that is not logged in; see `run_the_agent` for what each is for.
#
# **`--session-id` is load-bearing in the same way**, and less obviously so, since a run without it works
# perfectly and simply cannot be followed: the client picks an id of its own, writes the transcript under that
# name, and the only way left to find it is to take the newest file in the directory and hope. It is the one
# argument here that is about reading the run rather than about running it, which is why it is worth saying
# that dropping it is what makes `watch-transcript.sh` useless.
#
# An interactive session is the case this is most often wanted for, and there the transcript has a second
# reader that needs none of this: **ctrl+o** toggles the client's own transcript view, which expands the tool
# calls and the reasoning the normal view folds away. A `--print` run has no such view, which is the whole
# reason the file is worth following.
print_the_command() {
  local model="$1"
  local model_line=""
  [[ -n "$model" ]] && model_line="    --model $model \\
"
  cat <<END

Throw an agent at it:

  cd $HARNESS_DIRECTORY
  CLAUDE_CONFIG_DIR=$CLIENT_CONFIG_DIRECTORY
  CLAUDE_SECURESTORAGE_CONFIG_DIR=
  claude \\
    --print "\$(cat prompt.txt)" \\
$model_line    --session-id $SESSION_ID \\
    --permission-mode bypassPermissions

This starts claude with no local context or history, the only context is the prompt, which directs
claude to investigate the heap dump with shark dive.

$WHERE_THE_LOG_IS

$WHERE_THE_TRANSCRIPT_IS

END
}

require_client() {
  if ! command -v claude >/dev/null; then
    cat >&2 <<END
There is no \`claude\` on the PATH, and it is the only client this script knows how to start.

Either put one there, or run with --print-command and drive your own: everything is staged either way, and
the argument that keeps this machine's memories out of the run is printed with it.
END
    exit 1
  fi
}

absolute_path() {
  if [[ "$1" == /* ]]; then echo "$1"; else echo "$PWD/$1"; fi
}

usage() {
  cat <<END
Usage: start-harness.sh [--print-command] [--model <name>] [heap-dump.hprof]

Builds the app, copies it into a directory of its own, and starts an agent on one heap dump. Nothing opens the
dump and nothing stages a skill — the agent is given the launcher and finds out what it takes from --help.

Either way of starting a run, watch-transcript.sh beside this follows every call it makes and everything that
comes back, as it happens. See it for what that file is, and for what it doesn't carry.

  --print-command  Stage everything and print the command instead of running it, for driving a client
                   yourself. Default is to start the investigation.
  --model          Pin the client to a model. Default is to pass none and leave it on its own default.
  heap-dump.hprof  Default: $DEFAULT_HEAP_DUMP

  SHARK_HARNESS_DIR    Where to stage, instead of a timestamped directory under ${TEMPORARY_DIRECTORY%/}.
  SHARK_HARNESS_TITLE  What to name the bundle, and so the dock tile. Default carries the timestamp.
  SHARK_HARNESS_MODEL  A model to pin, as --model does.
END
}

main "$@"
