package leakcanary.internal.activity.screen

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Style.FILL
import android.graphics.Paint.Style.STROKE
import android.graphics.Rect
import com.squareup.leakcanary.core.R
import leakcanary.internal.navigation.getColorCompat
import shark.HprofRecord
import shark.HprofRecord.HeapDumpEndRecord
import shark.HprofRecord.HeapDumpRecord.GcRootRecord
import shark.HprofRecord.HeapDumpRecord.HeapDumpInfoRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import shark.HprofRecord.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import shark.HprofRecord.LoadClassRecord
import shark.HprofRecord.StackTraceRecord
import shark.HprofRecord.StringRecord
import shark.StreamingHprofReader
import shark.OnHprofRecordListener
import shark.StreamingRecordReaderAdapter.Companion.asStreamingRecordReader
import java.io.File

internal object HeapDumpRenderer {

  private class HasDensity(resources: Resources) {
    val density = resources.displayMetrics.density

    val Int.dp
      get() = this * density

    val Float.dp
      get() = this * density
  }

  @Suppress("LongMethod")
  fun render(
    context: Context,
    heapDumpFile: File,
    sourceWidth: Int,
    sourceHeight: Int,
    /**
     * If [sourceBytesPerPixel] > 0 then [sourceHeight] will be ignored.
     */
    sourceBytesPerPixel: Int
  ): Bitmap = with(HasDensity(context.resources)) {
    val recordPositions = mutableListOf<Pair<Int, Long>>()
    var currentRecord: HprofRecord? = null

    val otherColor = context.getColorCompat(R.color.leak_canary_heap_other)
    val stackTraceColor = context.getColorCompat(R.color.leak_canary_heap_stack_trace)
    val hprofStringColor = context.getColorCompat(R.color.leak_canary_heap_hprof_string)
    val loadClassColor = context.getColorCompat(R.color.leak_canary_heap_load_class)
    val classDumpColor = context.getColorCompat(R.color.leak_canary_heap_class_dump)
    val instanceColor = context.getColorCompat(R.color.leak_canary_heap_instance)
    val objectArrayColor = context.getColorCompat(R.color.leak_canary_heap_object_array)
    val booleanArrayColor = context.getColorCompat(R.color.leak_canary_heap_boolean_array)
    val charArrayColor = context.getColorCompat(R.color.leak_canary_heap_char_array)
    val floatArrayColor = context.getColorCompat(R.color.leak_canary_heap_float_array)
    val doubleArrayColor = context.getColorCompat(R.color.leak_canary_heap_double_array)
    val byteArrayColor = context.getColorCompat(R.color.leak_canary_heap_byte_array)
    val shortArrayColor = context.getColorCompat(R.color.leak_canary_heap_short_array)
    val intArrayColor = context.getColorCompat(R.color.leak_canary_heap_int_array)
    val longArrayColor = context.getColorCompat(R.color.leak_canary_heap_long_array)
    val colors = mapOf(
        StringRecord::class to hprofStringColor,
        LoadClassRecord::class to loadClassColor,
        ClassDumpRecord::class to classDumpColor,
        InstanceDumpRecord::class to instanceColor,
        ObjectArrayDumpRecord::class to objectArrayColor,
        BooleanArrayDump::class to booleanArrayColor,
        CharArrayDump::class to charArrayColor,
        FloatArrayDump::class to floatArrayColor,
        DoubleArrayDump::class to doubleArrayColor,
        ByteArrayDump::class to byteArrayColor,
        ShortArrayDump::class to shortArrayColor,
        IntArrayDump::class to intArrayColor,
        LongArrayDump::class to longArrayColor,
        StackTraceRecord::class to stackTraceColor,
        HeapDumpEndRecord::class to otherColor,
        GcRootRecord::class to otherColor
    )

    val appHeapColor = context.getColorCompat(R.color.leak_canary_heap_app)
    val imageHeapColor = context.getColorCompat(R.color.leak_canary_heap_image)
    val zygoteHeapColor = context.getColorCompat(R.color.leak_canary_heap_zygote)
    val stringColor = context.getColorCompat(R.color.leak_canary_heap_instance_string)

    var lastPosition = 0L

    val reader = StreamingHprofReader.readerFor(heapDumpFile).asStreamingRecordReader()
    val hprofStringCache = mutableMapOf<Long, String>()
    val classNames = mutableMapOf<Long, Long>()
    reader.readRecords(
        setOf(HprofRecord::class), OnHprofRecordListener { position, record ->
      lastPosition = position
      when (record) {
        is StringRecord -> {
          hprofStringCache[record.id] = record.string
        }
        is LoadClassRecord -> {
          classNames[record.id] = record.classNameStringId
        }
      }
      val localCurrentRecord = currentRecord
      when {
        localCurrentRecord is HeapDumpInfoRecord -> {
          val colorForHeapInfo =
            when (hprofStringCache[localCurrentRecord.heapNameStringId]) {
              // The primary heap on which your app allocates memory.
              "app" -> appHeapColor
              // The system boot image, containing classes that are preloaded during boot time.
              // Allocations here are guaranteed to never move or go away.
              "image" -> imageHeapColor
              // The copy-on-write heap where an app process is forked from in the Android system.
              "zygote" -> zygoteHeapColor
              // JNI heap: The heap that shows where Java Native Interface (JNI) references are allocated and released.
              // default heap: When no heap is specified by the system
              else -> otherColor
            }
          recordPositions.add(colorForHeapInfo to position)
          currentRecord = record
        }
        localCurrentRecord is InstanceDumpRecord
            && hprofStringCache[classNames[localCurrentRecord.classId]] == "java.lang.String"
            && (record !is InstanceDumpRecord || hprofStringCache[classNames[record.classId]]
            != "java.lang.String")
        -> {
          recordPositions.add(stringColor to position)
          currentRecord = record
        }
        currentRecord == null -> {
          recordPositions.add(otherColor to position)
          currentRecord = record
        }
        currentRecord!!::class != record::class -> {
          recordPositions.add(colors.getValue(currentRecord!!::class) to position)
          currentRecord = record
        }
      }
    })
    val heapLength = lastPosition

    val width = sourceWidth
    var height: Int
    val bytesPerPixel: Double

    if (sourceBytesPerPixel > 0) {
      bytesPerPixel = sourceBytesPerPixel.toDouble()
      height = Math.ceil((heapLength / bytesPerPixel) / width)
          .toInt()
    } else {
      height = sourceHeight
      bytesPerPixel = heapLength * 1.0 / (width * height)
    }

    val bitmap: Bitmap =
      Bitmap.createBitmap(width, height, ARGB_8888)

    val canvas = Canvas(bitmap)

    val legend = mapOf(
        "Hprof string" to hprofStringColor,
        "Class name" to loadClassColor,
        "App heap" to appHeapColor,
        "Image heap" to imageHeapColor,
        "Zygote heap" to zygoteHeapColor,
        "Other heap" to otherColor,
        "Class content" to classDumpColor,
        "Instance" to instanceColor,
        "String" to stringColor,
        "Object array" to objectArrayColor,
        "Boolean array" to booleanArrayColor,
        "Char array" to charArrayColor,
        "Float array" to floatArrayColor,
        "Double array" to doubleArrayColor,
        "Byte array" to byteArrayColor,
        "Short array" to shortArrayColor,
        "Int array" to intArrayColor,
        "Long array" to longArrayColor,
        "Stack trace" to stackTraceColor,
        "Heap End" to otherColor
    )

    val legendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    legendTextPaint.color = Color.WHITE
    legendTextPaint.style = FILL
    canvas.drawPaint(legendTextPaint)

    val legendSquareFillPaint = Paint()
    legendSquareFillPaint.style = FILL
    val legendSquareStrokePaint = Paint()
    legendSquareStrokePaint.style = STROKE
    legendSquareStrokePaint.strokeWidth = 0.8f.dp
    legendSquareStrokePaint.color = Color.BLACK

    legendTextPaint.color = Color.BLACK
    legendTextPaint.textSize = 16.dp

    val metrics = legendTextPaint.fontMetrics
    val textHeight = metrics.descent - metrics.ascent

    val xBounds = Rect()
    legendTextPaint.getTextBounds("x", 0, 1, xBounds)
    val squareSize = xBounds.height()
    val squarePaddingTop = (textHeight - squareSize) / 2
    val squareToTextPadding = 4.dp
    val blockToBlockPadding = 8.dp

    var maxTextWidth = 0f
    for (name in legend.keys) {
      maxTextWidth = Math.max(maxTextWidth, legendTextPaint.measureText(name))
    }

    val padding = 8.dp
    var blockLeft = padding
    var blockTop = padding
    val legendWidth = width - 2 * padding
    for ((name, color) in legend) {
      if (blockLeft + squareSize + squareToTextPadding + maxTextWidth > legendWidth) {
        blockLeft = padding
        blockTop += textHeight
      }

      legendSquareFillPaint.color = color
      canvas.drawRect(
          blockLeft, blockTop + squarePaddingTop, blockLeft + squareSize,
          blockTop + squarePaddingTop + squareSize,
          legendSquareFillPaint
      )
      canvas.drawRect(
          blockLeft, blockTop + squarePaddingTop, blockLeft + squareSize,
          blockTop + squarePaddingTop + squareSize,
          legendSquareStrokePaint
      )
      blockLeft += squareSize + squareToTextPadding
      canvas.drawText(name, blockLeft, blockTop - metrics.ascent, legendTextPaint)
      blockLeft += maxTextWidth
      blockLeft += blockToBlockPadding
    }
    val legendHeight = blockTop + textHeight + padding
    val source = Rect(0, 0, width, legendHeight.toInt())
    val destination = Rect(0, (height - legendHeight).toInt(), width, height)
    canvas.drawBitmap(bitmap, source, destination, null)
    height -= legendHeight.toInt()

    val pixelPaint = Paint()
    pixelPaint.style = FILL

    var recordIndex = 0
    for (y in 0 until height) {
      for (x in 0 until width) {
        val bitmapPosition = y * width + x
        val heapPosition = (bitmapPosition * bytesPerPixel).toInt()
        while (heapPosition > recordPositions[recordIndex].second && recordIndex < recordPositions.lastIndex) {
          recordIndex++
        }
        pixelPaint.color = recordPositions[recordIndex].first
        canvas.drawPoint(x.toFloat(), y.toFloat(), pixelPaint)
      }
    }
    return bitmap
  }
}