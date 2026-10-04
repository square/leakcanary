#!/bin/bash
#
# Follows what the harness's agent is doing, live: every call it makes, and everything that comes back.
#
# What this is for: `start-harness.sh` shows you the agent's *answer* — the text a `--print` run prints when it
# has finished — and `~/.shark-dive/logs` shows you the half of its work that reached Shark Dive. **Neither of
# them shows a run that never arrived.** An agent that opened the app some other way and then reported an
# investigation leaves that log empty while its answer reads like a success, which is not hypothetical: a run
# measured here launched the bundle with `open -a`, made no `--cli` call at all, and described the kinds of
# leak it would have found. The transcript is where that is visible, and this is the reader for it.
#
# Reasoning is the one thing it does not carry — see the measurement further down before going looking.
#
#   ./watch-transcript.sh            follow the newest harness run in this terminal
#   ./watch-transcript.sh --html     the same thing rendered in a browser, updating as it runs
#   ./watch-transcript.sh --path     print the transcript path and exit, for piping somewhere else
#
# **The file is appended as the run goes, so following it is a plain `tail -f`.** Measured on a run of this
# harness: 17 lines two seconds in, 36 by the time the client exited eleven seconds later. It is not written
# at the end, and nothing rewrites it from the top, so a reader that starts late still sees everything before
# it and a reader that starts early blocks until there is more.
#
# **Which file it is, is knowable before the run starts, and only because the harness pins the session id.**
# A client left to pick its own writes to a name nothing can predict, so the only way to find it afterwards is
# to take the newest file in the directory and hope no other session on this machine was newer. `--session-id`
# is what `start-harness.sh` passes to close that, and `session-id.txt` beside the prompt is where it writes
# the id down for this script to read.
#
# **Don't compute the path from the harness directory — glob for the id.** The client keys the directory on
# the *physical* working directory, and on macOS `$TMPDIR` is a symlink into `/private/var`, so the harness's
# own path and the one in the transcript's directory name differ by a `/private` prefix that nothing in the
# harness ever mentions. A path built by hand is therefore right on Linux and wrong here. The id is unique, so
# `projects/*/<id>.jsonl` matches one file whatever the encoding does next.
#
# **The reasoning text is not there, and no option here brings it back.** A thinking block is recorded with its
# signature and an empty `thinking`, so what a transcript shows is *where* the agent thought and not what it
# thought. Measured three ways on a `--print` run, all empty: the transcript file, `--output-format
# stream-json`, and the `thinking_delta` events under `--include-partial-messages` — two of them arrived
# carrying zero characters, so the text is not being withheld by the file, it never arrives. Interactive
# sessions on this machine do have it (5114 blocks with text across the recent archive, against 55 without),
# which is why the file looks like it ought to carry it. So **a run started with `--print` is followable for
# every call it made and not for the reasoning between them**, and the `· thought` lines below are that gap
# rather than a bug in this script. What is worth reading instead is the `reason` on each `--cli` call, which
# the surface requires for exactly this purpose — see `AgentTools.kt`.

set -euo pipefail

readonly TEMPORARY_DIRECTORY="${TMPDIR:-/tmp}"
readonly HARNESS_PARENT="${TEMPORARY_DIRECTORY%/}/shark-dive-harness"
# Long enough to cover being started in a second terminal while the first is still in jlink, which is about a
# minute on a cold build, plus a client starting up. A wait that ends is better than one that doesn't: the
# usual reason nothing appears is that the run being waited for is not the run that was started.
readonly WAIT_SECONDS=600

main() {
  local harness_directory="" mode=follow
  while (($#)); do
    case "$1" in
      --html) mode=html; shift ;;
      --path) mode=path; shift ;;
      --help | -h) usage; exit 0 ;;
      -*) echo "Unknown option $1" >&2; usage >&2; exit 1 ;;
      *) harness_directory="$1"; shift ;;
    esac
  done

  harness_directory="$(resolve_harness_directory "$harness_directory")"
  local session_id
  session_id="$(session_id_of "$harness_directory")"

  if [[ "$mode" == html ]]; then
    render_in_a_browser "$harness_directory"
    return
  fi

  local transcript
  transcript="$(wait_for_the_transcript "$harness_directory" "$session_id")"
  if [[ "$mode" == path ]]; then
    echo "$transcript"
    return
  fi
  follow "$transcript"
}

