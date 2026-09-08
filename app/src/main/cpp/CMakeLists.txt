# Builds the native DSP libraries and their thin JNI bindings for mumdroid.
#
#  - librnnoise.so   RNNoise (xiph/rnnoise), the neural noise suppressor used
#                    by the desktop client. BSD-3 licensed; vendored as a git
#                    submodule at ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise.
#  - libspeexdsp.so  speexdsp (xiph/speexdsp), the preprocessor used by the
#                    desktop client (denoise). BSD-3 licensed; vendored as a
#                    git submodule at ${CMAKE_CURRENT_SOURCE_DIR}/speexdsp.
#  - libopus.so      libopus (xiph/opus v1.6.1) plus a thin JNI encoder/decoder
#                    wrapper. BSD-3 licensed; vendored as a git submodule at
#                    ${CMAKE_CURRENT_SOURCE_DIR}/opus.

cmake_minimum_required(VERSION 3.22.1)

project(mumdroid_dsp LANGUAGES C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# RNNoise
# ---------------------------------------------------------------------------

set(RNNOISE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)

if(NOT EXISTS "${RNNOISE_DIR}/COPYING")
    message(FATAL_ERROR
            "${RNNOISE_DIR} was not found.\n"
            "Please checkout the submodule:\n"
            "git submodule update --init --recursive"
    )
endif()

# The xiph upstream no longer ships the model weights inside the repository:
# they are distributed per `model_version` from media.xiph.org. Download and
# verify them at configure time, mirroring the desktop client's rnnoise-build.
file(READ "${RNNOISE_DIR}/model_version" RNNOISE_MODEL_HASH)
string(STRIP "${RNNOISE_MODEL_HASH}" RNNOISE_MODEL_HASH)
string(CONCAT RNNOISE_MODEL_URL
        "https://media.xiph.org/rnnoise/models/rnnoise_data-"
        "${RNNOISE_MODEL_HASH}"
        ".tar.gz")

include(FetchContent)
# NOTE: DOWNLOAD_EXTRACT_TIMESTAMP is intentionally not passed here — it needs
# CMake >= 3.24 while AGP ships 3.22 (it only suppresses a CMP0135 warning).
FetchContent_Declare(rnnoise_model
        URL "${RNNOISE_MODEL_URL}"
        URL_HASH SHA256=${RNNOISE_MODEL_HASH})
FetchContent_MakeAvailable(rnnoise_model)
set(RNNOISE_MODEL_DIR "${rnnoise_model_SOURCE_DIR}")

# The xiph upstream has no CMake build; mirror the source list of its
# autotools build plus the downloaded model data.
add_library(rnnoise SHARED
        rnnoise_jni.c
        "${RNNOISE_MODEL_DIR}/src/rnnoise_data.c"
        ${RNNOISE_DIR}/src/denoise.c
        ${RNNOISE_DIR}/src/pitch.c
        ${RNNOISE_DIR}/src/rnn.c
        ${RNNOISE_DIR}/src/nnet.c
        ${RNNOISE_DIR}/src/nnet_default.c
        ${RNNOISE_DIR}/src/parse_lpcnet_weights.c
        ${RNNOISE_DIR}/src/kiss_fft.c
        ${RNNOISE_DIR}/src/celt_lpc.c
        ${RNNOISE_DIR}/src/rnnoise_tables.c)

target_compile_definitions(rnnoise PRIVATE RNNOISE_BUILD)
target_include_directories(rnnoise PRIVATE
        "${RNNOISE_MODEL_DIR}/src"
        ${RNNOISE_DIR}/include
        ${RNNOISE_DIR}/src)
target_compile_options(rnnoise PRIVATE -O2)
target_link_libraries(rnnoise m)

# ---------------------------------------------------------------------------
# speexdsp preprocessor
# ---------------------------------------------------------------------------

set(SPEEXDSP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/speexdsp)

# Full speexdsp library build (mirrors libspeexdsp_la_SOURCES from upstream
# Makefile.am minus scal.c, which does not exist in this release): preprocessor,
# echo canceller, resampler, jitter buffer and both FFT backends.
add_library(speexdsp SHARED
        speexdsp_jni.c
        ${SPEEXDSP_DIR}/libspeexdsp/buffer.c
        ${SPEEXDSP_DIR}/libspeexdsp/fftwrap.c
        ${SPEEXDSP_DIR}/libspeexdsp/filterbank.c
        ${SPEEXDSP_DIR}/libspeexdsp/jitter.c
        ${SPEEXDSP_DIR}/libspeexdsp/kiss_fft.c
        ${SPEEXDSP_DIR}/libspeexdsp/kiss_fftr.c
        ${SPEEXDSP_DIR}/libspeexdsp/mdf.c
        ${SPEEXDSP_DIR}/libspeexdsp/preprocess.c
        ${SPEEXDSP_DIR}/libspeexdsp/resample.c
        ${SPEEXDSP_DIR}/libspeexdsp/scal.c
        ${SPEEXDSP_DIR}/libspeexdsp/smallft.c)

# Static build configuration mirroring the desktop client (floating-point,
# smallft FFT backend). Our config dir provides config.h and the generated
# speex/speexdsp_config_types.h that upstream normally creates via configure;
# the include/speex subdirectory must be on the path because speexdsp_types.h
# includes "speexdsp_config_types.h" without a prefix.
target_include_directories(speexdsp PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/speexdsp-config/include/speex
        ${CMAKE_CURRENT_SOURCE_DIR}/speexdsp-config/libspeexdsp
        ${SPEEXDSP_DIR}/include
        ${SPEEXDSP_DIR}/libspeexdsp)

# HAVE_CONFIG_H makes the sources include our static config.h, which among
# other things defines the EXPORT symbol used on all public functions.
target_compile_definitions(speexdsp PRIVATE
        HAVE_CONFIG_H FLOATING_POINT USE_SMALLFT VAR_ARRAYS)
target_compile_options(speexdsp PRIVATE -O2)
target_link_libraries(speexdsp m)

# ---------------------------------------------------------------------------
# libopus (xiph/opus v1.6.1)
# ---------------------------------------------------------------------------

set(OPUS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/opus)

if(NOT EXISTS "${OPUS_DIR}/COPYING")
    message(FATAL_ERROR
            "${OPUS_DIR} was not found.\n"
            "Please checkout the submodule:\n"
            "git submodule update --init --recursive"
    )
endif()

# Build the upstream CMake target as a static library and link it into the
# JNI wrapper. Cache FORCE so opus's option() sees the values; DRED/OSCE
# pull in extra DNN weights we do not need for Mumble voice.
#
# Floating-point build: every ABI we ship has an FPU and either NEON (ARM)
# or SSE2 (x86). FLOAT_APPROX is safe on IEEE-754 Android; PRESUME_* skips
# runtime CPU detection on the hot path.
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "build shared library." FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "build tests." FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "build programs." FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "install pkg-config module." FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "install CMake package config module." FORCE)
set(OPUS_DRED OFF CACHE BOOL "enable DRED." FORCE)
set(OPUS_OSCE OFF CACHE BOOL "enable OSCE." FORCE)
set(OPUS_FIXED_POINT OFF CACHE BOOL "compile as fixed-point." FORCE)
set(OPUS_ENABLE_FLOAT_API ON CACHE BOOL "compile with the floating point API." FORCE)
set(OPUS_DISABLE_INTRINSICS OFF CACHE BOOL "disable all intrinsics optimizations." FORCE)
set(OPUS_FLOAT_APPROX ON CACHE BOOL "enable floating point approximations." FORCE)
set(OPUS_FAST_MATH ON CACHE BOOL "enable fast math." FORCE)

