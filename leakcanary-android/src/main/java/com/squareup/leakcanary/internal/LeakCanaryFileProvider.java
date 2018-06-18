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

/*
 * A copy of support-v4's FileProvider that supports a new <external-app-path/> tag which uses
 * Context.getExternalFilesDir(null) as its root.
 * http://b.android.com/67171 and http://b.android.com/184603.
 */

package com.squareup.leakcanary.internal;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

// A copy of FileProvider from support-core-utils.
// This was copied to avoid having a dependency on the support library and forcing our LeakCanary
// users to deal with support library versioning issues.
/**
 * FileProvider is a special subclass of {@link ContentProvider} that facilitates secure sharing
 * of files associated with an app by creating a <code>content://</code> {@link Uri} for a file
 * instead of a <code>file:///</code> {@link Uri}.
 * <p>
 * A content URI allows you to grant read and write access using
 * temporary access permissions. When you create an {@link Intent} containing
 * a content URI, in order to send the content URI
 * to a client app, you can also call {@link Intent#setFlags(int) Intent.setFlags()} to add
 * permissions. These permissions are available to the client app for as long as the stack for
 * a receiving {@link android.app.Activity} is active. For an {@link Intent} going to a
 * {@link android.app.Service}, the permissions are available as long as the
 * {@link android.app.Service} is running.
 * <p>
 * In comparison, to control access to a <code>file:///</code> {@link Uri} you have to modify the
 * file system permissions of the underlying file. The permissions you provide become available to
 * <em>any</em> app, and remain in effect until you change them. This level of access is
 * fundamentally insecure.
 * <p>
 * The increased level of file access security offered by a content URI
 * makes FileProvider a key part of Android's security infrastructure.
 * <p>
 * This overview of FileProvider includes the following topics:
 * </p>
 * <ol>
 * <li><a href="#ProviderDefinition">Defining a FileProvider</a></li>
 * <li><a href="#SpecifyFiles">Specifying Available Files</a></li>
 * <li><a href="#GetUri">Retrieving the Content URI for a File</li>
 * <li><a href="#Permissions">Granting Temporary Permissions to a URI</a></li>
 * <li><a href="#ServeUri">Serving a Content URI to Another App</a></li>
 * </ol>
 * <h3 id="ProviderDefinition">Defining a FileProvider</h3>
 * <p>
 * Since the default functionality of FileProvider includes content URI generation for files, you
 * don't need to define a subclass in code. Instead, you can include a FileProvider in your app
 * by specifying it entirely in XML. To specify the FileProvider component itself, add a
 * <code><a href="{@docRoot}guide/topics/manifest/provider-element.html">&lt;provider&gt;</a></code>
 * element to your app manifest. Set the <code>android:name</code> attribute to
 * <code>android.support.v4.content.FileProvider</code>. Set the <code>android:authorities</code>
 * attribute to a URI authority based on a domain you control; for example, if you control the
 * domain <code>mydomain.com</code> you should use the authority
 * <code>com.mydomain.fileprovider</code>. Set the <code>android:exported</code> attribute to
 * <code>false</code>; the FileProvider does not need to be public. Set the
 * <a href="{@docRoot}guide/topics/manifest/provider-element.html#gprmsn"
 * >android:grantUriPermissions</a> attribute to <code>true</code>, to allow you
 * to grant temporary access to files. For example:
 * <pre class="prettyprint">
 * &lt;manifest&gt;
 * ...
 * &lt;application&gt;
 * ...
 * &lt;provider
 * android:name="android.support.v4.content.FileProvider"
 * android:authorities="com.mydomain.fileprovider"
 * android:exported="false"
 * android:grantUriPermissions="true"&gt;
 * ...
 * &lt;/provider&gt;
 * ...
 * &lt;/application&gt;
 * &lt;/manifest&gt;</pre>
 * <p>
 * If you want to override any of the default behavior of FileProvider methods, extend
 * the FileProvider class and use the fully-qualified class name in the <code>android:name</code>
 * attribute of the <code>&lt;provider&gt;</code> element.
 * <h3 id="SpecifyFiles">Specifying Available Files</h3>
 * A FileProvider can only generate a content URI for files in directories that you specify
 * beforehand. To specify a directory, specify the its storage area and path in XML, using child
 * elements of the <code>&lt;paths&gt;</code> element.
 * For example, the following <code>paths</code> element tells FileProvider that you intend to
 * request content URIs for the <code>images/</code> subdirectory of your private file area.
 * <pre class="prettyprint">
 * &lt;paths xmlns:android="http://schemas.android.com/apk/res/android"&gt;
 * &lt;files-path name="my_images" path="images/"/&gt;
 * ...
 * &lt;/paths&gt;
 * </pre>
 * <p>
 * The <code>&lt;paths&gt;</code> element must contain one or more of the following child elements:
 * </p>
 * <dl>
 * <dt>
 * <pre class="prettyprint">
 * &lt;files-path name="<i>name</i>" path="<i>path</i>" /&gt;
 * </pre>
 * </dt>
 * <dd>
 * Represents files in the <code>files/</code> subdirectory of your app's internal storage
 * area. This subdirectory is the same as the value returned by {@link Context#getFilesDir()
 * Context.getFilesDir()}.
 * <dt>
 * <pre class="prettyprint">
 * &lt;external-path name="<i>name</i>" path="<i>path</i>" /&gt;
 * </pre>
 * </dt>
 * <dd>
 * Represents files in the root of your app's external storage area. The path
 * {@link Context#getExternalFilesDir(String) Context.getExternalFilesDir()} returns the
 * <code>files/</code> subdirectory of this this root.
 * </dd>
 * <dt>
 * <pre>
 * &lt;cache-path name="<i>name</i>" path="<i>path</i>" /&gt;
 * </pre>
 * <dt>
 * <dd>
 * Represents files in the cache subdirectory of your app's internal storage area. The root path
 * of this subdirectory is the same as the value returned by {@link Context#getCacheDir()
 * getCacheDir()}.
 * </dd>
 * </dl>
 * <p>
 * These child elements all use the same attributes:
 * </p>
 * <dl>
 * <dt>
 * <code>name="<i>name</i>"</code>
 * </dt>
 * <dd>
 * A URI path segment. To enforce security, this value hides the name of the subdirectory
 * you're sharing. The subdirectory name for this value is contained in the
 * <code>path</code> attribute.
 * </dd>
 * <dt>
 * <code>path="<i>path</i>"</code>
 * </dt>
 * <dd>
 * The subdirectory you're sharing. While the <code>name</code> attribute is a URI path
 * segment, the <code>path</code> value is an actual subdirectory name. Notice that the
 * value refers to a <b>subdirectory</b>, not an individual file or files. You can't
 * share a single file by its file name, nor can you specify a subset of files using
 * wildcards.
 * </dd>
 * </dl>
 * <p>
 * You must specify a child element of <code>&lt;paths&gt;</code> for each directory that contains
 * files for which you want content URIs. For example, these XML elements specify two directories:
 * <pre class="prettyprint">
 * &lt;paths xmlns:android="http://schemas.android.com/apk/res/android"&gt;
 * &lt;files-path name="my_images" path="images/"/&gt;
 * &lt;files-path name="my_docs" path="docs/"/&gt;
 * &lt;/paths&gt;
 * </pre>
 * <p>
 * Put the <code>&lt;paths&gt;</code> element and its children in an XML file in your project.
 * For example, you can add them to a new file called <code>res/xml/file_paths.xml</code>.
 * To link this file to the FileProvider, add a
 * <a href="{@docRoot}guide/topics/manifest/meta-data-element.html">&lt;meta-data&gt;</a> element
 * as a child of the <code>&lt;provider&gt;</code> element that defines the FileProvider. Set the
 * <code>&lt;meta-data&gt;</code> element's "android:name" attribute to
 * <code>android.support.FILE_PROVIDER_PATHS</code>. Set the element's "android:resource" attribute
 * to <code>&#64;xml/file_paths</code> (notice that you don't specify the <code>.xml</code>
 * extension). For example:
 * <pre class="prettyprint">
 * &lt;provider
 * android:name="android.support.v4.content.FileProvider"
 * android:authorities="com.mydomain.fileprovider"
 * android:exported="false"
 * android:grantUriPermissions="true"&gt;
 * &lt;meta-data
 * android:name="android.support.FILE_PROVIDER_PATHS"
 * android:resource="&#64;xml/file_paths" /&gt;
 * &lt;/provider&gt;
 * </pre>
 * <h3 id="GetUri">Generating the Content URI for a File</h3>
 * <p>
 * To share a file with another app using a content URI, your app has to generate the content URI.
 * To generate the content URI, create a new {@link File} for the file, then pass the {@link File}
 * to {@link #getUriForFile(Context, String, File) getUriForFile()}. You can send the content URI
 * returned by {@link #getUriForFile(Context, String, File) getUriForFile()} to another app in an
 * {@link android.content.Intent}. The client app that receives the content URI can open the file
 * and access its contents by calling
 * {@link android.content.ContentResolver#openFileDescriptor(Uri, String)
 * ContentResolver.openFileDescriptor} to get a {@link ParcelFileDescriptor}.
 * <p>
 * For example, suppose your app is offering files to other apps with a FileProvider that has the
 * authority <code>com.mydomain.fileprovider</code>. To get a content URI for the file
 * <code>default_image.jpg</code> in the <code>images/</code> subdirectory of your internal storage
 * add the following code:
 * <pre class="prettyprint">
 * File imagePath = new File(Context.getFilesDir(), "images");
 * File newFile = new File(imagePath, "default_image.jpg");
 * Uri contentUri = getUriForFile(getContext(), "com.mydomain.fileprovider", newFile);
 * </pre>
 * As a result of the previous snippet,
 * {@link #getUriForFile(Context, String, File) getUriForFile()} returns the content URI
 * <code>content://com.mydomain.fileprovider/my_images/default_image.jpg</code>.
 * <h3 id="Permissions">Granting Temporary Permissions to a URI</h3>
 * To grant an access permission to a content URI returned from
 * {@link #getUriForFile(Context, String, File) getUriForFile()}, do one of the following:
 * <ul>
 * <li>
 * Call the method
 * {@link Context#grantUriPermission(String, Uri, int)
 * Context.grantUriPermission(package, Uri, mode_flags)} for the <code>content://</code>
 * {@link Uri}, using the desired mode flags. This grants temporary access permission for the
 * content URI to the specified package, according to the value of the
 * the <code>mode_flags</code> parameter, which you can set to
 * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION}, {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION}
 * or both. The permission remains in effect until you revoke it by calling
 * {@link Context#revokeUriPermission(Uri, int) revokeUriPermission()} or until the device
 * reboots.
 * </li>
 * <li>
 * Put the content URI in an {@link Intent} by calling {@link Intent#setData(Uri) setData()}.
 * </li>
 * <li>
 * Next, call the method {@link Intent#setFlags(int) Intent.setFlags()} with either
 * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} or
 * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION} or both.
 * </li>
 * <li>
 * Finally, send the {@link Intent} to
 * another app. Most often, you do this by calling
 * {@link android.app.Activity#setResult(int, android.content.Intent) setResult()}.
 * <p>
 * Permissions granted in an {@link Intent} remain in effect while the stack of the receiving
 * {@link android.app.Activity} is active. When the stack finishes, the permissions are
 * automatically removed. Permissions granted to one {@link android.app.Activity} in a client
 * app are automatically extended to other components of that app.
 * </p>
 * </li>
 * </ul>
 * <h3 id="ServeUri">Serving a Content URI to Another App</h3>
 * <p>
 * There are a variety of ways to serve the content URI for a file to a client app. One common way
 * is for the client app to start your app by calling
 * {@link android.app.Activity#startActivityForResult(Intent, int, Bundle) startActivityResult()},
 * which sends an {@link Intent} to your app to start an {@link android.app.Activity} in your app.
 * In response, your app can immediately return a content URI to the client app or present a user
 * interface that allows the user to pick a file. In the latter case, once the user picks the file
 * your app can return its content URI. In both cases, your app returns the content URI in an
 * {@link Intent} sent via {@link android.app.Activity#setResult(int, Intent) setResult()}.
 * </p>
 * <p>
 * You can also put the content URI in a {@link android.content.ClipData} object and then add the
 * object to an {@link Intent} you send to a client app. To do this, call
 * {@link Intent#setClipData(ClipData) Intent.setClipData()}. When you use this approach, you can
 * add multiple {@link android.content.ClipData} objects to the {@link Intent}, each with its own
 * content URI. When you call {@link Intent#setFlags(int) Intent.setFlags()} on the {@link Intent}
 * to set temporary access permissions, the same permissions are applied to all of the content
 * URIs.
 * </p>
 * <p class="note">
 * <strong>Note:</strong> The {@link Intent#setClipData(ClipData) Intent.setClipData()} method is
 * only available in platform version 16 (Android 4.1) and later. If you want to maintain
 * compatibility with previous versions, you should send one content URI at a time in the
 * {@link Intent}. Set the action to {@link Intent#ACTION_SEND} and put the URI in data by calling
 * {@link Intent#setData setData()}.
 * </p>
 * <h3 id="">More Information</h3>
 * <p>
 * To learn more about FileProvider, see the Android training class
 * <a href="{@docRoot}training/secure-file-sharing/index.html">Sharing Files Securely with
 * URIs</a>.
 * </p>
 */
