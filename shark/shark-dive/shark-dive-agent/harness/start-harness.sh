#!/bin/bash
#
# Throws an agent at one heap dump through Shark Dive's agent surface, in a window it opens for itself.
#
# What this is for: the tools in this module are meant to hold an investigation to a method, and whether
# they do is not a thing a unit test can answer — it takes a model that has never seen this repository,
# reading nothing but what the tools hand back. So this stages the surface and starts the investigation: a
# copy of the packaged app, the skill rewritten to point at it, a prompt that says no more than which file
# to look at, and a client with nothing of this machine to work with.
#
# **Nothing here opens the heap dump.** The agent is told where the file is, and opening it is the first
# thing it has to get right — which is the first thing a person has to get right too, and the part
# `.claude/skills/shark-dive/SKILL.md` exists to carry. A harness that handed over a dump already open
# measured every step of an investigation except the one that starts it, and left the skill's own first
# section unexercised. Same reason `harness/eval/run-eval.sh` opens nothing. See `write_prompt`.
#
# **The agent reaches the window over the command line** — `--cli <command> name=value` against the launcher —
# which is what somebody who installed the app has. There is no MCP config here and no `--mcp-config` in the
# command this runs.
#
# The packaged app rather than `./gradlew run`, for two reasons. It is what a person has installed, so the
# command the skill carries is the command they would write; and a Gradle build of any kind kills a window
# launched from source, which here would be every window an agent opened. A copy of it, named after the
# title, for two more — see `bundle_named_after_the_title` and shark/shark-dive/AGENTS.md.

set -euo pipefail

readonly REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
readonly DEFAULT_HEAP_DUMP="shark/shark-android/src/test/resources/leak_asynctask_o.hprof"
readonly APP_PATH="shark/shark-dive/shark-dive-app/build/compose/binaries/main/app/Shark Dive.app"
readonly SKILL_PATH=".claude/skills/shark-dive"
# The launcher the shipped skill names, which is where a `.dmg` install puts it. Rewritten out of the staged
# copy by `install_the_skill`, and this is the string that has to match for that to work.
readonly INSTALLED_LAUNCHER="/Applications/Shark Dive.app/Contents/MacOS/Shark Dive"
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
# Where the client keeps its own configuration, which is what keeps this machine's memories and skills out of
# the run — see `run_the_agent`.
readonly CLIENT_CONFIG_DIRECTORY="$HARNESS_DIRECTORY/claude"
readonly LOGS_DIRECTORY="${SHARK_DIVE_DIR:-$HOME/.shark-dive}/logs"
readonly MODEL="${SHARK_HARNESS_MODEL:-opus}"

