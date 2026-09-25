/*
 * JNI binding between mumdroid and the speexdsp preprocessor.
 *
 * speexdsp (BSD-3, see COPYING in the cpp dir) provides the same noise
 * suppression / pre-processing backend used by the desktop Mumble client
 * (SpeexPreprocessState). This wrapper exposes exactly what mumdroid needs:
 * denoise with a configurable suppression level and the built-in AGC.
 * speexdsp's own VAD stays off — mumdroid runs its own threshold-driven
 * detector — while the AGC is used for the Speex AGC mode and driven per
 * frame by AudioPreprocessor (gain compensation + idle hold).
 *
 * As in opus_jni.c, the per-frame PCM arrays are pinned with
 * GetPrimitiveArrayCritical to avoid a copy on the audio hot path, falling
 * back to GetShortArrayElements.
 *
 * Both bound Kotlin classes are ordinary classes, so every function here is an
 * *instance* method and takes its receiver as `jobject instance` (unused). The
 * jclass in opus_jni.c is not a precedent to copy: LibOpusNative is an object
 * whose natives are @JvmStatic, so those really are static methods.
 */

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "speex/speex_preprocess.h"
#include "speex/speex_echo.h"

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
        JNIEnv *env, jobject instance, jint frame_size, jint sample_rate) {
    (void) env;
    (void) instance;
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
    /* Both start disabled. AudioPreprocessor enables the AGC on demand for the
     * Speex AGC mode (see setNativeAgc), driving it per frame. speexdsp's own
     * VAD stays off for good — mumdroid runs its own threshold-driven detector,
     * and speex_preprocess_run() returns a constant 1 while the VAD is off. */
    int zero = 0;
    speex_preprocess_ctl(h->state, SPEEX_PREPROCESS_SET_AGC, &zero);
    speex_preprocess_ctl(h->state, SPEEX_PREPROCESS_SET_VAD, &zero);
    return (jlong) (intptr_t) h;
}

/*
 * Destroys the state and the handle. The handle arrives by value, so this
 * cannot clear the caller's copy: SpeexDspProcessor.close() zeroes its
 * nativeHandle right after the call, and a repeated call with the same non-zero
 * value would be a double free (the only check here is for 0).
 */
JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeDestroy(
        JNIEnv *env, jobject instance, jlong handle) {
    (void) env;
    (void) instance;
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
        JNIEnv *env, jobject instance, jlong handle, jboolean enable) {
    (void) env;
    (void) instance;
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
        JNIEnv *env, jobject instance, jlong handle, jint db) {
    (void) env;
    (void) instance;
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
        JNIEnv *env, jobject instance, jlong handle, jboolean enable) {
    (void) env;
    (void) instance;
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
        JNIEnv *env, jobject instance, jlong handle, jint target) {
    (void) env;
    (void) instance;
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
        JNIEnv *env, jobject instance, jlong handle, jint db) {
    (void) env;
    (void) instance;
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
        JNIEnv *env, jobject instance, jlong handle, jint dbPerSec) {
    (void) env;
    (void) instance;
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
        JNIEnv *env, jobject instance, jlong handle, jint dbPerSec) {
    (void) env;
    (void) instance;
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    SpeexPreprocessState *st = h != NULL ? h->state : NULL;
    if (st == NULL) {
        return;
    }
    int value = (int) dbPerSec;
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_AGC_DECREMENT, &value);
}

/*
 * The AGC's current gain in dB, forwarded from the state. speexdsp holds the
 * gain as a linear multiplier (initialised to 1, i.e. 0 dB until it adapts) and
 * SPEEX_PREPROCESS_GET_AGC_GAIN is the ctl that reports it in dB
 * (preprocess.c). AudioPreprocessor uses it to compensate the
 * noise-suppression floor for the AGC boost.
 */
JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeGetAgcGain(
        JNIEnv *env, jobject instance, jlong handle) {
    (void) env;
    (void) instance;
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
 * @return -1 when the arguments were rejected, 1 otherwise. This is NOT a
 *         speech decision: nativeCreate turns speexdsp's own VAD off, and
 *         speex_preprocess_run() returns a constant 1 while it is off (see
 *         preprocess.c), so the value only distinguishes "processed" from
 *         "rejected" — mumdroid runs its own threshold-driven detector. On -1
 *         the buffer is left untouched so callers can fall back to a
 *         passthrough.
 */
JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeRun(
        JNIEnv *env, jobject instance, jlong handle, jshortArray frame) {
    (void) instance;
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    if (env == NULL || h == NULL || h->state == NULL) {
        return -1;
    }

    jsize len = (*env)->GetArrayLength(env, frame);
    /* Reject any length but the state's own. A shorter array would make
     * speex_preprocess_run() read and write past the Java array's end; a longer
     * one is memory-safe but rejected too, so this boundary is as strict as
     * SpeexDspProcessor.run()'s `samples.size == frameSize` check. */
    if (len != h->frame_size) {
        return -1;
    }

    jboolean frameCrit = JNI_FALSE;
    jshort *elements = lock_shorts(env, frame, &frameCrit);
    if (elements == NULL) {
        return -1;
    }

    /* Processes the buffer in place. The VAD is off (see nativeCreate), so
     * speex_preprocess_run() returns a constant 1 here (preprocess.c) — this is
     * "processed", not a speech decision. */
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

/*
 * Echo-canceller handle: the opaque SpeexEchoState plus the frame size it was
 * created for. speex_echo_cancellation() processes st->frame_size samples at
 * the near / far / out buffers (mdf.c: reads them in the DC-notch and copy
 * loops and writes the result back), so Java arrays of any other length would
 * read and write past their end — 16-bit samples in the JVM heap. The state
 * is opaque here (SpeexEchoState is defined in mdf.c), so nativeCancel() can
 * only enforce this by carrying the size on the handle, exactly as
 * SpeexPreprocessHandle does.
 */
typedef struct {
    SpeexEchoState *state;
    jint frame_size;
} SpeexEchoHandle;

static SpeexEchoHandle *to_echo_handle(jlong handle) {
    return (SpeexEchoHandle *) (intptr_t) handle;
}

JNIEXPORT jlong JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexEchoCanceller_nativeCreate(
        JNIEnv *env, jobject instance, jint frame_size, jint filter_length, jint sample_rate) {
    (void) env;
    (void) instance;
    if (frame_size <= 0 || filter_length <= 0 || sample_rate <= 0) {
        return 0;
    }
    SpeexEchoHandle *h = (SpeexEchoHandle *) calloc(1, sizeof(SpeexEchoHandle));
    if (h == NULL) {
        return 0;
    }
    SpeexEchoState *st = speex_echo_state_init((int) frame_size, (int) filter_length);
    if (st == NULL) {
        free(h);
        return 0;
    }
    h->state = st;
    h->frame_size = frame_size;
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
    return (jlong) (intptr_t) h;
}

/* Same contract as SpeexDspProcessor_nativeDestroy: the caller owns zeroing its
 * handle, and a repeated call with the same non-zero value is a double free. */
JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexEchoCanceller_nativeDestroy(
        JNIEnv *env, jobject instance, jlong handle) {
    (void) env;
    (void) instance;
    SpeexEchoHandle *h = to_echo_handle(handle);
    if (h != NULL) {
        if (h->state != NULL) {
            speex_echo_state_destroy(h->state);
        }
        free(h);
    }
}

/** Lets the preprocessor see the echo canceller for residual suppression.
 *  NOTE: unlike the int-valued ctl requests, SPEEX_PREPROCESS_SET_ECHO_STATE
 *  stores the passed value directly (it does not dereference ptr), so we must
 *  pass the SpeexEchoState pointer itself. */
JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexDspProcessor_nativeSetEchoState(
        JNIEnv *env, jobject instance, jlong handle, jlong echo_handle) {
    (void) env;
    (void) instance;
    SpeexPreprocessHandle *h = to_preprocess_handle(handle);
    SpeexPreprocessState *st = h != NULL ? h->state : NULL;
    SpeexEchoHandle *eh = to_echo_handle(echo_handle);
    SpeexEchoState *echo_st = eh != NULL ? eh->state : NULL;
    if (st == NULL) {
        return;
    }
    speex_preprocess_ctl(st, SPEEX_PREPROCESS_SET_ECHO_STATE, echo_st);
}

/*
 * Cancels one frame: out = near - estimate(far).
 *
 * @param near_arr/far_arr/out_arr the frame buffers; each length must be
 *        exactly the frame_size the state was created with, because
 *        speex_echo_cancellation() reads and writes that many samples at each.
 * @return true on success; on failure `out` is a copy of `near` (passthrough).
 */
JNIEXPORT jboolean JNICALL
Java_dev_woms_mumdroid_core_audio_noise_SpeexEchoCanceller_nativeCancel(
        JNIEnv *env, jobject instance, jlong handle, jshortArray near_arr,
        jshortArray far_arr, jshortArray out_arr) {
    (void) instance;
    SpeexEchoHandle *h = to_echo_handle(handle);
    if (env == NULL || h == NULL || h->state == NULL) {
        return JNI_FALSE;
    }

    jsize nearLen = (*env)->GetArrayLength(env, near_arr);
    /* Reject any length but the state's own: a shorter array would make
     * speex_echo_cancellation() read and write past the Java array's end. */
    if (nearLen != h->frame_size ||
        (*env)->GetArrayLength(env, far_arr) != nearLen ||
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

    speex_echo_cancellation(h->state, nearEl, farEl, outEl);

    unlock_shorts(env, near_arr, nearEl, nearCrit, JNI_ABORT);
    unlock_shorts(env, far_arr, farEl, farCrit, JNI_ABORT);
    unlock_shorts(env, out_arr, outEl, outCrit, 0);
    return JNI_TRUE;
}
