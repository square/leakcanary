package org.leakcanary.screens

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.ln
import kotlin.math.pow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.saket.extendedspans.ExtendedSpans
import me.saket.extendedspans.SquigglyUnderlineSpanPainter
import me.saket.extendedspans.drawBehind
import org.leakcanary.WhileSubscribedOrRetained
import org.leakcanary.data.HeapRepository
import org.leakcanary.screens.Destination.LeakDestination
import org.leakcanary.screens.LeakState.Loading
import org.leakcanary.screens.LeakState.Success
import shark.HeapAnalysisSuccess
import shark.Leak
import shark.LeakTrace.GcRootType.JAVA_FRAME
import shark.LeakTraceObject
import shark.LeakTraceObject.LeakingStatus.LEAKING
import shark.LeakTraceObject.LeakingStatus.NOT_LEAKING
import shark.LeakTraceObject.LeakingStatus.UNKNOWN
import shark.LeakTraceReference.ReferenceType.INSTANCE_FIELD
import shark.LeakTraceReference.ReferenceType.STATIC_FIELD

data class LeakData(
  val leak: Leak,
  val shortDescription: String,
  val isNew: Boolean,
  val isLibraryLeak: Boolean,
  val heapAnalysis: HeapAnalysisSuccess,
  val selectedLeakTraceIndex: Int,
  val leakTraces: List<LeakTraceData>
)

data class LeakTraceData(
  val leakTraceIndex: Int,
  val heapAnalysisId: Long,
  val classSimpleName: String,
  val createdAtTimeMillis: Long
)

sealed interface LeakState {
  object Loading : LeakState
  class Success(val leakData: LeakData) : LeakState
}

@HiltViewModel
class LeakViewModel @Inject constructor(
  private val repository: HeapRepository,
  private val navigator: Navigator,
  private val appBarTitle: AppBarTitle
) : ViewModel() {

  init {
    markLeakAsReadWhenEntering()
  }

  private fun markLeakAsReadWhenEntering() {
    viewModelScope.launch {
      navigator.filterDestination<LeakDestination>().collect { destination ->
        repository.markAsRead(destination.leakSignature)
      }
    }
  }

  val state =
    navigator.filterDestination<LeakDestination>()
      .flatMapLatest { state ->
        stateStream(state)
      }.stateIn(
        viewModelScope, started = WhileSubscribedOrRetained, initialValue = Loading
      )

  private fun stateStream(destination: LeakDestination): Flow<LeakState> {
    return repository
      .getLeak(destination.leakSignature).flatMapLatest { leakTraces ->
        val selectedHeapAnalysisId = destination.selectedAnalysisId
        val selectedLeakTraceIndex =
          if (selectedHeapAnalysisId == null) 0 else leakTraces.indexOfFirst { it.heap_analysis_id == selectedHeapAnalysisId }

        // TODO Handle selectedLeakIndex == -1, i.e. we could find the leak but no leaktrace
        // belonging to the expected analysis

        val heapAnalysisId = leakTraces[selectedLeakTraceIndex].heap_analysis_id

        repository.getHeapAnalysis(heapAnalysisId).map { heapAnalysis ->
          heapAnalysis as HeapAnalysisSuccess
          Success(
            with(leakTraces.first()) {
              LeakData(
                leak = heapAnalysis.allLeaks.first { it.signature == destination.leakSignature },
                shortDescription = short_description,
                isNew = !is_read,
                isLibraryLeak = is_library_leak,
                heapAnalysis = heapAnalysis,
                selectedLeakTraceIndex = selectedLeakTraceIndex,
                leakTraces = leakTraces.map {
                  LeakTraceData(
                    leakTraceIndex = it.leak_trace_index,
                    heapAnalysisId = it.heap_analysis_id,
                    classSimpleName = it.class_simple_name,
                    createdAtTimeMillis = it.created_at_time_millis
                  )
                }
              )
            })
        }.onEach {
          val leakData = it.leakData
          val leakTraceCount = leakData.leakTraces.size
          val plural = if (leakTraceCount > 1) "s" else ""
          appBarTitle.updateAppBarTitle("$leakTraceCount leak$plural at ${leakData.shortDescription}")
        }
      }
  }
}

