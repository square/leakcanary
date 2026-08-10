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
* Every tab takes a **note**, in markdown, kept between runs — see [Take notes](#take-notes).

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

**✎ Add Note**, at the end of the row that says where the tab is, starts a markdown note about the tab you
are on — this object, this list of leaks, or the heap dump itself on the tab a window opens with. Type into
the box, press **Save**, and the note is drawn where the box was; **Cancel** throws what you typed away. It
is there again the next time you open that tab, and the tab strip puts a ✎ on the tabs you have written
about.

A note appears under that row and above the panes, because it is about the whole of what the tab is showing.
On a tab nobody has written about there is nothing there at all — only the button, which goes away once
there is a note, since the note carries its own **✎ Edit**.

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

A note is filed under what its tab is *about* rather than how it is arranged: searching in the object list
or unfolding a leak stays on the same note, and the same object opened in two tabs, or in two windows, is
one note rather than two saving over each other.

Since a `shark://` link names a window, a link written into a note stops working once that window is closed
— see above. Copy one for the tab you want to come back to *while you are writing about it*, and it will
take you there for as long as that window is open.

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
