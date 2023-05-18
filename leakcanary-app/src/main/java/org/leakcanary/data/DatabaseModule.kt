package org.leakcanary.data

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.squareup.sqldelight.android.AndroidSqliteDriver
import com.squareup.sqldelight.db.SqlDriver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton
import org.leakcanary.Database

@Module
@InstallIn(SingletonComponent::class)
class DatabaseModule {

  @Qualifier
  @Retention(AnnotationRetention.BINARY)
  annotation class WriteAheadLoggingEnabled

  @Provides @WriteAheadLoggingEnabled
  fun provideWriteAheadLoggingEnabled(app: Application): Boolean {
    val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return !activityManager.isLowRamDevice
  }

  @Provides @Singleton fun provideSqliteDriver(
    app: Application, @WriteAheadLoggingEnabled wolEnabled: Boolean
  ): SqlDriver {
    val realFactory = FrameworkSQLiteOpenHelperFactory()
    return AndroidSqliteDriver(
      schema = Database.Schema, factory = { configuration ->
        realFactory.create(configuration).apply { setWriteAheadLoggingEnabled(wolEnabled) }
      }, context = app, name = "leakcanary.db"
    )
  }

  @Provides @Singleton fun provideDatabase(driver: SqlDriver): Database = Database(driver)

  @Provides fun provideDatabaseDispatchers(
    @WriteAheadLoggingEnabled wolEnabled: Boolean,
    wolDispatchers: Provider<WriteAheadLoggingEnabledDatabaseDispatchers>,
    singleDispatchers: Provider<SingleConnectionDatabaseDispatchers>
  ): DatabaseDispatchers {
    return if (wolEnabled) wolDispatchers.get() else singleDispatchers.get()
  }
}
