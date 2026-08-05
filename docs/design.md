# Design decisions

LeakCanary is opinionated, and this page is about the opinions: what each part of it optimizes for,
and what that choice costs. [How LeakCanary works](fundamentals-how-leakcanary-works.md) describes
what happens; this page is about why it happens that way.

## One object, not a number

Memory leaks announce themselves through symptoms that can't be traced back to a cause. Accumulated
leaks increase memory pressure, make the garbage collector run more often, which surfaces as jank and
`Application Not Responding` freezes, and eventually as an `OutOfMemoryError` thrown from wherever
the app happened to be — so every OOM has a different stack trace, and a thousand of them look like
a thousand unrelated crashes instead of one bug. Aggregate memory measurements have the same
problem in a milder form: they tell you that something is wrong without telling you what to change.

So LeakCanary works from the other end. Instead of watching memory, it watches individual objects.
The app states that a particular object is done and should be deleted, and LeakCanary checks that
expectation. When the expectation is broken, the answer isn't a number, it's an object and the chain
of references keeping it alive — something you can go and change.

That object is a symptom too — but unlike the others, it's one that leads somewhere. Nothing about it
is wrong: it reached the end of its life exactly when it was supposed to. The bug is a reference that
should have been cleared and wasn't, and since that reference is what holds the object, it has to be
somewhere on the chain. Retention is worth detecting precisely because it's the symptom that can be
followed back to the cause.

The cost of that decision is that LeakCanary has to be told what to expect. It knows Android's
lifecycles, so destroyed activities, destroyed fragments and their views, cleared view models and
destroyed services are watched for you. Anything else — a presenter, a detached view, a scope of
your own — LeakCanary can only check if you hand it over.

Detection is also incremental by design: the objects reported by one analysis aren't carried into the
next one, so a leak you've already been told about doesn't come back at you on every heap dump. The
alternative — scanning the whole heap for every object of a type known to have a lifecycle — finds
more leaks and is available for the runs where that's what you want, like a test suite, at the price
of surfacing the same objects over and over for the rest of a process's life.

## Watch, then confirm

An object that is still reachable a moment after its lifecycle ends is not evidence of anything, so
LeakCanary holds a weak reference to the watched object and waits five seconds before concluding
anything. That wait is not about the garbage collector. Android holds objects temporarily through
messages posted to the main thread with a delay — a blinking cursor, a fling settling, an animation
finishing — and those references clear themselves once the message runs. Five seconds is the window
judged long enough for that backlog to drain.

An object still held after that wait is **retained**, and only then is the heap worth looking at.

What LeakCanary waits for is weak reachability, not collection. A weak reference is enqueued as soon
as its target becomes weakly reachable, before finalization or collection has actually happened,
which is the earliest moment at which the object is known not to be held — and the cheapest, since
LeakCanary can then stop tracking it and forget about it. Watching therefore costs almost nothing for
the objects that behave.

The same caution decides when to dump the heap. Dumping freezes the app, and being frozen at random
while you are trying to work is worse than hearing about a leak a little later — particularly since
some of what you'll hear about can't be fixed from app code at all. So while the app is visible
LeakCanary waits until several objects are retained before dumping, and it accepts the trade
explicitly: a higher threshold means fewer heap dumps and less interruption, at the risk of missing
leaks. Once the app is in the background or the screen is off, nothing is being interrupted and one
retained object is enough. For the same reason LeakCanary doesn't dump the heap while a debugger is
attached — a thread stopped on a breakpoint holds objects alive that aren't leaking.

Choosing *what* to watch follows the same rule, because a report you learn to disbelieve is worse
than no report. Some Android widgets keep a detached view around to reattach it later, and app
developers do the same with dialogs, keeping one instance and calling `show()` and `hide()`. Watching
every detached root view indiscriminately therefore reported leaks that were not leaks, and the
watching was narrowed rather than left broad.

## The analysis runs on the device

A leak you have to go looking for is a leak that doesn't get fixed. Pull a heap dump off the device,
open it in a heap analyzer, find the object, walk it back to the roots — every step is a step at
which the leak stops being worth the trouble. LeakCanary's premise is that the report should arrive
on its own, while you are still on the screen that leaked.

