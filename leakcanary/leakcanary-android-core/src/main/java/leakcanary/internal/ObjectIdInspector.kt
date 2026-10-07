package leakcanary.internal

import shark.ObjectInspector
import shark.ObjectReporter

/**
 * Labels every object of a leak trace with its id in the heap dump, e.g. `Object id: 0x12c4a8f0`,
 * so that an object LeakCanary reports can be opened in [Shark Dive](https://square.github.io/leakcanary/shark-dive/)
 * by pasting its id: into a `shark://<heap dump>/object?id=…` link, as the `object` of a
 * `shark-dive --cli` command, or into a note.
 *
 * Spelled the way all three read an id back: `0x` and the id's 64 bits as an unsigned hexadecimal
 * number. An Android heap dump records an id in 4 bytes and shark widens it by sign, so an object
 * above the 2 GB mark has a negative id, which this spells with all 16 digits, `0xffffffff82000000`.
 * Shark Dive draws that same object as `0x82000000`, the 32 bit address every other tool prints,
 * but that spelling names no object in a link or on the command line, which take the id.
 *
 * Installed ahead of [leakcanary.LeakCanary.Config.objectInspectors] rather than as one of their
 * defaults: an id isn't an insight about an object, and an app that replaces that list shouldn't
 * lose it.
 */
internal object ObjectIdInspector : ObjectInspector {
  override fun inspect(reporter: ObjectReporter) {
    reporter.labels += "Object id: 0x${java.lang.Long.toHexString(reporter.heapObject.objectId)}"
  }
}
