---
name: shark-dive
description: "Use when investigating an Android or JVM heap dump (.hprof): what is leaking and why, what is holding an object, what the biggest objects are, what a process is spending its memory on. Drives Shark Dive from a shell, which reads the dump in a window a person can watch."
allowed-tools:
  - Bash
---

# Investigating a heap dump with Shark Dive

Use Shark Dive, a desktop app whose every screen and button is also a command — so you read the dump **in the
window somebody is looking at**, and what you leave behind is on their screen. Find the launcher, then ask it
what it takes:

```bash
ls -d /Applications/"Shark Dive.app" ~/Applications/"Shark Dive.app" 2>/dev/null
"/Applications/Shark Dive.app/Contents/MacOS/Shark Dive" --help
```

`--help` is every command with a line each and how to work on this surface, `--help <command>` is one command
in full, and `--leak-investigation-help` is how to solve a leak.

**Read those three rather than a file like this one.** They are text the build carries, so they describe the
commands that exist rather than the ones that existed when something was written down — which is why this
skill says no more than where to start. The space in the path has to stay quoted.