# The run to read, which defaults to the newest because the question is nearly always about the one just
# started. Named explicitly when it isn't: harness directories are never deleted — see `start-harness.sh` on
# why — so every run of the day is still here to be pointed at.
resolve_harness_directory() {
  local given="$1"
  if [[ -n "$given" ]]; then
    if [[ ! -d "$given" ]]; then
      echo "No harness directory at $given" >&2
      exit 1
    fi
    echo "$given"
    return
  fi
  local newest
  newest="$(ls -td "$HARNESS_PARENT"/*/ 2>/dev/null | head -1 || true)"
  if [[ -z "$newest" ]]; then
    cat >&2 <<END
No harness run under $HARNESS_PARENT to follow.

Start one with start-harness.sh, or name a directory to read — a run staged somewhere else with
SHARK_HARNESS_DIR is not under there to be found.
END
    exit 1
  fi
  echo "${newest%/}"
}

# Empty when the run was staged before `start-harness.sh` wrote one down, which `wait_for_the_transcript`
# takes as "the newest in this run's own configuration directory" rather than as an error — see there.
session_id_of() {
  local file="$1/session-id.txt"
  [[ -f "$file" ]] && tr -d '[:space:]' <"$file"
  return 0
}

# Waits rather than failing, because the two terminals are started in either order: a person who runs this
# first gets a line saying what it is waiting for, and the run appears under it. See the file header for why
# this globs for the id rather than building the path from the harness directory.
#
# **With no id, the newest transcript in this run's configuration directory is the answer**, and it is a sound
# one rather than a guess: that directory is staged per run and the client is isolated to it, so the only
# sessions under it are sessions of this run. The id is what tells *those* apart, which matters for the one
# case `print_the_command` invites — a second client run over an already staged directory — and for nothing
# else. So a run from before the id was written down is still followable, and says which rule it used.
wait_for_the_transcript() {
  local harness_directory="$1" session_id="$2"
  local projects="$harness_directory/claude/projects"
  local wanted="${session_id:-the newest session}"
  local found
  for ((waited = 0; waited < WAIT_SECONDS; waited++)); do
    if [[ -n "$session_id" ]]; then
      found="$(find "$projects" -name "$session_id.jsonl" 2>/dev/null | head -1 || true)"
    else
      # Two levels because that is the shape — projects/<the working directory>/<the session>.jsonl — and a
      # glob rather than `find | xargs ls -t`, BSD xargs having no `-r` to keep an empty match from running.
      found="$(ls -t "$projects"/*/*.jsonl 2>/dev/null | head -1 || true)"
    fi
    if [[ -n "$found" ]]; then
      [[ -z "$session_id" ]] &&
        echo "No session-id.txt in $harness_directory, so this is the newest transcript it has." >&2
      echo "$found"
      return
    fi
    if ((waited == 0)); then
      echo "Waiting for $wanted to start writing under $projects." >&2
    fi
    sleep 1
  done
  cat >&2 <<END
$wanted wrote nothing under $projects in ${WAIT_SECONDS}s.

A client writes its first line within a second or two of starting, so what this usually means is that the run
never started — the build failed, or start-harness.sh was given --print-command and the command it printed has
not been run yet.
END
  exit 1
}

