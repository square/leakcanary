package shark.dive

/**
 * What one object *is*, beyond the class it is an instance of, for the kinds Shark Dive can read it off.
 *
 * Which is what tells one object of a class apart from the next one. A path through four `String`s is four
 * rows reading `java.lang.String instance` and nothing else, and the characters in each of them are the
 * whole of the difference; the same goes for a `Thread`'s name and a `Bitmap`'s dimensions. All of these are
 * objects whose own fields say nothing about their size either — a bitmap keeps its pixels in native memory,
 * and a string's characters are folded into it by the size calculator — so there is no field list to read
 * this out of instead.
 *
 * Null for every other object, which is most of a heap dump: nothing here is a summary of an object's fields,
 * and an instance of an app's own class has nothing to say here that `describe_object` doesn't say in full.
 *
 * **Fields rather than the sentence they are drawn as**, which is the one thing to keep right about this.
 * This used to be a `headline: String?` on every model type that carries it, formatted while the heap dump
 * was being read, and that is a window's sentence on a surface that is also read by a program: `"420 × 467
 * pixels, recycled"` is a width, a height and a boolean, and an agent handed the sentence either parses it
 * back out or works with less than the heap dump knows. So the model carries the facts, [headline] draws the
 * sentence for the window, and `AgentJson` writes one field per fact.
 */
sealed class ObjectContent {

  /** The characters of a `java.lang.String`. */
  data class JavaString(val value: String) : ObjectContent()

  /**
   * An `android.graphics.Bitmap`'s dimensions in pixels, and whether it has been recycled.
   *
   * [width] and [height] are null for a bitmap whose `mWidth` and `mHeight` this dump does not carry, which
   * is a bitmap from a platform version that kept them elsewhere.
   */
  data class Bitmap(
    val width: Int?,
    val height: Int?,
    val isRecycled: Boolean
  ) : ObjectContent()

  /** A `java.lang.Thread`'s name, which is the only thing that tells two of them apart. */
  data class Thread(val name: String) : ObjectContent()

  /** How many elements an object array holds, every one of which it keeps alive. */
  data class ObjectArray(val elementCount: Int) : ObjectContent()

  /**
   * How many bytes a primitive array's record is, which is the whole of what the array is.
   *
   * `shark.HeapPrimitiveArray.recordSize`, so it is the elements **plus the record's own header** — 13 bytes
   * on a dump with 4 byte object ids — where the shallow size an answer carries beside this is the elements
   * alone. Which is why a `char[]` reads 57,093 here and 57,080 there, and the two are not a contradiction.
   */
  data class PrimitiveArray(val byteCount: Long) : ObjectContent()
}

/**
 * The one line a window draws this on, under the class name: `"the text"`, `420 × 467 pixels, recycled`.
 *
 * Here rather than in the app module for the reason [Verdict.text] is, and the rule is the same
 * one: a fact the model holds has one spelling, and the five places that draw this — the details panel, the
 * pointer card, a path's objects, the leaks screen, a list of objects — would otherwise be five chances for
 * two of them to word the same bitmap differently. What makes it a window's string and not the model's own
 * is [ObjectContent]: that is what is read, compared and written out as JSON, and this is what it looks
 * like on a screen.
 */
val ObjectContent.headline: String
  get() = when (this) {
    is ObjectContent.JavaString -> "\"$value\""
    is ObjectContent.Bitmap -> "$width × $height pixels" + if (isRecycled) ", recycled" else ""
    is ObjectContent.Thread -> "thread \"$name\""
    is ObjectContent.ObjectArray -> "$elementCount elements"
    is ObjectContent.PrimitiveArray -> "$byteCount bytes"
  }