That only works if the analysis is cheap enough to run on the device, in the app's own process,
which is why LeakCanary 2 replaced its heap parser with one written from scratch for a small memory
footprint: a 160 MB heap dump that takes 2 GB of memory to open in Android Studio opens in 40 MB with
Shark. Before that, the analysis had to run in a separate process, and every app needed code in
its `Application` class to avoid initializing itself there. Fitting the analysis into the app process
removed that requirement. A separate process is still available for apps where in-process analysis is
too expensive, but it is now an option rather than the default. Getting to that number took
[four parsers](history.md#four-heap-parsers), three of them replaced for the same reason.

Analyzing on the device also means the heap dump never has to go anywhere. A heap dump contains
everything that was in memory, which for a real app includes user data, credentials and keys. That's
why LeakCanary can strip a heap dump of its data before you share one, and why analysis in production
is set up to keep heap dumps in storage private to the app and to offer stripping as the analysis's
first step, at the cost of processing time and for the benefit of a shorter window during which that
data exists on disk.

## Adding the dependency is the whole setup

Every setup step is a step that can be skipped, done wrong, or lost in a merge. So LeakCanary has no
setup step: on Android, content providers are created before `Application.onCreate()` is called, and
LeakCanary declares one in its own manifest, which is enough for it to start itself. There is nothing
to call and nothing to remember.

The flip side is that a tool which installs itself must not be able to ship. LeakCanary is a
development tool — it freezes the app, writes heap dumps to storage and posts notifications — so it
is meant to be a debug-only dependency, which is also why it adds zero methods to a release build:
it isn't in one. Documentation alone didn't prevent apps from shipping it, so a mistake that used to
be silent is now loud: LeakCanary refuses to start in a build that isn't debuggable, and says how to
fix the dependency declaration or, if you really meant it, how to allow it.

The same reasoning disables LeakCanary in tests. Watching continuously, freezing the VM and
reporting through notifications is right for someone using an app and wrong for a test run, so
automatic heap dumping turns itself off when JUnit is found on the runtime classpath. Tests that do
want leak detection ask for it explicitly, at the point in the test where it makes sense.

## The leak trace is chosen to be understood

There are usually a great many paths of references from the garbage collection roots to a retained
object, and LeakCanary reports exactly one of them. Any of them would be correct; which one it picks
decides how much work it is to find the bad reference in it.

Start from what a leak usually is: **a single bad reference**. If that is what holds the object, then
*every* path from the roots to that object goes through it. Every candidate path contains the answer.

That is what makes the choice free. Since correctness doesn't separate the paths, what's left to
optimize is how much work it is to read one. A shorter path holds fewer references, and fewer
references means fewer things to rule out before you reach the one that matters. So LeakCanary
reports the shortest path.

**But shortness was never the goal.** The shortest path is a *proxy* for the path that is easiest to
understand, and that proxy comes apart in places. The clearest case is a reference held by a local
variable on a running thread: those often yield the shortest path in the heap, and they are among the
hardest to act on. You are told that a thread's stack is holding your object, and Android heap dumps
carry no stack trace data, so LeakCanary can't even tell you which method. Given a short path through
a thread's stack and a longer one through ordinary fields, the longer one is the one that teaches you
something.

So where the proxy (shortest path) and the goal (easiest to understand) disagree, the goal wins: some
references are explored only after every other reference has been. Any path that avoids them is found first and reported, and a path
through them is used only when there is no other. Two kinds of reference are treated that way:

* References that are hard for a developer to interpret, local variables on a thread being the main
  example.
* References that match a known leak in library code. Preferring paths that avoid them means that
  when your app has a leak of its own, that's the leak you hear about, rather than a framework bug
  that happens to sit on a shorter path.

A few references are left out entirely rather than deprioritized, because a path through them says
nothing at all. The references that don't keep their target alive — the referent of a weak, soft or
phantom reference, the links of the finalizer list — cannot be the reason an object is still in
memory. Neither can the stack of the thread that watches over finalization: if that is what holds
your object, the object was about to be collected. Locals on the main thread are excluded on the same
grounds, since the main thread's stack is ever changing and unlikely to hold anything for long, so a
path through it is a hint that a longer path with the real leak on it exists.

!!! note "A weak reference doesn't guarantee its target can be collected"
    Reading a weak reference hands back an ordinary strong reference, stored into a local variable on
    the stack, and some collectors then treat that object as strongly reachable for the rest of the
    collection cycle. An object whose weak
    reference is read on a loop — animations do this to their target once per frame — therefore
    survives every cycle that overlaps a read, and can sit in memory for as long as the loop runs,
    even though nothing but weak references point at it. Where that is known to happen, LeakCanary
    puts the reference back on the graph as one of the deprioritized kind above, so that a trace can
    reach the object without that path ever being preferred. [Why does LeakCanary report unreachable
    objects?](faq.md#why-does-leakcanary-report-unreachable-objects) walks through the case this was
    built for.

A path that goes through *another* retained object is left out too, for a related reason: it would
only tell you to go and fix that other leak first, and that other leak has a trace of its own in the
same results. So what you get is the shortest path that goes through neither a deprioritized
reference nor another leaking object. It is frequently not the shortest path in the heap. It is the
one chosen to be easiest to act on.

One consequence is worth stating: because leaks are grouped by the references they blame, path
selection has to be deterministic. Two analyses of the same bug that picked different paths would
report it as two separate leaks, so the choice is pinned down all the way to visiting the garbage
collection roots in a fixed order.

!!! note "More than one bad reference"
    This section has assumed that a leak is a single bad reference. In practice there are sometimes
    two separate bugs — two bad references, both preventing the same object from being garbage
    collected. LeakCanary still presents a single leak trace, to keep things simple. Find that leak,
    fix it, run leak detection again, and the second one surfaces.

## References are shown the way you would write them

A leak trace that walked a hash map the way a hash map is really built — through the table array,
into a bucket, along a chain of nodes — would be accurate and nearly unreadable. Developers think of
a map entry as `map["key"] = value`, not as `map.table[hash("key")].next.next.next = Node(value)`, so
that is how LeakCanary presents it: the internals of common data structures collapse into the single
reference a developer would have written.

That readability decision has a second payoff. A trace that doesn't spell out the internals of the
runtime's collections doesn't change when those internals change, so the same bug keeps the same
leak fingerprint across runtimes and versions, and stays grouped with itself.

None of this is LeakCanary's invention. Showing a collection by its API rather than its internals is
an old idea, long offered by debuggers and heap analyzers. What LeakCanary borrowed is the sharper
version of it: abstracting *while* the graph is walked, rather than in a pass over the finished path.
Compressing afterwards is the obvious alternative and it works badly — by the time there is a path,
the context needed to describe what was collapsed has been thrown away. Both the idea and the name
for the thing that does it — an **expander** — came from the Android Studio team, and the
[history page](history.md#expanders-and-where-they-came-from) has the provenance.

## The trace reads from the roots down

A leak trace is drawn as a chain: the garbage collection root at the top, the retained object at the
bottom, and every reference on its own line between the two objects it connects. That direction is a
choice, and not the obvious one. Ask a heap analyzer the same question and it answers the other way
round: a path to garbage collection roots starts at the object you asked about and walks back. So
does a stack trace, where the root of the call stack is at the bottom. The notes for LeakCanary 2's
redesign argued for that convention on exactly those grounds, and it was
[prototyped and rejected](history.md#leakcanary-2). Here is one of those prototypes, reading upwards
from the leaking activity to the garbage collection root:

```
MainActivity¹ is leaking, see ╭?─╮ for the potential causes:
╭→ MainActivity¹
│    MainActivity leaking: Activity#mDestroyed is true
├─ Button.mContext
┊    Button leaking: View#mAttachInfo is null
┊                    ╭?───╮
├─ HttpRequestHelper.button
┊               ╭?──────────────╮
├─ MainActivity².httpRequestHelper
│    MainActivity not leaking: Activity#mDestroyed is not true
├─ LinearLayout¹.mContext
│    LinearLayout not leaking: View#mAttachInfo is not null
├─ Toast$TN.mNextView
│    Toast$TN not leaking: LinearLayout¹ is not leaking
┴
```

What settled it is the thing the rest of this page keeps coming back to: the bug is a reference, not
an object. Read from the roots down and you are reading in the direction the references point, so
each line answers "and what does that hold?" until the answer is an object that should have been
gone. Read the other way and you are asking "and what holds this?" repeatedly, which is a good way to
arrive at the object and a bad way to notice the reference that shouldn't exist. The same redesign
gave each reference a line of its own under the object that holds it, for the same reason: version 1
put the object and the reference it followed on one line, and what readers looked at was the class
names. That is the same leak as the prototype above, as version 1 rendered it:

```
* com.example.leakcanary.MainActivity has leaked:
* Toast$TN.mNextView
* ↳ LinearLayout.mContext
* ↳ MainActivity.!(httpRequestHelper)!
* ↳ HttpRequestHelper.!(button)!
* ↳ Button.mContext
* ↳ MainActivity
```

The second decision is what *not* to put on those lines. A version 1 trace could be expanded into a
detailed view that listed every field of every object in it. That existed for a good reason: it was
the only way to find out anything about the state of the objects in the trace and reason about which
reference was the bad one. But it is complete and unreadable — the two or three fields that explain
something are buried in a hundred that don't — and it stopped being necessary once that reasoning
could be encoded instead, as an inspector that reads an object and reports a status. So a trace shows
the reference that was followed, and anything beyond that has to be put there on purpose, as a
**label**: a short string an inspector attaches to an object because it says something useful about
that object's state. The resource id name of a view, whether a service has been created, the fact
that an object is a `Binder` stub — each of those is on a trace because someone made the case that it
earns its line.

That is a different thing from hiding. The path itself is never abridged: every reference from the
root to the retained object is there to read. What's curated is the commentary around them, on the
grounds that a trace which emphasizes everything emphasizes nothing.

## A trace names suspects, not just references

Every object in a leak trace has an expected state: either it should still be in memory, or it
shouldn't. So a leak trace splits in two — an upper part of objects that belong in memory, a lower
part of objects that don't — and the leak is the single reference that joins the two parts. That
reference is the bug, and it is the only place the bug can be: everything above it is legitimately
alive, everything below it should already be gone, and it is the one thing making the second group
depend on the first.

Finding that split is the whole job, which is why LeakCanary annotates the objects *along* the path
instead of only listing the references between them. Each object is marked as known to be in memory
for a good reason, known to be dead, or unknown. Everything above the last object known to be alive
is ruled out, everything below the first object known to be dead is ruled out, and what's left is a
window of unknowns that has to contain the bad reference. That window is what a leak trace
highlights — and what identifies the leak, since leaks that blame the same references are the same
bug.

The window is also narrowed the same way by hand, and it collapses faster than it looks. The states
are ordered: if an object belongs in memory then so does everything above it, and if an object
doesn't then neither does anything below it. So answering the question for a *single* object in the
middle of the window cuts the window roughly in half whichever way the answer comes out. Ten unknown
objects are not ten investigations, they're about three — as long as you start in the middle instead
of at one end.

Every answer is worth keeping, too, because it is a fact about your own types that will be just as
true on the next heap dump. So the knowledge is an extension point on purpose: an inspector reads an
object and reports what you concluded, and LeakCanary reaches the same conclusion by itself from then
on. It ships with what it knows about the Android framework — an application is a singleton and never
leaks, a view whose context is a destroyed activity is leaking — and offers you the same hook for
your own types. Your own code is exactly the code LeakCanary knows nothing about, so teaching it
about your own lifecycles is what narrows traces in your own codebase.
[The LeakCanary Method](https://engineering.block.xyz/blog/the-leakcanary-method) works through that
reasoning on a real leak, from the unknown window to the inspector that removes it.

These verdicts are heuristics, so the bar for adding one is deliberately high: a decision is only
made when the state it reads is unlikely to be the result of a programmer mistake. No matter how many
mistakes you make in your own code, none of them will change whether an activity has been destroyed —
which is what makes that a safe thing to conclude from, and what rules out reading state that your
bugs could have set.

That conservatism matters because a wrong "not leaking" verdict doesn't merely mislabel one object:
the window starts at the last object known not to be leaking, so calling something alive when it
isn't hides whatever was actually holding the leak.

## Leaks you can't fix are reported separately, not hidden

The Android framework and manufacturer ROMs contain leaks that no amount of app code will fix. Mixed
in with your own leaks they are noise, and enough noise turns a leak report into something you stop
reading. So LeakCanary keeps a database of known leak patterns, matches them by reference name, and
reports what matches as **Library Leaks** in their own section, grouped by the pattern they matched
rather than by their path.

Reporting them at all, rather than suppressing them, is also deliberate:

!!! quote "Since I can't do much about this leak, is there a way I can ask LeakCanary to ignore it?"
    There's no way for LeakCanary to know whether a leak is a Library Leak prior to dumping the heap
    and analyzing it. If LeakCanary didn't show the result notification when a Library Leak is found
    then you'd start wondering what happened to the LeakCanary analysis after the dumping toast.

The same database does double duty: knowing that a reference is part of a known library leak is what
lets the path search prefer any path that avoids it, so a library leak is what you're shown when it
is the only thing there is to show.

Entries are scoped to the Android versions and the manufacturers they apply to, because a leak that
has since been fixed, or that only exists in one vendor's implementation, would otherwise be claimed
on devices where it isn't there. And the database only grows the way it was built: by developers
reporting the framework leaks LeakCanary doesn't recognize yet.

## The report comes to you

Analysis results are pushed, not pulled. When an analysis finishes, LeakCanary posts a notification
and prints the result to Logcat, and tapping through leads to the details. Leaks that blame the same
references are grouped into one entry, and marked as new the first time that entry appears, so a bug
that leaks a hundred objects is one thing to read rather than a hundred.

The launcher icon LeakCanary adds is there whether or not anything has leaked, because a tool that
only appears when there's bad news is a tool you can't ask. Developers were confused when the icon
went missing on a healthy app: they want to be able to go, see that nothing is leaking, and be
reassured.

## One heap analyzer, several places to run it

The heap analysis is released as a standalone library rather than as an internal part of the Android
library, in layers that can be used on their own: reading a heap dump, navigating the object graph,
producing an analysis, and the Android-specific heuristics on top.

That's what makes the same analysis available in each of the places a leak can be worth finding: in a
debug build on a device, in UI tests on CI, from a workstation against any debuggable app without
adding a dependency to it, in a plain JVM application, and — experimentally — in release builds in
production. A leak found in one of those contexts is described in exactly the same terms as in
another.

What's next? Customize LeakCanary to your needs with [code recipes](recipes.md), or read about
LeakCanary's [history](history.md).