# One line per thing the agent did, in the order it did them, with the time so that a step here can be lined
# up against the Shark Dive log of the same run.
#
# **`tail` from the first line rather than the last**, because a run is read from its beginning: the step that
# sent an investigation wrong is usually the first one, and -f alone would start after it.
#
# **Lines are truncated and that is the trade this view makes.** A transcript carries whole file reads and
# whole command outputs, so printing them in full is a terminal in which the calls can't be seen at all — which
# is the thing this is for. `--html` is the other half: nothing truncated, and a browser to scroll it in.
follow() {
  local transcript="$1"
  cat >&2 <<END
Following $transcript

  →  a call        ←  what came back        ·  a thought, whose text a --print run does not record

END
  # `-R` with `fromjson?` rather than reading JSON directly, so that the last line of a file being written to
  # right now — which is torn about as often as not — is skipped and the next one read, instead of ending the
  # stream on a parse error at the one moment the stream is worth having.
  tail -n +1 -f "$transcript" | jq -R -r --unbuffered "$(formatter)"
}

formatter() {
  cat <<'JQ'
def oneline($n): tostring | gsub("[\\r\\n\\t]+"; " ") | gsub("  +"; " ")
  | if (length > $n) then (.[:$n] + "…") else . end;
def body: if type == "array" then (map(.text // .content // "") | join(" ")) else tostring end;
fromjson? // empty
| select(.type == "assistant" or .type == "user")
| (.timestamp // "" | split("T") | last | split(".") | first) as $when
| .message.content as $content
| (if ($content | type) == "array" then $content[] else empty end)
| if .type == "thinking" then
    # Empty for every `--print` run, which is what the header measures; printed as a bare marker rather than
    # as an apology repeated once a block, with the explanation said once by `follow` instead. Non-empty is
    # still handled, since an interactive client over this same staged directory does record it.
    (.thinking | oneline(220)) as $thought
    | "\($when)  · \(if $thought == "" then "thought" else $thought end)"
  elif .type == "text" then "\($when)  \(.text | oneline(220))"
  elif .type == "tool_use" then
    # The description before the command for Bash, which on this harness is very nearly every call: an agent
    # reaching Shark Dive writes the launcher's whole path into a shell variable first, so a line showing the
    # command verbatim spends its width on the same 90 characters every time and truncates away the part that
    # differs. The description is the model's own summary of the call and is one short line by construction.
    (if .name == "Bash" then "\(.input.description // "") · \(.input.command // "")"
     else (.input | tojson) end) as $call
    | "\($when) → \(.name) \($call | oneline(220))"
  elif .type == "tool_result" then "\($when) ← \(.content | body | oneline(220))"
  else empty end
JQ
}

# The browser half, which is `claude-code-log` pointed at this run's configuration directory rather than at
# the person's own `~/.claude`. That is the whole of what keeps a harness run readable on its own: the client
# is already isolated to `$HARNESS_DIRECTORY/claude` — see `run_the_agent` in start-harness.sh — so the
# projects directory under it holds this run's transcript and nothing else of theirs.
#
# `--watch` is what makes it live, re-rendering the session as lines are appended; the page is reloaded by
# hand, since nothing pushes. `--port 0` because several harness runs being read at once is the normal case
# here, and a fixed port makes the second one fail.
render_in_a_browser() {
  local harness_directory="$1"
  local projects="$harness_directory/claude/projects"
  if ! command -v claude-code-log >/dev/null; then
    cat >&2 <<END
There is no \`claude-code-log\` on the PATH, and it is what renders a transcript as a page.

  uv tool install claude-code-log

Or follow the run in this terminal instead, which needs nothing but jq:

  $0 $harness_directory
END
    exit 1
  fi
  mkdir -p "$projects"
  echo "Serving $projects. The page is reloaded by hand; --watch re-renders behind it." >&2
  claude-code-log serve --projects-dir "$projects" --watch --open-browser --port 0
}

usage() {
  cat <<END
Usage: watch-transcript.sh [--html | --path] [harness-directory]

Follows a harness run's transcript — every call it makes, and everything that comes back — as it is written.
Defaults to the newest run under $HARNESS_PARENT.

  --html  Render it as a page that updates as the run goes, instead of following it in this terminal.
          Nothing is truncated there, unlike here. Needs claude-code-log.
  --path  Print the transcript's path and exit, having waited for it to appear.

A thinking block is recorded with no text in a --print run, so this shows where the agent thought and not
what it thought. The header has that measured, and what to read instead.
END
}

main "$@"