@Composable
fun LeakScreen(viewModel: LeakViewModel = viewModel()) {
  val stateProp by viewModel.state.collectAsState()
  when (val state = stateProp) {
    is Loading -> {
      Text("Loading...")
    }
    is Success -> {
      val leakData = state.leakData
      // TODO Support switching the selected leaktrace.
      val leakTrace = leakData.leak.leakTraces[leakData.selectedLeakTraceIndex]

      LazyColumn(
        modifier =
        Modifier
          .fillMaxHeight()
          .padding(horizontal = 16.dp)
      ) {
        item {
          // TODO Add header section from LeakScreen.onLeakTraceSelected
          Text("TODO Header")
        }

        // TODO Add connectors
        // GC Root
        item {
          Text(
            text = "GC Root: ${leakTrace.gcRootType.description}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 4.dp)
          )
        }
        itemsIndexed(leakTrace.referencePath) { index, reference ->
          val text = buildAnnotatedString {
            val referencePath = leakTrace.referencePath[index]
            val leakTraceObject = referencePath.originObject
            val typeName =
              if (index == 0 && leakTrace.gcRootType == JAVA_FRAME) "thread" else leakTraceObject.typeName
            appendLeakTraceObject(leakTrace.leakingObject, overriddenTypeName = typeName)
            append(INDENTATION)
            val isStatic = referencePath.referenceType == STATIC_FIELD
            if (isStatic) {
              append("static ")
            }
            val simpleName = reference.owningClassSimpleName.removeSuffix("[]")
            appendWithColor(simpleName, HIGHLIGHT_COLOR)
            if (referencePath.referenceType == STATIC_FIELD ||
              referencePath.referenceType == INSTANCE_FIELD
            ) {
              append('.')
            }

            val isSuspect = leakTrace.referencePathElementIsSuspect(index)

            // Underline for squiggly spans
            if (isSuspect) {
              pushStyle(
                SpanStyle(
                  color = LEAK_COLOR,
                  textDecoration = TextDecoration.Underline,
                )
              )
            }

            withStyle(
              style = SpanStyle(
                color = REFERENCE_COLOR,
                fontWeight = if (isSuspect) FontWeight.Bold else null,
                fontStyle = if (isStatic) FontStyle.Italic else null
              )
            ) {
              append(referencePath.referenceDisplayName)
            }

            if (isSuspect) {
              pop()
            }
          }

          val squigglySpans = ExtendedSpans(SquigglyUnderlineSpanPainter())
          val squigglyText = squigglySpans.extend(text)

          Text(
            modifier = Modifier.drawBehind(squigglySpans),
            text = squigglyText,
            style = MaterialTheme.typography.bodyMedium,
            onTextLayout = { layoutResult ->
              squigglySpans.onTextLayout(layoutResult)
            }
          )
        }
        // Leaking object
        item {
          Text(
            text = buildAnnotatedString {
              appendLeakTraceObject(leakTrace.leakingObject)
            },
            style = MaterialTheme.typography.bodyMedium
          )
        }
      }
    }
  }
}

private fun AnnotatedString.Builder.appendLeakTraceObject(
  leakTraceObject: LeakTraceObject,
  overriddenTypeName: String = leakTraceObject.typeName
) {
  with(leakTraceObject) {
    val packageEnd = className.lastIndexOf('.')
    if (packageEnd != -1) {
      appendExtra(className.substring(0, packageEnd))
      append('.')
    }
    val simpleName = classSimpleName.replace("[]", "[ ]")
    appendWithColor(simpleName, HIGHLIGHT_COLOR)
    append(' ')
    appendExtra(overriddenTypeName)
    append('\n')

    append(INDENTATION)
    appendExtra("Leaking: ")
    when (leakingStatus) {
      UNKNOWN -> {
        appendExtra("UNKNOWN")
      }
      NOT_LEAKING -> {
        append("NO")
        appendExtra(" (${leakingStatusReason})")
      }
      LEAKING -> {
        append("YES")
        appendExtra(" (${leakingStatusReason})")
      }
    }
    append('\n')

    retainedHeapByteSize?.let {
      val humanReadableRetainedHeapSize = humanReadableByteCount(it.toLong(), si = true)
      append(INDENTATION)
      appendExtra("Retaining")
      append(humanReadableRetainedHeapSize)
      appendExtra(" in ")
      append("$retainedObjectCount")
      appendExtra(" objects")
      append('\n')
    }

    labels.forEach { label ->
      append(INDENTATION)
      appendExtra(label)
      append('\n')
    }
  }
}

private fun AnnotatedString.Builder.appendExtra(text: String) {
  appendWithColor(text, EXTRA_COLOR)
}

private fun AnnotatedString.Builder.appendWithColor(
  text: String,
  color: Color
) {
  withStyle(style = SpanStyle(color = color)) {
    append(text)
  }
}

// https://stackoverflow.com/a/3758880
private fun humanReadableByteCount(
  bytes: Long,
  si: Boolean
): String {
  val unit = if (si) 1000 else 1024
  if (bytes < unit) return "$bytes B"
  val exp = (ln(bytes.toDouble()) / ln(unit.toDouble())).toInt()
  val pre = (if (si) "kMGTPE" else "KMGTPE")[exp - 1] + if (si) "" else "i"
  return String.format("%.1f %sB", bytes / unit.toDouble().pow(exp.toDouble()), pre)
}

private val EXTRA_COLOR = Color(0xFF919191)
private val LEAK_COLOR = Color(0xFFbe383f)
private val REFERENCE_COLOR = Color(0xFF9976a8)
private val HIGHLIGHT_COLOR = Color(0xFFbababa)

// 4 nbsp
private val INDENTATION = "\u00A0".repeat(4)
