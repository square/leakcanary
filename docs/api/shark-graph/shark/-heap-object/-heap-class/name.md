[shark-graph](../../../index.md) / [shark](../../index.md) / [HeapObject](../index.md) / [HeapClass](index.md) / [name](./name.md)

# name

`val name: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)

The name of this class, identical to [Class.getName](https://docs.oracle.com/javase/6/docs/api/java/lang/Class.html#getName()).
If this class is an array class, the name has a suffix of brackets for each dimension of
the array, e.g. `com.Foo[][]` is a class for 2 dimensional arrays of `com.Foo`.

The behavior for primitive types changes depending on the VM that dumped the heap. JVM
heap dumps don't have any [HeapClass](index.md) object for primitive types, instead the
`java.land.Class` class has 9 instances (the 8 primitive types and `void`). Android heap
dumps have an [HeapClass](index.md) object for primitive type and the `java.land.Class` class has no
instance.

If this is an array class, you can find the component type by removing the brackets at the
end, e.g. `name.substringBefore('[')`. Be careful when doing this for JVM heap dumps though,
as if the component type is a primitive type there will not be a [HeapClass](index.md) object for it.
This is especially tricky with N dimension primitive type arrays, which are instances of
[HeapObjectArray](../-heap-object-array/index.md) (vs single dimension primitive type arrays which are instances of
[HeapPrimitiveArray](../-heap-primitive-array/index.md)).

