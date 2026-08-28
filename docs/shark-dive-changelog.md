# Shark Dive Change Log

[Shark Dive](shark-dive.md) is a desktop app released on its own schedule, so it has its own
change log. The [LeakCanary change log](changelog.md) covers the libraries and never mentions this app.
See [Releasing Shark Dive](releasing-shark-dive.md) for how a version gets cut.

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
  unfolded. A link names the **heap dump** and nothing else — `shark://bug-4821.hprof/leaks`, short enough to
  read in a sentence — so it goes on working after the window it was copied from has gone: it opens the place
  in a window that has that dump, and opens the file in a new window when none has. Where that file is never
  goes in the link: every heap dump opened is written down in `~/.shark-dive/heap-dump-paths`, the last
  200 kept, and following a link looks it up there. A link about a heap dump this machine can't find asks for
  the file, and one about a name two heap dumps share asks which of them. See
  [Link to a tab](shark-dive.md#link-to-a-tab).
* ✨ **Notes**: every location takes a markdown note, kept between runs, and the tab strip marks the tabs
  whose location has one. A note belongs to the location rather than to the tab, so two tabs on one
  location are one note. Class names, addresses and `shark://` links written in a note become links back
  into the window, shortened to read as prose, and GitHub URLs are shortened the way GitHub shortens them.
  See [Take notes](shark-dive.md#take-notes).
* ✨ Drag the line along the bottom of a note to give it more of the window or less, the same way the edges
  between the panes are dragged sideways. Per window rather than per tab, and never more than its share of
  the window however far it is dragged. See [Take notes](shark-dive.md#take-notes).
* ✨ Right click ← or → for the list of everywhere that arrow leads, so going back four moves is one
  click rather than four.
* ✨ **Verdict**: whether the object a tab is on is stuck in memory — `✗ Stuck`, `✓ Expected` or `? Unknown`
  — is the first thing the **What it is** panel says, in the same colours a chain uses, and the pencil beside
  it overrules the verdict: a reason is required, kept with it, and what you overruled is recorded beside it.
  Where a verdict you set contradicts another one, every verdict it disagrees with is listed with the reason
  it was given, and keeping yours flips them. The **Leaks** screen follows what you set, since marking an
  object as stuck makes it a leak and takes whatever it holds off the list. Kept between runs in
  `~/.shark-dive/leak-statuses`, one file per heap dump.
  See [The verdict](shark-dive.md#the-verdict).
* ✨ **Hand a heap dump to an agent**: the window is an MCP server too, so an agent investigates the heap dump
  you have open — the same tree, the same verdicts, the same notes — and `show` puts what it is looking at on
  your screen. What it can be held to is the point: every call has to say why it was made and lands in the
  run's log beside the reads it caused, a verdict needs a reason another reader can check exactly as yours
  does, and reporting a root cause is refused until the chain names one faulty reference. There is no screen
  it can't reach and no button it can't press — the treemap as a tree of retained sizes, the notes read and
  rewritten as well as added to, `Open heap dump…` for a file nobody has open, and `Take heap dump…` down to
  picking the process off a device — because a surface with less than that answers "ask your human to click
  something". Point any MCP client at the installed app with `--mcp-stdio`.
  See [Hand it to an agent](shark-dive.md#hand-it-to-an-agent).
* ✨ **An agent no longer needs a window to have been opened for it.** With nothing running, `--mcp-stdio`
  opens one — on the heap dump its command line named, if it named one — and leaves it open for whoever comes
  back to it. And with `--no-ui`, the tools are served from that process with no window anywhere, for a build
  server or a heap dump at the end of an ssh session: everything works the same except `show`, which says it
  has nowhere to put a tab rather than answering that it showed you something — and hands back the link all
  the same, since a link names the heap dump, so whoever reads the answer can open the place nobody saw.
  Notes and verdicts were never on the screen, so a heap dump investigated with no window opens in one later
  with all of it on.
  See [Hand it to an agent](shark-dive.md#hand-it-to-an-agent).
* ✨ **The method sends an agent to the code, at the version the heap dump is of.** Isolating the reference
  says where the problem is and not how it happened, so the method that comes with the tools also says how to
  work out which framework, app and library versions this dump is of — and what to ask for rather than guess,
  since an app's own version number never reaches the heap.
  See [Hand it to an agent](shark-dive.md#hand-it-to-an-agent).
* ✨ **An agent answers with links into the window.** `show` and `conclude` hand back the `shark://` link to
  what they put on screen, and the method the tools come with tells an agent to put those links in its reply —
  so a sentence in a chat window, a pull request comment or a bug report carries a way into the heap dump
  rather than instructions for finding the object again by hand.
  See [Hand it to an agent](shark-dive.md#hand-it-to-an-agent).
* ✨ **Agent logs**: every agent that has connected to the app is a row on a screen of its own, and opening
  one is everything that agent did — what each call did, which object it did it to, and the sentence it gave
  for making it, with the refusals in red. A row leads where the call went, so reading what an agent did and
  going to look at it are one move — including a row about a heap dump this window hasn't got, which opens
  that dump, and whose link is there to copy like every other. Kept in `~/.shark-dive/agents/sessions`,
  one file per session and the newest hundred kept, so a session outlives the window it was worked in.
  See [Hand it to an agent](shark-dive.md#hand-it-to-an-agent).
* ✨ **The chain marks the faulty reference**: the one step going from an `Expected` object straight to a
  `Stuck` one reads `Holder.activity · faulty reference`, which is the leak itself rather than one of the
  objects it left behind, and the same reference the **Leaks** screen names that leak after. A chain whose
  two verdicts are further apart than one step carries no mark, since which reference in between is at fault
  is what isn't known — overrule a verdict in between and the mark appears. Once there is a mark, a
  `Leak solved` line above **What holds it** names that reference where the eye starts, rather than leaving
  it to be found tens of steps down a chain, and an agent reading the chain is answered with the same name.
  See [The verdict](shark-dive.md#the-verdict).
* ✨ **A `?` beside the labels that take more than a label to know.** Hovering it says what the label means in
  one sentence; clicking it opens the [reference](shark-dive-reference.md) as a tab of the window, and every
  other page of it is listed under the one being read. The app ships the text rather than opening a browser,
  so a release explains itself with the pages it was built with, and the sentence under the `?` is the page's
  own first sentence. The `?` never fades and is the same for everybody, however many heap dumps in.
