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

Dumping a heap freezes the app for a moment and pulls tens of megabytes over `adb`.

**Or open a heap dump from your file manager**: Shark Dive is installed as an app that opens `.hprof` files, so
double clicking one opens it here, as does *Open with → Shark Dive*. A dump that is already open comes to the
front instead of opening twice. If another profiler is installed it may be the one a double click goes to, since
`.hprof` is a type several apps claim and the system picks one — on macOS, ⌘I on a heap dump and *Change All*
under *Open with* makes it Shark Dive.

**A bitmap keeps its pixels outside the Java heap from API 26**, so a heap dump carries them only when it
was taken with `am dumpheap -b png`, which needs Android 15. On an older device the app offers to fetch
them instead: that attaches a debugger and has the app compress every bitmap, suspended throughout —
seconds of fixed cost, plus a fraction of a second per bitmap. It's the same offer as **Bitmaps from the
live process**, which fetches them for a heap dump already open, as long as the process that wrote it is
still running.

Each heap dump opens in a window of its own, so two of them stay on screen side by side. Opening one takes
a few seconds.

## Read the map

* **A rectangle is an object, and its area is what that object retains**: its own bytes, plus everything it
  alone is keeping alive. A rectangle drawn inside another is retained by it.
* **Point at one and the window describes it; click it and the tab goes to it**, redrawing that object's
  contents across the whole view. So reading the map is a sweep of the mouse, and a rectangle a pixel wide
  at the top of the tree is a full picture two clicks down.
* **The pane on the left is the answer to "what holds this"**: the shortest path from a garbage collection
  root down to the object, one row per object, naming the field that holds the next. Every row is
  clickable, which is also the way back out.
* **Everything naming an object is a way to it**, and the same three clicks work everywhere: click to go
  there in this tab, middle click or ⌘/Ctrl click to open it in a tab behind this one, right click for a
  menu offering both that and a link to the object. The buttons along the top always open a new tab, and
  every tab can be closed — including the last, which leaves the heap dump open and the buttons ready.
* **← and → walk the tab's own history**, so a tab you wandered off in is one click from where it was.
  **Right click either arrow** for the list of everywhere it leads: picking the fourth entry is one click
  rather than four.
* **The three panes are resizable, and each folds away to a button.** A path thirty steps long or a
  details panel of forty fields is sometimes worth the whole window.
* **Shape** switches between rectangles and rings. A ring has room for fewer children, so it groups the
  small ones sooner: better for the shape of the tree, worse for exact sizes.
* **Colour** splits the dump by how firmly things are held. Unchecking a strength greys it out rather than
  hiding it, which is what makes the little there is of everything else stand out.
* Bitmaps are drawn as their own pictures where the dump has the pixels. Android keeps them outside the
  Java heap from API 26 to 34, and for those the app offers to fetch them off the device the dump came from.
* **Object list** is the whole dump as a searchable list, and **Starred** keeps the objects you want to
  come back to — kept between runs in `~/.shark-dive/starred`, one address per line, one file per heap dump.
* **Metadata** is what the heap dump says about itself: the device, the app's process, what the heap is made
  of, its bitmaps and its open databases. It is LeakCanary's own map, the one printed above every leak trace
  it writes, so a figure read here is the figure in the `leaks.txt` somebody sent you.
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
of the map, a row of the object list or of the leaks, a step of a path, a field of the details panel, a
starred object. Wherever the window will take you somewhere, it will also hand you the link to it.

```
shark://bug-4821.hprof/object?id=0x7f2a4b18
shark://bug-4821.hprof/objects?query=Bitmap&exact=true
shark://bug-4821.hprof/leaks
```

Anywhere a tab can be is a link: an object, the object list with its search and filters filled in, the
leaks with the same groups unfolded, the starred objects, the metadata. So "look at this" is a URL rather
than a paragraph of directions, which is also how a tool or an agent that has read your heap dump can point
you straight at what it found.

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
an object, the object list, the leaks, the starred objects, the metadata, or the heap dump as a whole on the
tab a window opens with. Type into the box, press **Save**, and the note is drawn where the box was; **Cancel** throws what
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
a quiet `? Unknown` — with the reason under it, in the same colours the path on the left uses. Most objects
in a heap dump are `Unknown`, which is why that one is drawn small: the two that mean something are the ones
worth seeing across the room.

