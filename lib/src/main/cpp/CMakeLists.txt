cmake_minimum_required(VERSION 3.31.6)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

set(LLAMA_SRC "" CACHE PATH "Path to the pinned llama.cpp checkout")
if(NOT EXISTS "${LLAMA_SRC}/include/llama.h")
    message(FATAL_ERROR "Set -PllamaCppDir to a llama.cpp checkout")
endif()

if(GGML_VULKAN)
    set(VULKAN_TOOLS_DIR "" CACHE PATH "Host Vulkan tools and headers installation")
    if(NOT EXISTS "${VULKAN_TOOLS_DIR}/bin/glslc" OR NOT EXISTS "${VULKAN_TOOLS_DIR}/include/vulkan/vulkan.hpp")
        message(FATAL_ERROR "Vulkan needs host glslc and Vulkan-Headers in -PvulkanToolsDir (or set -PenableVulkan=false)")
    endif()
    set(Vulkan_GLSLC_EXECUTABLE "${VULKAN_TOOLS_DIR}/bin/glslc" CACHE FILEPATH "Host glslc" FORCE)
    # /usr/include is a host implicit include path: CMake can omit it from the
    # Android compiler command. Stage only Vulkan's portable API headers so the
    # cross compiler gets Vulkan-Hpp without importing the host C library.
    set(VULKAN_HEADER_STAGE "${CMAKE_CURRENT_BINARY_DIR}/vulkan-headers")
    file(MAKE_DIRECTORY "${VULKAN_HEADER_STAGE}")
    if(NOT EXISTS "${VULKAN_TOOLS_DIR}/include/spirv/unified1/spirv.hpp")
        message(FATAL_ERROR "Vulkan requires portable SPIRV-Headers in -PvulkanToolsDir")
    endif()
    file(COPY "${VULKAN_TOOLS_DIR}/include/vulkan" "${VULKAN_TOOLS_DIR}/include/spirv"
         DESTINATION "${VULKAN_HEADER_STAGE}")
    if(EXISTS "${VULKAN_TOOLS_DIR}/include/vk_video")
        file(COPY "${VULKAN_TOOLS_DIR}/include/vk_video" DESTINATION "${VULKAN_HEADER_STAGE}")
    endif()
    set(Vulkan_INCLUDE_DIR "${VULKAN_HEADER_STAGE}" CACHE PATH "Vulkan headers" FORCE)
    list(PREPEND CMAKE_PREFIX_PATH "${VULKAN_TOOLS_DIR}")
    set(SPIRV-Headers_DIR "${VULKAN_TOOLS_DIR}/share/cmake/SPIRV-Headers" CACHE PATH "SPIRV header package")
endif()
add_subdirectory("${LLAMA_SRC}" build-llama)

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
