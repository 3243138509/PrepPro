cmake_minimum_required(VERSION 3.22.1)

project(preppro_llama_classifier)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(PREPPRO_LLAMA_ROOT "${CMAKE_CURRENT_LIST_DIR}/third_party/llama.cpp")
set(PREPPRO_LLAMA_VENDOR_DIR "${PREPPRO_LLAMA_ROOT}/vendor")

set(PREPPRO_LLAMA_SOURCE_DIR "")
if(EXISTS "${PREPPRO_LLAMA_VENDOR_DIR}/CMakeLists.txt")
    set(PREPPRO_LLAMA_SOURCE_DIR "${PREPPRO_LLAMA_VENDOR_DIR}")
elseif(EXISTS "${PREPPRO_LLAMA_ROOT}/CMakeLists.txt")
    set(PREPPRO_LLAMA_SOURCE_DIR "${PREPPRO_LLAMA_ROOT}")
endif()

add_library(
    llama_classifier
    SHARED
    jni/llama_jni.cpp
    ${PREPPRO_LLAMA_ROOT}/llama_bridge.cpp
)

find_library(log-lib log)

target_compile_options(llama_classifier PRIVATE -O2 -fexceptions -frtti)
target_include_directories(llama_classifier PRIVATE "${PREPPRO_LLAMA_ROOT}")

if(PREPPRO_LLAMA_SOURCE_DIR)
    message(STATUS "Using real llama.cpp sources at: ${PREPPRO_LLAMA_SOURCE_DIR}")

    # Keep llama.cpp build lean for mobile classifier use-case.
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
    set(GGML_CUDA OFF CACHE BOOL "" FORCE)
    set(GGML_METAL OFF CACHE BOOL "" FORCE)
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)

    add_subdirectory(
        "${PREPPRO_LLAMA_SOURCE_DIR}"
        "${CMAKE_CURRENT_BINARY_DIR}/llama_vendor_build"
        EXCLUDE_FROM_ALL
    )

    # Target names can vary across llama.cpp versions; pick first available.
    if(TARGET llama)
        target_link_libraries(llama_classifier PRIVATE llama)
    elseif(TARGET llama_static)
        target_link_libraries(llama_classifier PRIVATE llama_static)
    elseif(TARGET ggml)
        target_link_libraries(llama_classifier PRIVATE ggml)
    else()
        message(WARNING "llama.cpp CMake targets not found. Bridge will fall back to heuristic path.")
    endif()
else()
    message(STATUS "Real llama.cpp source tree not found; building bridge in fallback mode.")
    message(STATUS "Expected one of:")
    message(STATUS "  1) ${PREPPRO_LLAMA_VENDOR_DIR}/CMakeLists.txt")
    message(STATUS "  2) ${PREPPRO_LLAMA_ROOT}/CMakeLists.txt")
endif()

target_link_libraries(
    llama_classifier
    ${log-lib}
)