`Stuck` says the object should be gone and something is holding it. `Expected` says its being in memory is
legitimate at this point in the app's life. It is the same answer a LeakCanary leak trace prints as
`Verdict: Stuck`, `Expected` and `Unknown`, in words that stop short of calling the object the leak — because
**the leak is the faulty reference**, the one that should have been cleared, and everything under it is stuck
by that single mistake. An object nothing reaches any more is `Stuck` as well: it was expected to be gone,
and only the garbage collector not having run keeps it here. The verdict means the same thing everywhere.

The reason is the rest of the answer, because half of these are about another object: an activity is red
because its own `mDestroyed` is true, and the view under it is red because the activity is. `Activity↑ is
stuck` is the path saying so.

**The path marks the faulty reference itself**: `Holder.activity · faulty reference`, in bold red, on the one
step that goes from an `Expected` object straight to a `Stuck` one. It is the one line of a path that says
where to go and change code — the shades on the objects are what the leak left behind, this is the leak — and
it is the same reference the Leaks screen names that leak after, so a row there and the path you open from it
name one thing.

**And when it has one, `Leak solved` says so above the path**, with the reference under it and nothing else:

```
Leak solved
Holder.activity
```

Because a real path is tens of steps and **What holds it** is scrolled to the last of them, so a mark
somewhere in the middle is an answer you have to go looking for. The name is the one to go and grep for, and
it is the same string the Leaks screen and an agent's `faultyReference` both use.

**A path with no such step carries no mark**, which is deliberate: what would be marked would be a guess
drawn as an answer. With objects nothing knows either way about between the two verdicts, the fault is at one
of those steps and nothing on the path says which. With nothing `Expected` above the stuck object at all,
what holds it may be something that should have let go of it too, so the fault can be further up than the
path reaches. Overruling a verdict is what closes either gap: say what you know about one object in between,
and the mark appears on the step that leaves.

**The pencil beside it** overrules the verdict. Pick one of the three, type why, and **Set the verdict**:

* **Your answer wins**, whatever the inspectors said. Overruling is the point — an inspector reads a field,
  you read the code, and a cache that is meant to hold what it holds is not something a field can say.
* **The reason is required.** A verdict with no reason is one nobody who reads your heap dump next — a
  colleague, an agent, you in a month — can check, and one of those makes every other verdict in it worth
  less. What you overruled is kept beside your reason rather than thrown away.
* **It reads as yours**, wherever it appears: `set by hand — the cache is bounded, this is fine`, in the
  panel and on every path that runs through the object.
* **Everything a stuck object holds is stuck too, and everything holding an expected one is expected too**,
  so a verdict you set changes what the objects around it read as. Which is why setting one is usually enough
  to make a whole path make sense.
* **The pencil again** on an object you have already decided about, and **Take it off** to hand it back to
  the heap dump.

Because a verdict propagates along the path, two of them can contradict each other: an object marked as
stuck, holding one marked as expected, cannot both be read off the path between them. When what you are
setting does that, **the window lists every verdict it disagrees with before writing anything** — what the
object is, which side of yours it is on, the reason it was given, and what it would become. **Keep this and
flip those** keeps yours and sets them to the opposite verdict, with what they said kept as part of the new
reason; **Undo** leaves the heap dump exactly as it was. Nothing is written until you pick one.

The verdicts live in `~/.shark-dive/verdicts`, one tab separated file per heap dump, with the columns
named at the top — so they can be read, edited, diffed or pasted into an issue without this app, and they are
there again the next time you open that dump.

**The Leaks screen follows what you set**, because a verdict changes which objects are leaks and not only how
one of them reads: marking something as stuck makes it a leak, and whatever it holds stops being one — it is
only still in memory because of the object you named, and that is the thing to fix. Marking a leak as
expected takes it off the list. The one thing this costs is that a leak's fingerprint matches the one
LeakCanary reports only while nothing has been set by hand, since the fingerprint is the stretch of path your
verdict has just moved.

## Hand it to an agent

!!! tip "Never dive alone"
    Take your agent with you.

**Every screen and every button of this app is also a command**, so an agent — Claude Code, Cursor, whatever
you use — investigates *the heap dump you have open* rather than one of its own. It reads the same tree, sets
verdicts you watch appear, puts what it is looking at on your screen, and leaves those verdicts — each with
the evidence for it — where you and the next reader will find them.

