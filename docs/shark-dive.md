# Shark Dive

Shark Dive is a desktop app that opens an Android heap dump and shows **what is holding its memory**.

LeakCanary answers "which of these objects should have been garbage collected". Shark Dive answers the
question next to it: this app is using 200 MB — *what is all of it, and what is keeping it around?* It runs
on [Shark](shark.md), so there is nothing to add to your app: any `.hprof` from any debuggable app will do.

What it draws is the heap dump's **dominator tree**, as a treemap or as rings. Every object is drawn inside
the object that is keeping it alive, so a block's area is the memory that would come back if the block
around it let go, and the nesting is the chain of responsibility.

!!! info "Shark Dive is an alpha"
    It is released separately from LeakCanary, on its own version line, and every release so far is marked
    as a prerelease.

## Install it

Download from the
[Shark Dive releases](https://github.com/square/leakcanary/releases?q=shark-dive&expanded=true):

| Platform | Download | Signed |
| --- | --- | --- |
| macOS, Apple Silicon | `Shark-Dive-<version>-macos-arm64.dmg` | Yes |
| macOS, Intel | `Shark-Dive-<version>-macos-x64.dmg` | Yes |
| Windows | `Shark-Dive-<version>-windows-x64.msi` | No |
| Linux | `Shark-Dive-<version>-linux-x64.deb` | No |

The macOS builds are signed and notarized by Block, so they open like any other app. The Windows and Linux
installers are not signed, so they warn — on Windows, SmartScreen calls the publisher unknown and the
installer runs from **More info → Run anyway**.

Nothing has to be installed alongside it: the app ships with the Java runtime it needs, and it tells you
when a newer release exists.

## Open a heap dump

**Open heap dump…** takes any Android `.hprof` file. **Take heap dump…** dumps one off a connected device,
through the `adb` of your Android SDK: pick a device, then a process. Only an app built debuggable can be
dumped, unless the device's whole build is debuggable (`ro.debuggable=1`, which is what a `userdebug`
emulator image is), where every process on it can be.

Each heap dump opens in a window of its own, so two of them stay on screen side by side. Opening one takes
a few seconds.

## Read the map

* **A rectangle is an object, and its area is what that object retains**: its own bytes, plus everything it
  alone is keeping alive. A rectangle drawn inside another is retained by it.
* **Point at one and the window describes it; click it and the tab goes to it**, redrawing that object's
  contents across the whole view. So reading the map is a sweep of the mouse, and a rectangle a pixel wide
  at the top of the tree is a full picture two clicks down.
* **The pane on the left is the answer to "what holds this"**: the shortest chain from a garbage collection
  root down to the object, one row per object, naming the field that holds the next. Every row is
  clickable, which is also the way back out.
* **Everything naming an object is a way to it**, and the same three clicks work everywhere: click to go
  there in this tab, middle click or ⌘/Ctrl click to open it in a tab behind this one, right click for a
  menu offering both that and a link to the object. The buttons along the top always open a new tab, and
  every tab can be closed — including the last, which leaves the heap dump open and the buttons ready.
* **← and → walk the tab's own history**, so a tab you wandered off in is one click from where it was.
  **Right click either arrow** for the list of everywhere it leads: picking the fourth entry is one click
  rather than four.
* **The three panes are resizable, and each folds away to a button.** A chain thirty steps long or a
  details panel of forty fields is sometimes worth the whole window.
* **Shape** switches between rectangles and rings. A ring has room for fewer children, so it groups the
  small ones sooner: better for the shape of the tree, worse for exact sizes.
* **Colour** splits the dump by how firmly things are held. Unchecking a strength greys it out rather than
  hiding it, which is what makes the little there is of everything else stand out.
* Bitmaps are drawn as their own pictures where the dump has the pixels. Android keeps them outside the
  Java heap from API 26 to 34, and for those the app offers to fetch them off the device the dump came from.
