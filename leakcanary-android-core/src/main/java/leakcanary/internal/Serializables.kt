package leakcanary.internal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

internal fun Serializable.toByteArray(): ByteArray {
  val outputStream = ByteArrayOutputStream()
  ObjectOutputStream(outputStream).writeObject(this)
  return outputStream.toByteArray()
}

internal object Serializables {

  inline fun <reified T> fromByteArray(byteArray: ByteArray): T? {
    val inputStream = ByteArrayInputStream(byteArray)
    return try {
      val deserializedObject = ObjectInputStream(inputStream).readObject()
      if (deserializedObject is T) {
        deserializedObject
      } else {
        null
      }
    } catch (ignored: Throwable) {
      null
    }
  }
}