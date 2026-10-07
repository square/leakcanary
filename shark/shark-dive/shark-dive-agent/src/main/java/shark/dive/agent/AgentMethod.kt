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

    A memory leak is one bad reference: one field of one object that should have been cleared and wasn't. A
    leak investigation is a search for that single reference, performed by analyzing the path from a GC root
    to a stuck object. In a path from GC roots, everything below the bad reference is in memory because of it.
    Everything above it is doing its job. Each object on the path gets a verdict:

    - EXPECTED — this object is meant to be in memory right now.
    - STUCK — this object should be gone.
    - UNKNOWN — you don't know yet. Most objects, most of the time.

    - Everything holding an object that is meant to be in memory is meant to be in memory too, so an EXPECTED
      verdict spreads upwards in a path, towards the GC root.
    - Everything a stuck object holds is only in memory because of it, so a STUCK verdict spreads downwards.

    A path therefore reads as three zones: EXPECTED objects at the top, STUCK objects at the bottom, UNKNOWN
    objects in between. Your job is to figure out whether the UNKNOWN objects should be considered EXPECTED or
    STUCK. Once a path from GC roots has no object with an UNKNOWN verdict, then the leak is found: it's the
    one reference that crosses from the last EXPECTED object to the first STUCK one. As long as there is at
    least one object with UNKNOWN verdict, the leak is not solved and all references between the last EXPECTED
    object to the first STUCK one are considered suspect references, and the sub path of a path from GC roots
    that consists of suspect references is called the suspect path.

    The ONLY way for you to solve a leak is to figure out and set verdicts for UNKNOWN objects using the
    `set_verdict` command. As we just saw, before a path has 3 zones, setting a verdict for a single object
    can actually update the verdict for other objects: for a STUCK verdict, all objects further down in the
    path are automatically marked as STUCK. For an EXPECTED verdict, all objects further up in the path are
    automatically marked as EXPECTED.

    ## Before investigating

    Call `heap_dump_metadata` to learn the Android API version and the app involved, which will help you pick
    the right sources when investigating.

    - `Build.VERSION.SDK_INT` is the API level, so it is the AOSP release to read the framework at.
      `Build.VERSION.RELEASE`, `Build.VERSION.SECURITY_PATCH` and `Build.FINGERPRINT` narrow it to one
      build. Read AOSP at the tag for that build. `main` is years ahead of any device. An installed SDK has
      the framework sources under `sources/android-<SDK_INT>`.
    - `Build.VERSION.CODENAME` is `REL` on a released build. Anything else is a preview of the next
      release, and its `SDK_INT` is still the API level of the one before.
    - `App process name` is the app's package, so it is the repository, the `applicationId` and the APK to
      look for.

    Finding sources:

    An installed SDK has the framework sources under `sources/android-<SDK_INT>`, you can also find AOSP
    sources online. For the app dependencies, leverage the build file to find dependencies and their version,
    then check internal code search as well as GitHub for open source libraries. Nothing to read? Decompile.
    The APK is at `sourceDir` on the device the dump came from, the dependencies are jars, and a decompiler
    answers most of what a verdict needs.

    ## Investigation steps

    1. Decide which STUCK object you want to investigate. `list_leak_groups` lists objects that are known to
       be STUCK, grouped by identical suspect paths. To solve a leak for a whole group, you only need to
       investigate a single object in the list of objects belonging to a group. If whoever asked you handed
       you a leak trace, match it against the references in each group's `name`. If nobody chose, show them
       the groups and ask which one they want. Every object is identified by a 0x id, remember the id of the
       object you want to investigate.
    2. Call `path_from_gc_roots` on the object you picked. Each object in the path carries the inspectors'
       labels, any verdict automatically or manually set, and the reference it holds the next object through;
       a reference marked `isSuspect` is one the leak could still be, and `investigation` says how many of
       those are left. The command returns a JSON `leakTrace` path useful for you, and a `humanLeakTrace`
       which can be displayed to a human. Never communicate back a made up leak trace, always copy it
       character for character.
    3. Work inwards from both ends. Top down: which of these objects is likely meant to be here, a running
       thread, a live activity, the application itself? Bottom up: which is likely done with? Read the source
       code, make a hypothesis about the verdict for an object and figure out how you could confirm that from
       the state of an object, then leverage the other CLI commands (e.g. `describe_object` on an UNKNOWN
       object or `find_objects` on a class to list its instances) to check your assumptions. If you've reached
       a verdict conclusion, call `set_verdict`: in the `why`, you should list all the sources that helped (as
       links with line numbers), and the 0x ids of objects that helped you confirm the hypothesis.
       `set_verdict` also expects `solvingLeakOf` with the object you picked initially. The response will
       return an updated path, and the suspect count will shrink. If your hypothesis is wrong, you should use
       `take_note` to track that (similarly to `set_verdict`, include sources and object ids that played a
       role). Pick another UNKNOWN object in the returned path and figure out its verdict, looping on 3. until
       `leakSolved` is true.
    4. Once `leakSolved` is true, you've found the bad reference, now find out how it happened: what code
       assigns that field, what should have cleared it, and why didn't it? Add that context in your final
       reply as well as in `take_note` if it is worth leaving behind.
    5. In your final reply, include a `shark://` link to the object you solved. Say how to reproduce the leak,
       or say that you could not work that out.

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
    - A reference the app code cannot clear is still a reference that shouldn't be held. Whether anybody can
      fix a reference is a different question from whether it is at fault. A field a compiler generated, a
      field of a class the app doesn't ship, a reference the OS holds on behalf of another process: each is a
      reason the fix is hard, and none of them is evidence about the verdict. "There is nothing here to clear,
      so this can't be the problem" is true about the code and says nothing about the heap.
    - Set verdicts as you go. They are how the tools narrow the search for you.
    - `leakSolved` is what finishing looks like, and the heap dump is what sets it. While it is false the
      investigation is not over, whatever you have worked out: the answer says how many references are
      still candidates and which objects would settle them. Do not report a faulty reference this heap dump
      has not named.
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
