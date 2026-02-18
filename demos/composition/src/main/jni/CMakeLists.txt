cmake_minimum_required(VERSION 3.22.1)

project("composition_jni")

set(DAWN_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../dawn")

# Configuration for Dawn
set(DAWN_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(DAWN_BUILD_SAMPLES OFF CACHE BOOL "" FORCE)
set(DAWN_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(DAWN_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(DAWN_USE_GLFW OFF CACHE BOOL "" FORCE)
set(DAWN_BUILD_PROTOBUF OFF CACHE BOOL "" FORCE)
set(TINT_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(TINT_BUILD_IR_BINARY OFF CACHE BOOL "" FORCE)

# Add Dawn subdirectory
add_subdirectory("${DAWN_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/dawn_build" EXCLUDE_FROM_ALL)

add_library(
    composition_jni
    SHARED
    composition_jni.cc
)

find_library(
    android-lib
    android
)

find_library(
    log-lib
    log
)

target_link_libraries(
    composition_jni
    PRIVATE
    ${android-lib}
    ${log-lib}
    webgpu_dawn
    webgpu_cpp
    dawn_common
    dawn_internal_config
    dawn_public_config
)