public class LeakCanaryFileProvider extends ContentProvider {
  private static final String[] COLUMNS = {
      OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE
  };

  private static final String META_DATA_FILE_PROVIDER_PATHS = "android.support.FILE_PROVIDER_PATHS";

  private static final String TAG_ROOT_PATH = "root-path";
  private static final String TAG_FILES_PATH = "files-path";
  private static final String TAG_CACHE_PATH = "cache-path";
  private static final String TAG_EXTERNAL = "external-path";
  private static final String TAG_EXTERNAL_APP = "external-app-path";

  private static final String ATTR_NAME = "name";
  private static final String ATTR_PATH = "path";

  private static final File DEVICE_ROOT = new File("/");

  // @GuardedBy("sCache")
  private static HashMap<String, PathStrategy> sCache = new HashMap<String, PathStrategy>();

  private PathStrategy mStrategy;

  /**
   * The default FileProvider implementation does not need to be initialized. If you want to
   * override this method, you must provide your own subclass of FileProvider.
   */
  @Override public boolean onCreate() {
    return true;
  }

  /**
   * After the FileProvider is instantiated, this method is called to provide the system with
   * information about the provider.
   *
   * @param context A {@link Context} for the current component.
   * @param info A {@link ProviderInfo} for the new provider.
   */
  @Override public void attachInfo(Context context, ProviderInfo info) {
    super.attachInfo(context, info);

    // Sanity check our security
    if (info.exported) {
      throw new SecurityException("Provider must not be exported");
    }
    if (!info.grantUriPermissions) {
      throw new SecurityException("Provider must grant uri permissions");
    }

    mStrategy = getPathStrategy(context, info.authority);
  }