There is nothing to install and nothing to configure. The app's own launcher takes the call:

```bash
"/Applications/Shark Dive.app/Contents/MacOS/Shark Dive" --help
"/Applications/Shark Dive.app/Contents/MacOS/Shark Dive" \
  --cli open_heap_dump path=/var/dumps/bug-4821.hprof reason="The dump the report came with"
"/Applications/Shark Dive.app/Contents/MacOS/Shark Dive" \
  --cli list_leak_groups heapDumpKey=bug-4821.hprof reason="Starting from what the dump says about itself"
```

**`--cli` goes on every command**, and it is what tells a command from a run of the app: the same launcher with
a heap dump after it opens a window, so nothing here is inferred from a command name arriving. And it is one or
the other — a command line that says `--cli` and also names a heap dump is saying two different things to do,
since a command opens a dump by calling `open_heap_dump path=…` and answers that it did. `--help` prints
every command with a line each, `--help <command>` prints one of them in full, and both answer with no run
open and no heap dump anywhere — which is the state an agent reads them in.

**There are two ways in and everything else names a heap dump.** `open_heap_dump` opens the file you were
given, or joins the run that already has it, and answers with the key every other command names that dump by;
`list_heap_dumps` is that key for an agent that was given no file. After those, `heapDumpKey=<key>` is on
every call, so each one says which dump it is about. The key is the dump's file name, and `bug-4821.hprof#2`
for a second dump open under that name — two devices with a `crash.hprof` on them is two files, and the name
alone would leave the second one unnameable.

A call goes to the run that has the dump open, over a loopback socket every run of the app publishes, so
there is no port to configure either. **Two runs open is an error rather than a guess**: the call names them
and asks for `--debug-run=<pid>`, since which heap dumps there are to read would otherwise depend on what
else is on the machine. And a call only reaches a run built from the same commit as the launcher that made
it, so a window left over from another checkout is never a command that has quietly changed meaning.

It exits 0 with the answer as JSON on stdout, **2 when the call was refused**, with the refusal on stderr
where a script can read it, and 1 when there was nothing to answer it. Which matters more than it sounds: a
refusal is the surface working, so an agent that treats a non-zero exit as a failure to retry is one that
never reads the sentence telling it what to do instead.

**A window open before you start is not a requirement.** With nothing running, `open_heap_dump` starts a run
and waits for it, as `dump_heap`, `list_devices` and `list_processes` do — the four commands whose answer is the
same whether the run was already there or not. Every other command is a question *about* a run, and one answered
by a run just started for it would come back empty while looking exactly like a run that was there and had
nothing open, so those say which command to call instead. The run outlives the agent's session, which is the
point: whatever it worked out is on the tabs it left open when you come back to it. `close_heap_dump` is the
other end of that, and closing the last dump open ends the run, so an agent that finishes tidily leaves no
window on your screen.

`--session=<name>` says which investigation a set of calls is one of, and **an agent is expected to pass one**,
with its own session id in the name: what you did then reads as one row of the *Agent logs* screen, and
somebody reviewing the agent's own logs can search that id and find the investigation beside them. Left off,
the calls of one shell are gathered for you, which is the case a person is in.

### And a case with no screen at all

A build server, a heap dump on the far end of an ssh session, or something driving an agent with nobody
watching. Open the dump in a run that draws nothing:

```bash
"/Applications/Shark Dive.app/Contents/MacOS/Shark Dive" --cli open_heap_dump --no-ui \
  path=/var/dumps/bug-4821.hprof reason="No display on this machine"
"/Applications/Shark Dive.app/Contents/MacOS/Shark Dive" \
  --cli list_leak_groups heapDumpKey=bug-4821.hprof reason="Starting from what the dump says about itself"
```

That run draws no window and publishes itself exactly as a run with windows does — so every call after the
first is the same call, and nothing has to be told which kind of run it is talking to. `--no-ui` goes with the
commands that start a run and no other, because what it picks is the kind of run to start rather than anything
about a dump that is already open; asking for the wrong kind is refused either way round rather than papered
over.

Everything works the same except `show`, which has nowhere to put a tab and is **refused** rather than
answering that it showed you something — being seen is the whole of what that one command does. The refusal
hands back the `shark://` link all the same, which names the heap dump: nobody saw the place, and the link
opens it for the next reader on the machine the dump is on. Nothing else changes, because
**notes and verdicts were never on the screen** — they are files beside the heap dump, so a dump investigated
over ssh today opens in a window tomorrow with the verdicts and their reasons already on it.

