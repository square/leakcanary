---
name: shark-dive
description: "Use when investigating an Android or JVM heap dump (.hprof): what is leaking and why, what is holding an object, what the biggest objects are, what a process is spending its memory on. Drives Shark Dive from a shell, which reads the dump in a window a person can watch."
allowed-tools:
  - Bash
---

# Investigating a heap dump with Shark Dive

Shark Dive is a desktop app that reads a heap dump, and every screen and button of it is also a command you
can call. So you read the dump **in the window somebody is looking at**: what you look at, they can look at,
and the verdicts and notes you leave are on their screen and in files that outlive the run.

It is not only for leaks. `list_leaks` is the dump's own answer about what shouldn't be there, and
`dominator_tree` is what the memory is actually going on, which is a different question — a heap where nothing
is leaking still has a biggest object.

## Start by working out which case you are in

**You were given a heap dump** — a file that came with a bug report, one you took earlier, or one somebody
already has open. Say which, and that is the whole of starting:

```bash
"/Applications/Shark Dive.app/Contents/MacOS/Shark Dive" --cli open_heap_dump \
  path=/absolute/path/bug-4821.hprof reason="The dump the report came with"
```

`path` takes the file, or the name of a dump already open — so this never opens a second copy of one somebody
is looking at, and `wasAlreadyOpen` in the answer says which happened. It answers once the dump is readable,
which on a large one is a wait rather than a moment. **Don't ask what is open first**; this is the call. The
`heapDumpKey` it answers with is what every command after it names that dump by.

**You were given nothing.** Then ask what is open, and pick:

```bash
… --cli list_heap_dumps reason="Finding out what is already open"
```

It reads nothing and waits for nothing. If it says no run of this build is open, then nothing is open anywhere
and there is no window to ask — that is the case where you need a file, so ask for the path, or take one. This
command starts no run, precisely because what it answers *is* what a run has open: the three that do are
`open_heap_dump`, `dump_heap` and `list_devices`, each of which answers the same whether the run was already
there or not.

**You need to take one.** From a device or emulator `adb` is connected to:

```bash
… --cli list_devices reason="Finding the device"
… --cli list_devices device=emulator-5554 reason="Finding the process to dump"
… --cli dump_heap device=emulator-5554 process=com.example.app reason="Reproduced the bug, dumping now"
```

`dump_heap` collects the garbage first, writes the dump on the device, pulls it and opens it — minutes on a
large app, and one call that does not come back until it is readable. A process can only be dumped if the app
was built debuggable or the whole device build is; `list_devices` says which.

**Whichever case it was, the answer says whether anybody has been here.** An untouched heap dump is the
normal case, so there is nothing to ask: `alreadyWorkedOn` is in that answer only when somebody left verdicts
or a note behind, and it says which command reads what — `read_notes` for what they found, `agent_log` for how
they got there when their conclusion looks wrong. **So don't open with `agent_log`**; a dump nobody has
touched makes it a call spent on "nobody has been here", and the call that opened the dump has already said
so.

## The command line

```bash
"/Applications/Shark Dive.app/Contents/MacOS/Shark Dive" --cli <command> name=value …
```

- **`--cli` is on every command.** Without it the same command line is a run of the app opening windows, so
  there is no leaving it off and no inferring it from a command name.
- `--help` prints every command, with a line each and the rest of what a command line takes. `--help <command>`
  prints one of them in full: what it answers, and every argument it takes. **Read that rather than guessing
  at a command**, and rather than trusting a list in a file like this one, which goes stale.
- `--investigation-help` is how to work on this surface, and `--leak-investigation-help` is how to find a
  faulty reference. Both are text this build prints with nothing open, and neither is in any answer — so an
  investigation that never asks for one never reads it.
- **Find the launcher first** — the path above is where a `.dmg` install puts it, and the space in it has to
  stay quoted:
  ```bash
  ls -d /Applications/"Shark Dive.app" ~/Applications/"Shark Dive.app" 2>/dev/null
  ```
