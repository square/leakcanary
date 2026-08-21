# Shark Explorer Change Log

[Shark Explorer](shark-explorer.md) is a desktop app released on its own schedule, so it has its own
change log. The [LeakCanary change log](changelog.md) covers the libraries and never mentions this app.
See [Releasing Shark Explorer](releasing-shark-explorer.md) for how a version gets cut.

Each entry starts with a marker for the kind of change it is, the same markers the LeakCanary change log
uses, without the one for a newly recognized library leak:

| | Means |
| --- | --- |
| ⚠️ | **Breaking change**: something you relied on works differently or is gone. |
| 🔀 | **Behavior change**: the app now does something different. |
| 💥 | **Crash fix**: something used to crash, and no longer does. |
| 🐛 | **Bug fix**: the app did the wrong thing, without crashing. |
| ✨ | **New**: a capability that didn't exist before. |
| 🔨 | **Improvement**: something that already worked, now works better. |

## Unreleased

* ✨ Initial release.
* ✨ Right click anything the window can take you to — a tab, a rectangle, a row, a field — and copy a
  `shark://` link to it, beside opening it in a new tab. Clicking one brings the app to the front and
  opens that place in a new tab: an object, a filtered object list, the leaks with the same groups
  unfolded. See [Link to a tab](shark-explorer.md#link-to-a-tab).
* ✨ **Notes**: every location takes a markdown note, kept between runs, and the tab strip marks the tabs
  whose location has one. A note belongs to the location rather than to the tab, so two tabs on one
  location are one note. Class names, addresses and `shark://` links written in a note become links back
  into the window, shortened to read as prose, and GitHub URLs are shortened the way GitHub shortens them.
  See [Take notes](shark-explorer.md#take-notes).
* ✨ Right click ← or → for the list of everywhere that arrow leads, so going back four moves is one
  click rather than four.
* ✨ **Verdict**: whether the object a tab is on is stuck in memory — `✗ Stuck`, `✓ Expected` or `? Unknown`
  — is the first thing the **What it is** panel says, in the same colours a chain uses, and the pencil beside
  it overrules the verdict: a reason is required, kept with it, and what you overruled is recorded beside it.
  Where a verdict you set contradicts another one, every verdict it disagrees with is listed with the reason
  it was given, and keeping yours flips them. The **Leaks** screen follows what you set, since marking an
  object as stuck makes it a leak and takes whatever it holds off the list. Kept between runs in
  `~/.shark-explorer/leak-statuses`, one file per heap dump.
  See [The verdict](shark-explorer.md#the-verdict).
* ✨ **Hand a heap dump to an agent**: the window is an MCP server too, so an agent investigates the heap dump
  you have open — the same tree, the same verdicts, the same notes — and `show` puts what it is looking at on
  your screen. What it can be held to is the point: every call has to say why it was made and lands in the
  run's log beside the reads it caused, a verdict needs a reason another reader can check exactly as yours
  does, and reporting a root cause is refused until the chain names one faulty reference. Point any MCP client
  at the installed app with `--mcp-stdio`.
  See [Hand it to an agent](shark-explorer.md#hand-it-to-an-agent).
* ✨ **The chain marks the faulty reference**: the one step going from an `Expected` object straight to a
  `Stuck` one reads `Holder.activity · faulty reference`, which is the leak itself rather than one of the
  objects it left behind, and the same reference the **Leaks** screen names that leak after. A chain whose
  two verdicts are further apart than one step carries no mark, since which reference in between is at fault
  is what isn't known — overrule a verdict in between and the mark appears.
  See [The verdict](shark-explorer.md#the-verdict).
