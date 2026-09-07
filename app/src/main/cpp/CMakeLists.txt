cmake_minimum_required(VERSION 3.22)

project(neutts)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)

# Build the native side optimised even in the debug variant.
#
# Android Gradle compiles native code as CMAKE_BUILD_TYPE=Debug for a debuggable variant, which for
# ordinary JNI glue costs nothing. ggml is not ordinary glue: it is a numerical kernel library that
# depends on -O3 and vectorisation, and unoptimised it runs roughly ninety times slower. Measured on
# a Pixel 8: real-time factor 39 with the default debug flags, against 0.44 for the same model on a
# desktop. That is not a slow phone, it is a debug build - and since the on-device timings that
# decide whether this app is usable are taken from debug builds, they have to be measured on code
# compiled the way it ships.
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

# Add llama.cpp as a subdirectory so CMake builds it natively
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Build examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Build tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Build server" FORCE)
# ggml-vulkan includes <vulkan/vulkan.hpp>, the C++ bindings. The NDK sysroot ships only the C
# headers, so the include path is pointed at a full Vulkan-Hpp header set. VULKAN_HPP_INCLUDE_DIR is
# supplied by the build; nothing here is used unless GGML_VULKAN is on.
if (GGML_VULKAN AND VULKAN_HPP_INCLUDE_DIR)
    include_directories(BEFORE "${VULKAN_HPP_INCLUDE_DIR}")
endif()

# ggml-vulkan also includes <spirv/unified1/spirv.hpp> directly. It never links the
# SPIRV-Headers::SPIRV-Headers target, so that package's include directory is never propagated -
# the headers have to be put on the path here. The NDK ships them, so nothing extra is vendored.
if (GGML_VULKAN AND SPIRV_HEADERS_INCLUDE_DIR)
    include_directories(BEFORE "${SPIRV_HEADERS_INCLUDE_DIR}")
endif()

add_subdirectory(llama.cpp)

# espeak-ng comes in as a prebuilt .so (jniLibs/arm64-v8a) rather than being built from source:
# the library is already built for aarch64 and adding its build tree here would add minutes to
# every clean build for a component that never changes.
add_library(espeak-ng SHARED IMPORTED)
set_target_properties(espeak-ng PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libespeak-ng.so)

# Our JNI library
add_library(neutts_jni SHARED llama_engine.cpp espeak_jni.cpp)
target_include_directories(neutts_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(neutts_jni llama espeak-ng log)