`--help` prints every option of the command line, the ones above included, and `--cli` with nothing after it
prints exactly that — so a launcher typed with no idea what it takes answers with all of it and reaches for
nothing.

### And no MCP server

That one is deliberate. A command line is what an agent reaches for without being configured at all: nothing
to install, nothing to point at a config file, no server running until a call is made — and you can type the
same call the agent just made, read the same answer, and pipe it into `jq`. What an agent needs instead is to
be told any of this exists, which is the skill below.

### The skill

An agent still has to be told that any of this exists. This repository carries a
[skill](https://platform.claude.com/docs/en/agents-and-tools/agent-skills/overview) that does it, and it is one
short page on purpose: where the launcher is, and that `--help` is the rest. Everything a command takes, how to
work here and how to find a faulty reference are text the build prints, so a copy of them in a skill is a copy
that describes the commands that existed when it was written. Every client that reads the standard (Claude
Code, Codex, Cursor, Gemini CLI) picks it up from the same directory:

```bash
git clone git@github.com:square/leakcanary.git
cp -R leakcanary/.claude/skills/shark-dive ~/.claude/skills/
```

Then "there's a heap dump in ~/Downloads, what's using all the memory" is enough: the skill is what turns that
into opening the dump in a window you can watch.

Then ask for what you actually want. This is the whole prompt the session below was given:

> A heap dump is open in Shark Dive, which you can reach with its `--cli` command line. Something in it is
> leaking. Find the root cause.

**The method comes with the commands**, so it doesn't have to come from you. `--leak-investigation-help` is
what a leak is — one bad reference, the three zones of a path, the rules that spread a verdict up and down
it — and the order that finds it, which is [the LeakCanary
method](https://engineering.block.xyz/blog/the-leakcanary-method) as the commands enforce it.
It is a text this build prints rather than a field of an answer, which is what keeps an investigation of six
leaks from reading the whole method six times. How to work on this surface at all — the `reason` on every
call, the `shark://` links to hand back, the gap to admit — is four paragraphs of `--help`, where an agent
that has been told nothing already is. Three places point at the leak method, each of them somewhere an
agent already has a leak in hand: `list_leak_groups`'s own description, the answer that opened the dump, and
`--help` itself.

**Including the part that isn't in the heap dump at all.** Isolating the reference says *where* the problem
is, not how it happened, and stopping there is the most common way an investigation fails — so the method
sends an agent to the code, at the version this dump is of, and tells it how to work out which version that
is: `android.os.Build$VERSION.SDK_INT` for the framework, the app's `ApplicationInfo` for its package, its
APK path and its target SDK, the build file or the APK for a library's version, and a decompiler when there
is no source to read. What it can't work out — the app's own version number is usually absent, since
`BuildConfig` constants never reach the heap — it is told to ask you for rather than guess.

**Everything the window can do, it can do** — there is no screen an agent can't reach and no button it can't
press, because a surface with less than that is one whose answer is "ask your human to click something":

| Command | What it is |
| --- | --- |
| `open_heap_dump` | The heap dump you gave it, by path or by name: **Open heap dump…** for a file nobody has open, and the window that already has it when somebody does. The one way in. |
| `list_heap_dumps` | Every heap dump open, for an agent that was given none, and the name each of the rest goes on to use. |
| `close_heap_dump` | Done with a dump: the window closes, and closing the last one ends the run. |
| `heap_dump_metadata` | The **Metadata** screen: what the dump says about itself, as the map LeakCanary prints above a leak trace — the device, the app's process, the bitmaps, the open databases. |
| `list_leak_groups` | The **Leaks** screen: what this heap dump says shouldn't be there, gathered into one group per leak, with the objects leaking for each one — any of which solves it. |
| `agent_log` | The **Agent logs** screen: what has already been tried on this dump, and what it came to — and, for one session, every call it made with the text it sent and read back. |
| `path_from_gc_roots` | One path: the objects from the GC root down, each with its labels, its verdict and the reference it holds the next one through, and how far the verdicts have narrowed it. |
| `describe_object` | What an object is: its class, fields, labels, size. |
| `ways_held` | Every way an object is held, rather than the one path — the *X ways from here* list. |
| `find_objects` | The object list, by class name. |
| `dominator_tree` | The treemap, without the pixels: where the memory has gone, a level at a time. |
| `set_verdict`, `clear_verdict` | The pencil, with the *Why* required the same way. |
| `read_notes`, `take_note` | The notes: where somebody has been, what they wrote, and adding to or replacing it. |
| `show` | Opens a tab in your window and brings it to the front, and answers with the `shark://` link to it. |
| `list_devices`, `list_processes`, `dump_heap` | **Take heap dump…**: which device, which process, and the dump itself. |

`open_heap_dump` and the last three are what make an agent useful when there is nothing open yet: point it at a
dump a bug report came with, or at a process on a device, and the window it lands in is one you can look over
its shoulder in. Naming the dump is the whole of starting — an agent that was handed one never has to ask what
is open. **`open_heap_dump` and `dump_heap` are also the only calls with a wait worth planning for**, both of
them minutes on a large app, because both answer once the dump can actually be read rather than once it has been
named; the steps are in the run's log while they work. Everything else in the table is a read of something
already indexed.

**And the commands refuse.** That is the part worth knowing about, because it is what an agent's confidence
cannot argue with:

* **Every call has to say why it was made.** A call with none is refused — *describe_object needs `reason`,
  and it was not given* — and so is one whose reason is blank. What that buys is the log below.
* **An argument a tool doesn't take is refused**, naming both it and the ones the tool does take. Which
  matters more than it sounds: `find_objects` given `query`, the name of the window's own search box, would
  otherwise match nothing in particular and answer with the biggest objects in the heap dump, and a list of
  the wrong objects reads exactly like an answer.
* **A verdict needs a `why` another reader can check**, exactly like one you typed, and it is kept with the
  verdict in the same file as yours and drawn in the same *Why* box. It is a separate argument from the
  `reason` above, because the two outlive each other by different amounts: a `reason` is a line of one
  session's log, and a `why` is what the next person to open this dump reads off it. A verdict that
  contradicts one already recorded is refused with the list of what it disagrees with, the same way the
  window asks you.
* **Nothing ever asks an agent which reference is at fault.** That is the one question this surface does
  not take an answer to, and it is why there is no "report the root cause" command: which reference a leak is
  gets *derived* from the verdicts recorded about the objects on the path, by the same rule the window draws
  by. So the work is deciding, object by object, whether that object's own job is done — and the last verdict
  that narrows the stretch to one reference is what leaves the heap dump naming it.

**`leakSolved` is what finishing looks like**, and it comes back under `investigation`, from
`path_from_gc_roots` and from `set_verdict`:

```json
{
  "leakSolved": false,
  "state": "NARROWED",
  "suspectReferenceCount": 2,
  "leakSolvingProgressRatio": 0.67,
  "next": "The fault is at one of those references, and what settles which is the objects between them that
           have no verdict […] They are 0x12e9ed60. […]"
}
```

Two references left and one object to go and read, rather than a number of steps — one undecided object
leaves the reference into it and the reference out of it, and its own verdict rules one of them out. *Which*
two references is on the path beside this, as `isSuspect` on each of them, and which object to settle is the
one the path gives no verdict; a `faultyReference` appears here once the verdicts leave a single candidate,
with the index in the path of the object that holds it. Pass `solvingLeakOf=<the stuck object>` on a
`set_verdict` and its answer says what that verdict did to the leak: the candidates before and after, the
progress ratio, and `leakSolved` when there is nothing left to narrow.

Nothing here judges the answer — no model is called and nothing is scored. An agent that has narrowed a path
to three unexplained steps has `leakSolved: false` and the three objects to go and read, however sure it is
that it already knows; and when it does become true, no agent made it true.

**What it did is on the *Agent logs* screen**, one row per agent that has connected to the app. Open a row
and there is everything that agent sent, in order and in words — what each call did, which object it did it
to, and the sentence it gave for doing it:

```
08:23:04  Asked which heap dumps are open
          because: Seeing what there is to read before asking anything about it.
08:23:11  Listed the leaks
          because: Starting from what the heap dump already says shouldn't be here.
08:23:18  Read the path to 0x12d368b8
          because: This is the one App leak: a MainActivity the app watched and whose mDestroyed is
          true. Reading the path from a GC root.
08:23:27  Looked at 0x12d00c30
          because: The FutureTask in the middle of the path: checking whether it is really running.
08:23:34  Looked for every way of holding 0x12d368b8
          because: Checking whether anything else holds the activity, or only this one path.
```

**A row leads where the call went**, and what leads there is the thing rather than the verb: click
*0x12d368b8* on *Read the path to 0x12d368b8* and the window opens that object, so reading what an agent did
and going to look at it are one move. A call that named nothing went somewhere all the same — *leaks* on
*Listed the leaks* is the leaks screen, and *dominator tree* on *Read the dominator tree* is the tree from its
root. The one row that leads to several places keeps them behind its fold instead: *Asked which heap dumps are
open* opens into the dumps that were open, each of them a window away.

**A refused call is a row too**, in red, under the reason the agent gave for making it — and those are the
half of a session worth reading, since a refusal is where the method sent an agent back to the heap dump
rather than on to an answer:

```
08:23:45  Recorded EXPECTED on 0x12d00c30
          because: […]
          Refused: Not set: EXPECTED on 0x12d00c30 contradicts 1 verdict(s) already recorded about this
          heap dump. Everything a stuck object holds is stuck […]
```

**And so is every line that arrived and reached no tool.** A call naming a tool that doesn't exist, a line
that wasn't JSON at all, a read that failed: each is a row, and the ones nothing could answer say *Failed*
rather than *Refused*, which is the opposite claim. A refusal is the method working; this is Shark Dive not
working.

```
08:24:02  Called solve_the_leak 0x12d368b8
          because: Trying my luck.
          Failed: There is no tool called "solve_the_leak". This build has open_heap_dump, […]
08:24:09  Sent something this app could not read
          Failed: That is not one JSON object.
```

Which is the point of keeping them: what this screen gets opened for is often why *nothing* happened, and a
screen holding only the calls that worked is the one screen that can't answer that. The `n call(s)` above the
rows counts the calls rather than the lines, for the same reason.

**One command typed is one row**, however many calls a shell makes: `--cli` is a process per call, and each
of those connects, makes its one call and ends, so nothing crosses that socket but the call itself. There is
no hello in front of it to draw.

**And every row unfolds onto the call itself** — the `▸ {}` under a row opens what the agent sent and what it
read back, as the text each of them was, so a step you don't follow is one question rather than a dead end:

```
11:37:31  Looked at 0x12d368b8
          because: The one App leak: a MainActivity the app watched. Reading what it is before the path.
          ▾ {}
            sent:
              describe_object {
                  "object": "0x12d368b8",
                  "reason": "The one App leak: a MainActivity the app watched. Reading what it is
                             before the path."
              }
            answered:
              {
                  "object": "0x12d368b8",
                  "label": "MainActivity",
                  "className": "com.example.leakcanary.MainActivity",
                  "kind": "INSTANCE",
                  "strength": "STRONG",
                  "shallowBytes": 214,
                  "retainedBytes": 210978,
                  "verdict": "STUCK",
                  "verdictReason": "ObjectWatcher was watching this and Activity#mDestroyed is true",
                  […]
              }
```

What it sent is the tool's own name and then the arguments under it, which is the call as the model wrote it:
*Looked at* is this window's word for `describe_object`, and the pair is worth having side by side exactly
when a step doesn't follow from the one before it.

Whole, never a first line of it: what you open a call for is the part the sentence left out, so an answer cut
to fit would be one where the field that misled the agent is the part that got cut. And a session recorded by
an older Shark Dive says so rather than opening onto a gap.

**Including the calls that came to nothing**, which is what this is most worth opening for. A refusal is
under `answered:` as the agent was handed it, as well as in red on the row; so is an error, if a read failed
or this app has a bug; so is the answer to a call naming a tool that doesn't exist. Every line that arrived
was answered with something, so a row with nothing under `answered:` means the app was killed while the call
was in flight — which is the one failure an agent can't tell from this app having gone quiet.

The mark is under the row rather than on the verb because a row is a sentence with one link in it, and a fold
over its first words made hovering light up the half that isn't the link.

**Reading it is what makes the reasons worth anything**, since a reason is what an agent *said* it was doing:
a step that reads as sound and was taken on an answer that said nothing looks exactly like a step taken on
one that said everything, until you open both. Select and copy either half — into an issue, into a message to
whoever handed you the dump, into a diff of two runs.

A session is kept in `~/.shark-dive/agents/sessions`, one file per agent that connected and the newest
hundred kept, a line of JSON per message, each carrying that message's `input` and `output` — so it outlives
the window and can be read by something other than this app. `agent_log` hands an agent the same text, which
is how one agent works out where another went wrong. **And the reads each call cost are in the run's log**, in
`~/.shark-dive/logs`, where the reason it gave is followed by the work it caused:

```
18:19:48.035 [shark-dive-agents] An agent called path_from_gc_roots(heapDumpKey=leak_asynctask_o.hprof, object=0x12d368b8)
  because: This is the one App leak: a MainActivity the app watched and whose mDestroyed is true. Getting the
  path from a GC root to see every reference holding it and where the faulty one might be.
18:19:48.038 [heap-dump-leak_asynctask_o.hprof] Reading the path to 0x12d368b8, for an agent
18:19:48.043 [heap-dump-leak_asynctask_o.hprof] Read the path to 0x12d368b8, for an agent in 4 ms
```

So an investigation is something you can follow afterwards rather than a conclusion you have to trust — which
is the other half of the point, since the reasoning is the part a chat window throws away.

**What it worked out is in the heap dump**, not only in your terminal. The verdicts are in
`~/.shark-dive/verdicts` with everyone else's, each with the evidence the agent gave for it — which is
what solved the leak, so it is also the whole of the argument for the answer, in the window beside the object
and still there next week:

```
0x12d00c30  EXPECTED  ExampleApplication is the app's own Application subclass and is held by a static
                      field of ActivityThread, so it is meant to be in memory for the life of the process.
0x12d368b8  STUCK     ObjectWatcher was watching this and Activity#mDestroyed is true.
```

**What a note adds is the part that isn't in the heap dump**: why the field was never cleared, which the
method sends an agent to the code for. That is `take_note` like any other note, and nothing writes one for
an agent — a paragraph nobody asked for in the notes of an object is one the next reader has to work out
whether to believe.

**And the link to the object comes back with `show`**, because the answer usually arrives somewhere that
isn't this app. It answers with the `shark://` link to what it put on screen, and the method tells an agent
to put that link in its reply beside the leak trace — so a sentence in your chat window, a pull request
comment or a bug report ends up carrying a way in:

> The leak is `MainActivity$2.this$0`, a non-static inner class holding the activity it was declared in:
> shark://leak_asynctask_o.hprof/object?id=0x12d368b8
>
> ```
> ┬───
> │ GC Root: Thread object
> …
> ╰→ com.example.leakcanary.MainActivity
> ​     Leaking: YES (ObjectWatcher was watching this)
> ```

Clicking it opens that object with the reasoning on its tabs — in a window that has the heap dump while one
is up, and by opening the file again once none is. So an answer worth keeping keeps working, and it is short
enough to read: it names the heap dump, and where that file is, is looked up.

**The leak trace beside it is the one LeakCanary prints**, and an agent is told to quote it exactly as the
answer handed it over rather than to write one out: `path_from_gc_roots` and `set_verdict` both carry it,
produced by Shark itself, because a trace retold by a model that drops a step or moves the `~~~~` underline
is indistinguishable from the real thing to whoever reads it. They carry the same path as fields too, under
`leakTrace` beside the text's `humanLeakTrace`, so that the form a program reads and the form a person reads
are never one field a reader has to guess the shape of.

An agent's verdicts are verdicts like any other: they say `set by hand` on every path that runs through the
object, the reason is the one it gave, and the pencil takes one off if you disagree with it. Which is the
last thing this surface is for — the disagreement is about a reason you can read, not about who said it.

## Everything it keeps is in one directory

The notes, the verdicts, the starred objects, the agent sessions, the logs and the record of where each heap
dump was are all under `~/.shark-dive`, and **`SHARK_DIVE_DIR` puts them somewhere else**:

```bash
SHARK_DIVE_DIR=~/second-opinion open -a "Shark Dive" --args path/to/dump.hprof
```

Which is a second set of notes and verdicts over the same heap dumps, kept apart from the first — for reading
a dump again without yesterday's verdicts in front of you, or for a run whose verdicts shouldn't end up in
yours. Set it for every process that should share those files: a window started this way and each of the
`--cli` calls made at it read the variable from their own environment, and a call from a shell that doesn't
export it won't even find that window — where a run publishes its port is under that directory too, so it
looks in `~/.shark-dive`, finds nothing and opens a window of its own.

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
