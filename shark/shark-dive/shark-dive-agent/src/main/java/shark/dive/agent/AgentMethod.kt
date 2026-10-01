package shark.dive.agent

import shark.dive.unwrappedMarkdown

/**
 * The method an agent is asked to follow, which is the part of this surface that isn't data.
 *
 * **Two texts, and neither of them is in an answer.** [SURFACE] is how to work here at all — the reason on
 * every call, the window somebody is watching, the links to hand back, the gap to admit — and it is
 * [AgentCommandLine.SURFACE_METHOD_OPTION]. [LEAK] is how to find a faulty reference, and it is
 * [AgentCommandLine.LEAK_METHOD_OPTION]. Both are text this build prints, with no run and no heap dump.
 *
 * **Which is a read rather than an answer, and that is what it is for.** Each of them was carried in a tool's
 * answer — [LEAK] in a field of `list_leaks`, [SURFACE] prepended to whatever a session asked first — on the
 * grounds that a tool result is the one thing an agent is certain to read, since it asked for the answer. True,
 * and what it costs is paid per call rather than per session: an investigation of four leaks read the whole of
 * how to narrow a chain four times, and a session is handed how to work here by a call that only wanted to
 * know which heap dumps are open. An option is read once by whoever has a use for it, and nothing about it is
 * optional to find — `--help` lists both, which is the one text an agent reaches for having been told nothing.
 *
 * So **an investigation that never read either option never read the method**, and that is the intended
 * consequence rather than a hole to patch. What points a model at [LEAK] is three places that each know a leak
 * is in hand: `list_leaks`'s own description, [AgentTools.NEXT_WITH_A_NEW_DUMP] on the answer that opened the
 * dump, and the paragraph of [SURFACE] itself.
 *
 * **And no example in either text names a real leak.** `Owner.field` is the shape a reference is spelled in
 * rather than a reference, because an eval run reads the method before it has asked the heap dump anything: a
 * concrete `Holder.activity` written in here is one scenario's answer key printed into the answer that run is
 * scored against, which is exactly the kind of channel `shark/shark-dive/notes/agent-eval.md` counts. It has
 * happened, and it voided every number that eval had produced.
 *
 * **It is prose because its reader is a language model**, which is the one place in this app where a
 * paragraph beats a label — the window says `Verdict` in one word to someone who already knows what a
 * verdict is for. What keeps the prose honest is that the tools enforce the two claims it can't make on its
 * own: a verdict is refused without a `why`, and `conclude` is refused until the heap dump
 * itself says one reference is at fault. So the method describes what the tools will hold you to rather
 * than asking to be trusted.
 *
 * Adapted from [The LeakCanary Method](https://engineering.block.xyz/blog/the-leakcanary-method), which is
 * the same five phases done by hand.
 */
internal object AgentMethod {

  /**
   * [SURFACE] as it is written here, wrapped at the column the rest of this repository is.
   *
   * **Short on purpose, because it is the one of the two every session is expected to read** — so a paragraph
   * added here is a paragraph read by an agent that only wanted to know which heap dumps are open. [LEAK] is
   * five times the size and is read by the investigations it is about.
   * `shark/shark-dive/notes/agent-surface.md` has the measurement.
   */
  private val WRAPPED_SURFACE = """
    You are reading a heap dump through Shark Dive, a window a person may be watching. Everything you
    ask is a read of that dump, and everything you conclude is written into it where the next reader — a
    colleague, another agent, the same person in a month — will find it.

    **For anything about a leak, read `${AgentCommandLine.LEAK_METHOD_OPTION}` before the first chain.** That
    is where the method is, and nowhere else on this surface has it: what a leak is, how a verdict spreads, and
    the order that finds the faulty reference. It needs no run and no heap dump, and it is one read per
    investigation rather than per leak. An investigation that skipped it is one `conclude` will refuse.

    ## On every call

    - **Every call takes a `reason`**: what you are trying to learn, or what you concluded from the last
      answer. It goes in this run's log next to the read it caused, which is what makes an investigation
      something a person can follow afterwards rather than a conclusion they have to trust.
    - **`show` puts what you are looking at on screen.** Use it when you reach something that matters. The
      window is how the person watching follows the work, and it costs you one call.
    - **Put the `shark://` links you are answered with in your reply.** `show` and `conclude` hand one back:
      it opens that exact object, in this heap dump, with your notes on it. A link names the dump rather than
      the window, so it still works once this run has ended — it opens the file again. Whoever asked you can
      click it while reading your answer, and again next week. So write "the leak is
      `Owner.field`(shark://…)", with the reference this dump named, rather than describing which screen to
      open and what to click — a link is the difference between an answer they have to take your word for and
      one they can go and look at.
    - **Say what you did not check.** An answer with a stated gap is worth more than a confident one with
      an unstated gap.
  """.trimIndent()