if(ANDROID)
    if(ANDROID_ABI STREQUAL "armeabi-v7a" OR ANDROID_ABI STREQUAL "arm64-v8a")
        set(OPUS_USE_NEON ON CACHE BOOL "Option to enable NEON" FORCE)
        set(OPUS_PRESUME_NEON ON CACHE BOOL "Assume target CPU has NEON support" FORCE)
        set(OPUS_MAY_HAVE_NEON ON CACHE BOOL "Does runtime check for neon support" FORCE)
        set(OPUS_MAY_HAVE_NEON_INTR ON CACHE BOOL "Does runtime check for neon intrinsics" FORCE)
        set(OPUS_PRESUME_NEON_INTR ON CACHE BOOL "Assume target CPU has NEON intrinsics" FORCE)
    endif()
    if(ANDROID_ABI STREQUAL "x86" OR ANDROID_ABI STREQUAL "x86_64")
        set(OPUS_X86_PRESUME_SSE ON CACHE BOOL "assume target CPU has SSE1 support" FORCE)
        set(OPUS_X86_PRESUME_SSE2 ON CACHE BOOL "assume target CPU has SSE2 support" FORCE)
    endif()
    if(ANDROID_ABI STREQUAL "x86_64")
        set(OPUS_X86_PRESUME_SSE4_1 ON CACHE BOOL "assume target CPU has SSE4.1 support" FORCE)
    endif()
