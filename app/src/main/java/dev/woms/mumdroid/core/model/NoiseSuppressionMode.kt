package dev.woms.mumdroid.core.model

/**
 * Noise suppression engine selection, mirroring the desktop Mumble client
 * which offers several denoising presets:
 *
 *  - [SYSTEM]        the platform NoiseSuppressor effect on the mic session
 *  - [SPEEX]         the native speexdsp preprocessor (the desktop backend)
 *  - [RNNOISE]       native xiph RNNoise neural-network suppressor
 *  - [SPEEX_RNNOISE] both in series (RNNoise first, then speexdsp)
 */
enum class NoiseSuppressionMode {
    SYSTEM,
    SPEEX,
    RNNOISE,
    SPEEX_RNNOISE,
}
