package shark.explorer

import shark.HeapGraph
import shark.HeapObject
import shark.HeapObject.HeapInstance
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump

/**
 * The pixels of the bitmaps of a heap dump, where the dump has any.
 *
 * A bitmap's pixels are in one of two places, and which one decides whether a heap dump can show the
 * image at all:
 *
 * - **Before API 26** they are a `byte[]` on the Java heap, `Bitmap.mBuffer`, so every dump has them
 *   and [imageOf] decodes them.
 * - **From API 26** they are in native memory, which a Java heap dump does not cover. The only pixels
 *   such a dump has are the ones the process was asked to compress into the dump before it was
 *   written: `am dumpheap -b png` makes `Bitmap.dumpAll` fill in a static `Bitmap.dumpData` with one
 *   compressed image per live bitmap, keyed by its native pointer. That flag is API 35 and up.
 *
 * So a dump of API 26 to 34, and any dump of API 35 and up taken without `-b`, has no pixels at all —
 * and the only place they still exist is the process that wrote it, which is what [addNativePixels]
 * is for. Dumping that process again with `-b png` yields the same pointer keyed images, and the
 * native pointer of a bitmap is what ties them back to the bitmaps of the dump being read.
 *
 * Reads the heap dump, so not from the UI thread. See [HeapExplorer].
 */
internal class HeapBitmaps(private val graph: HeapGraph) {

  private val bitmapClass by lazy { graph.findClassByName(BITMAP_CLASS_NAME) }

  /** The images `Bitmap.dumpAll` left in the dump, by the native pointer of the bitmap they show. */
  private val dumpedImages: PointerImages by lazy { readDumpData() }

  /** The same, pulled off the live process rather than found in the dump. See [addNativePixels]. */
  private var liveImages: PointerImages = PointerImages.NONE

  /** Whether [heapObject] is a bitmap, which is what makes a cell one an image can be drawn on. */
  fun isBitmap(heapObject: HeapObject): Boolean =
    heapObject is HeapInstance && heapObject.instanceClassId == bitmapClass?.objectId

  /**
   * The image of the bitmap [objectId], or null when nothing has its pixels.
   *
   * [maxDimension] caps the size of the pixels handed back, since a full screen bitmap is megabytes of
   * them and a rectangle on a treemap is a hundred pixels across. It does nothing for a compressed
   * image, which is small already and which only a decoder can resize.
   */
  fun imageOf(
    objectId: Long,
    maxDimension: Int
  ): BitmapImage? {
    val bitmap = graph.findObjectByIdOrNull(objectId) as? HeapInstance ?: return null
    if (!isBitmap(bitmap)) {
      return null
    }
    val size = bitmap.drawableSize() ?: return null
    val nativePointer = bitmap.nativePointer()
    return dumpedImages.imageOf(nativePointer, size)
      ?: bitmap.readBufferPixels(size, maxDimension)
      ?: liveImages.imageOf(nativePointer, size)
  }

  /**
   * How many bitmaps this heap dump has, and how many of them anything has the pixels of.
   *
   * A pass over every bitmap of the dump, reading no pixels: what a compressed image belongs to is
   * already indexed, and whether an `mBuffer` holds enough bytes for the bitmap it's on is its length.
   */
  fun counts(): BitmapCounts {
    var count = 0
    var withImageCount = 0
    var mismatchedCount = 0
    bitmapClass?.instances?.forEach { bitmap ->
      count++
      val size = bitmap.drawableSize()
      val pointer = bitmap.nativePointer()
      when {
        size != null && bitmap.hasImage(size) -> withImageCount++
        // Pixels were handed over for this bitmap's pointer and refused, which is the pointer now
        // belonging to something else. Worth a count of its own: it's the one failure that looks like
        // the fetch having silently done nothing.
        dumpedImages.bytesOf(pointer) != null || liveImages.bytesOf(pointer) != null ->
          mismatchedCount++
      }
    }
    return BitmapCounts(
      count = count,
      withImageCount = withImageCount,
      carriesNativePixels = dumpedImages.isNotEmpty() || liveImages.isNotEmpty(),
      mismatchedCount = mismatchedCount
    )
  }

  /**
   * Takes the pixels of a live process's bitmaps, for the bitmaps of this dump that are still the same
   * bitmap, and says how many that turned out to be.
   *
   * A native pointer is only the address of a native allocation, and an address is reused once what was
   * there is freed: a bitmap of this dump that has since been recycled can have its pointer held by an
   * unrelated bitmap of the live process. So an image is only accepted for a bitmap it is the size of.
   */
  fun addNativePixels(pixels: NativeBitmapPixels): BitmapCounts {
    liveImages = PointerImages.of(pixels)
    return counts()
  }

