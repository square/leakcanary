package leakcanary.internal.activity.db

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * Similar to the more generic use() for Closable.
 * Cursor started implementing Closable in API 16.
 */
internal inline fun <R> Cursor.use(block: (Cursor) -> R): R {
  var exception: Throwable? = null
  try {
    return block(this)
  } catch (e: Throwable) {
    exception = e
    throw e
  } finally {
    when (exception) {
      null -> close()
      else -> try {
        close()
      } catch (ignoredCloseException: Throwable) {
      }
    }
  }
}

internal inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
  try {
    beginTransaction()
    val result = block()
    setTransactionSuccessful()
    return result
  } finally {
    endTransaction()
  }
}