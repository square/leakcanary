/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package leakcanary.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.provider.OpenableColumns
import android.text.TextUtils
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import org.xmlpull.v1.XmlPullParser.END_DOCUMENT
import org.xmlpull.v1.XmlPullParser.START_TAG
import org.xmlpull.v1.XmlPullParserException

/**
 * Copy of androidx.core.content.FileProvider, converted to Kotlin.
 * This is an internal class, only public to be usable in another module.
 * TODO Consider building a public API for exposing files with the right permissions to
 * be shared.
 */
class LeakCanaryFileProvider : ContentProvider() {

  private lateinit var mStrategy: PathStrategy

  /**
   * The default FileProvider implementation does not need to be initialized. If you want to
   * override this method, you must provide your own subclass of FileProvider.
   */
  override fun onCreate(): Boolean = true

  /**
   * After the FileProvider is instantiated, this method is called to provide the system with
   * information about the provider.
   *
   * @param context A [Context] for the current component.
   * @param info A [ProviderInfo] for the new provider.
   */
  override fun attachInfo(
    context: Context,
    info: ProviderInfo
  ) {
    super.attachInfo(context, info)

    // Sanity check our security
    if (info.exported) {
      throw SecurityException("Provider must not be exported")
    }
    if (!info.grantUriPermissions) {
      throw SecurityException("Provider must grant uri permissions")
    }

    mStrategy = getPathStrategy(context, info.authority)!!
  }

  /**
   * Use a content URI returned by
   * [getUriForFile()][.getUriForFile] to get information about a file
   * managed by the FileProvider.
   * FileProvider reports the column names defined in [android.provider.OpenableColumns]:
   *
   *  * [android.provider.OpenableColumns.DISPLAY_NAME]
   *  * [android.provider.OpenableColumns.SIZE]
   *
   * For more information, see
   * [ ContentProvider.query()][ContentProvider.query].
   *
   * @param uri A content URI returned by [.getUriForFile].
   * @param projectionArg The list of columns to put into the [Cursor]. If null all columns are
   * included.
   * @param selection Selection criteria to apply. If null then all data that matches the content
   * URI is returned.
   * @param selectionArgs An array of [java.lang.String], containing arguments to bind to
   * the *selection* parameter. The *query* method scans *selection* from left to
   * right and iterates through *selectionArgs*, replacing the current "?" character in
   * *selection* with the value at the current position in *selectionArgs*. The
   * values are bound to *selection* as [java.lang.String] values.
   * @param sortOrder A [java.lang.String] containing the column name(s) on which to sort
   * the resulting [Cursor].
   * @return A [Cursor] containing the results of the query.
   */
  override fun query(
    uri: Uri,
    projectionArg: Array<String>?,
    selection: String?,
    selectionArgs: Array<String>?,
    sortOrder: String?
  ): Cursor {
    val projection = projectionArg ?: COLUMNS
    // ContentProvider has already checked granted permissions
    val file = mStrategy.getFileForUri(uri)

    var cols = arrayOfNulls<String>(projection.size)
    var values = arrayOfNulls<Any>(projection.size)
    var i = 0
    for (col in projection) {
      if (OpenableColumns.DISPLAY_NAME == col) {
        cols[i] = OpenableColumns.DISPLAY_NAME
        values[i++] = file.name
      } else if (OpenableColumns.SIZE == col) {
        cols[i] = OpenableColumns.SIZE
        values[i++] = file.length()
      }
    }

    cols = copyOfStringArray(cols, i)
    values = copyOfAnyArray(values, i)

    val cursor = MatrixCursor(cols, 1)
    cursor.addRow(values)
    return cursor
  }

  /**
   * Returns the MIME type of a content URI returned by
   * [getUriForFile()][.getUriForFile].
   *
   * @param uri A content URI returned by
   * [getUriForFile()][.getUriForFile].
   * @return If the associated file has an extension, the MIME type associated with that
   * extension; otherwise `application/octet-stream`.
   */
  override fun getType(uri: Uri): String {
    // ContentProvider has already checked granted permissions
    val file = mStrategy.getFileForUri(uri)

    val lastDot = file.name.lastIndexOf('.')
    if (lastDot >= 0) {
      val extension = file.name.substring(lastDot + 1)
      val mime = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extension)
      if (mime != null) {
        return mime
      }
    }

