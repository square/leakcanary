# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

set(TARGET lzma)
# Sets the minimum version of CMake required to build the native library.
cmake_minimum_required(VERSION 3.4.1)

project(${TARGET} C)

add_definitions(-D_7ZIP_ST)

set(LZMA_SOURCES 7zAlloc.c 7zArcIn.c 7zBuf2.c 7zBuf.c 7zCrc.c 7zCrcOpt.c
        7zDec.c 7zFile.c 7zStream.c Aes.c AesOpt.c Alloc.c Bcj2.c
        Bra86.c Bra.c BraIA64.c CpuArch.c Delta.c LzFind.c
        Lzma2Dec.c Lzma2Enc.c Lzma86Dec.c Lzma86Enc.c LzmaDec.c LzmaEnc.c
        LzmaLib.c Ppmd7.c Ppmd7Dec.c Ppmd7Enc.c Sha256.c Sort.c
        Xz.c XzCrc64.c XzCrc64Opt.c XzDec.c XzEnc.c XzIn.c)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
add_library( # Sets the name of the library.
        ${TARGET}

        # Sets the library as a static library.
        STATIC

        # Provides a relative path to your source file(s).
        ${LZMA_SOURCES}
        )

# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.

find_library( # Sets the name of the path variable.
        log-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries( # Specifies the target library.
        ${TARGET}
        # Links the target library to the log library
        # included in the NDK.
        ${log-lib})