* **Object list** is the whole dump as a searchable list, and **Starred** keeps the objects you want to
  come back to.
* **The verdict on the object a tab is on is the first thing "What it is" says** — `Stuck`, `Expected` or
  `Unknown` — and you can overrule it, see [The verdict](#the-verdict).
* Every location takes a **note**, in markdown, kept between runs — see [Take notes](#take-notes).
* **A `?` follows the labels that take more than a label to know.** Hover it for one sentence, click it to
  read the rest as a tab of the window — it's the [reference](shark-dive-reference.md), shipped with the app.

## Link to a tab

**Right click and pick "Copy link"** to get a `shark://` URL of wherever that is. Paste it anywhere links
are clickable — a chat message, an issue, a note to yourself — and clicking it brings Shark Dive to
the front and opens that place in a new tab.

It sits beside "open in a new tab" everywhere that offers one: a tab, a button along the top, a rectangle
of the map, a row of the object list or of the leaks, a step of a chain, a field of the details panel, a
starred object. Wherever the window will take you somewhere, it will also hand you the link to it.

```
shark://bug-4821.hprof/object?id=0x7f2a4b18
shark://bug-4821.hprof/objects?query=Bitmap&exact=true
shark://bug-4821.hprof/leaks
```

Anywhere a tab can be is a link: an object, the object list with its search and filters filled in, the
leaks with the same groups unfolded, the starred objects. So "look at this" is a URL rather than a
paragraph of directions, which is also how a tool or an agent that has read your heap dump can point you
straight at what it found.

The part after `shark://` is **the heap dump**, and it is the whole of what a link says about which one,
because every place a link can name belongs to the dump rather than to the window showing it. So a link goes
on working: following one opens that place in a window that has the dump open, and opens the file in a new
window when none has — the run it was copied from can be long gone. A link never replaces what you were
reading: it always opens a tab of its own. What you copy is what you can type, and nothing more.

**Where the file is doesn't travel in the link.** Every heap dump this app opens is written down in
`~/.shark-dive/heap-dump-paths`, the last 200 kept, so following a link is a lookup rather than a path
pasted into a URL — which is what keeps a link short enough to read in a sentence.

Two links can't be sorted out on their own, and both ask rather than guess:

* **A heap dump this machine can't find**, which is a link from somebody else's machine, or about a dump
  deleted or moved since the link was written. A window opens saying which, and asks for the file. You can
  also put the path in the link yourself, as `&dump=/Users/you/dumps/bug-4821.hprof`.
* **Two heap dumps of one name**, which is one app dumped on two devices, or a dump copied somewhere. The
  places they are in are offered, and the one you pick is where the link goes. Uncommon: a dump this app
  takes is named after the process, its pid and a random number, and LeakCanary names its own after the
  time of the dump.

Links reach the app from an installed build — the installer is what tells the OS that `shark://` is this
app's. A copy run from source can still be linked to from another one, but the OS won't start it for a
link.

## Take notes

**✎ Add Note**, under the title saying where the tab is, starts a markdown note about **that location** —
an object, the object list, the leaks, the starred objects, or the heap dump as a whole on the tab a window
opens with. Type into the box, press **Save**, and the note is drawn where the box was; **Cancel** throws what
you typed away. It is there again the next time you are at that location, and the tab strip puts a ✎ on the
tabs whose location has one.

A note belongs to the **location**, not to the tab: two tabs on one location are one note, and so are two
windows on one heap dump. Which is why writing about an object you got to twice adds to what you already wrote
rather than starting again beside it, and why the same is true of the note you left there last week. To throw a
note away, open it, delete the text and **Save**: an empty note is no note, and the ✎ comes off the tab.

The note appears under that row and above the panes, because it is about the whole of what the tab is showing.
Where nobody has written anything there is nothing there at all — only the button, which goes away once there
is a note, since the note carries its own **✎ Edit**.

**Drag the line along its bottom edge** to give the note more of the window or less, the same way the edges
between the panes are dragged sideways. A long note scrolls rather than pushing the heap dump off the screen,
and how tall it has been dragged to is per window rather than per tab, so it stays where you put it as you
move around. It never takes more than its share of the window, however far it is dragged: the edge has to stay
somewhere you can reach it.

The notes live in `~/.shark-dive/notes`, one directory per heap dump and one `.md` file per tab, so a
note can be opened in an editor, pasted into an issue, or read by an agent without going through this app.

You type plain markdown — nothing is reformatted as you go — and once it is saved, **anything in it that
this heap dump recognises becomes a way back into the window**:

| What you write | What it reads as | What clicking it does |
| --- | --- | --- |
| `com.example.MyApp$Cache` | `MyApp$Cache` | Opens that class in a new tab |
| `0x7f2a4b18` | `Cache instance (0x7f2a4b18)` | Opens that object in a new tab |
| `shark://bug-4821.hprof/leaks` | `Leaks` | Follows the link, like clicking it anywhere else |
| `https://github.com/square/leakcanary/issues/2841` | `square/leakcanary#2841` | Opens it in your browser |

A name or an address this dump has nothing for is left exactly as you typed it: a class this heap dump has
never heard of is a class you wrote about, not a broken link. Which is also how the notes stay readable
outside the app — nothing is rewritten on disk, only on screen.

Headings, lists, quotes, `code`, **bold**, *italic*, fenced code blocks and `[links](https://example.com)`
all work, and **one line is one line**: no blank line needed between two of them, the way a comment box on
GitHub reads markdown. Nothing inside a fenced code block is linked or shortened.

A location is *where* you are rather than how it is arranged, so searching in the object list, unfolding a leak
or resizing the window stays on the same note rather than starting a new one.

A `shark://` link written into a note keeps working, since it names the heap dump rather than the window it
was copied from — see above. So a note that links to three places an investigation turned on still leads to
all three next week, in whatever window has that dump open by then.

## The verdict

At the top of **What it is**, under the object's name, is the **Verdict** on it — `✗ Stuck`, `✓ Expected`, or
a quiet `? Unknown` — with the reason under it, in the same colours the chain on the left uses. Most objects
in a heap dump are `Unknown`, which is why that one is drawn small: the two that mean something are the ones
worth seeing across the room.

`Stuck` says the object should be gone and something is holding it. `Expected` says its being in memory is
legitimate at this point in the app's life. It is the same answer a LeakCanary leak trace prints as
`Leaking: YES`, `NO` and `UNKNOWN`, in words that stop short of calling the object the leak — because
**the leak is the faulty reference**, the one that should have been cleared, and everything under it is stuck
by that single mistake. An object nothing reaches any more is `Stuck` as well: it was expected to be gone,
and only the garbage collector not having run keeps it here. The verdict means the same thing everywhere.

The reason is the rest of the answer, because half of these are about another object: an activity is red
because its own `mDestroyed` is true, and the view under it is red because the activity is. `Activity↑ is
stuck` is the chain saying so.

**The chain marks the faulty reference itself**: `Holder.activity · faulty reference`, in bold red, on the one
step that goes from an `Expected` object straight to a `Stuck` one. It is the one line of a chain that says
where to go and change code — the shades on the objects are what the leak left behind, this is the leak — and
it is the same reference the Leaks screen names that leak after, so a row there and the chain you open from it
name one thing.

**And when it has one, `Leak solved` says so above the chain**, with the reference under it and nothing else:

```
Leak solved
Holder.activity
```

Because a real chain is tens of steps and **What holds it** is scrolled to the last of them, so a mark
somewhere in the middle is an answer you have to go looking for. The name is the one to go and grep for, and
it is the same string the Leaks screen, a note written by `conclude`, and an agent's `faultyReference` all
use.

**A chain with no such step carries no mark**, which is deliberate: what would be marked would be a guess
drawn as an answer. With objects nothing knows either way about between the two verdicts, the fault is at one
of those steps and nothing on the chain says which. With nothing `Expected` above the stuck object at all,
what holds it may be something that should have let go of it too, so the fault can be further up than the
chain reaches. Overruling a verdict is what closes either gap: say what you know about one object in between,
and the mark appears on the step that leaves.

**The pencil beside it** overrules the verdict. Pick one of the three, type why, and **Set the verdict**:

* **Your answer wins**, whatever the inspectors said. Overruling is the point — an inspector reads a field,
  you read the code, and a cache that is meant to hold what it holds is not something a field can say.
* **The reason is required.** A verdict with no reason is one nobody who reads your heap dump next — a
  colleague, an agent, you in a month — can check, and one of those makes every other verdict in it worth
  less. What you overruled is kept beside your reason rather than thrown away.
* **It reads as yours**, wherever it appears: `set by hand — the cache is bounded, this is fine`, in the
  panel and on every chain that runs through the object.
* **Everything a stuck object holds is stuck too, and everything holding an expected one is expected too**,
  so a verdict you set changes what the objects around it read as. Which is why setting one is usually enough
  to make a whole chain make sense.
* **The pencil again** on an object you have already decided about, and **Take it off** to hand it back to
  the heap dump.

Because a verdict propagates along the chain, two of them can contradict each other: an object marked as
stuck, holding one marked as expected, cannot both be read off the chain between them. When what you are
setting does that, **the window lists every verdict it disagrees with before writing anything** — what the
object is, which side of yours it is on, the reason it was given, and what it would become. **Keep this and
flip those** keeps yours and sets them to the opposite verdict, with what they said kept as part of the new
reason; **Undo** leaves the heap dump exactly as it was. Nothing is written until you pick one.

The verdicts live in `~/.shark-dive/leak-statuses`, one tab separated file per heap dump, with the columns
named at the top — so they can be read, edited, diffed or pasted into an issue without this app, and they are
there again the next time you open that dump.

**The Leaks screen follows what you set**, because a verdict changes which objects are leaks and not only how
one of them reads: marking something as stuck makes it a leak, and whatever it holds stops being one — it is
only still in memory because of the object you named, and that is the thing to fix. Marking a leak as
expected takes it off the list. The one thing this costs is that a leak's fingerprint matches the one
LeakCanary reports only while nothing has been set by hand, since the fingerprint is the stretch of chain your
verdict has just moved.

## Hand it to an agent

!!! tip "Never dive alone"
    Take your agent with you.

The window is also an **[MCP](https://modelcontextprotocol.io) server**, so an agent — Claude Code, Cursor,
whatever you use — investigates *the heap dump you have open* rather than one of its own. It reads the same
tree, sets verdicts you watch appear, puts what it is looking at on your screen, and writes what it concluded
into the notes where you and the next reader will find it.

Point your client at the app itself:

```json
{
  "mcpServers": {
    "shark-dive": {
      "command": "/Applications/Shark Dive.app/Contents/MacOS/Shark Dive",
      "args": ["--mcp-stdio"]
    }
  }
}
```

That is the app's own launcher, and `--mcp-stdio` makes this copy of it a pipe to the window already open
rather than a second window. Nothing else to install and no port to configure: it talks to the run that
started most recently, says which one that was, and takes `--agent-run=<pid>` when several Shark Dive windows are
open.

**A window open before you start is not a requirement.** If nothing is running, this opens one — and if the
command line named a heap dump, that window opens it, so the same configuration works whether or not you got
there first:

```json
"args": ["--mcp-stdio", "--title=For an agent", "/Users/you/dumps/bug-4821.hprof"]
```

The window it opens outlives the agent's session, which is the point: whatever it concluded is on the tabs it
left open when you come back to it.

**And there is a case with no screen at all** — a build server, a heap dump on the far end of an ssh session,
or something driving an agent with nobody watching. Add `--no-ui` and the tools are served from that process
instead of piped to a window:

```json
"args": ["--mcp-stdio", "--no-ui", "/var/dumps/bug-4821.hprof"]
```

Everything works the same except `show`, which has nowhere to put a tab and says so rather than answering that
it showed you something. It still hands back the `shark://` link, which names the heap dump: nobody saw the
place, and the link opens it for the next reader on the machine the dump is on. Nothing else changes, because **notes and verdicts
were never on the screen** — they are files
beside the heap dump, so a dump investigated over ssh today opens in a window tomorrow with the verdicts, the
reasons and the conclusion already on it.

### Or from a shell, with nothing configured

The same tools are a command away, for an agent whose client speaks no MCP and for one that has a terminal and
hasn't been set up with anything:

```bash
"/Applications/Shark Dive.app/Contents/MacOS/Shark Dive" --agent-help
"/Applications/Shark Dive.app/Contents/MacOS/Shark Dive" \
  --agent list_leaks reason="Starting from what the dump says about itself"
```

`--agent-help` prints every tool with its arguments; `--agent-help <tool>` prints one of them. A call goes to
the window that has the heap dump open — the same socket the MCP pipe uses — or opens one when nothing is
running. It exits 0 with the answer as JSON on stdout, **2 when the call was refused**, with the refusal on
stderr where a script can read it, and 1 when there was nothing to answer it.

Calls from one shell are one session, so what an agent did reads as one row of the *Agent logs* screen rather
than a row per command. `--agent-session=<name>` says so explicitly, and `--agent-run=<pid>` picks between
several Shark Dive windows.

### The skill

An agent still has to be told that any of this exists. This repository carries a
[skill](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview) that does it — the three
ways in, the command line, and what to do with an answer — and every client that reads the standard (Claude
Code, Codex, Cursor, Gemini CLI) picks it up from the same directory:

```bash
git clone git@github.com:square/leakcanary.git
cp -R leakcanary/.claude/skills/shark-dive ~/.claude/skills/
```

Then "there's a heap dump in ~/Downloads, what's using all the memory" is enough: the skill is what turns that
into opening the dump in a window you can watch.

Then ask for what you actually want. This is the whole prompt the session below was given:

> A heap dump is open in Shark Dive, which you can reach through its MCP tools. Something in it is
> leaking. Find the root cause.

**The method comes with the tools**, so it doesn't have to come from you. The handshake hands over what a
leak is — one bad reference, the three zones of a chain, the rules that spread a verdict up and down it — and
the order that finds it, which is [the LeakCanary
method](https://engineering.block.xyz/blog/the-leakcanary-method) as the tools enforce it.

**Including the part that isn't in the heap dump at all.** Isolating the reference says *where* the problem
is, not how it happened, and stopping there is the most common way an investigation fails — so the method
sends an agent to the code, at the version this dump is of, and tells it how to work out which version that
is: `android.os.Build$VERSION.SDK_INT` for the framework, the app's `ApplicationInfo` for its package, its
APK path and its target SDK, the build file or the APK for a library's version, and a decompiler when there
is no source to read. What it can't work out — the app's own version number is usually absent, since
`BuildConfig` constants never reach the heap — it is told to ask you for rather than guess.

**Everything the window can do, it can do** — there is no screen an agent can't reach and no button it can't
press, because a surface with less than that is one whose answer is "ask your human to click something":

| Tool | What it is |
| --- | --- |
| `open_heap_dumps` | Every heap dump open, by the file name the other tools take, with the method to follow. |
| `list_leaks` | The **Leaks** screen: what this heap dump says shouldn't be there. |
| `agent_log` | The **Agent logs** screen: what has already been tried on this dump, and what it came to. |
| `chain_from_gc_root` | One chain, every step with its labels and its verdict. |
| `describe_object` | What an object is: its class, fields, labels, size. |
| `ways_held` | Every way an object is held, rather than the one chain — the *X ways from here* list. |
| `find_objects` | The object list, by class name. |
| `dominator_tree` | The treemap, without the pixels: where the memory has gone, a level at a time. |
| `set_verdict`, `clear_verdict` | The pencil, with the reason required the same way. |
| `read_notes`, `take_note` | The notes: where somebody has been, what they wrote, and adding to or replacing it. |
| `show` | Opens a tab in your window and brings it to the front, and answers with the `shark://` link to it. The one tool a `--no-ui` run can only half do — no tab, and the link all the same. |
| `conclude` | The root cause, and the only way to finish. |
| `open_heap_dump` | **Open heap dump…**, for a file nobody has open yet. |
| `list_devices`, `dump_heap` | **Take heap dump…**: which device, which process, and the dump itself. |

The last three are what make an agent useful when there is nothing open yet: point it at a dump a bug report
came with, or at a process on a device, and the window it lands in is one you can look over its shoulder in.
`dump_heap` takes minutes on a large app and answers once the dump can be read — the steps are in the run's
log while it works.

**And the tools refuse.** That is the part worth knowing about, because it is what an agent's confidence
cannot argue with:

* **Every call has to say why it was made.** A call with none is refused — *describe_object needs `reason`,
  and it was not given* — and so is one whose reason is blank. What that buys is the log below.
* **An argument a tool doesn't take is refused**, naming both it and the ones the tool does take. Which
  matters more than it sounds: `find_objects` given `query`, the name of the window's own search box, would
  otherwise match nothing in particular and answer with the biggest objects in the heap dump, and a list of
  the wrong objects reads exactly like an answer.
* **A verdict needs a reason another reader can check**, exactly like one you typed, and it is kept with the
  verdict in the same file as yours. A verdict that contradicts one already recorded is refused with the list
  of what it disagrees with, the same way the window asks you.
* **`conclude` is refused until the heap dump agrees that one reference is at fault** — one object above it
  recorded as `Expected`, the object below it recorded as `Stuck`, and nothing unexplained in between. Reporting
  a root cause before that gets this back:

```
Not concluded. 1 step(s) between the last EXPECTED object and the first STUCK one have no verdict, so the
fault is at one of them and the chain doesn't say which: 0x12e9ed60 java.util.ArrayList. Until the chain names
one reference, a root cause would be a guess about which of those steps is at fault. Read the objects in the
unexplained stretch with describe_object, check whether anything else holds them with ways_held, and record
what you can defend with set_verdict.
```

Nothing here judges the answer — no model is called and nothing is scored. It is the same rule the chain
draws by, held to before an answer can be written down: an agent that has narrowed a chain to three
unexplained steps cannot report a root cause, however sure it is, and what it gets instead is the three
objects to go and read.

**What it did is on the *Agent logs* screen**, one row per agent that has connected to the app. Open a row
and there is every call that agent made, in order and in words — what it did, which object it did it to, and
the sentence it gave for doing it:

```
08:23:04 ▸ Asked which heap dumps are open
          because: Seeing what there is to read before asking anything about it.
08:23:11  Listed the leaks
          because: Starting from what the heap dump already says shouldn't be here.
08:23:18  Read the chain to 0x12d368b8
          because: This is the one App leak: a MainActivity the app watched and whose mDestroyed is
          true. Reading the chain from a GC root.
08:23:27  Looked at 0x12d00c30
          because: The FutureTask in the middle of the chain: checking whether it is really running.
08:23:34  Looked for every way of holding 0x12d368b8
          because: Checking whether anything else holds the activity, or only this one chain.
```

**A row leads where the call went**, and what leads there is the thing rather than the verb: click
*0x12d368b8* on *Read the chain to 0x12d368b8* and the window opens that object, so reading what an agent did
and going to look at it are one move. A call that named nothing went somewhere all the same — *leaks* on
*Listed the leaks* is the leaks screen, and *dominator tree* on *Read the dominator tree* is the tree from its
root. The one row that leads to several places unfolds instead: *Asked which heap dumps are open* opens into
the dumps that were open, each of them a window away.

**A refused call is a row too**, in red, under the reason the agent gave for making it — and those are the
half of a session worth reading, since a refusal is where the method sent an agent back to the heap dump
rather than on to an answer:

```
08:23:45  Concluded about 0x12d00c30
          because: […]
          Refused: Not concluded. Nothing on this chain of 4 steps is STUCK, so it points at no
          reference: the rules can only name one once something below it is known not to belong. […]
```

A session is kept in `~/.shark-dive/agents/sessions`, one file per agent that connected and the newest
hundred kept, a line of JSON per call — so it outlives the window and can be read by something other than
this app. **And the reads each call cost are in the run's log**, in `~/.shark-dive/logs`, where the reason
it gave is followed by the work it caused:

```
18:19:48.035 [shark-dive-agents] An agent called chain_from_gc_root(object=0x12d368b8, window=zvphq4r3)
  because: This is the one App leak: a MainActivity the app watched and whose mDestroyed is true. Getting the
  chain from a GC root to see every reference holding it and where the faulty one might be.
18:19:48.038 [heap-dump-leak_asynctask_o.hprof] Reading the chain to 0x12d368b8, for an agent
18:19:48.043 [heap-dump-leak_asynctask_o.hprof] Read the chain to 0x12d368b8, for an agent in 4 ms
```

So an investigation is something you can follow afterwards rather than a conclusion you have to trust — which
is the other half of the point, since the path is the part a chat window throws away.

**What it concluded is in the heap dump**, not only in your terminal. The verdicts are in
`~/.shark-dive/leak-statuses` with everyone else's, and `conclude` writes a **Root cause** note on the
stuck object and opens that tab, so the answer is in the window beside the evidence and still there next week:

> ## Root cause
>
> **Faulty reference:** `MainActivity$2.this$0`
>
> […] Because it is a non-static inner class, javac gives it a synthetic `this$0` field and assigns the
> enclosing activity to it in the constructor. That field is final and written once at construction — no code
> in the app or the framework can ever clear it. […]
>
> **Not checked:** I could not read the app's source. […] The "anonymous inner class" reading rests on the
> class name `MainActivity$2`, the synthetic `this$0` field and Shark's inspector label, not on a line of
> source.

**And the link to that note comes back with the conclusion**, because the answer usually arrives somewhere
that isn't this app. `show` and `conclude` both answer with the `shark://` link to what they put on screen,
and the method tells an agent to put those links in its reply — so a sentence in your chat window, a pull
request comment or a bug report ends up carrying a way in:

> The leak is `MainActivity$2.this$0`, a non-static inner class holding the activity it was declared in:
> shark://leak_asynctask_o.hprof/object?id=0x12d368b8

Clicking it opens that object with the reasoning on its tabs — in a window that has the heap dump while one
is up, and by opening the file again once none is. So an answer worth keeping keeps working, and it is short
enough to read: it names the heap dump, and where that file is, is looked up.

An agent's verdicts are verdicts like any other: they say `set by hand` on every chain that runs through the
object, the reason is the one it gave, and the pencil takes one off if you disagree with it. Which is the
last thing this surface is for — the disagreement is about a reason you can read, not about who said it.

## Reporting a problem

Bug reports go to the [LeakCanary issue tracker](https://github.com/square/leakcanary/issues). Every run
writes a log file to `~/.shark-dive/logs`, one per run, the last 20 kept — **attach the one for the run
that went wrong**. It holds the JVM, the OS and the heap limit the app was given, every step of opening the
heap dump with how long it took, and every read of it afterwards.

## Run it from source

```bash
git clone git@github.com:square/leakcanary.git
cd leakcanary
./gradlew :shark:shark-dive:shark-dive-app:run --args="path/to/dump.hprof"
```

Needs JDK 17. The path is optional: without one, the window opens with the **Open heap dump…** button and
nothing else.
