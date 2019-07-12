package leakcanary

class GraphContext {
  private val store = mutableMapOf<String, Any>()
  operator fun <T> get(key: String): T? {
    @Suppress("UNCHECKED_CAST")
    return store[key] as T?
  }

  operator fun <T> set(
    key: String,
    value: T
  ) {
    store[key] = (value as Any)
  }

  operator fun contains(key: String): Boolean {
    return key in store
  }

  operator fun minusAssign(key: String) {
    @Suppress("UNCHECKED_CAST")
    store -= key
  }
}