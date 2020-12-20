package leakcanary

import android.content.res.Resources
import android.content.res.Resources.NotFoundException
import leakcanary.HeapAnalysisInterceptor.Chain
import leakcanary.HeapAnalysisJob.Result
import shark.AndroidResourceIdNames

/**
 * Interceptor that saves the names of R.id.* entries and their associated int values to a static
 * field that can then be read from the heap dump.
 */
class SaveResourceIdsInterceptor(private val resources: Resources) : HeapAnalysisInterceptor {
  override fun intercept(chain: Chain): Result {
    saveResourceIdNamesToMemory()
    return chain.proceed()
  }

  private fun saveResourceIdNamesToMemory() {
    AndroidResourceIdNames.saveToMemory(
      getResourceTypeName = { id ->
        try {
          resources.getResourceTypeName(id)
        } catch (e: NotFoundException) {
          null
        }
      },
      getResourceEntryName = { id ->
        try {
          resources.getResourceEntryName(id)
        } catch (e: NotFoundException) {
          null
        }
      })
  }
}