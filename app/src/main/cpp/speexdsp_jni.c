/*
 * JNI binding between mumdroid and the speexdsp preprocessor.
 *
 * speexdsp (BSD-3, see COPYING in the cpp dir) provides the same noise
 * suppression / pre-processing backend used by the desktop Mumble client
 * (SpeexPreprocessState). This wrapper exposes exactly what mumdroid needs:
 * denoise with a configurable suppression level, plus an optional VAD flag
 * read back per frame. AGC/VAD inside speexdsp are intentionally left off;
 * mumdroid applies its own AGC and VAD stages so behaviour stays consistent
 * across all suppression modes.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "speex/speex_preprocess.h"
#include "speex/speex_echo.h"

JNIEXPORT jlong JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeCreate(
        JNIEnv *env, jclass clazz, jint frame_size, jint sample_rate) {
    (void) env;
    (void) clazz;
    if (frame_size <= 0 || sample_rate <= 0) {
        return 0;
    }
    SpeexPreprocessState *st = speex_preprocess_state_init((int) frame_size, (int) sample_rate);
    if (st == NULL) {
        return 0;
    }
    /* Denoise only; AGC and VAD are handled by the Kotlin pipeline. */
    int zero = 0;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC, &zero);
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_VAD, &zero);
    return (jlong) (intptr_t) st;
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    SpeexPreprocessState *st = (SpeexPreprocessState *) (intptr_t) handle;
    if (st != NULL) {
        speex_preprocess_state_destroy(st);
    }
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeSetDenoise(
        JNIEnv *env, jclass clazz, jlong handle, jboolean enable) {
    (void) env;
    (void) clazz;
    SpeexPreprocessState *st = (SpeexPreprocessState *) (intptr_t) handle;
    if (st == NULL) {
        return;
    }
    int value = enable ? 1 : 0;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_DENOISE, &value);
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeSetNoiseSuppress(
        JNIEnv *env, jclass clazz, jlong handle, jint db) {
    (void) env;
    (void) clazz;
    SpeexPreprocessState *st = (SpeexPreprocessState *) (intptr_t) handle;
    if (st == NULL) {
        return;
    }
    int value = (int) db;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_NOISE_SUPPRESS, &value);
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeSetAgc(
        JNIEnv *env, jclass clazz, jlong handle, jboolean enable) {
    (void) env;
    (void) clazz;
    SpeexPreprocessState *st = (SpeexPreprocessState *) (intptr_t) handle;
    if (st == NULL) {
        return;
    }
    int value = enable ? 1 : 0;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC, &value);
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeSetAgcTarget(
        JNIEnv *env, jclass clazz, jlong handle, jint target) {
    (void) env;
    (void) clazz;
    SpeexPreprocessState *st = (SpeexPreprocessState *) (intptr_t) handle;
    if (st == NULL) {
        return;
    }
    int value = (int) target;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_TARGET, &value);
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeSetAgcMaxGain(
        JNIEnv *env, jclass clazz, jlong handle, jint db) {
    (void) env;
    (void) clazz;
    SpeexPreprocessState *st = (SpeexPreprocessState *) (intptr_t) handle;
    if (st == NULL) {
        return;
    }
    int value = (int) db;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_MAX_GAIN, &value);
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeSetAgcIncrement(
        JNIEnv *env, jclass clazz, jlong handle, jint dbPerSec) {
    (void) env;
    (void) clazz;
    SpeexPreprocessState *st = (SpeexPreprocessState *) (intptr_t) handle;
    if (st == NULL) {
        return;
    }
    int value = (int) dbPerSec;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_INCREMENT, &value);
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeSetAgcDecrement(
        JNIEnv *env, jclass clazz, jlong handle, jint dbPerSec) {
    (void) env;
    (void) clazz;
    SpeexPreprocessState *st = (SpeexPreprocessState *) (intptr_t) handle;
    if (st == NULL) {
        return;
    }
    int value = (int) dbPerSec;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_DECREMENT, &value);
}

/*
 * Runs one frame of 16-bit PCM through the preprocessor in place.
 *
 * @return 1 when speech was detected by the internal VAD, 0 for non-speech,
 *         or -1 on error (invalid handle/length); on -1 the buffer is left
 *         untouched so callers can fall back to a passthrough.
 */
JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeRun(
        JNIEnv *env, jclass clazz, jlong handle, jshortArray frame) {
    (void) clazz;
    SpeexPreprocessState *st = (SpeexPreprocessState *) (intptr_t) handle;
    if (env == NULL || st == NULL) {
        return -1;
    }

    jsize len = (*env)->GetArrayLength(env, frame);
    if (len <= 0) {
        return -1;
    }

    jshort *elements = (*env)->GetShortArrayElements(env, frame, NULL);
    if (elements == NULL) {
        return -1;
    }

    /* speex_preprocess_run() processes the buffer in place and returns the
     * VAD decision for this frame (1 = speech probable). */
    int vad = speex_preprocess_run(st, elements);

    (*env)->ReleaseShortArrayElements(env, frame, elements, 0);
    return vad;
}

/* ---------------------------------------------------------------------------
 * Echo canceller (software AEC)
 *
 * The captured microphone signal ("near end") is cancelled against the
 * speaker reference signal ("far end") so the far speaker's own voice does
 * not feed back into the transmission.
 * ------------------------------------------------------------------------- */

JNIEXPORT jlong JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexEchoCanceller_nativeCreate(
        JNIEnv *env, jclass clazz, jint frame_size, jint filter_length) {
    (void) env;
    (void) clazz;
    if (frame_size <= 0 || filter_length <= 0) {
        return 0;
    }
    SpeexEchoState *st = speex_echo_state_init((int) frame_size, (int) filter_length);
    return st ? (jlong) (intptr_t) st : 0;
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexEchoCanceller_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    SpeexEchoState *st = (SpeexEchoState *) (intptr_t) handle;
    if (st != NULL) {
        speex_echo_state_destroy(st);
    }
}

/** Lets the preprocessor see the echo canceller for residual suppression.
 *  NOTE: unlike the int-valued ctl requests, SPEEX_PREPROCESS_SET_ECHO_STATE
 *  stores the passed value directly (it does not dereference ptr), so we must
 *  pass the SpeexEchoState pointer itself. */
JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeSetEchoState(
        JNIEnv *env, jclass clazz, jlong handle, jlong echo_handle) {
    (void) env;
    (void) clazz;
    SpeexPreprocessState *st = (SpeexPreprocessState *) (intptr_t) handle;
    SpeexEchoState *echo_st = (SpeexEchoState *) (intptr_t) echo_handle;
    if (st == NULL) {
        return;
    }
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_ECHO_STATE, echo_st);
}

/*
 * Cancels one frame: out = near - estimate(far).
 *
 * @return true on success; on failure `out` is a copy of `near` (passthrough).
 */
JNIEXPORT jboolean JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexEchoCanceller_nativeCancel(
        JNIEnv *env, jclass clazz, jlong handle, jshortArray near_arr,
        jshortArray far_arr, jshortArray out_arr) {
    (void) clazz;
    SpeexEchoState *st = (SpeexEchoState *) (intptr_t) handle;
    if (env == NULL || st == NULL) {
        return JNI_FALSE;
    }

    jsize nearLen = (*env)->GetArrayLength(env, near_arr);
    if (nearLen <= 0 || (*env)->GetArrayLength(env, far_arr) != nearLen ||
        (*env)->GetArrayLength(env, out_arr) != nearLen) {
        return JNI_FALSE;
    }

    jshort *nearEl = (*env)->GetShortArrayElements(env, near_arr, NULL);
    jshort *farEl = (*env)->GetShortArrayElements(env, far_arr, NULL);
    jshort *outEl = (*env)->GetShortArrayElements(env, out_arr, NULL);
    if (nearEl == NULL || farEl == NULL || outEl == NULL) {
        if (nearEl != NULL) (*env)->ReleaseShortArrayElements(env, near_arr, nearEl, JNI_ABORT);
        if (farEl != NULL) (*env)->ReleaseShortArrayElements(env, far_arr, farEl, JNI_ABORT);
        if (outEl != NULL) (*env)->ReleaseShortArrayElements(env, out_arr, outEl, JNI_ABORT);
        return JNI_FALSE;
    }

    speex_echo_cancellation(st, nearEl, farEl, outEl);

    (*env)->ReleaseShortArrayElements(env, near_arr, nearEl, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, far_arr, farEl, JNI_ABORT);
    (*env)->ReleaseShortArrayElements(env, out_arr, outEl, 0);
    return JNI_TRUE;
}
