package shark.dive

import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import shark.CloseableHeapGraph
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.ValueHolder.IntHolder
import shark.dump

/**
 * Covers where a bitmap's pixels can be found, which is the whole of what this reads: an `mBuffer` on
 * the Java heap before API 26, a compressed image `am dumpheap -b` left in the dump from API 35, pixels
 * fetched off the live process, and none of the three in between.
 */
class HeapBitmapsTest {

  @get:Rule
  var testFolder = TemporaryFolder()

  @Test fun `a pre API 26 bitmap is decoded off its buffer`() {
    // Two pixels, in the RGBA byte order the framework stores ARGB_8888 in.
    bitmapGraph(
      pixels = byteArrayOf(
        0xff.toByte(), 0x00, 0x00, 0xff.toByte(),
        0x00, 0x00, 0xff.toByte(), 0x80.toByte()
      ),
      width = 2,
      height = 1,
      isPremultiplied = false
    ).use { graph ->
      val image = graph.imageOfTheBitmap()

      assertThat(image).isInstanceOf(BitmapImage.Pixels::class.java)
      val pixels = image as BitmapImage.Pixels
      assertThat(pixels.width).isEqualTo(2)
      assertThat(pixels.height).isEqualTo(1)
      assertThat(pixels.argb.map { it.toUInt().toString(16) })
        .containsExactly("ffff0000", "800000ff")
    }
  }

