package leakcanary.internal

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/** Visible across several internal modules. */
object InternalHelper {
  inline fun <reified T : Any> noOpDelegate(): T {
    val javaClass = T::class.java
    val noOpHandler = InvocationHandler { _, _, _ ->
      // no op
    }
    return Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(javaClass), noOpHandler
    ) as T
  }

}