  /** Whether anything has the pixels of this bitmap, without reading any of them. */
  private fun HeapInstance.hasImage(size: PixelSize): Boolean {
    val pointer = nativePointer()
    return dumpedImages.imageOf(pointer, size) != null ||
      liveImages.imageOf(pointer, size) != null ||
      bufferPixelLayout(size) != null
  }

  /** The size the dump says the bitmap is, or null when it can't be drawn at all. */
  private fun HeapInstance.drawableSize(): PixelSize? {
    val width = readIntField("mWidth") ?: return null
    val height = readIntField("mHeight") ?: return null
    return if (width <= 0 || height <= 0 || isRecycled()) null else PixelSize(width, height)
  }

  private fun HeapInstance.nativePointer(): Long? =
    this[BITMAP_CLASS_NAME, "mNativePtr"]?.value?.asLong

  private fun HeapInstance.isRecycled(): Boolean =
    this[BITMAP_CLASS_NAME, "mRecycled"]?.value?.asBoolean == true

  private fun HeapInstance.readIntField(name: String): Int? =
    this[BITMAP_CLASS_NAME, name]?.value?.asInt

  /**
   * How wide a pixel of this bitmap's `mBuffer` is, or null when it has none or holds too few bytes.
   *
   * `mBuffer` is the raw pixels and nothing in the dump says which `Bitmap.Config` they are in — the
   * config lives in native memory even before API 26. What the buffer does say is how many bytes each
   * pixel took, which is the one thing the layout depends on, so that's what this goes by. A buffer
   * bigger than the bitmap needs is one that was allocated for a larger bitmap and reconfigured down,
   * so the division rounds down and the bytes past the end are left alone.
   */
  private fun HeapInstance.bufferPixelLayout(size: PixelSize): PixelLayout? {
    val buffer = this[BITMAP_CLASS_NAME, "mBuffer"]?.valueAsPrimitiveArray ?: return null
    return PixelLayout.ofByteCount((buffer.byteSize / size.pixelCount).toInt())
  }

  /** The pixels of a pre API 26 bitmap, decoded off its `mBuffer`, scaled down to [maxDimension]. */
  private fun HeapInstance.readBufferPixels(
    size: PixelSize,
    maxDimension: Int
  ): BitmapImage.Pixels? {
    val layout = bufferPixelLayout(size) ?: return null
    val bytes = (this[BITMAP_CLASS_NAME, "mBuffer"]?.valueAsPrimitiveArray?.readRecord()
      as? ByteArrayDump)?.array ?: return null
    // Premultiplied is what the framework asks the allocator for and what it stores, so undoing it is
    // part of reading the pixels rather than something the view could do later.
    val isPremultiplied = this[BITMAP_CLASS_NAME, "mRequestPremultiplied"]?.value?.asBoolean
      ?: this[BITMAP_CLASS_NAME, "mIsPremultiplied"]?.value?.asBoolean
      ?: true
    return scaleDown(size, maxDimension) { targetWidth, targetHeight ->
      IntArray(targetWidth * targetHeight) { index ->
        val x = (index % targetWidth) * size.width / targetWidth
        val y = (index / targetWidth) * size.height / targetHeight
        val offset = (y * size.width + x) * layout.byteCount
        layout.argbAt(bytes, offset, isPremultiplied)
      }
    }
  }

  /** The images of one source, by the native pointer of the bitmap each belongs to. */
  private class PointerImages(
    private val format: EncodedImageFormat,
    /**
     * Read on demand rather than held: a screen's worth of PNGs is megabytes, and the ones in a heap
     * dump are already in the heap dump.
     */
    private val bytesByPointer: Map<Long, () -> ByteArray?>
  ) {

    fun isNotEmpty(): Boolean = bytesByPointer.isNotEmpty()

    fun bytesOf(nativePointer: Long?): ByteArray? = bytesByPointer[nativePointer ?: return null]?.invoke()

    fun imageOf(
      nativePointer: Long?,
      size: PixelSize
    ): BitmapImage.Encoded? {
      val bytes = bytesOf(nativePointer) ?: return null
      // A pointer says which allocation, never which bitmap, so an image that isn't the size this dump
      // says the bitmap is belongs to something that has since taken the same address. Only a PNG says
      // its own size cheaply, and PNG is what both sources are asked for.
      val pngSize = pngSize(bytes)
      if (pngSize != null && pngSize != size) {
        return null
      }
      return BitmapImage.Encoded(size.width, size.height, format, bytes)
    }

    companion object {
      val NONE = PointerImages(EncodedImageFormat.PNG, emptyMap())

      fun of(pixels: NativeBitmapPixels) = PointerImages(
        pixels.format,
        pixels.bytesByNativePointer.mapValues { (_, bytes) -> { bytes } }
      )
    }
  }

