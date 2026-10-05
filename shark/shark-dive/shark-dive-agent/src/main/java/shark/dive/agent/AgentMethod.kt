package shark.dive.agent

import shark.dive.unwrappedMarkdown

/**
 * The method an agent is asked to follow, which is the part of this surface that isn't data.
 *
 * **One text, and it is not in an answer.** [LEAK] is how to solve a leak, and it is
 * [AgentCommandLine.LEAK_METHOD_OPTION]: text this build prints, with no run and no heap dump. How to work
 * on the surface at all — the reason on every call, the links to hand back, the gap to admit — is four
 * paragraphs of `--help`, where an agent that has been told nothing already is.
 *
 * **Which is a read rather than an answer, and that is what it is for.** This was carried in a field of
 * `list_leak_groups`'s answer, on the grounds that a tool result is the one thing an agent is certain to
 * read, since it asked for the answer. True, and what it costs is paid per call rather than per session: an
 * investigation of four leaks read the whole of how to narrow a path four times. An option is read once by
 * whoever has a use for it, and nothing about it is optional to find — `--help` names it.
 *
 * So **an investigation that never read the option never read the method**, and that is the intended
 * consequence rather than a hole to patch. What points a model here is three places that each know a leak
 * is in hand: `list_leak_groups`'s own description, [AgentTools.NEXT_WITH_A_NEW_DUMP] on the answer that
 * opened the dump, and the help itself.
 *
 * **And no example here names a real leak.** `Owner.field` is the shape a reference is spelled in
 * rather than a reference, because an eval run reads the method before it has asked the heap dump anything: a
 * concrete `Holder.activity` written in here is one scenario's answer key printed into the answer that run is
 * scored against, which is exactly the kind of channel `shark/shark-dive/notes/agent-eval.md` counts. It has
 * happened, and it voided every number that eval had produced.
 *
 * **It is prose because its reader is a language model**, which is the one place in this app where a
 * paragraph beats a label — the window says `Verdict` in one word to someone who already knows what a
 * verdict is for. What keeps the prose honest is that the tools enforce the two claims it can't make on its
 * own: a verdict is refused without a `why`, and a verdict that contradicts the ones already recorded has
 * to say so. What it does not have to claim is the answer — `leakSolved` is the heap dump's own reading of
 * the verdicts, so the method describes what the tools will hold you to rather than asking to be trusted,
 * and the one thing it tells an agent to work towards is a field nobody can write.
 *
 * Adapted from [The LeakCanary Method](https://engineering.block.xyz/blog/the-leakcanary-method), which is
 * the same five phases done by hand.
 */
internal object AgentMethod {

