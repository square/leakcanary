package leakcanary

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import leakcanary.internal.activity.db.LeaksDbHelper
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class DatabaseRule(private val updateDb: (SQLiteDatabase) -> Unit = {}) : TestRule {
  override fun apply(
    base: Statement,
    description: Description
  ): Statement {
    return object : Statement() {
      override fun evaluate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.deleteDatabase(LeaksDbHelper.DATABASE_NAME)
        LeaksDbHelper(context).writableDatabase.use(updateDb)
        base.evaluate()
        context.deleteDatabase(LeaksDbHelper.DATABASE_NAME)
      }
    }
  }
}