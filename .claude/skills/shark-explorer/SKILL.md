---
name: shark-explorer
description: "Use when investigating an Android or JVM heap dump (.hprof): what is leaking and why, what is holding an object, what the biggest objects are, what a process is spending its memory on. Drives Shark Explorer, which reads the dump in a window a person can watch, from a shell or over MCP."
allowed-tools:
  - Bash
---

# Investigating a heap dump with Shark Explorer

Shark Explorer is a desktop app that reads a heap dump, and every screen and button of it is also a tool you
can call. So you read the dump **in the window somebody is looking at**: what you look at, they can look at,
and the verdicts and notes you leave are on their screen and in files that outlive the run.

It is not only for leaks. `list_leaks` is the dump's own answer about what shouldn't be there, and
`dominator_tree` is what the memory is actually going on, which is a different question — a heap where nothing
is leaking still has a biggest object.

## Start by working out which case you are in

**Something is already open.** Ask, and the answer carries the method to follow, the file names every other
tool names a dump by, and any verdicts somebody has already reached:

```bash
"/Applications/Shark Explorer.app/Contents/MacOS/Shark Explorer" --agent open_heap_dumps \
  reason="Finding out what is already open"
```

If that says nothing is open, it opened a window for you, so the same command again lists it.

**You have a file.** A dump that came with a bug report, or one you took earlier:

```bash
… --agent open_heap_dump path=/absolute/path/bug-4821.hprof reason="The dump the report came with"
```

It answers once the dump is readable, which on a large one is a wait rather than a moment.

**You need to take one.** From a device or emulator `adb` is connected to:

```bash
… --agent list_devices reason="Finding the device"
… --agent list_devices device=emulator-5554 reason="Finding the process to dump"
… --agent dump_heap device=emulator-5554 process=com.example.app reason="Reproduced the bug, dumping now"
```

`dump_heap` collects the garbage first, writes the dump on the device, pulls it and opens it — minutes on a
large app, and one call that does not come back until it is readable. A process can only be dumped if the app
was built debuggable or the whole device build is; `list_devices` says which.

**And before investigating anything, read what has already been tried on that dump:**

```bash
… --agent agent_log reason="Finding out whether somebody has already been through this"
```

An investigation somebody already ran is either the answer or the half of the dump not worth doing again.

## The command line

```bash
"/Applications/Shark Explorer.app/Contents/MacOS/Shark Explorer" --agent <tool> name=value …
```

- `--agent-help` prints every tool, with its arguments and what each one is for. `--agent-help <tool>` prints
  one tool instead of all of them. **Read that rather than guessing at a tool**, and rather than trusting a
  list in a file like this one, which goes stale.
- **Find the launcher first** — the path above is where a `.dmg` install puts it, and the space in it has to
  stay quoted:
  ```bash
  ls -d /Applications/"Shark Explorer.app" ~/Applications/"Shark Explorer.app" 2>/dev/null
  ```
- **Every tool takes `reason`**, which is why you are making the call. It is logged beside the reads it causes
  and read afterwards by a person on the *Agent logs* screen, so write the sentence you would say to somebody
  watching over your shoulder.
- **Exit code 0** means the answer is the JSON on stdout. **2 means the call was refused**, and the refusal on
  stderr is the next thing to do, not an error to retry. **1** means nothing was there to answer it.
- **Addresses are `0x…`, exactly as the surface writes them.** Never decimal: a heap dump's addresses do not
  survive a JSON number.
- **A call is about one heap dump**, and `heapDump=<file name>` says which — needed once more than one is
  open, and the window id instead in the one case a name cannot answer, which is the same file open twice.
  The `shark://` link `show` and `conclude` answer with names the dump too, so it still opens after this run
  has ended: **put those links in your reply** rather than describing which screen to open.
- `--agent-run=<pid>` picks between several open runs. `--agent-session=<name>` says which investigation these
  calls are one of; by default one shell is one session, so what you did reads as one row of that screen
  rather than a row per call.

**Over MCP instead, if your client can be configured**, which gets the same tools with their schemas in band:

```json
{ "mcpServers": { "shark-explorer": {
  "command": "/Applications/Shark Explorer.app/Contents/MacOS/Shark Explorer",
  "args": ["--mcp-stdio"]
} } }
```

Add `--no-ui` for a machine with no screen — a build server, or a dump at the far end of an ssh session.
Everything works the same except `show`, which has nowhere to put a tab and says so; it still answers with the
link, since a link names the heap dump rather than a window.

## What to do with it

**The method comes with the tools.** `open_heap_dumps` hands back the whole of it — what a leak is, the three
zones of a chain, how a verdict spreads, and the order that finds the faulty reference. Follow that; it is
[the LeakCanary method](https://engineering.block.xyz/blog/the-leakcanary-method) as the tools enforce it, and
it does not need repeating here.

Two things about it that are easy to miss:

- **`conclude` will refuse you** until the heap dump agrees that one reference is at fault, and the refusal
  says which of the three reasons it is. That is the surface working. Go and do what it says — usually
  `set_verdict` on the object it named — rather than reporting a root cause it would not accept.
- **Isolating the reference is not the root cause.** It says where the problem is, not how it happened, so the
  method sends you to the code at the version this dump is of, and tells you how to work out which version
  that is.

**When the question isn't a leak**, the tools are the same and the order is yours. What is big is
`dominator_tree`, top down, and `describe_object` on whatever it names; what is holding one thing is
`ways_held`; what instances of a class there are, and how much they retain between them, is `find_objects`.
`show` puts any of it on the person's screen, and `take_note` writes what you found where they and the next
reader will find it.
