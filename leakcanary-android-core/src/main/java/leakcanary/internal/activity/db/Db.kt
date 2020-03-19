package leakcanary.internal.activity.db

import android.database.sqlite.SQLiteDatabase
import android.view.View
import leakcanary.internal.InternalLeakCanary
import leakcanary.internal.activity.db.Db.OnDb
import leakcanary.internal.activity.db.Io.OnIo

internal object Db {

  private val dbHelper = LeaksDbHelper(InternalLeakCanary.application)

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

    Io.execute(view) {
      val dbBlock = DbContext(dbHelper.writableDatabase)
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
      dbHelper.close()
    }
  }

}

internal fun View.executeOnDb(block: OnDb.() -> Unit) {
  Db.execute(this, block)
}