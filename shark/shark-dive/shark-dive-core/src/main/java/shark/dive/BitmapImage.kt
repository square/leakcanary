package shark.dive

/**
 * The pixels of one `android.graphics.Bitmap`, ready to be turned into whatever image type the view
 * draws.
 *
 * Two shapes, because two eras of Android keep a bitmap's pixels in two places and neither is a
 * format a UI toolkit takes directly. See [HeapBitmaps] for which dump gives which.
 */
sealed interface BitmapImage {

  /** The width the bitmap says it has, which is also the width of these pixels. */
  val width: Int

  val height: Int

  /**
   * A compressed image, which is what `Bitmap.dumpAll` writes into a heap dump and what a live
   * process hands over: bytes any image decoder reads.
   */
  class Encoded(
    override val width: Int,
    override val height: Int,
    val format: EncodedImageFormat,
    val bytes: ByteArray
  ) : BitmapImage

  /**
   * [width] × [height] pixels, row major, one packed ARGB Int each, alpha not premultiplied.
   *
   * Already scaled down to whatever size was asked for, because the pixels of a full screen bitmap
   * are megabytes and a rectangle on a treemap is a hundred pixels across.
   */
  class Pixels(
    override val width: Int,
    override val height: Int,
    val argb: IntArray
  ) : BitmapImage {
    init {
      require(argb.size == width * height) {
        "A $width × $height image needs ${width * height} pixels, not ${argb.size}"
      }
    }
  }
}

/**
 * The formats `Bitmap.dumpAll` compresses to, which is what the `format` it records in a heap dump
 * means.
 *
 * The values are `Bitmap.CompressFormat.nativeInt`, since that is what ends up in the dump rather
 * than the enum ordinal.
 */
enum class EncodedImageFormat(val nativeInt: Int) {
  JPEG(0),
  PNG(1),
  WEBP(2),
  WEBP_LOSSY(3),
  WEBP_LOSSLESS(4),
  ;

  companion object {
    fun ofNativeInt(nativeInt: Int): EncodedImageFormat? = values().firstOrNull {
      it.nativeInt == nativeInt
    }
  }
}