main() {
  local heap_dump="" model="$MODEL" start_the_agent=true
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

  install_the_skill "$launcher"
  write_prompt "$heap_dump" >"$HARNESS_DIRECTORY/prompt.txt"

  echo "Staged in $HARNESS_DIRECTORY: the app, the skill pointed at it, and the prompt."
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

# The skill this repository ships, beside the prompt, **rewritten to name this harness's own launcher**.
#
# A session started here has to be able to find the launcher: over the command line the surface is not in its
# context, and the skill is the whole of how it gets there. But the shipped skill names the installed app and
# tells the agent to go looking in `/Applications` — and this app is in neither place, it is a copy of a build
# in a temporary directory. Left alone, the two outcomes are both wrong: an agent that finds nothing measures
# an `ls` this harness's own shape broke, and an agent that finds a real install investigates through a
# *different build* of the surface than the one that was just compiled, which is the whole point of the
# exercise gone silently. So the path is substituted and the bullet that sends it hunting is replaced.
#
# **The check afterwards is the part that matters.** A substitution against prose is a substitution that goes
# stale the next time somebody edits the skill, and a stale one here fails by handing the agent the installed
# app rather than by saying anything. So the staged copy is grepped for what should no longer be in it, and a
# hit is this script stopping with the lines in it rather than a run that measured the wrong binary.
#
# Copied rather than symlinked because a symlink out of a temporary directory into the checkout is a skill the
# client may decline to load, and because a rewritten copy is the point.
install_the_skill() {
  local launcher="$1"
  local skill="$REPO_ROOT/$SKILL_PATH"
  if [[ ! -d "$skill" ]]; then
    echo "No shark-dive skill at $skill" >&2
    exit 1
  fi
  local staged="$HARNESS_DIRECTORY/.claude/skills/shark-dive"
  mkdir -p "$HARNESS_DIRECTORY/.claude/skills"
  rm -rf "$staged"
  cp -R "$skill" "$staged"

  local file
  for file in "$staged"/*.md; do
    [[ -e "$file" ]] || continue
    # `|` as the delimiter because both paths are full of `/`, and neither can contain a `|`.
    LC_ALL=C sed -i '' "s|$INSTALLED_LAUNCHER|$launcher|g" "$file"
    # The "find the launcher" bullet and the `ls` under it, as one block: `-0777` so the fenced code block is
    # matched across lines, non-greedy so it stops at the *closing* fence, which is the first `\n  ```\n`
    # after it — the opening one is `  ```bash` and so cannot match.
    perl -0777 -i -pe 's/- \*\*Find the launcher first\*\*.*?\n  ```\n/- **The launcher is the copy this harness built**, named in the command above, and the space in it has to stay quoted. There is nothing to go looking for.\n/s' "$file"
  done

  local left
  left="$(grep -rn 'Applications' "$staged" || true)"
  if [[ -n "$left" ]]; then
    cat >&2 <<END
The staged skill still points at an installed app, so this run would measure a different build:

$left

\`install_the_skill\` rewrites $SKILL_PATH to name this harness's launcher, and the substitution it does no
longer matches what the skill says. Fix the substitution rather than this check — the check is what stopped a
run that would have looked like it worked.
END
    exit 1
  fi
}

# What the agent is asked, and it says one thing: which file to look at.
#
# **Not how to investigate, and not which tool to call.** What the agent follows has to come from the surface —
# the method arrives with whichever call opens the dump, and `--leak-investigation-help` is a call it can make —
# or what this measures is this function. Opening the dump is itself a step of the investigation and the one
# the skill is most of.
#
# **And not where the launcher is either**, which is what the rewriting in `install_the_skill` buys: the prompt
# naming it would be a prompt that works whether or not the skill was ever loaded, and whether the skill loads
# at all — from its description, against a sentence this short — is a thing worth finding out. A run that never
# invokes it is a finding about that description. The eval names the launcher in its prompt instead, and pays
# for it by not measuring that.
write_prompt() {
  local heap_dump="$1"
  echo "Investigate the heap dump at $heap_dump."
}

# The client, with nothing of this machine to work with but the heap dump.
#
# **Two tools, and they are the whole surface.** `Bash`, to run the launcher, and `Skill`, to load the one
# staged beside the prompt. Both are pre-approved, because a harness that stops on a permission prompt for
# every `--cli` call is a harness nobody watches to the end — which does mean this runs a model's shell
# commands on your machine unattended, deliberately, and is why it is a script you invoke rather than
# something that runs itself.
#
# **`CLAUDE_CONFIG_DIR` is what keeps this machine out**, and it is the half that was missing: it keys
# auto-memory, so a scratch one has no memories, and the person's own skills are not in it either — 71 are
# installed here, one of them about investigating memory leaks, and every one of them would otherwise be in
# the system prompt of a run whose whole subject is whether *this* skill carries an investigation.
#
# **`--strict-mcp-config` with no `--mcp-config` beside it** keeps every MCP server on the machine out, the
# same job it does in `run-eval.sh`. It is only about MCP, which is worth saying because the comment here used
# to credit it with the rest.
#
# **What survives all of that is `~/.claude/CLAUDE.md`**, which is loaded whatever `CLAUDE_CONFIG_DIR` says —
# measured by probe. `--bare` is the one switch that suppresses it, **and it cannot be used here**: it turns
# off skill auto-discovery along with everything else, so the skill is reachable only by typing
# `/shark-dive`. Measured both ways on 2026-10-02 with a throwaway skill whose description matched the
# prompt: without `--bare` it was discovered and followed, with `--bare` it was never loaded and the model
# improvised an answer. Since whether the description triggers is a thing this harness is for, `--bare` would
# buy isolation by deleting the measurement. So the one file stays, and a run whose answer looks like it came
# from somewhere other than the surface is worth reading that file before trusting.
run_the_agent() {
  local model="$1"
  cat <<END

Starting the investigation. The agent opens the window itself, so one appears shortly — tiled "$TITLE",
after the bundle it is launched from.

Its output is below and in $HARNESS_DIRECTORY/agent-output.txt. What it did call by call, with the reason it
gave for each and the reads each cost, is the newest file in $LOGS_DIRECTORY once a dump is open:

  tail -f "\$(ls -t $LOGS_DIRECTORY/*.log | head -1)"

That log is the point of the exercise as much as the answer is.

END
  # Not `set -e`'s business: a client that exits non-zero — a refusal it gave up on, a crash, a model that ran
  # out of turns — is a run to read rather than a script to abandon, and everything it did is already in the
  # Shark Dive log and in its own output.
  if ! (
    cd "$HARNESS_DIRECTORY"
    export CLAUDE_CONFIG_DIR="$CLIENT_CONFIG_DIRECTORY"
    claude \
      --print "$(cat "$HARNESS_DIRECTORY/prompt.txt")" \
      --model "$model" \
      --strict-mcp-config \
      --tools "Bash,Skill" \
      --allowedTools "Bash Skill" \
      </dev/null
  ) | tee "$HARNESS_DIRECTORY/agent-output.txt"; then
    echo
    echo "The client exited non-zero. What it managed is above, and every call it made is in $LOGS_DIRECTORY."
  fi
}

# For driving the client yourself — a different model, an interactive session, or a second run over the staged
# directory. The isolation is in these arguments rather than in the script, so a command that drops one of them
# is a run with this machine's memories and skills in it; see `run_the_agent` for what each is for.
print_the_command() {
  local model="$1"
  cat <<END

Throw an agent at it:

  cd $HARNESS_DIRECTORY
  CLAUDE_CONFIG_DIR=$CLIENT_CONFIG_DIRECTORY claude \\
    --print "\$(cat prompt.txt)" \\
    --model $model \\
    --strict-mcp-config \\
    --tools "Bash,Skill" \\
    --allowedTools "Bash Skill"

Started from that directory, so the skill beside the prompt is the one the session loads and the one it names
the launcher from. Nothing opens the heap dump for it: the window is the agent's to open, which is the first
thing the skill is for.

Watch what it does, in the window and in the log:

  tail -f "\$(ls -t $LOGS_DIRECTORY/*.log | head -1)"

END
}

require_client() {
  if ! command -v claude >/dev/null; then
    cat >&2 <<END
There is no \`claude\` on the PATH, and it is the only client this script knows how to start.

Either put one there, or run with --print-command and drive your own: everything is staged either way, and
the arguments that keep this machine out of the run are printed with it.
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

Builds the app, copies it and the skill into a directory of its own, and starts an agent on one heap dump.
Nothing opens the dump — the agent does that, following the skill.

  --print-command  Stage everything and print the command instead of running it, for driving a client
                   yourself. Default is to start the investigation.
  --model          What to pass the client as its model. Default: $MODEL.
  heap-dump.hprof  Default: $DEFAULT_HEAP_DUMP

  SHARK_HARNESS_DIR    Where to stage, instead of a timestamped directory under ${TEMPORARY_DIRECTORY%/}.
  SHARK_HARNESS_TITLE  What to name the bundle, and so the dock tile. Default carries the timestamp.
  SHARK_HARNESS_MODEL  The default model.
END
}

main "$@"
