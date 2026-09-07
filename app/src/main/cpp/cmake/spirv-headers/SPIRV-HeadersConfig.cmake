# Minimal package config for SPIRV-Headers.
#
# ggml-vulkan does `find_package(SPIRV-Headers CONFIG REQUIRED)` but never references the imported
# target - it only wants the headers on the include path. The Android NDK already ships a copy
# (under sources/third_party/shaderc), so rather than vendoring a second one or requiring the
# Vulkan SDK to be installed, this points at the NDK's.
#
# SPIRV_HEADERS_INCLUDE_DIR is passed in by the build; if it is not, fall back to locating it
# relative to the NDK so a plain cmake invocation still works.
if (NOT SPIRV_HEADERS_INCLUDE_DIR)
    set(SPIRV_HEADERS_INCLUDE_DIR
        "${CMAKE_ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include")
endif()

if (NOT EXISTS "${SPIRV_HEADERS_INCLUDE_DIR}/spirv")
    message(FATAL_ERROR "SPIRV headers not found at ${SPIRV_HEADERS_INCLUDE_DIR}")
endif()

if (NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${SPIRV_HEADERS_INCLUDE_DIR}")
endif()

set(SPIRV-Headers_FOUND TRUE)
