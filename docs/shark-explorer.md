# Shark Explorer

Shark Explorer is a desktop app that opens an Android heap dump and shows **what is holding its memory**.

LeakCanary answers "which of these objects should have been garbage collected". Shark Explorer answers the
question next to it: this app is using 200 MB — *what is all of it, and what is keeping it around?* It runs
on [Shark](shark.md), so there is nothing to add to your app: any `.hprof` from any debuggable app will do.

What it draws is the heap dump's **dominator tree**, as a treemap or as rings. Every object is drawn inside
the object that is keeping it alive, so a block's area is the memory that would come back if the block
around it let go, and the nesting is the chain of responsibility.

!!! info "Shark Explorer is an alpha"
    It is released separately from LeakCanary, on its own version line, and every release so far is marked
    as a prerelease.

## Install it

Download from the
[Shark Explorer releases](https://github.com/square/leakcanary/releases?q=shark-explorer&expanded=true):

| Platform | Download | Signed |
| --- | --- | --- |
| macOS, Apple Silicon | `Shark-Explorer-<version>-macos-arm64.dmg` | Yes |
| macOS, Intel | `Shark-Explorer-<version>-macos-x64.dmg` | Yes |
| Windows | `Shark-Explorer-<version>-windows-x64.msi` | No |
| Linux | `Shark-Explorer-<version>-linux-x64.deb` | No |

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

## Link to a tab

**Right click and pick "Copy link"** to get a `shark://` URL of wherever that is. Paste it anywhere links
are clickable — a chat message, an issue, a note to yourself — and clicking it brings Shark Explorer to
the front and opens that place in a new tab.

It sits beside "open in a new tab" everywhere that offers one: a tab, a button along the top, a rectangle
of the map, a row of the object list or of the leaks, a step of a chain, a field of the details panel, a
starred object. Wherever the window will take you somewhere, it will also hand you the link to it.

```
shark://vugs93jp/object?id=0x7f2a4b18
shark://vugs93jp/objects?query=Bitmap&exact=true
shark://vugs93jp/leaks
```

Anywhere a tab can be is a link: an object, the object list with its search and filters filled in, the
leaks with the same groups unfolded, the starred objects. So "look at this" is a URL rather than a
paragraph of directions, which is also how a tool or an agent that has read your heap dump can point you
straight at what it found.

The part after `shark://` is **the window, not the heap dump** — the same dump open twice is two windows,
and a link leads to the one it was copied from. Which means a link works while that window is open and
stops working once it is closed or the app is restarted; following one then opens an empty window saying
so. A link never replaces what you were reading: it always opens a tab of its own.

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

The notes live in `~/.shark-explorer/notes`, one directory per heap dump and one `.md` file per tab, so a
note can be opened in an editor, pasted into an issue, or read by an agent without going through this app.

You type plain markdown — nothing is reformatted as you go — and once it is saved, **anything in it that
this heap dump recognises becomes a way back into the window**:

| What you write | What it reads as | What clicking it does |
| --- | --- | --- |
| `com.example.MyApp$Cache` | `MyApp$Cache` | Opens that class in a new tab |
| `0x7f2a4b18` | `Cache instance (0x7f2a4b18)` | Opens that object in a new tab |
| `shark://vugs93jp/leaks` | `Leaks` | Follows the link, like clicking it anywhere else |
| `https://github.com/square/leakcanary/issues/2841` | `square/leakcanary#2841` | Opens it in your browser |

A name or an address this dump has nothing for is left exactly as you typed it: a class this heap dump has
never heard of is a class you wrote about, not a broken link. Which is also how the notes stay readable
outside the app — nothing is rewritten on disk, only on screen.

Headings, lists, quotes, `code`, **bold**, *italic*, fenced code blocks and `[links](https://example.com)`
all work, and **one line is one line**: no blank line needed between two of them, the way a comment box on
GitHub reads markdown. Nothing inside a fenced code block is linked or shortened.

A location is *where* you are rather than how it is arranged, so searching in the object list, unfolding a leak
or resizing the window stays on the same note rather than starting a new one.

Since a `shark://` link names a window, a link written into a note stops working once that window is closed
— see above. Copy one for the tab you want to come back to *while you are writing about it*, and it will
take you there for as long as that window is open.

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

The verdicts live in `~/.shark-explorer/leak-statuses`, one tab separated file per heap dump, with the columns
named at the top — so they can be read, edited, diffed or pasted into an issue without this app, and they are
there again the next time you open that dump.

**The Leaks screen follows what you set**, because a verdict changes which objects are leaks and not only how
one of them reads: marking something as stuck makes it a leak, and whatever it holds stops being one — it is
only still in memory because of the object you named, and that is the thing to fix. Marking a leak as
expected takes it off the list. The one thing this costs is that a leak's fingerprint matches the one
LeakCanary reports only while nothing has been set by hand, since the fingerprint is the stretch of chain your
verdict has just moved.

## Hand it to an agent

The window is also an **[MCP](https://modelcontextprotocol.io) server**, so an agent — Claude Code, Cursor,
whatever you use — investigates *the heap dump you have open* rather than one of its own. It reads the same
tree, sets verdicts you watch appear, puts what it is looking at on your screen, and writes what it concluded
into the notes where you and the next reader will find it.

Point your client at the app itself:

```json
{
  "mcpServers": {
    "shark-explorer": {
      "command": "/Applications/Shark Explorer.app/Contents/MacOS/Shark Explorer",
      "args": ["--mcp-stdio"]
    }
  }
}
```

That is the app's own launcher, and `--mcp-stdio` makes this copy of it a pipe to the window already open
rather than a second window. Nothing else to install and no port to configure: it talks to the run that
started most recently, says which one that was, and takes `--agent-run=<pid>` when several explorers are
open. Open a heap dump before you start — with no window there is nothing to investigate, and it says so
rather than waiting.

Then ask for what you actually want. This is the whole prompt the session below was given:

> A heap dump is open in Shark Explorer, which you can reach through its MCP tools. Something in it is
> leaking. Find the root cause.

**The method comes with the tools**, so it doesn't have to come from you. The handshake hands over what a
leak is — one bad reference, the three zones of a chain, the rules that spread a verdict up and down it — and
the order that finds it, which is [the LeakCanary
method](https://engineering.block.xyz/blog/the-leakcanary-method) as the tools enforce it.

| Tool | What it is |
| --- | --- |
| `open_heap_dumps` | Every window and what is open in it, with the method to follow. |
| `list_leaks` | The **Leaks** screen: what this heap dump says shouldn't be there. |
| `chain_from_gc_root` | One chain, every step with its labels and its verdict. |
| `describe_object` | What an object is: its class, fields, labels, size. |
| `ways_held` | Every way an object is held, rather than the one chain. |
| `find_objects` | The object list, by class name. |
| `set_verdict`, `clear_verdict` | The pencil, with the reason required the same way. |
| `take_note` | The notes, appended to. |
| `show` | Opens a tab in your window and brings it to the front. |
| `conclude` | The root cause, and the only way to finish. |

**And the tools refuse.** That is the part worth knowing about, because it is what an agent's confidence
cannot argue with:

* **Every call has to say why it was made.** A call with none is refused — *describe_object needs `reason`,
  and it was not given* — and so is one whose reason is blank. What that buys is the log below.
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

**What it did is in the log**, in `~/.shark-explorer/logs`, one line per call with the reason it gave followed
by the reads that call cost:

```
18:19:48.035 [shark-explorer-agents] An agent called chain_from_gc_root(object=0x12d368b8, window=zvphq4r3)
  because: This is the one App leak: a MainActivity the app watched and whose mDestroyed is true. Getting the
  chain from a GC root to see every reference holding it and where the faulty one might be.
18:19:48.038 [heap-dump-leak_asynctask_o.hprof] Reading the chain to 0x12d368b8, for an agent
18:19:48.043 [heap-dump-leak_asynctask_o.hprof] Read the chain to 0x12d368b8, for an agent in 4 ms
```

So an investigation is something you can follow afterwards rather than a conclusion you have to trust — which
is the other half of the point, since the path is the part a chat window throws away.

**What it concluded is in the heap dump**, not only in your terminal. The verdicts are in
`~/.shark-explorer/leak-statuses` with everyone else's, and `conclude` writes a **Root cause** note on the
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

An agent's verdicts are verdicts like any other: they say `set by hand` on every chain that runs through the
object, the reason is the one it gave, and the pencil takes one off if you disagree with it. Which is the
last thing this surface is for — the disagreement is about a reason you can read, not about who said it.

## Reporting a problem

Bug reports go to the [LeakCanary issue tracker](https://github.com/square/leakcanary/issues). Every run
writes a log file to `~/.shark-explorer/logs`, one per run, the last 20 kept — **attach the one for the run
that went wrong**. It holds the JVM, the OS and the heap limit the app was given, every step of opening the
heap dump with how long it took, and every read of it afterwards.

## Run it from source

```bash
git clone git@github.com:square/leakcanary.git
cd leakcanary
./gradlew :shark:shark-explorer:shark-explorer-app:run --args="path/to/dump.hprof"
```

Needs JDK 17. The path is optional: without one, the window opens with the **Open heap dump…** button and
nothing else.
