package org.leakcanary.service

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.leakcanary.internal.HeapDataRepository
import shark.Dominators

/**
 * A one shot wrapper around [HeapDataRepository] that handles service lifecycle, and retrieves the
 * data, all from a suspend function. This isn't meant to stick around, more a place to play with
 * all the components at hand.
 */
class TreeMapFetcher @Inject constructor(
  private val application: Application
) {

  suspend fun fetchTreeMap(
    packageName: String,
    heapDumpFile: File
  ): Dominators {
    return withContext(Dispatchers.IO) {
      val intent = Intent("org.leakcanary.internal.HeapDataRepositoryService.BIND")
        .apply {
          setPackage(packageName)
        }

      val (service, connection) = application.connectToService<HeapDataRepository>(intent) {
        HeapDataRepository.Stub.asInterface(it)
      }
      try {
        service.sayHi(heapDumpFile.absolutePath).wrapped
      } finally {
        application.unbindService(connection)
      }
    }
  }

  suspend inline fun <B : IInterface> Context.connectToService(
    intent: Intent,
    crossinline asInterface: (IBinder) -> B
  ): Pair<B, ServiceConnection> = suspendCoroutine { continuation ->
    val connection = object : ServiceConnection {
      override fun onServiceDisconnected(name: ComponentName) {
        // TODO Is this right? we should handle this.
        continuation.resumeWithException(RuntimeException("Service Disconnected"))
      }

      override fun onServiceConnected(
        name: ComponentName,
        binder: IBinder
      ) {
        continuation.resume(asInterface(binder) to this)
      }
    }

    // TODO What handle error case.
    try {
      val bringingServiceUp =
        applicationContext.bindService(
          intent,
          connection,
          Context.BIND_AUTO_CREATE
        )
      // TODO If bringingServiceUp is false, must call unbindService
      //  false if the system couldn't find the service or if your client doesn't have permission
      //  to bind to it. Documentation says the same thing for throwing SecurityException though.
      // TODO If this is false we don't have access to the service. Adding queries in manifest fixes it
      //  but that's not the right solution.
      //  This works again once we have the source app make a call. Unclear when this stops working.
      //  Maybe we need a way to flush like starting a transparent activity that just establishes
      //  a back service call which then makes this authorized.
      check(bringingServiceUp) {
        "Service not brought up"
      }
    } catch (e: SecurityException) {
      // TODO must call unbindService
      throw e
    }
  }
}