  /**
   * Return a content URI for a given {@link File}. Specific temporary
   * permissions for the content URI can be set with
   * {@link Context#grantUriPermission(String, Uri, int)}, or added
   * to an {@link Intent} by calling {@link Intent#setData(Uri) setData()} and then
   * {@link Intent#setFlags(int) setFlags()}; in both cases, the applicable flags are
   * {@link Intent#FLAG_GRANT_READ_URI_PERMISSION} and
   * {@link Intent#FLAG_GRANT_WRITE_URI_PERMISSION}. A FileProvider can only return a
   * <code>content</code> {@link Uri} for file paths defined in their <code>&lt;paths&gt;</code>
   * meta-data element. See the Class Overview for more information.
   *
   * @param context A {@link Context} for the current component.
   * @param authority The authority of a {@link LeakCanaryFileProvider} defined in a
   * {@code &lt;provider&gt;} element in your app's manifest.
   * @param file A {@link File} pointing to the filename for which you want a
   * <code>content</code> {@link Uri}.
   * @return A content URI for the file.
   * @throws IllegalArgumentException When the given {@link File} is outside
   * the paths supported by the provider.
   */
  protected static Uri getUriForFile(Context context, String authority, File file) {
    final PathStrategy strategy = getPathStrategy(context, authority);
    return strategy.getUriForFile(file);
  }

