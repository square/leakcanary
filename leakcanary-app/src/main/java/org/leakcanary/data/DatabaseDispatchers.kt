package org.leakcanary.data

import android.content.res.Resources
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext

interface DatabaseDispatchers {
  val forWrites: CoroutineDispatcher
  val forReads: CoroutineDispatcher
}

/**
 * [DatabaseDispatchers] for when Write-Ahead logging is enabled, in which case
 * only a single thread can access the database connection to write, while up to
 * `android.R.integer.db_connection_pool_size` threads can access the database
 * connection to read.
 */
@Singleton
class WriteAheadLoggingEnabledDatabaseDispatchers @Inject constructor() : DatabaseDispatchers {
  override val forWrites = newSingleThreadContext("database-writes")
  override val forReads: CoroutineDispatcher

  init {
    val resources = Resources.getSystem()
    val resId =
      resources.getIdentifier("db_connection_pool_size", "integer", "android")
    val connectionPoolSize = if (resId != 0) {
      resources.getInteger(resId)
    } else {
      2
    }
    forReads = newFixedThreadPoolContext(connectionPoolSize, "database-reads")
  }
}

/**
 * [DatabaseDispatchers] for when Write-Ahead logging is not enabled, in which case
 * only a single thread can access the database connection, there's no parallelism.
 */
@Singleton
class SingleConnectionDatabaseDispatchers @Inject constructor() : DatabaseDispatchers {
  override val forWrites = newSingleThreadContext("database-reads-writes")
  override val forReads = forWrites
}
