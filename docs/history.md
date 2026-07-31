# History

[Design decisions](design.md) is about why LeakCanary works the way it does. This page is about how
it got that way: what it was before it was a library, what has been thrown out since, and where the
evidence for all of it lives.

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
It watched every destroyed activity through a weak reference, waited five seconds, forced a garbage
collection, and if the activity was still there, posted a notification titled `<Activity> has
leaked!` with the content text *"We love you!"* and the notification id `0xDEAFBEEF`.

It dumped the heap from that very first commit. What it didn't do was analyze it. Tapping the
notification opened an email with the `.hprof` file attached, under a message that read:

!!! quote ""
    You just found a memory leak and the Register Android team loves you for that. Please email
    seller-android@ so that we can fix it.

Two weeks later the email was replaced by an upload to a Slack channel called `#register-diaper`,
which is also where the name came from, and which explains the icon it had been given a few days
earlier:

<img src="../images/history-diaper-icon.png" width="96" alt="The memory diaper notification icon: a nappy with a safety pin">

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

On 5 April 2015 the detection code was lifted out of the app into a repository of its own, under a
commit called *"Introducing LeakCanary, au revoir memory leaks"*:

!!! quote ""
    TL;DR: memory leak detection with 0% false positives!

    * Extracted memory leak detection code into a dedicated library, used in dev builds only.
    * On device automated analysis of the heapdump, using a headless version of Eclipse
      MemoryAnalyzer.
    * Now uploading to #register-leakcanary. OOMs go to #register-oom

That is the moment the notification stopped being the whole product. Analysis moved onto the device,
and the thing that had told you a heap dump was worth taking could now tell you what was in it.

The month that followed — 5 April to 30 April 2015, 50 commits — is where most of the design that
survives today was settled. It also produced the first canary, drawn as pixel art, which was the
first image the readme ever had:

<img src="../images/history-first-canary.gif" width="160" alt="The original pixel art canary">

<img src="../images/history-first-notification-icon.png" width="72" alt="The first LeakCanary notification icon: a shield with an exclamation mark and a canary">

Three things from that month are worth pulling out.

**It was told to be automatic.** In April 2015 LeakCanary was a set of parts you wired together in
your `Application` class: construct a heap dump listener, hand it to a watcher, hold on to the
watcher. That this was the wrong shape — that LeakCanary should have a user interface, and should
work out of the box with no configuration, everything automatic — is Jesse Wilson's idea. What the
repository records is the acting on it, over the two days after a commit on 24 April whose entire
message is *"Logan and Jesse feedback"*: on 25 April, *"Move more out of the box stuff into the AAR"*
moved the leak display screen out of the sample app and into the library, and the wiring collapsed to
a single `LeakCanary.install(this)`; the next day, *"Event better out of the box experience"* (sic)
built that screen out. Both survived to the first public release two weeks later, and the call
disappeared altogether in version 2, which
[installs itself](design.md#adding-the-dependency-is-the-whole-setup). The 24 April commit is also
where `KeyedWeakReference` first appears, a class LeakCanary still has.

**The five second wait got its reason.** The delay had been inherited from the diaper, where the
constant was called `DELAY_FOR_GC_S` and the number was picked for the garbage collector. It was
briefly dropped to two seconds, then put back up to five on 9 April with an explanation that names a
completely different mechanism — and that explanation, not the original name, is
[the decision LeakCanary still follows](design.md#watch-then-confirm):

!!! quote "LeakCanary.watchActivities()"
    Automatically start watching activities when they are destroyed, and expect them to be weakly
    reachable 5 seconds after the main thread is idle.

    Android has a lot of temporary memory leaks due to the delayed posting of messages to the main
    thread (e.g. for blinking, scrolling, etc). 5 seconds should give it enough time to clear
    everything.

**Analysis moved to a separate process**, in the same commit, because the app it was built for ran
out of memory parsing its own heap dumps. That decision stood for four years and cost every app that
used LeakCanary a guard in its `Application` class, until Shark made in-process analysis cheap enough
to be the default again.

## Released

LeakCanary was open sourced on 7 May 2015, as a single squashed commit — the month of internal
history was left behind, deliberately, in a repository that said so:

!!! quote ""
    so many changes. too lazy to write down, will squash history anyway

The first public release, the next day, was **1.3**. Not 1.0: versions 1.0 through 1.2.9 had already
been used internally by the app it came from, and the numbering simply carried on.

## Four heap parsers

The most rewritten part of LeakCanary is the thing that reads the heap dump. It has been replaced
three times, each time for the same reason — the previous one cost too much memory to run on a phone.

**AndroMAT.** The first version vendored Eclipse MAT's parser directly into the library. Not a fresh
fork: an existing Android port by Joe Bowbeer, credited in the first readme along with the licence it
forced on the whole project.

!!! quote ""
    Eclipse Public License, because it uses a modifier version of Eclipse's Memory Analyzer (aka MAT,
    but this fork is based of AndroMAT) which is under EPL.

Bitbucket dropped Mercurial hosting in 2020 and took AndroMAT with it; the last working copy of that
page is a [2016 snapshot](https://web.archive.org/web/20160114113732/https://bitbucket.org/joebowbeer/andromat).

**HAHA.** Ten days later the parser moved out into a repository of its own,
[HAHA](https://github.com/square/haha), which put the MAT-derived code and its licence behind a
dependency instead of inside the library. So the EPL only ever applied to the versions used
internally: the first public release vendors none of it and is Apache 2.0.

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
team runs against Android Studio itself, which they pointed out; the concept is theirs, built there
by Nathan Paige as a set of [expanders](https://cs.android.com/android-studio/platform/tools/adt/idea/+/mirror-goog-studio-main:bleak/src/com/android/tools/idea/bleak/expander/Expander.kt).

That harness is named BLeak and finds leaks the way [the BLeak paper](https://doi.org/10.1145/3192366.3192376)
does — John Vilk and Emery D. Berger, PLDI 2018 — but the paper is about JavaScript in web
applications, and the expanders are the Android Studio implementation's own. LeakCanary shipped its
version in 2.8, and reused the same machinery two years later for heap growth detection, which is the
one part of LeakCanary that finds leaks without anything being watched.

## Where the history lives

Almost none of the above is in [square/leakcanary](https://github.com/square/leakcanary), which
begins with a single squashed commit on 7 May 2015. The rest survives in three places:

* **`squareup/android-leakcanary`** — the pre-public history, on two branches with no common
  ancestor: `stash_master_prior_to_github` (the internal repository, 5–30 April 2015) and
  `github_master_prior_to_clean_history` (release preparation, 30 April – 7 May 2015).
* **`squareup/android-haha`** — the same for HAHA.
* **`squareup/android-register`** — the app LeakCanary was born inside, with `ActivityMemoryDiaper`
  and every revision it went through there.

All three are private Square repositories, and the first two are archived. The servers that hosted
the originals are gone, and the internally released artifacts were never published anywhere that
outlived them, so those repositories are the only surviving copy.
