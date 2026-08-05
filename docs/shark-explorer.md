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
* **Point at one and the window describes it; click it and the map goes there**, redrawing that object's
  contents across the whole view. So reading the map is a sweep of the mouse, and a rectangle a pixel wide
  at the top of the tree is a full picture two clicks down.
* **The pane on the left is the answer to "what holds this"**: the shortest chain from a garbage collection
  root down to the object, one row per object, naming the field that holds the next. Every row is
  clickable, which is also the way back out of a zoom.
* **Shape** switches between rectangles and rings. A ring has room for fewer children, so it groups the
  small ones sooner: better for the shape of the tree, worse for exact sizes.
* **Colour** splits the dump by how firmly things are held. Unchecking a strength greys it out rather than
  hiding it, which is what makes the little there is of everything else stand out.
* Bitmaps are drawn as their own pictures where the dump has the pixels. Android keeps them outside the
  Java heap from API 26 to 34, and for those the app offers to fetch them off the device the dump came from.
* **All objects** is the whole dump as a searchable list, and **Starred** keeps the objects you want to
  come back to.

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