    return "application/octet-stream"
  }

  /**
   * By default, this method throws an [java.lang.UnsupportedOperationException]. You must
   * subclass FileProvider if you want to provide different functionality.
   */
  override fun insert(
    uri: Uri,
    values: ContentValues?
  ): Uri? {
    throw UnsupportedOperationException("No external inserts")
  }

  /**
   * By default, this method throws an [java.lang.UnsupportedOperationException]. You must
   * subclass FileProvider if you want to provide different functionality.
   */
  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<String>?
  ): Int {
    throw UnsupportedOperationException("No external updates")
  }

  /**
   * Deletes the file associated with the specified content URI, as
   * returned by [getUriForFile()][.getUriForFile]. Notice that this
   * method does **not** throw an [java.io.IOException]; you must check its return value.
   *
   * @param uri A content URI for a file, as returned by
   * [getUriForFile()][.getUriForFile].
   * @param selection Ignored. Set to `null`.
   * @param selectionArgs Ignored. Set to `null`.
   * @return 1 if the delete succeeds; otherwise, 0.
   */
  override fun delete(
    uri: Uri,
    selection: String?,
    selectionArgs: Array<String>?
  ): Int {
    // ContentProvider has already checked granted permissions
    val file = mStrategy.getFileForUri(uri)
    return if (file.delete()) 1 else 0
  }

  /**
   * By default, FileProvider automatically returns the
   * [ParcelFileDescriptor] for a file associated with a `content://`
   * [Uri]. To get the [ParcelFileDescriptor], call
   * [ ContentResolver.openFileDescriptor][android.content.ContentResolver.openFileDescriptor].
   *
   * To override this method, you must provide your own subclass of FileProvider.
   *
   * @param uri A content URI associated with a file, as returned by
   * [getUriForFile()][.getUriForFile].
   * @param mode Access mode for the file. May be "r" for read-only access, "rw" for read and
   * write access, or "rwt" for read and write access that truncates any existing file.
   * @return A new [ParcelFileDescriptor] with which you can access the file.
   */
  @Throws(FileNotFoundException::class)
  override fun openFile(
    uri: Uri,
    mode: String
  ): ParcelFileDescriptor? {
    // ContentProvider has already checked granted permissions
    val file = mStrategy.getFileForUri(uri)
    val fileMode = modeToMode(mode)
    return ParcelFileDescriptor.open(file, fileMode)
  }

  /**
   * Strategy for mapping between [File] and [Uri].
   *
   *
   * Strategies must be symmetric so that mapping a [File] to a
   * [Uri] and then back to a [File] points at the original
   * target.
   *
   *
   * Strategies must remain consistent across app launches, and not rely on
   * dynamic state. This ensures that any generated [Uri] can still be
   * resolved if your process is killed and later restarted.
   *
   * @see SimplePathStrategy
   */
  internal interface PathStrategy {
    /**
     * Return a [Uri] that represents the given [File].
     */
    fun getUriForFile(file: File): Uri

    /**
     * Return a [File] that represents the given [Uri].
     */
    fun getFileForUri(uri: Uri): File
  }

  /**
   * Strategy that provides access to files living under a narrow allowlist of
   * filesystem roots. It will throw [SecurityException] if callers try
   * accessing files outside the configured roots.
   *
   *
   * For example, if configured with
   * `addRoot("myfiles", context.getFilesDir())`, then
   * `context.getFileStreamPath("foo.txt")` would map to
   * `content://myauthority/myfiles/foo.txt`.
   */
  internal class SimplePathStrategy(private val mAuthority: String) : PathStrategy {
    private val mRoots = HashMap<String, File>()

    /**
     * Add a mapping from a name to a filesystem root. The provider only offers
     * access to files that live under configured roots.
     */
    fun addRoot(
      name: String,
      root: File
    ) {

      if (TextUtils.isEmpty(name)) {
        throw IllegalArgumentException("Name must not be empty")
      }

      mRoots[name] = try {
        // Resolve to canonical path to keep path checking fast
        root.canonicalFile
      } catch (e: IOException) {
        throw IllegalArgumentException(
          "Failed to resolve canonical path for $root", e
        )
      }
    }

    override fun getUriForFile(file: File): Uri {
      var path: String
      try {
        path = file.canonicalPath
      } catch (e: IOException) {
        throw IllegalArgumentException("Failed to resolve canonical path for $file")
      }

      // Find the most-specific root path
      var mostSpecific: MutableMap.MutableEntry<String, File>? = null
      for (root in mRoots.entries) {
        val rootPath = root.value.path
        if (path.startsWith(
            rootPath
          ) && (mostSpecific == null || rootPath.length > mostSpecific.value.path.length)
        ) {
          mostSpecific = root
        }
      }

      if (mostSpecific == null) {
        throw IllegalArgumentException(
          "Failed to find configured root that contains $path"
        )
      }

      // Start at first char of path under root
      val rootPath = mostSpecific.value.path
      val startIndex = if (rootPath.endsWith("/")) rootPath.length else rootPath.length + 1
      path = path.substring(startIndex)

      // Encode the tag and path separately
      path = Uri.encode(mostSpecific.key) + '/'.toString() + Uri.encode(path, "/")
      return Uri.Builder()
        .scheme("content")
        .authority(mAuthority)
        .encodedPath(path)
        .build()
    }

    override fun getFileForUri(uri: Uri): File {
      var path = uri.encodedPath!!

      val splitIndex = path.indexOf('/', 1)
      val tag = Uri.decode(path.substring(1, splitIndex))
      path = Uri.decode(path.substring(splitIndex + 1))

      val root = mRoots[tag]
        ?: throw IllegalArgumentException("Unable to find configured root for $uri")

      var file = File(root, path)
      try {
        file = file.canonicalFile
      } catch (e: IOException) {
        throw IllegalArgumentException("Failed to resolve canonical path for $file")
      }

      if (!file.path.startsWith(root.path)) {
        throw SecurityException("Resolved path jumped beyond configured root")
      }

      return file
    }
  }

  companion object {
    private val COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)

    private const val META_DATA_FILE_PROVIDER_PATHS = "android.support.FILE_PROVIDER_PATHS"

    private const val TAG_ROOT_PATH = "root-path"
    private const val TAG_FILES_PATH = "files-path"
    private const val TAG_CACHE_PATH = "cache-path"
    private const val TAG_EXTERNAL = "external-path"
    private const val TAG_EXTERNAL_FILES = "external-files-path"
    private const val TAG_EXTERNAL_CACHE = "external-cache-path"
    private const val TAG_EXTERNAL_MEDIA = "external-media-path"

    private const val ATTR_NAME = "name"
    private const val ATTR_PATH = "path"

    private val DEVICE_ROOT = File("/")

    private val sCache = HashMap<String, PathStrategy>()

    /**
     * Return a content URI for a given [File]. Specific temporary
     * permissions for the content URI can be set with
     * [Context.grantUriPermission], or added
     * to an [Intent] by calling [setData()][Intent.setData] and then
     * [setFlags()][Intent.setFlags]; in both cases, the applicable flags are
     * [Intent.FLAG_GRANT_READ_URI_PERMISSION] and
     * [Intent.FLAG_GRANT_WRITE_URI_PERMISSION]. A FileProvider can only return a
     * `content` [Uri] for file paths defined in their `<paths>`
     * meta-data element. See the Class Overview for more information.
     *
     * @param context A [Context] for the current component.
     * @param authority The authority of a [FileProvider] defined in a
     * `<provider>` element in your app's manifest.
     * @param file A [File] pointing to the filename for which you want a
     * `content` [Uri].
     * @return A content URI for the file.
     * @throws IllegalArgumentException When the given [File] is outside
     * the paths supported by the provider.
     */
    fun getUriForFile(
      context: Context,
      authority: String,
      file: File
    ): Uri {
      val strategy = getPathStrategy(context, authority)
      return strategy!!.getUriForFile(file)
    }

    /**
     * Return [PathStrategy] for given authority, either by parsing or
     * returning from cache.
     */
    private fun getPathStrategy(
      context: Context,
      authority: String
    ): PathStrategy? {
      var strat: PathStrategy?
      synchronized(sCache) {
        strat = sCache[authority]
        if (strat == null) {
          // Minimal "fix" for https://github.com/square/leakcanary/issues/2202
          try {
            val previousPolicy = StrictMode.getThreadPolicy()
            try {
              StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().build())
              strat = parsePathStrategy(context, authority)
            } finally {
              StrictMode.setThreadPolicy(previousPolicy)
            }
          } catch (e: IOException) {
            throw IllegalArgumentException(
              "Failed to parse $META_DATA_FILE_PROVIDER_PATHS meta-data", e
            )
          } catch (e: XmlPullParserException) {
            throw IllegalArgumentException(
              "Failed to parse $META_DATA_FILE_PROVIDER_PATHS meta-data", e
            )
          }
          sCache[authority] = strat!!
        }
      }
      return strat
    }

    /**
     * Parse and return [PathStrategy] for given authority as defined in
     * [.META_DATA_FILE_PROVIDER_PATHS] `<meta-data>`.
     *
     * @see .getPathStrategy
     */
    @Throws(IOException::class, XmlPullParserException::class)
    private fun parsePathStrategy(
      context: Context,
      authority: String
    ): PathStrategy {
      val strat = SimplePathStrategy(authority)

      val info = context.packageManager
        .resolveContentProvider(authority, PackageManager.GET_META_DATA)
        ?: throw IllegalArgumentException(
          "Couldn't find meta-data for provider with authority $authority"
        )
      val resourceParser = info.loadXmlMetaData(
        context.packageManager, META_DATA_FILE_PROVIDER_PATHS
      ) ?: throw IllegalArgumentException(
        "Missing $META_DATA_FILE_PROVIDER_PATHS meta-data"
      )

      var type: Int
      while (run {
          type = resourceParser.next()
          (type)
        } != END_DOCUMENT) {
        if (type == START_TAG) {
          val tag = resourceParser.name

          val name = resourceParser.getAttributeValue(null, ATTR_NAME)
          val path = resourceParser.getAttributeValue(null, ATTR_PATH)

          var target: File? = null
          if (TAG_ROOT_PATH == tag) {
            target = DEVICE_ROOT
          } else if (TAG_FILES_PATH == tag) {
            target = context.filesDir
          } else if (TAG_CACHE_PATH == tag) {
            target = context.cacheDir
          } else if (TAG_EXTERNAL == tag) {
            target = Environment.getExternalStorageDirectory()
          } else if (TAG_EXTERNAL_FILES == tag) {
            val externalFilesDirs = getExternalFilesDirs(context, null)
            if (externalFilesDirs.isNotEmpty()) {
              target = externalFilesDirs[0]
            }
          } else if (TAG_EXTERNAL_CACHE == tag) {
            val externalCacheDirs = getExternalCacheDirs(context)
            if (externalCacheDirs.isNotEmpty()) {
              target = externalCacheDirs[0]
            }
          } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && TAG_EXTERNAL_MEDIA == tag) {
            val externalMediaDirs = context.externalMediaDirs
            if (externalMediaDirs.isNotEmpty()) {
              target = externalMediaDirs[0]
            }
          }

          if (target != null) {
            strat.addRoot(name, buildPath(target, path))
          }
        }
      }

      return strat
    }

    private fun getExternalFilesDirs(
      context: Context,
      type: String?
    ): Array<File> {
      return if (Build.VERSION.SDK_INT >= 19) {
        context.getExternalFilesDirs(type)
      } else {
        arrayOf(context.getExternalFilesDir(type)!!)
      }
    }

    private fun getExternalCacheDirs(context: Context): Array<File> {
      return if (Build.VERSION.SDK_INT >= 19) {
        context.externalCacheDirs
      } else {
        arrayOf(context.externalCacheDir!!)
      }
    }

    /**
     * Copied from ContentResolver.java
     */
    private fun modeToMode(mode: String): Int {
      return when (mode) {
        "r" -> ParcelFileDescriptor.MODE_READ_ONLY
        "w", "wt" -> (
          ParcelFileDescriptor.MODE_WRITE_ONLY
            or ParcelFileDescriptor.MODE_CREATE
            or ParcelFileDescriptor.MODE_TRUNCATE
          )
        "wa" -> (
          ParcelFileDescriptor.MODE_WRITE_ONLY
            or ParcelFileDescriptor.MODE_CREATE
            or ParcelFileDescriptor.MODE_APPEND
          )
        "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        "rwt" -> (
          ParcelFileDescriptor.MODE_READ_WRITE
            or ParcelFileDescriptor.MODE_CREATE
            or ParcelFileDescriptor.MODE_TRUNCATE
          )
        else -> throw IllegalArgumentException("Invalid mode: $mode")
      }
    }

    private fun buildPath(
      base: File,
      vararg segments: String
    ): File {
      var cur = base
      for (segment in segments) {
        cur = File(cur, segment)
      }
      return cur
    }

    private fun copyOfStringArray(
      original: Array<String?>,
      newLength: Int
    ): Array<String?> {
      val result = arrayOfNulls<String>(newLength)
      System.arraycopy(original, 0, result, 0, newLength)
      return result
    }

    private fun copyOfAnyArray(
      original: Array<Any?>,
      newLength: Int
    ): Array<Any?> {
      val result = arrayOfNulls<Any>(newLength)
      System.arraycopy(original, 0, result, 0, newLength)
      return result
    }
  }
}
