/*
 * JNI binding between mumdroid and the xiph RNNoise library.
 *
 * RNNoise (github.com/xiph/rnnoise, BSD-3, see COPYING in the cpp dir) is a
 * real-time noise suppression library based on a recurrent neural network —
 * the same backend used by the desktop Mumble client.
 *
 * The processing frame is 480 samples (10 ms at 48 kHz) and the native API
 * operates on floats in [-1, 1]; this binding converts from/to 16-bit PCM and
 * accepts any positive multiple of 480 samples (10/20/40/60 ms Opus frames),
 * processing them as consecutive 10 ms sub-frames.
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

static RnNoiseHandle *to_handle(JNIEnv *env, jobject obj) {
    jclass cls = (*env)->GetObjectClass(env, obj);
    if (cls == NULL) {
        return NULL;
    }
    jfieldID fid = (*env)->GetFieldID(env, cls, "nativeHandle", "J");
    (*env)->DeleteLocalRef(env, cls);
    if (fid == NULL) {
        return NULL;
    }
    jlong ptr = (*env)->GetLongField(env, obj, fid);
    return (RnNoiseHandle *)(intptr_t)ptr;
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
 * Denoises one frame of 16-bit PCM (any positive multiple of the 10 ms native
 * frame, e.g. 480/960/1920/2880 samples = 10/20/40/60 ms at 48 kHz).
 *
 * The frame is processed in consecutive 10 ms sub-frames so that 20/40/60 ms
 * packet sizes all get the full RNNoise treatment. A VAD probability is OR-ed
 * across every sub-frame.
 *
 * @param in  the input frame (must be a positive multiple of RNNOISE_FRAME)
 * @param out the output frame (same length as in); only written on success
 * @return the VAD decision (1 = speech in any sub-frame, 0 = noise),
 *         or -1 on error; on -1 `out` is left untouched so callers can
 *         fall back to a passthrough instead of emitting digital silence.
 */
JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_noise_RnNoiseProcessor_nativeProcess(
        JNIEnv *env, jobject obj, jshortArray in, jshortArray out) {
    RnNoiseHandle *h = to_handle(env, obj);
    if (h == NULL || h->state == NULL) {
        return -1;
    }

    jsize inLen = (*env)->GetArrayLength(env, in);
    jsize outLen = (*env)->GetArrayLength(env, out);
    if (inLen != outLen || inLen <= 0 || inLen % RNNOISE_FRAME != 0) {
        return -1;
    }

    jshort *inEl = (*env)->GetShortArrayElements(env, in, NULL);
    jshort *outEl = (*env)->GetShortArrayElements(env, out, NULL);
    if (inEl == NULL || outEl == NULL) {
        if (inEl != NULL) (*env)->ReleaseShortArrayElements(env, in, inEl, JNI_ABORT);
        if (outEl != NULL) (*env)->ReleaseShortArrayElements(env, out, outEl, JNI_ABORT);
        return -1;
    }

    int frames = (int)(inLen / RNNOISE_FRAME);
    int speech = 0;
    for (int sub = 0; sub < frames; sub++) {
        const jshort *src = inEl + sub * RNNOISE_FRAME;

        /* Convert 16-bit PCM to float in the range [-1, 1]. */
        for (int i = 0; i < RNNOISE_FRAME; i++) {
            h->inBuf[i] = (float)src[i] / 32768.0f;
        }

        float vad = rnnoise_process_frame(h->state, h->outBuf, h->inBuf);

        jshort *dst = outEl + sub * RNNOISE_FRAME;
        for (int i = 0; i < RNNOISE_FRAME; i++) {
            float v = h->outBuf[i] * 32768.0f;
            if (v > 32767.0f) v = 32767.0f;
            if (v < -32768.0f) v = -32768.0f;
            dst[i] = (jshort)v;
        }

        if (vad > 0.5f) {
            speech = 1;
        }
    }

    (*env)->ReleaseShortArrayElements(env, in, inEl, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, out, outEl, 0);
    return speech;
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_RnNoiseProcessor_nativeDestroy(
        JNIEnv *env, jobject obj) {
    RnNoiseHandle *h = to_handle(env, obj);
    if (h == NULL) {
        return;
    }
    if (h->state != NULL) {
        rnnoise_destroy(h->state);
    }
    free(h->inBuf);
    free(h->outBuf);
    free(h);

    jclass cls = (*env)->GetObjectClass(env, obj);
    if (cls != NULL) {
        jfieldID fid = (*env)->GetFieldID(env, cls, "nativeHandle", "J");
        if (fid != NULL) {
            (*env)->SetLongField(env, obj, fid, 0);
        }
        (*env)->DeleteLocalRef(env, cls);
    }
}
