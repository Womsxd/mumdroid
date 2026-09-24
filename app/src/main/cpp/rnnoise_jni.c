/*
 * JNI binding between mumdroid and the xiph RNNoise library.
 *
 * RNNoise (github.com/xiph/rnnoise, BSD-3, see COPYING in the cpp dir) is a
 * real-time noise suppression library based on a recurrent neural network —
 * the same backend used by the desktop Mumble client.
 *
 * The processing frame is 480 samples (10 ms at 48 kHz) and the native API
 * operates on floats whose values are the raw 16-bit PCM samples (roughly
 * [-32768, 32767]), NOT a [-1, 1] normalisation: upstream's
 * examples/rnnoise_demo.c, the desktop client and the training feature dumper
 * all feed that magnitude, and the internal silence gate (E < 0.04) plus the
 * trained feature scaling are calibrated for it. This binding therefore
 * converts from/to 16-bit PCM without rescaling and accepts any positive
 * multiple of 480 samples (10/20/40/60 ms Opus frames), processing them as
 * consecutive 10 ms sub-frames.
 *
 * As in opus_jni.c, the per-frame PCM array is pinned with
 * GetPrimitiveArrayCritical to avoid a copy on the audio hot path, falling
 * back to GetShortArrayElements.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "rnnoise.h"

/* Number of samples per RNNoise processing frame (10 ms @ 48 kHz). */
#define RNNOISE_FRAME 480

/* The native handle returned to Kotlin wraps a DenoiseState* plus scratch
 * buffers for the float in/out arrays used by the float-based native API. */
typedef struct {
    DenoiseState *state;
    float *inBuf;   /* RNNOISE_FRAME floats of input */
    float *outBuf;  /* RNNOISE_FRAME floats of output */
} RnNoiseHandle;

static int g_frame_size = -1;

static RnNoiseHandle *to_handle(jlong handle) {
    return (RnNoiseHandle *)(intptr_t)handle;
}

/*
 * Pins a Java array for the duration of one native call. Nested
 * GetPrimitiveArrayCritical calls are explicitly permitted by the JNI spec, but
 * inside a critical region no *other* JNI function may be called — so every
 * GetArrayLength and any similar metadata query has to happen before the first
 * lock_*() of the function.
 */
static jshort *lock_shorts(JNIEnv *env, jshortArray arr, jboolean *critical) {
    *critical = JNI_TRUE;
    jshort *ptr = (*env)->GetPrimitiveArrayCritical(env, arr, NULL);
    if (ptr != NULL) {
        return ptr;
    }
    *critical = JNI_FALSE;
    return (*env)->GetShortArrayElements(env, arr, NULL);
}

static void unlock_shorts(JNIEnv *env, jshortArray arr, jshort *ptr,
                          jboolean critical, jint mode) {
    if (critical) {
        (*env)->ReleasePrimitiveArrayCritical(env, arr, ptr, mode);
    } else {
        (*env)->ReleaseShortArrayElements(env, arr, ptr, mode);
    }
}

JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_noise_RnNoiseProcessor_nativeGetFrameSize(
        JNIEnv *env, jobject obj) {
    (void)env;
    (void)obj;
    if (g_frame_size < 0) {
        g_frame_size = rnnoise_get_frame_size();
    }
    return g_frame_size;
}

JNIEXPORT jlong JNICALL
Java_dev_woms_mumdroid_core_audio_noise_RnNoiseProcessor_nativeCreate(
        JNIEnv *env, jobject obj) {
    (void)env;
    (void)obj;
    RnNoiseHandle *h = (RnNoiseHandle *)calloc(1, sizeof(RnNoiseHandle));
    if (h == NULL) {
        return 0;
    }
    /* NULL model -> use the built-in default model. */
    h->state = rnnoise_create(NULL);
    if (h->state == NULL) {
        free(h);
        return 0;
    }
    h->inBuf = (float *)malloc(sizeof(float) * RNNOISE_FRAME);
    h->outBuf = (float *)malloc(sizeof(float) * RNNOISE_FRAME);
    if (h->inBuf == NULL || h->outBuf == NULL) {
        rnnoise_destroy(h->state);
        free(h->inBuf);
        free(h->outBuf);
        free(h);
        return 0;
    }
    return (jlong)(intptr_t)h;
}

/*
 * Denoises one frame of 16-bit PCM **in place** (any positive multiple of the
 * 10 ms native frame, e.g. 480/960/1920/2880 samples = 10/20/40/60 ms at
 * 48 kHz).
 *
 * The frame is processed in consecutive 10 ms sub-frames so that 20/40/60 ms
 * packet sizes all get the full RNNoise treatment. Each sub-frame is copied
 * into the float scratch buffer before rnnoise_process_frame() runs and the
 * result is written back over the same samples, so processing one array is
 * safe and the caller needs neither an output array nor a copy-back. A VAD
 * probability is OR-ed across every sub-frame.
 *
 * @param handle the `RnNoiseHandle*` returned by nativeCreate
 * @param frame  the frame to process, modified in place; its length must be a
 *               positive multiple of RNNOISE_FRAME
 * @return the VAD decision (1 = speech in any sub-frame, 0 = noise), or -1 on
 *         error. The arguments are validated before any sample is touched, so
 *         on -1 the frame is left untouched and callers can fall back to a
 *         passthrough instead of emitting digital silence.
 */
JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_noise_RnNoiseProcessor_nativeProcess(
        JNIEnv *env, jobject obj, jlong handle, jshortArray frame) {
    (void)obj;
    RnNoiseHandle *h = to_handle(handle);
    if (h == NULL || h->state == NULL) {
        return -1;
    }

    jsize len = (*env)->GetArrayLength(env, frame);
    if (len <= 0 || len % RNNOISE_FRAME != 0) {
        return -1;
    }

    jboolean frameCrit = JNI_FALSE;
    jshort *el = lock_shorts(env, frame, &frameCrit);
    if (el == NULL) {
        return -1;
    }

    int frames = (int)(len / RNNOISE_FRAME);
    int speech = 0;
    for (int sub = 0; sub < frames; sub++) {
        jshort *buf = el + sub * RNNOISE_FRAME;

        /* Keep the raw 16-bit PCM magnitude: RNNoise's silence gate and its
         * trained features assume int16-scale floats, not [-1, 1]. */
        for (int i = 0; i < RNNOISE_FRAME; i++) {
            h->inBuf[i] = (float)buf[i];
        }

        float vad = rnnoise_process_frame(h->state, h->outBuf, h->inBuf);

        for (int i = 0; i < RNNOISE_FRAME; i++) {
            float v = h->outBuf[i];
            if (v > 32767.0f) v = 32767.0f;
            if (v < -32768.0f) v = -32768.0f;
            buf[i] = (jshort)v;
        }

        if (vad > 0.5f) {
            speech = 1;
        }
    }

    unlock_shorts(env, frame, el, frameCrit, 0);
    return speech;
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_RnNoiseProcessor_nativeDestroy(
        JNIEnv *env, jobject obj, jlong handle) {
    (void)env;
    (void)obj;
    RnNoiseHandle *h = to_handle(handle);
    if (h == NULL) {
        return;
    }
    if (h->state != NULL) {
        rnnoise_destroy(h->state);
    }
    free(h->inBuf);
    free(h->outBuf);
    free(h);
}