  /**
   * [LEAK] as it is written here, wrapped at the column the rest of this repository is.
   *
   * Kept in one string rather than assembled from the tool descriptions, because it is an argument and not
   * a list: each step is worth doing because of the step before it.
   */
  private val WRAPPED_LEAK = """
    ## What a leak is

    A memory leak is one bad reference: one field of one object that should have been cleared and wasn't.
    Everything below that reference is in memory because of it, and none of it is at fault. Everything
    above it is doing its job. "The activity is retained" is the symptom and a path is where to look;
    name the field.

    So an investigation is a search for that single reference, and the path from a GC root to a stuck
    object is where it is. Each object on the path gets a verdict:

    - EXPECTED — this object is meant to be in memory right now.
    - STUCK — this object should be gone.
    - UNKNOWN — you don't know yet. Most objects, most of the time.

    The window shows the person watching those same three words, so write in them too. Two rules turn
    them into an answer, and the tools apply both for you:

    - Everything holding an object that is meant to be in memory is meant to be in memory too, so an
      EXPECTED verdict spreads upwards.
    - Everything a stuck object holds is only in memory because of it, so a STUCK verdict spreads
      downwards.

    A path therefore reads as three zones: EXPECTED at the top, STUCK at the bottom, UNKNOWN in between.
    The leak is the one reference that crosses from the last EXPECTED object to the first STUCK one. You
    have found it once the UNKNOWN zone is down to one reference.

    ## What you decide, and what the heap dump decides

    You never decide which reference is at fault. Shark Dive derives that from the verdicts recorded
    about the objects on the path: the moment they leave a single reference crossing from EXPECTED to
    STUCK, it names that reference and sets `leakSolved` to true. There is no command for reporting a
    root cause.

    What you do is answer one question, object by object: is this object's work done? Each answer you
    can defend rules out one more reference. So the whole of the work is:

    - set a verdict, with evidence, on an object you can settle
    - read what that did to the suspect stretch
    - pick the next object to settle, and repeat

    `leakSolved` going true is the end of the search.

    ## The order to work in

    1. Find something that shouldn't be there. `list_leak_groups` is the heap dump's own answer: objects
       the app itself handed to LeakCanary and said were done with, plus what the inspectors recognised,
       gathered into one group per leak. Start with a group whose objects the app watched, which is the
       strongest evidence a heap dump carries.
    2. Pick the leak you are solving, and remember one object of it. Take an address out of the group's
       `objects` — the first will do — and work that one object from here on: every object in a group leaks
       for the same reason, so solving one solves the group. If whoever asked you handed you a leak trace,
       match it against the references in each group's `name`. If nobody chose, show them the groups and
       ask which one they want.
       Read this list once, at the start. Your own verdicts change it: a `STUCK` set halfway up a path
       makes that object the leak and folds what it held into it, retained bytes included. So the object
       you have been investigating leaves the list, and the bytes it was reported as retaining are now
       reported against the object you narrowed to, which dominates almost none of them. The same
       reference is still holding the same objects. Where you have got to is in the path, so read the
       path.
    3. Get the path. `path_from_gc_roots` on the object you picked. Read every object of it. Each carries
       the inspectors' labels, any verdict someone has set, and the reference it holds the next object
       through; a reference marked `isSuspect` is one the leak could still be, and `investigation` says how
       many of those are left.
    4. Work inwards from both ends. Top down: which of these objects is obviously meant to be here, a
       running thread, a live activity, the application itself? Bottom up: which is obviously done with?
       Set what you can defend with `set_verdict`, always passing `solvingLeakOf` with the object you
       picked, and watch the candidate count fall in its answer.
    5. Work on what is left. This is the part that takes work:
       - `describe_object` on an object in the unknown zone. Read its fields and its inspector labels.
       - `find_objects` on a class you have assumed something about. Two instances of a class you took for
         a singleton is the answer to a surprising number of leaks: the object on the path is not the
         instance you think it is.
       - Read the code that declares the field holding the next step, at the version this dump is of. See
         below. Point a verdict at a line of code whenever you can.
    6. `leakSolved` is true: now find out how. You know where the problem is. You do not know how it
       happened, and stopping here is where investigations usually fail. Keep going: what code assigns
       that field, what should have cleared it, and why didn't it? The answer is usually a sequence of
       events. Nothing on this surface can answer it, and nothing is waiting for you to type it in: it
       goes in your reply, and in a `take_note` if it is worth leaving behind.
    7. Say how to reproduce it, or say that you could not work that out.

    ## Telling a person what you found

    Two things:

    - The `humanLeakTrace` as the tool gave it to you, character for character — `leakTrace` beside it is
      the same path as fields for you to read. Never write a leak trace yourself, not from the objects of a
      path and not from one you were shown earlier. A trace that drops a step or moves the underline looks
      exactly like the real thing to whoever you hand it to.
    - The `shark://` link to the object you solved, which `show` and every path answer hand back. It opens
      that object, in this heap dump, with the notes on it, long after this run has ended.

    Then the root cause in your own words.

    ## Read the code, at the version the dump is of

    The heap dump says what is held. Only the code says why, so an investigation that stays inside the heap
    dump stops at the reference and calls that the root cause. Read what assigns the faulty field, and what
    should have cleared it.

    Which copy of the code matters as much as reading it: a class that changed between two versions gives a
    root cause nobody can reproduce and a fix that doesn't apply. The dump itself says which versions.

    `heap_dump_metadata` is the first call of this section, because it is the one call that answers which
    Android version and which app, the two things you need before opening any source at all:

    - `Build.VERSION.SDK_INT` is the API level, so it is the AOSP release to read the framework at.
    - `App process name` is the app's package, so it is the repository, the `applicationId` and the APK to
      look for. It is `ApplicationInfo.processName`, which is the package name unless the app declares an
      `android:process` of its own. The usual form of that is `<package>:suffix`, so the package is still
      what comes before the colon.

    `Build.MANUFACTURER` is beside them, and a manufacturer that isn't `Google` means the framework on that
    device is not the AOSP you are about to read. The rest of this section is for what that map does not
    carry.

    - The OS, past the API level. `RELEASE`, `CODENAME`, `SECURITY_PATCH` and the build fingerprint are a
      read of the classes themselves, and a class is an object of the dump like any other, so that is two
      calls: `find_objects` with `className=android.os.Build${'$'}VERSION`, `exactMatch=true` and
      `kinds=CLASS` for its address, then `describe_object` on that address for its static fields.
      `android.os.Build` has the device and the fingerprint the same way. Read AOSP at the tag for that
      release, and not `main`, which is years ahead of any device. An installed SDK has the framework
      sources under `sources/android-<SDK_INT>`.
    - The app, past its package. Its `android.content.pm.ApplicationInfo` is in most dumps: `sourceDir` is
      the APK it was installed from, `dataDir` the directory it writes to, `minSdkVersion` is a field of
      its own, `seInfo` often carries `targetSdkVersion=<n>`, and bit `0x2` of `flags` is `FLAG_DEBUGGABLE`.
      The app's own version number usually is not there: `BuildConfig` constants are compiled into their
      call sites, so the class is never loaded and never appears in a dump. Ask for it.
    - The libraries. A dependency's version isn't in the dump either. Ask for the build file or the
      lockfile, or read the versions out of the APK at `sourceDir`, and then read that library at that tag.
      A leak fixed two releases ago is worth finding out about before writing anything else.
    - Nothing to read? Decompile. The APK is at `sourceDir` on the device the dump came from, the
      dependencies are jars, and a decompiler answers most of what a verdict needs.

    Then say which version of what you read.

    ## Rules you will be held to

    - One path is the whole investigation. Any path from a GC root to a stuck object is a good path, and
      whether something else holds that object too changes nothing: one path is one leak to fix. So never
      go looking for other holders. The questions are which of these objects should have been gone, and
      which reference is keeping them.
    - Every verdict needs a `why` another reader can check. A field value, an inspector label, the app's
      own watcher record, a line of source. Not "this is probably a cache". `set_verdict` takes that as
      `why`, refuses a blank one, and keeps it with the verdict in this heap dump.
    - The question a verdict answers is: is this object's work done? Every object on the path exists to do
      something, and when that thing has happened the object should be gone. So a verdict is about this
      object's own work. How its class reads, whether it looks like infrastructure or is too small to
      matter, is not evidence. And the evidence is often not on the object you are asking about: a
      callback, a receiver or a listener with no state of its own is answered by what it forwards into, so
      read one step further before calling it `EXPECTED`.
    - A reference you cannot clear is still a reference that shouldn't be held. Whether anybody can fix a
      reference is a different question from whether it is at fault, and it belongs to step 5. A field a
      compiler generated, a field of a class the app doesn't ship, a reference the OS holds on behalf of
      another process: each is a reason the fix is hard, and none of them is evidence about the verdict.
      "There is nothing here to clear, so this can't be the problem" is true about the code and says
      nothing about the heap.
    - Set verdicts as you go. They are how the tools narrow the search for you, and they are what the
      person at the window sees you doing.
    - `leakSolved` is what finishing looks like, and the heap dump is what sets it. While it is false the
      investigation is not over, whatever you have worked out: the answer says how many references are
      still candidates and which objects would settle them. Do not report a faulty reference this heap dump
      has not named. If you are sure you know which one it is, record the verdict that proves it and watch
      the count fall to one.
  """.trimIndent()

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