- **Every command takes `reason`**, which is why you are making the call. It is logged beside the reads it
  causes and read afterwards by a person on the *Agent logs* screen, so write the sentence you would say to
  somebody watching over your shoulder.
- **Exit code 0** means the answer is the JSON on stdout. **2 means the call was refused**, and the refusal on
  stderr is the next thing to do, not an error to retry. **1** means nothing was there to answer it.
- **Addresses are `0x…`, exactly as the surface writes them.** Never decimal: a heap dump's addresses do not
  survive a JSON number.
- **Every command but those two is about one heap dump**, and `heapDumpKey=<key>` says which — the key
  `open_heap_dump` and `list_heap_dumps` answer with, which is the file name, and `crash.hprof#2` for a second
  dump open under that name. The `shark://` link `show` and `conclude` answer with
  names the dump too, so it still opens after this run has ended: **put those links in your reply** rather than
  describing which screen to open.
- **`--session=<name>` is the one option to pass on every call**, naming the session you are working in:
  a word a person would recognise with your own session id after it, letters and digits only —
  `--session=dive7f3c9a21`. It gathers your calls into one row of the *Agent logs* screen, and the id is what
  lets somebody reading your logs find the investigation beside them. Left off, the calls of one conversation
  are gathered by the process that issued them, which is right for a person at a shell and only sometimes
  right for you.
- `--run=<pid>` says which run to talk to. Needed when more than one is open: a command that finds two refuses
  and lists them rather than picking, since which dumps there are to read would otherwise depend on what else
  is on the machine.

**That command line is the whole surface** — there is nothing to configure, and no MCP server to point a client
at. The app needs a window somewhere, which is the point of it: `open_heap_dump` with nothing running opens
one, and whoever is at the machine can then watch what you do.

**Unless there is no screen to open one on** — a build machine, or the far end of an ssh session. Then open the
dump in a run that draws nothing, and call it exactly as above:

```bash
"/Applications/Shark Dive.app/Contents/MacOS/Shark Dive" --cli open_heap_dump --no-ui \
  path=/absolute/path/bug-4821.hprof reason="No display on this machine"
```

`--no-ui` goes with the commands that start a run — `open_heap_dump`, `dump_heap`, `list_devices` — and with no
other, because it says what kind of run to start rather than anything about a dump that is open already.
Everything works the same except `show`, which is **refused** by a run that draws nothing, since being seen is
the whole of what it does. The refusal carries the `shark://` link all the same — so put the link in your reply
and whoever has a screen opens what you were looking at, with your notes and verdicts on it.

## What to do with it

**`--leak-investigation-help` is the method**, and it needs no run and no heap dump — it is text this build
carries. Read it once, before the first chain: what a leak is, the three zones of a chain, how a verdict
spreads, and the order that finds the faulty reference. Follow it; it is
[the LeakCanary method](https://engineering.block.xyz/blog/the-leakcanary-method) as the commands enforce it,
and it does not need repeating here.

Two things about it that are easy to miss:

- **`conclude` will refuse you** until the heap dump agrees that one reference is at fault, and the refusal
  says what is in the way of that rather than only saying no. That is the surface working. Go and do what it
  says — usually `set_verdict` on the object it named — rather than reporting a root cause it would not accept.
- **Isolating the reference is not the root cause.** It says where the problem is, not how it happened, so the
  method sends you to the code at the version this dump is of, and tells you how to work out which version
  that is.

**When the question isn't a leak**, the commands are the same and the order is yours. What is big is
`dominator_tree`, top down, and `describe_object` on whatever it names; what is holding one thing is
`ways_held`; what instances of a class there are, and how much they retain between them, is `find_objects`.
`show` puts any of it on the person's screen, and `take_note` writes what you found where they and the next
reader will find it.

**When you are done with a dump you opened, close it.** `close_heap_dump` closes the window drawing it, and
closing the last one open ends the run — so a run started to investigate in goes away rather than being left
on somebody's screen. Nothing is lost: the notes and verdicts are on disk and open with the dump. Don't close
one you didn't open unless you were asked to.
