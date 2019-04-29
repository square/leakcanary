package leakcanary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

fun Serializable.save(file: File): Boolean {
  val fileOutputStream = try {
    FileOutputStream(file)
  } catch (e: FileNotFoundException) {
    return false
  }

  fileOutputStream.use {
    return try {
      val outputStream = ObjectOutputStream(it)
      outputStream.writeObject(this)
      true
    } catch (e: IOException) {
      CanaryLog.d(e, "Could not save leak analysis result to disk.")
      false
    }
  }
}

fun Serializable.toByteArray(): ByteArray {
  val outputStream = ByteArrayOutputStream()
  ObjectOutputStream(outputStream).writeObject(this)
  return outputStream.toByteArray()
}

object Serializables {

  @Suppress("UNCHECKED_CAST")
  fun <T> fromByteArray(byteArray: ByteArray): T? {
    val inputStream = ByteArrayInputStream(byteArray)
    return try {
      ObjectInputStream(inputStream).readObject() as T
    } catch (ignored: Throwable) {
      null
    }
  }

  @Suppress("UNCHECKED_CAST")
  fun <T> load(file: File): T? {

    val fileInputStream = try {
      FileInputStream(file)
    } catch (e: FileNotFoundException) {
      return null
    }

    fileInputStream.use {
      return try {
        val inputStream = ObjectInputStream(it)
        inputStream.readObject() as T
      } catch (e: Exception) {
        CanaryLog.d(e, "Could not read file %s", file)
        null
      }
    }
  }
}