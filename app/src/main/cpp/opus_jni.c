/*
 * JNI binding between mumdroid and the xiph libopus encoder/decoder.
 *
 * libopus (github.com/xiph/opus, BSD-3, see COPYING in the opus submodule)
 * is the reference Opus implementation used by the desktop Mumble client.
 * This wrapper exposes a handle-based encoder and decoder matching the
 * Concentus surface that OpusCodec already uses: CBR encode, per-session
 * decode, PLC via a NULL packet, and OPUS_RESET_STATE.
 *
 * Encode/decode pin Java arrays with GetPrimitiveArrayCritical to avoid a
 * copy on the 10–60 ms audio hot path, falling back to GetArrayElements.
 */

#include <jni.h>
#include <stdint.h>

#include "opus.h"

static OpusEncoder *to_encoder(jlong handle) {
    return (OpusEncoder *) (intptr_t) handle;
}

static OpusDecoder *to_decoder(jlong handle) {
    return (OpusDecoder *) (intptr_t) handle;
}

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

static jbyte *lock_bytes(JNIEnv *env, jbyteArray arr, jboolean *critical) {
    *critical = JNI_TRUE;
    jbyte *ptr = (*env)->GetPrimitiveArrayCritical(env, arr, NULL);
    if (ptr != NULL) {
        return ptr;
    }
    *critical = JNI_FALSE;
    return (*env)->GetByteArrayElements(env, arr, NULL);
}

static void unlock_bytes(JNIEnv *env, jbyteArray arr, jbyte *ptr,
                         jboolean critical, jint mode) {
    if (critical) {
        (*env)->ReleasePrimitiveArrayCritical(env, arr, ptr, mode);
    } else {
        (*env)->ReleaseByteArrayElements(env, arr, ptr, mode);
    }
}

JNIEXPORT jlong JNICALL
Java_dev_woms_mumdroid_core_audio_LibOpusNative_encoderCreate(
        JNIEnv *env, jclass clazz, jint sample_rate, jint channels, jint application) {
    (void) env;
    (void) clazz;
    if (sample_rate <= 0 || channels <= 0) {
        return 0;
    }
    int error = OPUS_OK;
    OpusEncoder *enc = opus_encoder_create((opus_int32) sample_rate, (int) channels,
                                           (int) application, &error);
    if (enc == NULL || error != OPUS_OK) {
        if (enc != NULL) {
            opus_encoder_destroy(enc);
        }
        return 0;
    }
    return (jlong) (intptr_t) enc;
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_LibOpusNative_encoderDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    OpusEncoder *enc = to_encoder(handle);
    if (enc != NULL) {
        opus_encoder_destroy(enc);
    }
}

JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_LibOpusNative_encoderSetBitrate(
        JNIEnv *env, jclass clazz, jlong handle, jint bps) {
    (void) env;
    (void) clazz;
    OpusEncoder *enc = to_encoder(handle);
    if (enc == NULL) {
        return OPUS_BAD_ARG;
    }
    return opus_encoder_ctl(enc, OPUS_SET_BITRATE(bps));
}

JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_LibOpusNative_encoderSetVbr(
        JNIEnv *env, jclass clazz, jlong handle, jboolean enable) {
    (void) env;
    (void) clazz;
    OpusEncoder *enc = to_encoder(handle);
    if (enc == NULL) {
        return OPUS_BAD_ARG;
    }
    opus_int32 value = enable ? 1 : 0;
    return opus_encoder_ctl(enc, OPUS_SET_VBR(value));
}

JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_LibOpusNative_encoderReset(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    OpusEncoder *enc = to_encoder(handle);
    if (enc == NULL) {
        return OPUS_BAD_ARG;
    }
    return opus_encoder_ctl(enc, OPUS_RESET_STATE);
}

JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_LibOpusNative_encode(
        JNIEnv *env, jclass clazz, jlong handle, jshortArray pcm, jint samples,
        jbyteArray out) {
    (void) clazz;
    OpusEncoder *enc = to_encoder(handle);
    if (enc == NULL || pcm == NULL || out == NULL || samples <= 0) {
        return OPUS_BAD_ARG;
    }
    jsize pcm_len = (*env)->GetArrayLength(env, pcm);
    jsize out_len = (*env)->GetArrayLength(env, out);
    if (pcm_len < samples || out_len <= 0) {
        return OPUS_BAD_ARG;
    }
    jboolean pcm_crit = JNI_FALSE;
    jshort *pcm_ptr = lock_shorts(env, pcm, &pcm_crit);
    if (pcm_ptr == NULL) {
        return OPUS_ALLOC_FAIL;
    }
    jboolean out_crit = JNI_FALSE;
    jbyte *out_ptr = lock_bytes(env, out, &out_crit);
    if (out_ptr == NULL) {
        unlock_shorts(env, pcm, pcm_ptr, pcm_crit, JNI_ABORT);
        return OPUS_ALLOC_FAIL;
    }
    opus_int32 n = opus_encode(enc, pcm_ptr, (int) samples,
                               (unsigned char *) out_ptr, (opus_int32) out_len);
    unlock_shorts(env, pcm, pcm_ptr, pcm_crit, JNI_ABORT);
    unlock_bytes(env, out, out_ptr, out_crit, 0);
    return n;
}

JNIEXPORT jlong JNICALL
Java_dev_woms_mumdroid_core_audio_LibOpusNative_decoderCreate(
        JNIEnv *env, jclass clazz, jint sample_rate, jint channels) {
    (void) env;
    (void) clazz;
    if (sample_rate <= 0 || channels <= 0) {
        return 0;
    }
    int error = OPUS_OK;
    OpusDecoder *dec = opus_decoder_create((opus_int32) sample_rate, (int) channels, &error);
    if (dec == NULL || error != OPUS_OK) {
        if (dec != NULL) {
            opus_decoder_destroy(dec);
        }
        return 0;
    }
    return (jlong) (intptr_t) dec;
}

JNIEXPORT void JNICALL
Java_dev_woms_mumdroid_core_audio_LibOpusNative_decoderDestroy(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    OpusDecoder *dec = to_decoder(handle);
    if (dec != NULL) {
        opus_decoder_destroy(dec);
    }
}

JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_LibOpusNative_decoderReset(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    OpusDecoder *dec = to_decoder(handle);
    if (dec == NULL) {
        return OPUS_BAD_ARG;
    }
    return opus_decoder_ctl(dec, OPUS_RESET_STATE);
}

JNIEXPORT jint JNICALL
Java_dev_woms_mumdroid_core_audio_LibOpusNative_decode(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray packet, jshortArray pcm,
        jint frame_size, jboolean fec) {
    (void) clazz;
    OpusDecoder *dec = to_decoder(handle);
    if (dec == NULL || pcm == NULL || frame_size <= 0) {
        return OPUS_BAD_ARG;
    }
    jsize pcm_len = (*env)->GetArrayLength(env, pcm);
    if (pcm_len < frame_size) {
        return OPUS_BAD_ARG;
    }
    jboolean pcm_crit = JNI_FALSE;
    jshort *pcm_ptr = lock_shorts(env, pcm, &pcm_crit);
    if (pcm_ptr == NULL) {
        return OPUS_ALLOC_FAIL;
    }
    const unsigned char *data = NULL;
    opus_int32 len = 0;
    jbyte *pkt_ptr = NULL;
    jboolean pkt_crit = JNI_FALSE;
    if (packet != NULL) {
        len = (opus_int32) (*env)->GetArrayLength(env, packet);
        pkt_ptr = lock_bytes(env, packet, &pkt_crit);
        if (pkt_ptr == NULL) {
            unlock_shorts(env, pcm, pcm_ptr, pcm_crit, JNI_ABORT);
            return OPUS_ALLOC_FAIL;
        }
        data = (const unsigned char *) pkt_ptr;
    }
    int n = opus_decode(dec, data, len, pcm_ptr, (int) frame_size, fec ? 1 : 0);
    if (pkt_ptr != NULL) {
        unlock_bytes(env, packet, pkt_ptr, pkt_crit, JNI_ABORT);
    }
    unlock_shorts(env, pcm, pcm_ptr, pcm_crit, 0);
    return n;
}
