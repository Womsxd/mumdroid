# Builds the native DSP libraries and their thin JNI bindings for mumdroid.
#
#  - librnnoise.so   RNNoise (xiph/rnnoise), the neural noise suppressor used
#                    by the desktop client. BSD-3 licensed; vendored as a git
#                    submodule at ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise.
#  - libspeexdsp.so  speexdsp (xiph/speexdsp), the preprocessor used by the
#                    desktop client (denoise). BSD-3 licensed; vendored as a
#                    git submodule at ${CMAKE_CURRENT_SOURCE_DIR}/speexdsp.

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

foreach(target rnnoise speexdsp)
    target_compile_options(${target} PRIVATE
            $<$<CONFIG:${RELEASE_CONFIGS}>:-flto=thin;-ffunction-sections;-fdata-sections>)
    target_link_options(${target} PRIVATE
            $<$<CONFIG:${RELEASE_CONFIGS}>:-flto=thin;-Wl,--gc-sections;-Wl,--icf=all;-Wl,-s>)
endforeach()
