cmake_minimum_required(VERSION 3.22.1)
project(fanyixia LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
# Baseline emulator CPUs need not implement AVX or F16C.
foreach(feature AVX AVX2 AVX_VNNI AVX512 AVX512_VBMI AVX512_VNNI AVX512_BF16 FMA F16C BMI2)
    set(GGML_${feature} OFF CACHE BOOL "" FORCE)
endforeach()
# whisper.cpp v1.7.6, pinned to its immutable release commit.
FetchContent_Declare(whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG a8d002cfd879315632a579e73f0148d06959de36
    GIT_SHALLOW FALSE)
FetchContent_MakeAvailable(whisper)
add_library(fanyixia SHARED whisper_jni.cpp)
target_link_libraries(fanyixia PRIVATE whisper android log)
# Support Android devices with 16 KiB memory pages.
target_link_options(fanyixia PRIVATE "-Wl,-z,max-page-size=16384")
