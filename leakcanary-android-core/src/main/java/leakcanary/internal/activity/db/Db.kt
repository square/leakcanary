package leakcanary.internal.activity.db

import android.database.sqlite.SQLiteDatabase
import android.view.View
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.activity.db.Db.OnDb
import leakcanary.internal.activity.db.Db.dbHelper
import leakcanary.internal.activity.db.Io.OnIo

internal object Db {

  // Accessed on the IO thread only.
  private var dbHelper: LeaksDbHelper? = null

  interface OnDb : OnIo {
    val db: SQLiteDatabase
  }

  private class DbContext(override val db: SQLiteDatabase) : OnDb {
    var updateUi: (View.() -> Unit)? = null

    override fun updateUi(updateUi: View.() -> Unit) {
      this.updateUi = updateUi
    }
  }

  fun execute(
    view: View,
    block: OnDb.() -> Unit
  ) {
    val appContext = view.context.applicationContext
    Io.execute(view) {
      if (dbHelper == null) {
        dbHelper = LeaksDbHelper(appContext)
      }
      val dbBlock = DbContext(dbHelper!!.writableDatabase)
      block(dbBlock)
      val updateUi = dbBlock.updateUi
      if (updateUi != null) {
        updateUi(updateUi)
      }
    }
  }

  fun closeDatabase() {
    // Closing on the serial IO thread to ensure we don't close while using the db.
    Io.execute {
      dbHelper?.close()
    }
  }

}

internal fun View.executeOnDb(block: OnDb.() -> Unit) {
  Db.execute(this, block)
}