  /** `Bitmap.dumpData` if the dump has it, with each image left where it is until it's asked for. */
  private fun readDumpData(): PointerImages {
    val dumpData = graph.readDumpDataIndex() ?: return PointerImages.NONE
    return PointerImages(
      dumpData.format,
      dumpData.bufferIdByPointer.mapValues { (_, bufferId) -> { graph.readByteArray(bufferId) } }
    )
  }
}

/**
 * Which `byte[]` of a heap dump holds the compressed image of which bitmap, off the `Bitmap.dumpData`
 * that `am dumpheap -b` fills in before the dump is written. See [HeapBitmaps].
 */
internal class DumpDataIndex(
  val format: EncodedImageFormat,
  val bufferIdByPointer: Map<Long, Long>
)

/** [DumpDataIndex] for this heap dump, or null when it was taken without `am dumpheap -b`. */
internal fun HeapGraph.readDumpDataIndex(): DumpDataIndex? {
  val dumpData = findClassByName(BITMAP_CLASS_NAME)?.get(DUMP_DATA_FIELD)?.valueAsInstance ?: return null
  val className = dumpData.instanceClassName
  val count = dumpData[className, "count"]?.value?.asInt ?: return null
  val format = dumpData[className, "format"]?.value?.asInt
    ?.let { EncodedImageFormat.ofNativeInt(it) } ?: return null
  val pointers = (dumpData[className, "natives"]?.valueAsPrimitiveArray?.readRecord()
    as? LongArrayDump)?.array ?: return null
  val bufferIds = dumpData[className, "buffers"]?.valueAsObjectArray?.readRecord()?.elementIds
    ?: return null
  val bufferIdByPointer = LinkedHashMap<Long, Long>(count)
  // Its own count rather than the length of either array: `DumpData` allocates for every bitmap of the
  // process and fills in only the ones that compressed, so the tail of both is empty.
  for (index in 0 until minOf(count, pointers.size, bufferIds.size)) {
    bufferIdByPointer[pointers[index]] = bufferIds[index]
  }
  return DumpDataIndex(format, bufferIdByPointer)
}

internal fun HeapGraph.readByteArray(objectId: Long): ByteArray? =
  (findObjectByIdOrNull(objectId)?.asPrimitiveArray?.readRecord() as? ByteArrayDump)?.array

private const val BITMAP_CLASS_NAME = "android.graphics.Bitmap"

/** `Bitmap.dumpData`, a static of `Bitmap` since API 35. */
private const val DUMP_DATA_FIELD = "dumpData"

/** What the pixels of one pre API 26 bitmap look like in its `mBuffer`, by how wide a pixel is. */
private enum class PixelLayout(val byteCount: Int) {

  /** `ALPHA_8`: a mask, drawn as the black it stands in for. */
  ALPHA_8(1) {
    override fun argbAt(
      bytes: ByteArray,
      offset: Int,
      isPremultiplied: Boolean
    ): Int = bytes.byteAt(offset) shl ALPHA_SHIFT
  },

  /** `RGB_565`: two bytes, little endian, five bits of red, six of green, five of blue. */
  RGB_565(2) {
    override fun argbAt(
      bytes: ByteArray,
      offset: Int,
      isPremultiplied: Boolean
    ): Int {
      val value = bytes.byteAt(offset) or (bytes.byteAt(offset + 1) shl 8)
      val red = (value ushr 11) and 0x1f
      val green = (value ushr 5) and 0x3f
      val blue = value and 0x1f
      return argb(
        alpha = 0xff,
        // Repeating the high bits rather than multiplying, so that all ones stays all ones.
        red = (red shl 3) or (red ushr 2),
        green = (green shl 2) or (green ushr 4),
        blue = (blue shl 3) or (blue ushr 2)
      )
    }
  },

  /** `ARGB_8888`, which the framework stores as RGBA: the byte order is the memory order. */
  ARGB_8888(4) {
    override fun argbAt(
      bytes: ByteArray,
      offset: Int,
      isPremultiplied: Boolean
    ): Int {
      val alpha = bytes.byteAt(offset + 3)
      val red = bytes.byteAt(offset)
      val green = bytes.byteAt(offset + 1)
      val blue = bytes.byteAt(offset + 2)
      return if (isPremultiplied && alpha in 1..0xfe) {
        argb(alpha, red * 0xff / alpha, green * 0xff / alpha, blue * 0xff / alpha)
      } else {
        argb(alpha, red, green, blue)
      }
    }
  },
  ;