endif()

set(_mumdroid_saved_shared_libs "${BUILD_SHARED_LIBS}")
set(BUILD_SHARED_LIBS OFF)
add_subdirectory(${OPUS_DIR} EXCLUDE_FROM_ALL)
set(BUILD_SHARED_LIBS "${_mumdroid_saved_shared_libs}")
unset(_mumdroid_saved_shared_libs)

set_property(TARGET opus PROPERTY POSITION_INDEPENDENT_CODE ON)
target_compile_options(opus PRIVATE
        -O2
        -fno-math-errno
        -ffp-contract=fast)

add_library(opusjni SHARED opus_jni.c)
# Upstream's static target is already named `opus` (libopus.a). Keep a
# distinct CMake target for the JNI wrapper and ship it as libopus.so.
set_target_properties(opusjni PROPERTIES OUTPUT_NAME opus)
target_link_libraries(opusjni PRIVATE opus)
target_compile_options(opusjni PRIVATE -O2 -fvisibility=hidden)
# Do not re-export libopus's static symbols from the JNI wrapper.
target_link_options(opusjni PRIVATE -Wl,--exclude-libs,ALL)

# ---------------------------------------------------------------------------
# Release-only size optimizations (applied to every target above):
#
#  - thin LTO (-flto=thin) for cross-translation-unit inlining and dead code
#    elimination; "thin" keeps link times reasonable compared to full LTO.
#  - -ffunction-sections / -fdata-sections + --gc-sections so the linker drops
#    unused functions/objects (e.g. speexdsp code paths the JNI layer never
#    calls).
#  - --icf=all folds identical functions (helps on ARM).
#  - -Wl,-s strips the symbol table and debug info from the shipped .so.
#
# NOTE: AGP maps the release variant to RelWithDebInfo when invoking CMake,
# so all three size-oriented configs are matched here.
# ---------------------------------------------------------------------------

set(RELEASE_CONFIGS Release,RelWithDebInfo,MinSizeRel)

target_compile_options(opus PRIVATE
        $<$<CONFIG:${RELEASE_CONFIGS}>:-flto=thin;-ffunction-sections;-fdata-sections>)

foreach(target rnnoise speexdsp opusjni)
    target_compile_options(${target} PRIVATE
            $<$<CONFIG:${RELEASE_CONFIGS}>:-flto=thin;-ffunction-sections;-fdata-sections>)
    target_link_options(${target} PRIVATE
            $<$<CONFIG:${RELEASE_CONFIGS}>:-flto=thin;-Wl,--gc-sections;-Wl,--icf=all;-Wl,-s>)
endforeach()