  /**
   * Use a content URI returned by
   * {@link #getUriForFile(Context, String, File) getUriForFile()} to get information about a file
   * managed by the FileProvider.
   * FileProvider reports the column names defined in {@link android.provider.OpenableColumns}:
   * <ul>
   * <li>{@link android.provider.OpenableColumns#DISPLAY_NAME}</li>
   * <li>{@link android.provider.OpenableColumns#SIZE}</li>
   * </ul>
   * For more information, see
   * {@link ContentProvider#query(Uri, String[], String, String[], String)
   * ContentProvider.query()}.
   *
   * @param uri A content URI returned by {@link #getUriForFile}.
   * @param projection The list of columns to put into the {@link Cursor}. If null all columns are
   * included.
   * @param selection Selection criteria to apply. If null then all data that matches the content
   * URI is returned.
   * @param selectionArgs An array of {@link java.lang.String}, containing arguments to bind to
   * the <i>selection</i> parameter. The <i>query</i> method scans <i>selection</i> from left to
   * right and iterates through <i>selectionArgs</i>, replacing the current "?" character in
   * <i>selection</i> with the value at the current position in <i>selectionArgs</i>. The
   * values are bound to <i>selection</i> as {@link java.lang.String} values.
   * @param sortOrder A {@link java.lang.String} containing the column name(s) on which to sort
   * the resulting {@link Cursor}.
   * @return A {@link Cursor} containing the results of the query.
   */
  @Override public Cursor query(Uri uri, String[] projection, String selection,
      String[] selectionArgs, String sortOrder) {
    // ContentProvider has already checked granted permissions
    final File file = mStrategy.getFileForUri(uri);

    if (projection == null) {
      projection = COLUMNS;
    }

    String[] cols = new String[projection.length];
    Object[] values = new Object[projection.length];
    int i = 0;
    for (String col : projection) {
      if (OpenableColumns.DISPLAY_NAME.equals(col)) {
        cols[i] = OpenableColumns.DISPLAY_NAME;
        values[i++] = file.getName();
      } else if (OpenableColumns.SIZE.equals(col)) {
        cols[i] = OpenableColumns.SIZE;
        values[i++] = file.length();
      }
    }

    cols = copyOf(cols, i);
    values = copyOf(values, i);

    final MatrixCursor cursor = new MatrixCursor(cols, 1);
    cursor.addRow(values);
    return cursor;
  }