  abstract fun argbAt(
    bytes: ByteArray,
    offset: Int,
    isPremultiplied: Boolean
  ): Int

  companion object {
    /**
     * The layout of a pixel that takes [byteCount] bytes, or null when nothing does.
     *
     * More than four bytes a pixel is `RGBA_F16` or `RGBA_1010102`, which nothing here decodes yet, and
     * fewer than one is a buffer too small for the bitmap it belongs to.
     */
    fun ofByteCount(byteCount: Int): PixelLayout? = values().firstOrNull {
      it.byteCount == byteCount
    }
  }
}

private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

private fun ByteArray.byteAt(index: Int): Int = this[index].toInt() and 0xff

private fun argb(
  alpha: Int,
  red: Int,
  green: Int,
  blue: Int
): Int = (alpha shl ALPHA_SHIFT) or
  (minOf(red, 0xff) shl RED_SHIFT) or
  (minOf(green, 0xff) shl GREEN_SHIFT) or
  minOf(blue, 0xff)

/**
 * Builds pixels for [width] × [height] scaled to fit [maxDimension], calling [pixels] with the size it
 * settled on. Never scales up: a 12 pixel icon on a 200 pixel rectangle is 12 pixels, and the view can
 * stretch what it draws.
 */
private inline fun scaleDown(
  size: PixelSize,
  maxDimension: Int,
  pixels: (Int, Int) -> IntArray
): BitmapImage.Pixels {
  val longestSide = maxOf(size.width, size.height)
  val scale = if (longestSide <= maxDimension) 1.0 else maxDimension.toDouble() / longestSide
  // At least one pixel each way, so that a very wide and very short bitmap stays an image.
  val targetWidth = maxOf(1, (size.width * scale).toInt())
  val targetHeight = maxOf(1, (size.height * scale).toInt())
  return BitmapImage.Pixels(targetWidth, targetHeight, pixels(targetWidth, targetHeight))
}

/** The size in the `IHDR` of [bytes], or null when they aren't a PNG. */
private fun pngSize(bytes: ByteArray): PixelSize? {
  if (bytes.size < PNG_HEADER_SIZE) {
    return null
  }
  PNG_SIGNATURE.forEachIndexed { index, byte ->
    if (bytes[index] != byte) {
      return null
    }
  }
  return PixelSize(
    width = bytes.intAt(PNG_WIDTH_OFFSET),
    height = bytes.intAt(PNG_WIDTH_OFFSET + Int.SIZE_BYTES)
  )
}

private fun ByteArray.intAt(index: Int): Int = (byteAt(index) shl 24) or
  (byteAt(index + 1) shl 16) or
  (byteAt(index + 2) shl 8) or
  byteAt(index + 3)

/** The eight bytes every PNG starts with. */
private val PNG_SIGNATURE = byteArrayOf(
  0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
  0x0d, 0x0a, 0x1a, 0x0a
)

/** The signature, then the length and the name of the `IHDR` chunk, then the width and the height. */
private const val PNG_WIDTH_OFFSET = 16
private const val PNG_HEADER_SIZE = PNG_WIDTH_OFFSET + 2 * Int.SIZE_BYTES

private data class PixelSize(
  val width: Int,
  val height: Int
) {
  val pixelCount: Long get() = width.toLong() * height
}

/**
 * Compressed images keyed by the native pointer of the bitmap each shows, which is how
 * `Bitmap.dumpAll` records them and therefore how both a heap dump and a live process hand them over.
 */
class NativeBitmapPixels(
  val format: EncodedImageFormat,
  val bytesByNativePointer: Map<Long, ByteArray>
)

/** How many bitmaps a heap dump has and how many of them anything has the pixels of. */
data class BitmapCounts(
  val count: Int,
  val withImageCount: Int,
  /**
   * Whether the pixels are the compressed ones `am dumpheap -b` produces rather than the `mBuffer` of
   * a pre API 26 dump, which is what says whether dumping the process again would add any.
   */
  val carriesNativePixels: Boolean,
  /**
   * How many bitmaps were sent an image of the wrong size, which is a native pointer that now belongs
   * to a different bitmap. See [HeapBitmaps.addNativePixels].
   */
  val mismatchedCount: Int = 0
) {
  val withoutImageCount: Int get() = count - withImageCount

  companion object {
    val NONE = BitmapCounts(count = 0, withImageCount = 0, carriesNativePixels = false)
  }
}
