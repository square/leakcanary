# History

[Design decisions](design.md) is about why LeakCanary works the way it does. This page is about how
it got that way: what it was before it was a library, and what has been thrown out since.

## It started with wanting to know when to look

Before there was a library there was a habit. Dump the heap at a random moment, pull the file off the
device, open it in [Eclipse MAT](https://eclipse.org/mat/), and go looking for something that had
accumulated. Most of those dumps showed nothing, and the ones that showed something did so long after
the change that caused it.

What was missing wasn't a way to analyze a heap dump. It was a way to know that taking one was worth
the trouble — a trigger. That is the idea LeakCanary was built on, and everything it is now known
for, including analyzing the heap dump on the device, came afterwards.

## The memory diaper

The first version was not a library and was not called LeakCanary. It was a 154 line debug-only class
called `ActivityMemoryDiaper`, written in March 2015 inside Square's point of sale app for Android.
Its notification icon was a nappy with a safety pin:

<img src="../images/history-diaper-icon.png" width="96" alt="The memory diaper notification icon: a nappy with a safety pin">

It watched every destroyed activity through a weak reference, waited five seconds, forced a garbage
collection, and if the activity was still there, dumped the heap and posted a notification that
opened an email with the `.hprof` file attached, under a message that read:

!!! quote ""
    You just found a memory leak and the Register Android team loves you for that. Please email
    seller-android@ so that we can fix it.

### Three ways to notice the same thing

The detection technique changed twice within the first fortnight, and the third attempt is what
LeakCanary still does today.

It started with a weak reference to the activity itself. That was replaced nine days later out of a
worry about the tool changing what it was measuring:

!!! quote "Detect memory leaks with no weak references"
    We're now attaching an object to the activity and checking if it was finalized. This avoids
    holding a weak ref to the activity, which may expand its lifetime and can also be confusing when
    analyzing heap dumps.

A week after that, finalization was replaced by weak reachability, for the reason the current design
still rests on:

!!! quote "Detect weakly reachable leak tags"
    In theory, as soon as a LeakTag becomes weakly reachable it will be enqueued to the reference
    queue. This will happen earlier than the finalize() method being called, so we'll hopefully be
    able to have less false positives due to weak references to activity instances.

Watching for weak reachability rather than for collection is still
[how it works](design.md#watch-then-confirm).

## Becoming a library

The name didn't make the trip. It was settled on Slack, in three messages:

!!! quote ""
    **P-Y:** Is "memory-diaper" a suitable project / repo name? I'm in lack of a better pun.

    **Logan Johnson:** No, it's gross and not really funny. I wouldn't want to see it on a slide deck.

    **Eric Denman:** maybe something "canary in the coal mine" related?

On 5 April 2015 the detection code was lifted out of the app into a repository of its own, under a
commit called *"Introducing LeakCanary, au revoir memory leaks"*, and started using a headless
version of Eclipse MemoryAnalyzer. Analysis moved onto the device, and the code that took a heap dump
could now tell you what was in it.

The month that followed — 5 April to 30 April 2015, 50 commits — is where most of the design that
survives today was settled. It is also where LeakCanary got a canary, twice.

The first one was not drawn for it. It was a sprite lifted from Swipey Bird, a Flappy Bird clone
built at a Square hack week in 2014, in which you made the bird fly by swiping a credit card through
a card reader — with a 250 ms delay between the swipe and the app picking up the reader signal, which
made the game very hard. The bird is a credit card with an eye, a beak and a wing, and the yellow one
made a serviceable canary. It was the first image the readme ever had:

<img src="../images/history-first-canary.gif" width="160" alt="The first canary: a pixel art credit card with an eye and a beak, a sprite from Swipey Bird">

<img src="../images/history-swipey-bird.jpg" width="200" alt="The Swipey Bird poster from Square's Q3 2014 hack week">

A day later it was replaced by an icon of LeakCanary's own, assembled rather than drawn: a photograph
of a real canary, outlined in Photoshop, over a shield taken from one of the online Android icon
generators of the time. The outline was rough, both in the bird and in the shield around it. Two days
after the first public release Romain Guy redrew the whole thing, and two days after that he
committed the vector it was drawn from — his version on the right:

<img src="../images/history-icon-before-after.png" width="480" alt="The hand outlined icon next to Romain Guy's redraw of it">

Two things from that month are worth pulling out.

**It was made to work out of the box.** In April 2015 LeakCanary was a set of parts you wired
together in your `Application` class: construct a heap dump listener, hand it to a watcher, hold on
to the watcher — plus a sample app demonstrating that wiring, and a sample activity carrying the code
that displayed the leaks. Jesse Wilson suggested that instead LeakCanary should have a user interface
and should work out of the box with no configuration: all of that code belonged in the library.
Within days it was there, and the wiring had collapsed to a single `LeakCanary.install(this)`. It
disappeared altogether in version 2, which
[installs itself](design.md#adding-the-dependency-is-the-whole-setup). `KeyedWeakReference`, a class
LeakCanary still has, first appears that same month.

**Analysis moved to a separate process**, because the app it was built for ran out of memory parsing
its own heap dumps. That decision stood for four years and cost every app that used LeakCanary a
guard in its `Application` class, until Shark made in-process analysis cheap enough to be the default
again.

## Released

LeakCanary was open sourced on 7 May 2015, as a single squashed commit. The first public release, the
next day, was **1.3**. Not 1.0: versions 1.0 through 1.2.9 had already been used internally by the
app it came from, and the numbering simply carried on.

## Four heap parsers

The most rewritten part of LeakCanary is the thing that reads the heap dump. It has been replaced
three times, each time for the same reason — the previous one cost too much memory to run on a phone.

**AndroMAT.** The first version vendored Eclipse MAT's parser directly into the library. Not a fresh
fork: an existing Android port by Joe Bowbeer. Bitbucket dropped Mercurial hosting in 2020 and took
AndroMAT with it; the last working copy of that page is a
[2016 snapshot](https://web.archive.org/web/20160114113732/https://bitbucket.org/joebowbeer/andromat).

**HAHA.** Ten days later the parser moved out into a repository of its own,
[HAHA](https://github.com/square/haha), which put the MAT-derived code behind a dependency instead of
inside the library.

**perflib.** In July 2015, HAHA 2.0.2 swapped MAT's parser for
[perflib](https://android.googlesource.com/platform/tools/base/+/2f03004c181baf9d291a9bf992e1b444e83cd82d/perflib/),
the heap dump parser from Android Studio. The changelog entry is four words long about why: *"This
fixes crashes and improves speed a lot."*

**Shark.** perflib was still built for a workstation. LeakCanary 2 replaced it with a parser written
from scratch for a small memory footprint, which is what made analysis in the app's own process
practical: a 160 MB heap dump that takes 2 GB of memory to open in Android Studio opens in 40 MB with
Shark. That number is why the separate process stopped being the default, and it is the argument the
[design page](design.md#the-analysis-runs-on-the-device) makes.

## LeakCanary 2

Released 27 November 2019, after 7 months, 3 alphas and 5 betas, 23 contributors and 493 commits.
About 6000 lines of Java became about 16000 lines of Kotlin, most of the increase being Shark and a
much larger test suite.

Three changes to how leaks are reported came out of that rewrite:

* **The leak trace was redesigned.** The [current layout](design.md#the-trace-reads-from-the-roots-down)
  — root at the top, one reference per line, object state annotated alongside — was not the first
  idea. The design notes argued for putting the garbage collection roots at the *bottom*, by analogy
  with a stack trace, and half a dozen prototypes were drawn that way over a single day in October
  2018 before the direction was kept as it was.
* **Every field stopped being printed.** Version 1 could expand a trace to show every field of every
  object in it. That was replaced by labels: short strings an object inspector attaches because they
  say something worth saying.
* **Reporting became batched.** Version 1 triggered on every retained instance. Version 2 waits for
  the app to go to the background, or for five retained instances in the foreground, then finds all
  the leaks at once and groups the identical ones.

There is a separate [upgrade guide](upgrading-to-leakcanary-2.0.md) for the API changes.

## Expanders, and where they came from

The idea that a leak trace should show a collection the way you would write it, rather than the way
it is implemented, is not LeakCanary's. It came from the leak checking harness the Android Studio
team runs against Android Studio itself, built there by Nathan Paige as a set of [expanders](https://cs.android.com/android-studio/platform/tools/adt/idea/+/mirror-goog-studio-main:bleak/src/com/android/tools/idea/bleak/expander/Expander.kt).

That harness is named BLeak and finds leaks the way [the BLeak paper](https://doi.org/10.1145/3192366.3192376)
does — John Vilk and Emery D. Berger, PLDI 2018 — but the paper is about JavaScript in web
applications, and the expanders are the Android Studio implementation's own. LeakCanary shipped its
version of expanders in 2.8, and then reused the same machinery two years later when implementing
BLeak / heap growth detection, which is the one part of LeakCanary that finds leaks without anything
being watched.