  /**
   * Returns the MIME type of a content URI returned by
   * {@link #getUriForFile(Context, String, File) getUriForFile()}.
   *
   * @param uri A content URI returned by
   * {@link #getUriForFile(Context, String, File) getUriForFile()}.
   * @return If the associated file has an extension, the MIME type associated with that
   * extension; otherwise <code>application/octet-stream</code>.
   */
  @Override public String getType(Uri uri) {
    // ContentProvider has already checked granted permissions
    final File file = mStrategy.getFileForUri(uri);

    final int lastDot = file.getName().lastIndexOf('.');
    if (lastDot >= 0) {
      final String extension = file.getName().substring(lastDot + 1);
      final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
      if (mime != null) {
        return mime;
      }
    }

    return "application/octet-stream";
  }

  /**
   * By default, this method throws an {@link java.lang.UnsupportedOperationException}. You must
   * subclass FileProvider if you want to provide different functionality.
   */
  @Override public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException("No external inserts");
  }

  /**
   * By default, this method throws an {@link java.lang.UnsupportedOperationException}. You must
   * subclass FileProvider if you want to provide different functionality.
   */
  @Override public int update(Uri uri, ContentValues values, String selection,
      String[] selectionArgs) {
    throw new UnsupportedOperationException("No external updates");
  }

  /**
   * Deletes the file associated with the specified content URI, as
   * returned by {@link #getUriForFile(Context, String, File) getUriForFile()}. Notice that this
   * method does <b>not</b> throw an {@link java.io.IOException}; you must check its return value.
   *
   * @param uri A content URI for a file, as returned by
   * {@link #getUriForFile(Context, String, File) getUriForFile()}.
   * @param selection Ignored. Set to {@code null}.
   * @param selectionArgs Ignored. Set to {@code null}.
   * @return 1 if the delete succeeds; otherwise, 0.
   */
  @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
    // ContentProvider has already checked granted permissions
    final File file = mStrategy.getFileForUri(uri);
    return file.delete() ? 1 : 0;
  }

  /**
   * By default, FileProvider automatically returns the
   * {@link ParcelFileDescriptor} for a file associated with a <code>content://</code>
   * {@link Uri}. To get the {@link ParcelFileDescriptor}, call
   * {@link android.content.ContentResolver#openFileDescriptor(Uri, String)
   * ContentResolver.openFileDescriptor}.
   *
   * To override this method, you must provide your own subclass of FileProvider.
   *
   * @param uri A content URI associated with a file, as returned by
   * {@link #getUriForFile(Context, String, File) getUriForFile()}.
   * @param mode Access mode for the file. May be "r" for read-only access, "rw" for read and
   * write access, or "rwt" for read and write access that truncates any existing file.
   * @return A new {@link ParcelFileDescriptor} with which you can access the file.
   */
  @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
      throws FileNotFoundException {
    // ContentProvider has already checked granted permissions
    final File file = mStrategy.getFileForUri(uri);
    final int fileMode = modeToMode(mode);
    return ParcelFileDescriptor.open(file, fileMode);
  }

  /**
   * Return {@link PathStrategy} for given authority, either by parsing or
   * returning from cache.
   */
  private static PathStrategy getPathStrategy(Context context, String authority) {
    PathStrategy strat;
    synchronized (sCache) {
      strat = sCache.get(authority);
      if (strat == null) {
        try {
          strat = parsePathStrategy(context, authority);
        } catch (IOException e) {
          throw new IllegalArgumentException(
              "Failed to parse " + META_DATA_FILE_PROVIDER_PATHS + " meta-data", e);
        } catch (XmlPullParserException e) {
          throw new IllegalArgumentException(
              "Failed to parse " + META_DATA_FILE_PROVIDER_PATHS + " meta-data", e);
        }
        sCache.put(authority, strat);
      }
    }
    return strat;
  }

  /**
   * Parse and return {@link PathStrategy} for given authority as defined in
   * {@link #META_DATA_FILE_PROVIDER_PATHS} {@code &lt;meta-data>}.
   *
   * @see #getPathStrategy(Context, String)
   */
  private static PathStrategy parsePathStrategy(Context context, String authority)
      throws IOException, XmlPullParserException {
    final SimplePathStrategy strat = new SimplePathStrategy(authority);

    final ProviderInfo info =
        context.getPackageManager().resolveContentProvider(authority, PackageManager.GET_META_DATA);
    final XmlResourceParser in =
        info.loadXmlMetaData(context.getPackageManager(), META_DATA_FILE_PROVIDER_PATHS);
    if (in == null) {
      throw new IllegalArgumentException("Missing " + META_DATA_FILE_PROVIDER_PATHS + " meta-data");
    }

    int type;
    while ((type = in.next()) != END_DOCUMENT) {
      if (type == START_TAG) {
        final String tag = in.getName();

        final String name = in.getAttributeValue(null, ATTR_NAME);
        String path = in.getAttributeValue(null, ATTR_PATH);

        File target = null;
        if (TAG_ROOT_PATH.equals(tag)) {
          target = buildPath(DEVICE_ROOT, path);
        } else if (TAG_FILES_PATH.equals(tag)) {
          target = buildPath(context.getFilesDir(), path);
        } else if (TAG_CACHE_PATH.equals(tag)) {
          target = buildPath(context.getCacheDir(), path);
        } else if (TAG_EXTERNAL.equals(tag)) {
          target = buildPath(Environment.getExternalStorageDirectory(), path);
        } else if (TAG_EXTERNAL_APP.equals(tag)) {
          target = buildPath(context.getExternalFilesDir(null), path);
        }

        if (target != null) {
          strat.addRoot(name, target);
        }
      }
    }

    return strat;
  }

  /**
   * Strategy for mapping between {@link File} and {@link Uri}.
   * <p>
   * Strategies must be symmetric so that mapping a {@link File} to a
   * {@link Uri} and then back to a {@link File} points at the original
   * target.
   * <p>
   * Strategies must remain consistent across app launches, and not rely on
   * dynamic state. This ensures that any generated {@link Uri} can still be
   * resolved if your process is killed and later restarted.
   *
   * @see SimplePathStrategy
   */
  interface PathStrategy {
    /**
     * Return a {@link Uri} that represents the given {@link File}.
     */
    public Uri getUriForFile(File file);

    /**
     * Return a {@link File} that represents the given {@link Uri}.
     */
    public File getFileForUri(Uri uri);
  }

  /**
   * Strategy that provides access to files living under a narrow whitelist of
   * filesystem roots. It will throw {@link SecurityException} if callers try
   * accessing files outside the configured roots.
   * <p>
   * For example, if configured with
   * {@code addRoot("myfiles", context.getFilesDir())}, then
   * {@code context.getFileStreamPath("foo.txt")} would map to
   * {@code content://myauthority/myfiles/foo.txt}.
   */
  static class SimplePathStrategy implements PathStrategy {
    private final String mAuthority;
    private final HashMap<String, File> mRoots = new HashMap<String, File>();

    public SimplePathStrategy(String authority) {
      mAuthority = authority;
    }

    /**
     * Add a mapping from a name to a filesystem root. The provider only offers
     * access to files that live under configured roots.
     */
    public void addRoot(String name, File root) {
      if (TextUtils.isEmpty(name)) {
        throw new IllegalArgumentException("Name must not be empty");
      }

      try {
        // Resolve to canonical path to keep path checking fast
        root = root.getCanonicalFile();
      } catch (IOException e) {
        throw new IllegalArgumentException("Failed to resolve canonical path for " + root, e);
      }

      mRoots.put(name, root);
    }

    @Override public Uri getUriForFile(File file) {
      String path;
      try {
        path = file.getCanonicalPath();
      } catch (IOException e) {
        throw new IllegalArgumentException("Failed to resolve canonical path for " + file);
      }

      // Find the most-specific root path
      Map.Entry<String, File> mostSpecific = null;
      for (Map.Entry<String, File> root : mRoots.entrySet()) {
        final String rootPath = root.getValue().getPath();
        if (path.startsWith(rootPath) && (mostSpecific == null
            || rootPath.length() > mostSpecific.getValue().getPath().length())) {
          mostSpecific = root;
        }
      }

      if (mostSpecific == null) {
        throw new IllegalArgumentException("Failed to find configured root that contains " + path);
      }

      // Start at first char of path under root
      final String rootPath = mostSpecific.getValue().getPath();
      if (rootPath.endsWith("/")) {
        path = path.substring(rootPath.length());
      } else {
        path = path.substring(rootPath.length() + 1);
      }

      // Encode the tag and path separately
      path = Uri.encode(mostSpecific.getKey()) + '/' + Uri.encode(path, "/");
      return new Uri.Builder().scheme("content").authority(mAuthority).encodedPath(path).build();
    }

    @Override public File getFileForUri(Uri uri) {
      String path = uri.getEncodedPath();

      final int splitIndex = path.indexOf('/', 1);
      final String tag = Uri.decode(path.substring(1, splitIndex));
      path = Uri.decode(path.substring(splitIndex + 1));

      final File root = mRoots.get(tag);
      if (root == null) {
        throw new IllegalArgumentException("Unable to find configured root for " + uri);
      }

      File file = new File(root, path);
      try {
        file = file.getCanonicalFile();
      } catch (IOException e) {
        throw new IllegalArgumentException("Failed to resolve canonical path for " + file);
      }

      if (!file.getPath().startsWith(root.getPath())) {
        throw new SecurityException("Resolved path jumped beyond configured root");
      }

      return file;
    }
  }

  /**
   * Copied from ContentResolver.java
   */
  private static int modeToMode(String mode) {
    int modeBits;
    if ("r".equals(mode)) {
      modeBits = ParcelFileDescriptor.MODE_READ_ONLY;
    } else if ("w".equals(mode) || "wt".equals(mode)) {
      modeBits = ParcelFileDescriptor.MODE_WRITE_ONLY
          | ParcelFileDescriptor.MODE_CREATE
          | ParcelFileDescriptor.MODE_TRUNCATE;
    } else if ("wa".equals(mode)) {
      modeBits = ParcelFileDescriptor.MODE_WRITE_ONLY
          | ParcelFileDescriptor.MODE_CREATE
          | ParcelFileDescriptor.MODE_APPEND;
    } else if ("rw".equals(mode)) {
      modeBits = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
    } else if ("rwt".equals(mode)) {
      modeBits = ParcelFileDescriptor.MODE_READ_WRITE
          | ParcelFileDescriptor.MODE_CREATE
          | ParcelFileDescriptor.MODE_TRUNCATE;
    } else {
      throw new IllegalArgumentException("Invalid mode: " + mode);
    }
    return modeBits;
  }

  private static File buildPath(File base, String... segments) {
    File cur = base;
    for (String segment : segments) {
      if (segment != null) {
        cur = new File(cur, segment);
      }
    }
    return cur;
  }

  private static String[] copyOf(String[] original, int newLength) {
    final String[] result = new String[newLength];
    System.arraycopy(original, 0, result, 0, newLength);
    return result;
  }

  private static Object[] copyOf(Object[] original, int newLength) {
    final Object[] result = new Object[newLength];
    System.arraycopy(original, 0, result, 0, newLength);
    return result;
  }
}