  @Test fun `premultiplied pixels are divided back out`() {
    // Half transparent red, as the allocator was asked to store it: every channel already multiplied by
    // the alpha, so the red byte is half of full red.
    bitmapGraph(
      pixels = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x80.toByte()),
      width = 1,
      height = 1,
      isPremultiplied = true
    ).use { graph ->
      val pixels = graph.imageOfTheBitmap() as BitmapImage.Pixels

      assertThat(pixels.argb.single().toUInt().toString(16)).isEqualTo("80ff0000")
    }
  }

  @Test fun `a two byte a pixel buffer is read as RGB_565`() {
    // Nothing in the dump says which config the pixels are in, so the buffer's length is what decides.
    bitmapGraph(
      pixels = byteArrayOf(0x00, 0xf8.toByte()),
      width = 1,
      height = 1
    ).use { graph ->
      val pixels = graph.imageOfTheBitmap() as BitmapImage.Pixels

      // All five bits of red set, and all ones stays all ones rather than becoming 0xf8.
      assertThat(pixels.argb.single().toUInt().toString(16)).isEqualTo("ffff0000")
    }
  }

  @Test fun `pixels are scaled down to the size they were asked for`() {
    bitmapGraph(pixels = ByteArray(4 * 2 * 4), width = 4, height = 2).use { graph ->
      val pixels = graph.imageOfTheBitmap(maxDimension = 2) as BitmapImage.Pixels

      assertThat(pixels.width).isEqualTo(2)
      assertThat(pixels.height).isEqualTo(1)
      assertThat(pixels.argb).hasSize(2)
    }
  }

  @Test fun `a bitmap smaller than the max is not scaled up`() {
    bitmapGraph(pixels = ByteArray(2 * 4), width = 2, height = 1).use { graph ->
      val pixels = graph.imageOfTheBitmap(maxDimension = 64) as BitmapImage.Pixels

      assertThat(pixels.width).isEqualTo(2)
      assertThat(pixels.height).isEqualTo(1)
    }
  }

  @Test fun `a buffer too small for the bitmap it is on is no image`() {
    // Fewer bytes than there are pixels is no layout there is, so the buffer is left alone rather than
    // read as whichever layout is closest and drawn from bytes past its end.
    bitmapGraph(pixels = ByteArray(1), width = 2, height = 1).use { graph ->
      assertThat(graph.imageOfTheBitmap()).isNull()
      assertThat(graph.bitmapCounts().withImageCount).isEqualTo(0)
    }
  }

  @Test fun `a recycled bitmap has no image`() {
    bitmapGraph(pixels = ByteArray(4), width = 1, height = 1, isRecycled = true).use { graph ->
      assertThat(graph.imageOfTheBitmap()).isNull()
      assertThat(graph.bitmapCounts()).isEqualTo(
        BitmapCounts(count = 1, withImageCount = 0, carriesNativePixels = false)
      )
    }
  }

  @Test fun `a bitmap of API 26 and up has nothing to draw`() {
    // Which is the whole reason for fetching the pixels off the device: the dump has the size and the
    // address of the pixels, and no pixels.
    bitmapGraph(pixels = null, width = 8, height = 8).use { graph ->
      assertThat(graph.imageOfTheBitmap()).isNull()
      assertThat(graph.bitmapCounts()).isEqualTo(
        BitmapCounts(count = 1, withImageCount = 0, carriesNativePixels = false)
      )
    }
  }

  @Test fun `an image compressed into the dump is found by the native pointer of its bitmap`() {
    val png = pngBytes(width = 8, height = 8)
    bitmapGraph(
      pixels = null,
      width = 8,
      height = 8,
      nativePointer = NATIVE_POINTER,
      dumpedImages = mapOf(NATIVE_POINTER to png)
    ).use { graph ->
      val image = graph.imageOfTheBitmap()

      assertThat(image).isInstanceOf(BitmapImage.Encoded::class.java)
      val encoded = image as BitmapImage.Encoded
      assertThat(encoded.format).isEqualTo(EncodedImageFormat.PNG)
      assertThat(encoded.bytes).isEqualTo(png)
      assertThat(encoded.width).isEqualTo(8)
      assertThat(graph.bitmapCounts()).isEqualTo(
        BitmapCounts(count = 1, withImageCount = 1, carriesNativePixels = true)
      )
    }
  }

  @Test fun `pixels fetched off the live process fill in the bitmaps that had none`() {
    val png = pngBytes(width = 8, height = 8)
    bitmapGraph(pixels = null, width = 8, height = 8, nativePointer = NATIVE_POINTER).use { graph ->
      val bitmaps = HeapBitmaps(graph)
      assertThat(bitmaps.counts().withoutImageCount).isEqualTo(1)

      val counts = bitmaps.addNativePixels(
        NativeBitmapPixels(EncodedImageFormat.PNG, mapOf(NATIVE_POINTER to png))
      )

      assertThat(counts).isEqualTo(
        BitmapCounts(count = 1, withImageCount = 1, carriesNativePixels = true)
      )
      val encoded = bitmaps.imageOf(graph.theBitmapId(), maxDimension = 64) as BitmapImage.Encoded
      assertThat(encoded.bytes).isEqualTo(png)
    }
  }

  @Test fun `an image of another size is refused, because the address has been taken over`() {
    // A native pointer is an address, and an address is reused: the bitmap of this dump was recycled, and
    // by the time the process was asked for its pixels a 4 × 4 one was living there.
    bitmapGraph(pixels = null, width = 8, height = 8, nativePointer = NATIVE_POINTER).use { graph ->
      val bitmaps = HeapBitmaps(graph)

      val counts = bitmaps.addNativePixels(
        NativeBitmapPixels(
          EncodedImageFormat.PNG,
          mapOf(NATIVE_POINTER to pngBytes(width = 4, height = 4))
        )
      )

      assertThat(counts).isEqualTo(
        BitmapCounts(
          count = 1,
          withImageCount = 0,
          carriesNativePixels = true,
          mismatchedCount = 1
        )
      )
      assertThat(bitmaps.imageOf(graph.theBitmapId(), maxDimension = 64)).isNull()
    }
  }

  @Test fun `a heap dump with no bitmap in it has no bitmaps`() {
    val file = testFolder.newFile("no-bitmap.hprof")
    file.dump {
      "com.example.Holder" instance { field["name"] = IntHolder(1) }
    }
    file.openHeapGraph().use { graph ->
      assertThat(graph.bitmapCounts()).isEqualTo(BitmapCounts.NONE)
    }
  }

  @Test fun `an object that is not a bitmap has no image`() {
    bitmapGraph(pixels = ByteArray(4), width = 1, height = 1).use { graph ->
      val holder = graph.findClassByName("com.example.Holder")!!.instances.single()

      assertThat(HeapBitmaps(graph).isBitmap(holder)).isFalse()
      assertThat(HeapBitmaps(graph).imageOf(holder.objectId, maxDimension = 64)).isNull()
    }
  }

  /**
   * A heap dump with one bitmap in it, shaped like the Android version it stands for: [pixels] for the
   * `mBuffer` of a dump before API 26, [dumpedImages] for the `Bitmap.dumpData` of one taken with
   * `am dumpheap -b png`, and neither for everything in between.
   *
   * There's an unrelated instance alongside it, so that finding the bitmap is finding something.
   */
  private fun bitmapGraph(
    pixels: ByteArray?,
    width: Int,
    height: Int,
    isPremultiplied: Boolean = false,
    isRecycled: Boolean = false,
    nativePointer: Long = 0L,
    dumpedImages: Map<Long, ByteArray> = emptyMap()
  ): CloseableHeapGraph {
    val file = testFolder.newFile()
    file.dump {
      val dumpData = if (dumpedImages.isEmpty()) NULL_REFERENCE else bitmapDumpData(dumpedImages)
      bitmapInstance(
        bitmapClassId = bitmapClass(dumpData),
        width = width,
        height = height,
        isRecycled = isRecycled,
        isPremultiplied = isPremultiplied,
        nativePointer = nativePointer,
        pixels = pixels
      )
      "com.example.Holder" instance { field["count"] = IntHolder(1) }
    }
    return file.openHeapGraph()
  }

  private fun CloseableHeapGraph.imageOfTheBitmap(maxDimension: Int = 64): BitmapImage? =
    HeapBitmaps(this).imageOf(theBitmapId(), maxDimension)

  private fun CloseableHeapGraph.theBitmapId(): Long =
    findClassByName("android.graphics.Bitmap")!!.instances.single().objectId

  private fun CloseableHeapGraph.bitmapCounts(): BitmapCounts = HeapBitmaps(this).counts()

  companion object {
    private const val NATIVE_POINTER = 0x7f4321L
  }
}
