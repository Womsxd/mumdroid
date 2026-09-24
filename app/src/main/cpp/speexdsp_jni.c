/*
 * JNI binding between mumdroid and the speexdsp preprocessor.
 *
 * speexdsp (BSD-3, see COPYING in the cpp dir) provides the same noise
 * suppression / pre-processing backend used by the desktop Mumble client
 * (SpeexPreprocessState). This wrapper exposes exactly what mumdroid needs:
 * denoise with a configurable suppression level, the built-in AGC, and a VAD
 * flag read back per frame. speexdsp's own VAD stays off — mumdroid runs its
 * own threshold-driven detector — while the AGC is used for the Speex AGC mode
 * and driven per frame by AudioPreprocessor (gain compensation + idle hold).
 *
 * As in opus_jni.c, the per-frame PCM arrays are pinned with
 * GetPrimitiveArrayCritical to avoid a copy on the audio hot path, falling
 * back to GetShortArrayElements.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "speex/speex_preprocess.h"
#include "speex/speex_echo.h"

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

/*
 * Preprocessor handle: the opaque SpeexPreprocessState plus the frame size it
 * was created for. speex_preprocess_run() processes st->frame_size samples at
 * the buffer *in place* (preprocess.c: reads x[0..frame_size-1] in
 * preprocess_analysis, writes x[0..frame_size-1] back in the synthesis), so a
 * Java array of any other length would read and write past its end — 16-bit
 * samples in the JVM heap. nativeRun() therefore rejects a length that does not
 * match the state, which is why the size has to travel with the handle.
 * (Same shape as RnNoiseHandle in rnnoise_jni.c.)
 */
typedef struct {
    SpeexPreprocessState *state;
    jint frame_size;
} SpeexPreprocessHandle;

static SpeexPreprocessHandle *to_preprocess_handle(jlong handle) {
    return (SpeexPreprocessHandle *) (intptr_t) handle;
}

JNIEXPORT jlong JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeCreate(
        JNIEnv *env, jclass clazz, jint frame_size, jint sample_rate) {
    (void) env;
    (void) clazz;
    if (frame_size <= 0 || sample_rate <= 0) {
        return 0;
    }
    SpeexPreprocessHandle *h = (SpeexPreprocessHandle *) calloc(1, sizeof(SpeexPreprocessHandle));
    if (h == NULL) {
        return 0;
    }
    h->state = speex_preprocess_state_init((int) frame_size, (int) sample_rate);
    if (h->state == NULL) {
        free(h);
        return 0;
    }
    h->frame_size = frame_size;
    /* Denoise only; AGC and VAD are handled by the Kotlin pipeline. */
    int zero = 0;
    speex_preprocess_ctl(h->state, SPEEX_PREPROCESS_SET_AGC, &zero);
    speex_preprocess_ctl(h->state, SPEEX_PREPROCESS_SET_VAD, &zero);
    return (jlong) (intptr_t) h;
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    if (h != NULL) {
        if (h->state != NULL) {
            speex_preprocess_state_destroy(h->state);
        }
        free(h);
    }
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeSetDenoise(
        JNIEnv *env, jclass clazz, jlong handle, jboolean enable) {
    (void) env;
    (void) clazz;
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    SpeexPreprocessState *st = h != NULL ? h->state : NULL;
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
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    SpeexPreprocessState *st = h != NULL ? h->state : NULL;
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
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    SpeexPreprocessState *st = h != NULL ? h->state : NULL;
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
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    SpeexPreprocessState *st = h != NULL ? h->state : NULL;
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
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    SpeexPreprocessState *st = h != NULL ? h->state : NULL;
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
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    SpeexPreprocessState *st = h != NULL ? h->state : NULL;
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
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    SpeexPreprocessState *st = h != NULL ? h->state : NULL;
    if (st == NULL) {
        return;
    }
    int value = (int) dbPerSec;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_DECREMENT, &value);
}

/*
 * The AGC's current gain in dB (0 until it has adapted). speexdsp keeps it as a
 * linear multiplier, so this is the same conversion SPEEX_PREPROCESS_GET_AGC_GAIN
 * performs. Used to compensate the noise-suppression floor for the AGC boost.
 */
JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeGetAgcGain(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    SpeexPreprocessState *st = h != NULL ? h->state : NULL;
    if (st == NULL) {
        return 0;
    }
    int gain = 0;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_GET_AGC_GAIN, &gain);
    return (jint) gain;
}

/*
 * Runs one frame of 16-bit PCM through the preprocessor in place.
 *
 * @param frame the frame to process; its length must be exactly the frame_size
 *              the state was created with, because speex_preprocess_run()
 *              reads and writes that many samples at the buffer.
 * @return 1 when speech was detected by the internal VAD, 0 for non-speech,
 *         or -1 on error (invalid handle/length); on -1 the buffer is left
 *         untouched so callers can fall back to a passthrough.
 */
JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeRun(
        JNIEnv *env, jclass clazz, jlong handle, jshortArray frame) {
    (void) clazz;
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    if (env == NULL || h == NULL || h->state == NULL) {
        return -1;
    }

    jsize len = (*env)->GetArrayLength(env, frame);
    /* Reject any length but the state's own: a shorter array would make
     * speex_preprocess_run() read and write past the Java array's end. */
    if (len != h->frame_size) {
        return -1;
    }

    jboolean frameCrit = JNI_FALSE;
    jshort *elements = lock_shorts(env, frame, &frameCrit);
    if (elements == NULL) {
        return -1;
    }

    /* speex_preprocess_run() processes the buffer in place and returns the
     * VAD decision for this frame (1 = speech probable). */
    int vad = speex_preprocess_run(h->state, elements);

    unlock_shorts(env, frame, elements, frameCrit, 0);
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
        JNIEnv *env, jclass clazz, jint frame_size, jint filter_length, jint sample_rate) {
    (void) env;
    (void) clazz;
    if (frame_size <= 0 || filter_length <= 0 || sample_rate <= 0) {
        return 0;
    }
    SpeexEchoState *st = speex_echo_state_init((int) frame_size, (int) filter_length);
    if (st == NULL) {
        return 0;
    }
    /* speex_echo_state_init*() starts from st->sampling_rate = 8000 and derives
     * notch_radius / beta0 / beta_max / spec_average from it (mdf.c:427-434,
     * 500-505). SPEEX_ECHO_SET_SAMPLING_RATE is the only thing that recomputes
     * them (mdf.c:1231-1246), so without this call the canceller adapts as if
     * it ran at 8 kHz: notch_radius 0.9 instead of 0.992 (a much wider DC
     * notch, which eats the low end of the near-end signal) and beta0/beta_max
     * 6x too large for a 48 kHz / 480-sample frame. The desktop client makes
     * the same call right after speex_echo_state_init_mc() (AudioInput.cpp). */
    int rate = (int) sample_rate;
    speex_echo_ctl(st, SPEEX_ECHO_SET_SAMPLING_RATE, &rate);
    return (jlong) (intptr_t) st;
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
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    SpeexPreprocessState *st = h != NULL ? h->state : NULL;
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

    jboolean nearCrit = JNI_FALSE;
    jshort *nearEl = lock_shorts(env, near_arr, &nearCrit);
    if (nearEl == NULL) {
        return JNI_FALSE;
    }
    jboolean farCrit = JNI_FALSE;
    jshort *farEl = lock_shorts(env, far_arr, &farCrit);
    if (farEl == NULL) {
        unlock_shorts(env, near_arr, nearEl, nearCrit, JNI_ABORT);
        return JNI_FALSE;
    }
    jboolean outCrit = JNI_FALSE;
    jshort *outEl = lock_shorts(env, out_arr, &outCrit);
    if (outEl == NULL) {
        unlock_shorts(env, far_arr, farEl, farCrit, JNI_ABORT);
        unlock_shorts(env, near_arr, nearEl, nearCrit, JNI_ABORT);
        return JNI_FALSE;
    }

    speex_echo_cancellation(st, nearEl, farEl, outEl);

    unlock_shorts(env, near_arr, nearEl, nearCrit, JNI_ABORT);
    unlock_shorts(env, far_arr, farEl, farCrit, JNI_ABORT);
    unlock_shorts(env, out_arr, outEl, outCrit, 0);
    return JNI_TRUE;
}
