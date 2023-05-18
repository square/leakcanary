package leakcanary.internal.activity.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable

internal object ScopedLeaksDb {

  @Volatile
  private lateinit var leaksDbHelper: LeaksDbHelper

  private val lock = Any()

  @Volatile
  private var openCount: Int = 0

  fun <T> readableDatabase(context: Context, block: (SQLiteDatabase) -> T): T {
    return open(context).use {
      block(it.readableDatabase)
    }
  }

  fun <T> writableDatabase(context: Context, block: (SQLiteDatabase) -> T): T {
    return open(context).use {
      block(it.writableDatabase)
    }
  }

  fun open(context: Context): DbOpener {
    synchronized(lock) {
      if (!::leaksDbHelper.isInitialized) {
        leaksDbHelper = LeaksDbHelper(context.applicationContext)
      }
      openCount++
      return DbOpener()
    }
  }

  class DbOpener : Closeable {

    private var closed = false

    val readableDatabase: SQLiteDatabase
      get() {
        checkClosed()
        return leaksDbHelper.readableDatabase
      }

    val writableDatabase: SQLiteDatabase
      get() {
        checkClosed()
        return leaksDbHelper.writableDatabase
      }

    override fun close() {
      synchronized(lock) {
        checkClosed()
        closed = true
        openCount--
        if (openCount == 0) {
          // No one else needs this right now, let's close the database (will reopen on
          // next use)
          leaksDbHelper.close()
        }
      }
    }

    private fun checkClosed() {
      check(!closed) {
        "Already closed"
      }
    }
  }
}
