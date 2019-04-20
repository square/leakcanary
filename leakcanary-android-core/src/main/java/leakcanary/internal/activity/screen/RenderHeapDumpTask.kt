package leakcanary.internal.activity.screen

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Style.FILL
import android.graphics.Paint.Style.STROKE
import android.graphics.Rect
import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import com.squareup.leakcanary.core.R
import leakcanary.HprofParser
import leakcanary.HprofParser.RecordCallbacks
import leakcanary.ObjectIdMetadata.STRING
import leakcanary.Record
import leakcanary.Record.HeapDumpEndRecord
import leakcanary.Record.HeapDumpRecord.HeapDumpInfoRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import leakcanary.Record.LoadClassRecord
import leakcanary.Record.StackTraceRecord
import leakcanary.Record.StringRecord
import java.io.File

class RenderHeapDumpTask private constructor(
  private val resources: Resources,
  private val heapDumpFile: File,
  private val width: Int,
  private val height: Int,
  private val bytesPerPixel: Int,
  private var resultCallback: ((Bitmap) -> Unit)?
) {

  private val uiHandler = Handler(Looper.getMainLooper())

  private val runnable = Runnable {
    val parser = HprofParser.open(heapDumpFile)

    val recordPositions = mutableListOf<Pair<Int, Long>>()
    var currentRecord: Record? = null

    val otherColor = resources.getColor(R.color.leak_canary_heap_other)
    val stackTraceColor = resources.getColor(R.color.leak_canary_heap_stack_trace)
    val hprofStringColor = resources.getColor(R.color.leak_canary_heap_hprof_string)
    val loadClassColor = resources.getColor(R.color.leak_canary_heap_load_class)
    val classDumpColor = resources.getColor(R.color.leak_canary_heap_class_dump)
    val instanceColor = resources.getColor(R.color.leak_canary_heap_instance)
    val objectArrayColor = resources.getColor(R.color.leak_canary_heap_object_array)
    val booleanArrayColor = resources.getColor(R.color.leak_canary_heap_boolean_array)
    val charArrayColor = resources.getColor(R.color.leak_canary_heap_char_array)
    val floatArrayColor = resources.getColor(R.color.leak_canary_heap_float_array)
    val doubleArrayColor = resources.getColor(R.color.leak_canary_heap_double_array)
    val byteArrayColor = resources.getColor(R.color.leak_canary_heap_byte_array)
    val shortArrayColor = resources.getColor(R.color.leak_canary_heap_short_array)
    val intArrayColor = resources.getColor(R.color.leak_canary_heap_int_array)
    val longArrayColor = resources.getColor(R.color.leak_canary_heap_long_array)
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
        HeapDumpEndRecord::class to otherColor
    )

    val appHeapColor = resources.getColor(R.color.leak_canary_heap_app)
    val imageHeapColor = resources.getColor(R.color.leak_canary_heap_image)
    val zygoteHeapColor = resources.getColor(R.color.leak_canary_heap_zygote)
    val stringColor = resources.getColor(R.color.leak_canary_heap_instance_string)

    val updatePosition: (Record) -> Unit =
      { newRecord ->
        val localCurrentRecord = currentRecord
        when {
          localCurrentRecord is HeapDumpInfoRecord -> {
            val colorForHeapInfo =
              when (parser.hprofStringById(localCurrentRecord.heapNameStringId)) {
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
            recordPositions.add(colorForHeapInfo to parser.position)
            currentRecord = newRecord
          }
          localCurrentRecord is InstanceDumpRecord
              && parser.objectIdMetadata(localCurrentRecord.id) == STRING
              && (newRecord !is InstanceDumpRecord || parser.objectIdMetadata(
              newRecord.id
          ) != STRING)
          -> {
            recordPositions.add(stringColor to parser.position)
            currentRecord = newRecord
          }
          currentRecord == null -> {
            recordPositions.add(otherColor to parser.position)
            currentRecord = newRecord
          }
          currentRecord!!::class != newRecord::class -> {
            recordPositions.add(colors.getValue(currentRecord!!::class) to parser.position)
            currentRecord = newRecord
          }
        }
      }

    parser.scan(
        RecordCallbacks()
            .on(StringRecord::class.java, updatePosition)
            .on(LoadClassRecord::class.java, updatePosition)
            .on(ClassDumpRecord::class.java, updatePosition)
            .on(InstanceDumpRecord::class.java, updatePosition)
            .on(ObjectArrayDumpRecord::class.java, updatePosition)
            .on(PrimitiveArrayDumpRecord::class.java, updatePosition)
            .on(HeapDumpInfoRecord::class.java, updatePosition)
            .on(HeapDumpEndRecord::class.java, updatePosition)
            .on(StackTraceRecord::class.java, updatePosition)
    )

    val heapLength = parser.position
    parser.close()

    val width = width

    var height: Int
    val bytesPerPixel: Double

    if (this.bytesPerPixel > 0) {
      bytesPerPixel = this.bytesPerPixel.toDouble()
      height = Math.ceil((heapLength / bytesPerPixel) / width)
          .toInt()
    } else {
      height = this.height
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
    uiHandler.post {
      resultCallback?.invoke(bitmap)
    }
  }

  private val Int.dp
    get() = this * resources.displayMetrics.density

  private val Float.dp
    get() = this * resources.displayMetrics.density

  fun cancel() {
    resultCallback = null
  }

  companion object {
    fun renderAsync(
      resources: Resources,
      heapDumpFile: File,
      width: Int,
      height: Int,
      /**
       * If [bytesPerPixel] > 0 then height will be ignored.
       */
      bytesPerPixel: Int,
      resultCallback: ((Bitmap) -> Unit)?
    ): RenderHeapDumpTask {
      val renderTask =
        RenderHeapDumpTask(resources, heapDumpFile, width, height, bytesPerPixel, resultCallback)
      AsyncTask.THREAD_POOL_EXECUTOR.execute(renderTask.runnable)
      return renderTask
    }
  }
}