package org.leakcanary.service

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.inject.Inject
import org.leakcanary.data.HeapRepository
import org.leakcanary.internal.LeakUiApp
import org.leakcanary.internal.ParcelableHeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog

@AndroidEntryPoint
class LeakUiAppService : Service() {

  @Inject lateinit var heapRepository: HeapRepository

  // TODO Stubs can be longer lived than the outer service, handle
  //  manually clearing out the stub reference to the service.
  private val binder = object : LeakUiApp.Stub() {

    override fun sendHeapAnalysis(
      heapAnalysis: ParcelableHeapAnalysis,
      heapDumpUri: Uri
    ) {
      val heapDumpDir = File(filesDir, "heapdumps")
      if (!heapDumpDir.exists()) {
        check(heapDumpDir.mkdirs()) {
          "Failed to create directory $heapDumpDir"
        }
      }

      val sourceHeapAnalysis = heapAnalysis.wrapped

      val destination = File(heapDumpDir, sourceHeapAnalysis.heapDumpFile.name)

      val updatedHeapAnalysis = when (sourceHeapAnalysis) {
        is HeapAnalysisSuccess -> {
          sourceHeapAnalysis.copy(heapDumpFile = destination)
        }

        is HeapAnalysisFailure -> {
          sourceHeapAnalysis.copy(heapDumpFile = destination)
        }
      }

      val parcelFileDescriptor = contentResolver.openFileDescriptor(heapDumpUri, "r")
      if (parcelFileDescriptor != null) {
        parcelFileDescriptor.use {
          FileInputStream(it.fileDescriptor).use { inputStream ->
            FileOutputStream(destination).use { fos ->
              val sourceChannel = inputStream.channel
              sourceChannel.transferTo(0, sourceChannel.size(), fos.channel)
            }
          }
        }
      } else {
        SharkLog.d { "ContentProvider crashed, skipping copy of $heapDumpUri" }
      }

      // TODO Use sendHeapAnalysis as a trigger to check if this is the first ever linking and
      //  if we need to import any past analysis. This should start background work that
      //  asks the app for all analysis between 2 times: last analysis that we know and this
      //  analysis time.
      val callerPackageName = packageManager.getNameForUid(Binder.getCallingUid())!!
      // TODO maybe return an intent for notification?
      heapRepository.insertHeapAnalysis(callerPackageName, updatedHeapAnalysis)
    }
  }

  override fun onBind(intent: Intent): IBinder {
    // TODO Return null if we can't handle the caller's version
    return binder
  }
}
