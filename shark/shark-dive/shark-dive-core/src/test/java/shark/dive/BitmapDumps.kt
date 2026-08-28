package shark.dive

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import shark.HprofWriterHelper
import shark.ValueHolder
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder

/**
 * `android.graphics.Bitmap` as a heap dump has it, with the fields Shark Dive reads and no others.
 *
 * Written as one class with instances of it rather than through the `instance { }` shorthand, because
 * that shorthand declares a class per instance and a dump with two classes both called
 * `android.graphics.Bitmap` is not one any device could produce.
 *
 * [dumpData] is what `am dumpheap -b png` leaves behind, from API 35. Null covers both the versions
 * before that, where the field doesn't exist at all, and a dump of API 35 and up taken without `-b`.
 */
fun HprofWriterHelper.bitmapClass(dumpData: ReferenceHolder = NULL_REFERENCE): Long = clazz(
  className = "android.graphics.Bitmap",
  staticFields = listOf("dumpData" to dumpData),
  fields = listOf(
    "mWidth" to IntHolder::class,
    "mHeight" to IntHolder::class,
    "mRecycled" to BooleanHolder::class,
    "mRequestPremultiplied" to BooleanHolder::class,
    "mNativePtr" to LongHolder::class,
    "mBuffer" to ReferenceHolder::class
  )
)

/**
 * One bitmap of [bitmapClass].
 *
 * [pixels] is its `mBuffer`, the raw pixels a bitmap keeps on the Java heap before API 26 and nowhere
 * after it, so null is what every bitmap of a current device looks like.
 */
fun HprofWriterHelper.bitmapInstance(
  bitmapClassId: Long,
  width: Int,
  height: Int,
  isRecycled: Boolean = false,
  isPremultiplied: Boolean = false,
  nativePointer: Long = 0L,
  pixels: ByteArray? = null
): ReferenceHolder = instance(
  classId = bitmapClassId,
  fields = listOf(
    IntHolder(width),
    IntHolder(height),
    BooleanHolder(isRecycled),
    BooleanHolder(isPremultiplied),
    LongHolder(nativePointer),
    pixels?.let { ReferenceHolder(primitiveByteArray(it)) } ?: NULL_REFERENCE
  )
)

/**
 * The `Bitmap$DumpData` of a dump taken with `am dumpheap -b png`: one compressed image per bitmap the
 * process managed to compress, keyed by the native pointer of that bitmap.
 */
fun HprofWriterHelper.bitmapDumpData(imagesByPointer: Map<Long, ByteArray>): ReferenceHolder {
  val buffers = imagesByPointer.values.map { ReferenceHolder(primitiveByteArray(it)) }
  val dumpDataClassId = clazz(
    className = "android.graphics.Bitmap\$DumpData",
    fields = listOf(
      "count" to IntHolder::class,
      "format" to IntHolder::class,
      "natives" to ReferenceHolder::class,
      "buffers" to ReferenceHolder::class
    )
  )
  return instance(
    classId = dumpDataClassId,
    fields = listOf(
      IntHolder(imagesByPointer.size),
      IntHolder(EncodedImageFormat.PNG.nativeInt),
      ReferenceHolder(primitiveLongArray(imagesByPointer.keys.toLongArray())),
      objectArrayOf(arrayClass("byte[]"), *buffers.toTypedArray())
    )
  )
}

/** A real PNG, so that what a decoder reads and what Shark Dive reads out of its header agree. */
fun pngBytes(
  width: Int,
  height: Int
): ByteArray {
  val bytes = ByteArrayOutputStream()
  ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", bytes)
  return bytes.toByteArray()
}

/** What a field pointing at nothing holds, which is the `mBuffer` of a bitmap of API 26 and up. */
val NULL_REFERENCE = ReferenceHolder(ValueHolder.NULL_REFERENCE)