  /**
   * [LEAK] as it is written here, wrapped at the column the rest of this repository is.
   *
   * Kept in one string rather than assembled from the tool descriptions, because it is an argument and not
   * a list: each step is worth doing because of the step before it.
   */
  private val WRAPPED_LEAK = """
    ## What a leak is

    A memory leak is ONE bad reference. Not a chain, not a subsystem, not "the activity is retained": one
    field of one object that should have been cleared and wasn't. Everything below that reference is in
    memory because of it and is not itself at fault. Everything above it is doing its job.

    So an investigation is a search for that single reference, and the chain from a GC root to a stuck
    object is where it is. Each object on the chain gets a verdict:

    - EXPECTED — this object is meant to be in memory right now.
    - STUCK — this object should be gone.
    - UNKNOWN — you don't know yet. Most objects, most of the time.

    Those are the three words the window shows the person watching, so they are the three words to think in
    and to write. Two rules turn them into an answer, and the tools apply both for you:

    - Everything holding an object that is meant to be in memory is meant to be in memory too, so an
      EXPECTED verdict spreads upwards.
    - Everything a stuck object holds is only in memory because of it, so a STUCK verdict spreads
      downwards.

    A chain therefore reads as three zones: EXPECTED at the top, STUCK at the bottom, UNKNOWN in between.
    **The leak is the one reference that crosses from the last EXPECTED object to the first STUCK one.**
    While the UNKNOWN zone is more than one reference wide, you have not found it — you have narrowed it.

    ## The order to work in

    1. **Find something that shouldn't be there.** `list_leaks` is the heap dump's own answer: objects the app
       itself handed to LeakCanary and said were done with, plus what the inspectors recognised. Start with a
       leak whose objects the app watched, which is the strongest evidence a heap dump carries.
       **Pick one here, then work its chain: this list is where an investigation starts and not somewhere to
       come back to.** Your own verdicts change it. A `STUCK` set halfway up a chain makes that object the
       leak and folds what it held into it, retained bytes included — so the object you have been
       investigating leaves the list, and the bytes it was reported as retaining are now reported against the
       object you narrowed to, which dominates almost none of them. The same reference is still holding the
       same objects. Where you have got to is in the chain, so read that rather than this list again.
    2. **Get the chain.** `chain_from_gc_root` for one stuck object. Read every step. The steps already
       carry the inspectors' labels and any verdict someone has set.
    3. **Work inwards from both ends.** Top down: which of these objects is obviously meant to be here — a
       running thread, a live activity, the application itself? Bottom up: which is obviously done with?
       Set what you can defend with `set_verdict` and watch the UNKNOWN zone shrink.
    4. **Attack what is left.** This is the part that takes work, and it is where the tools earn their
       keep:
       - `describe_object` on an object in the unknown zone. Read its fields and its inspector labels.
       - `find_objects` on a class you have assumed something about. Two instances of a class you took for
         a singleton is the answer to a surprising number of leaks: the object on the chain is not the
         instance you think it is.
       - Read the code that declares the field holding the next step, at the version this dump is of — see
         below. A verdict you can point at a line of code for is a verdict that survives review.
    5. **Isolating the reference is not the root cause.** When one reference is left, you know *where* the
       problem is. You still do not know *how* it happened, and stopping here is the most common way an
       investigation fails. Keep going: what code assigns that field, what should have cleared it, and why
       didn't it? The answer is usually a sequence of events, not a line.
    6. **Say how to reproduce it**, or say that you couldn't work that out. A root cause nobody can trigger
       is a hypothesis.

    ## Read the code, at the version the dump is of

    The heap dump says what is held. Only the code says why, so an investigation that stays inside the heap
    dump stops at the reference and calls that the root cause. Read what assigns the faulty field, and what
    should have cleared it.

    **Which copy of the code matters as much as reading it.** A class that changed between two versions is a
    root cause nobody can reproduce and a fix that doesn't apply. The dump itself says which versions:

    - **The OS.** A class is an object of the dump like any other, so reading one is two calls: `find_objects`
      with `className=android.os.Build${'$'}VERSION`, `exactMatch=true` and `kinds=CLASS` for its address, then
      `describe_object` on that address for its static fields. `SDK_INT` is the API level, with `RELEASE`,
      `CODENAME` and `SECURITY_PATCH` beside it, and `android.os.Build` has the device and the build
      fingerprint. Read AOSP at the tag for that release — an installed SDK has the framework sources
      under `sources/android-<SDK_INT>` — and not `main`, which is years ahead of any device.
    - **The app.** Its `android.content.pm.ApplicationInfo` is in most dumps: `processName` and `dataDir`
      name the app, `sourceDir` is the APK it was installed from, `minSdkVersion` is a field of its own,
      `seInfo` often carries `targetSdkVersion=<n>`, and bit `0x2` of `flags` is `FLAG_DEBUGGABLE`. The app's
      own version number usually is **not** there: `BuildConfig` constants are compiled into their call sites,
      so the class is never loaded and never appears in a dump. Ask for it rather than guessing it.
    - **The libraries.** A dependency's version isn't in the dump either. Ask for the build file or the
      lockfile, or read the versions out of the APK at `sourceDir`, and then read that library at that tag. A
      leak fixed two releases ago is worth finding out about before writing anything else.
    - **Nothing to read?** Decompile. The APK is at `sourceDir` on the device the dump came from, the
      dependencies are jars, and a decompiler answers most of what a verdict needs.

    Then **say which version of what you read**. "Nothing clears this in onDestroy" about a class the app
    doesn't ship is the confident wrong answer this section exists to stop.

    ## Rules you will be held to

    - **One chain is the whole investigation.** Any path from a GC root to a stuck object is a good path,
      and whether something else holds that object too changes nothing: one path is one leak to fix. So never
      go looking for other holders. The questions are which of these objects should have been gone, and which
      reference is keeping them — never whether this reference is the only one.
    - **Every verdict needs a `why` another reader can check.** A field value, an inspector label, the
      app's own watcher record, a line of source. Not "this is probably a cache" and not "activities are
      usually leaked this way". `set_verdict` takes that as `why` and refuses a blank one, it is kept with
      the verdict in this heap dump, and a `why` that isn't evidence is worse than none.
    - **The question a verdict answers is: is this object's work done?** Every object on the chain exists to
      do something, and when that thing has happened the object should be gone — so a verdict is an answer
      about *this* object's work, not about how its class reads. Not whether it looks like infrastructure,
      not whether it sounds long-lived, not whether it is too small to matter. And the evidence is often not
      on the object you are asking about: a callback, a receiver or a listener with no state of its own is
      answered by what it forwards into, so read one step further before calling it `EXPECTED`.
    - **A reference you cannot clear is still a reference that shouldn't be held.** Whether anybody *can*
      fix a reference is a different question from whether it is at fault, and it belongs to step 5 rather
      than to a verdict. A field a compiler generated, a field of a class the app doesn't ship, a reference
      the OS holds on behalf of another process: each is a reason the fix is hard, and none of them is
      evidence about the verdict. "There is nothing here to clear, so this can't be the problem" is the most
      comfortable wrong turn on this surface, because it is true about the code and says nothing at all
      about the heap.
    - **Set verdicts as you go, not at the end.** They are how the tools narrow the search for you, and
      they are what the person at the window sees you doing.
    - **`conclude` is the only way to finish**, and it will refuse you unless the heap dump agrees that one
      reference is at fault. If it refuses, the investigation is not over — the message says what is
      missing. Do not report a root cause you could not conclude.
  """.trimIndent()

  /**
   * How to work on this surface at all, which [AgentCommandLine.SURFACE_METHOD_OPTION] prints.
   *
   * [unwrappedMarkdown] for the reason [LEAK] is, and the same call: the reader is a model reading text and
   * not a diff.
   */
  val SURFACE = unwrappedMarkdown(WRAPPED_SURFACE)

  /**
   * What to do with a leak, in the order it works, which [AgentCommandLine.LEAK_METHOD_OPTION] prints.
   *
   * [unwrappedMarkdown] because the reader is a model reading text and not a diff. Handed over as
   * [WRAPPED_LEAK] is written, every sentence of it arrives broken at whatever column this file happened to
   * wrap at, and what is left of the line breaks after unwrapping is the ones that mean something: the blank
   * line between two paragraphs, and the one in front of a heading or an item. Same reading
   * `shark.dive.Note.ofDocument` gives a page of the reference, for the same reason.
   *
   * Still unwrapped now that this is printed rather than answered with, and for the same reason one way round
   * as the other: a terminal wraps a paragraph to the width it has, and this file's column is only ever that
   * width by accident.
   */
  val LEAK = unwrappedMarkdown(WRAPPED_LEAK)
}
