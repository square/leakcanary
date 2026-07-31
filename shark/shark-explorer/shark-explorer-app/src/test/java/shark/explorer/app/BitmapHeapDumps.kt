package shark.explorer.app

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import shark.GcRoot.JniGlobal
import shark.HprofWriterHelper
import shark.ValueHolder
import shark.ValueHolder.BooleanHolder
import shark.ValueHolder.IntHolder
import shark.ValueHolder.LongHolder
import shark.ValueHolder.ReferenceHolder
import shark.dump

/**
 * A heap dump with one bitmap in it, and the device it came from recorded the way a real one records it.
 *
 * [hasPixels] writes the `mBuffer` a bitmap keeps its pixels in before API 26. Without it the dump is what
 * every device since has written: the size of the bitmap, the address of its pixels, and no pixels.
 *
 * Shared by the tests that show a bitmap and the ones that go to a device for one, because both need a
 * dump that has a bitmap and says which device wrote it.
 */
internal fun File.writeBitmapHeapDump(hasPixels: Boolean) {
  dump {
    val bitmapClassId = clazz(
      className = "android.graphics.Bitmap",
      fields = listOf(
        "mWidth" to IntHolder::class,
        "mHeight" to IntHolder::class,
        "mRecycled" to BooleanHolder::class,
        "mNativePtr" to LongHolder::class,
        "mBuffer" to ReferenceHolder::class
      )
    )
    val pixels = if (hasPixels) {
      // Four bytes a pixel, which is what makes them ARGB_8888.
      ReferenceHolder(primitiveByteArray(ByteArray(BITMAP_SIDE * BITMAP_SIDE * 4)))
    } else {
      ReferenceHolder(ValueHolder.NULL_REFERENCE)
    }
    val bitmap = instance(
      classId = bitmapClassId,
      fields = listOf(
        IntHolder(BITMAP_SIDE),
        IntHolder(BITMAP_SIDE),
        BooleanHolder(false),
        LongHolder(NATIVE_POINTER),
        pixels
      )
    )
    gcRoot(JniGlobal(id = bitmap.value, jniGlobalRefId = 0))
    androidBuild()
  }
}

/** What `android.os.Build` looks like in a dump, which is how the explorer knows which device to go to. */
internal fun HprofWriterHelper.androidBuild() {
  "android.os.Build" clazz {
    staticField["FINGERPRINT"] = string(FINGERPRINT)
    staticField["MANUFACTURER"] = string(MANUFACTURER)
    staticField["MODEL"] = string(MODEL)
  }
  "android.os.Build\$VERSION" clazz {
    staticField["SDK_INT"] = IntHolder(SDK_INT)
  }
}

/**
 * A PNG of that size, which is what a device hands over for a bitmap it has no pixels for in the dump.
 *
 * Only the size in the `IHDR` matters here: it's what an image is accepted or rejected for a bitmap by.
 * Duplicated from `shark-explorer-core`'s tests rather than shared, since a test helper is not worth a
 * module's public API.
 */
internal fun pngBytes(
  width: Int,
  height: Int
): ByteArray {
  val bytes = ByteArrayOutputStream()
  ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", bytes)
  return bytes.toByteArray()
}

/** Big enough that the biggest object of the dump is the bitmap's buffer rather than anything else. */
internal const val BITMAP_SIDE = 64
internal const val NATIVE_POINTER = 0x7f4321L

internal const val FINGERPRINT = "google/tokay/tokay:16/BP31.250610.004/13698546:user/release-keys"
internal const val MANUFACTURER = "Google"
internal const val MODEL = "Pixel 9"
internal const val SDK_INT = 36

/** What the dialogs say the heap dump came from, off the `android.os.Build` written into it. */
internal const val DUMP_ORIGIN = "$MANUFACTURER $MODEL · API $SDK_INT"
