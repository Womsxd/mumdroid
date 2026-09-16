package dev.woms.mumdroid.core.security

import java.security.MessageDigest

/**
 * SHA-256 of [bytes] as uppercase, colon-separated hex (`AB:CD:…`) — the form in
 * which signing-certificate digests are compared.
 *
 * Both sides of that comparison have to produce the *same* string: the
 * certificate a signer carries ([ApkSigningBlock.Signer.sha256Hex]) and the one
 * `PackageManager` reports ([SignatureVerifier]). One implementation keeps them
 * from drifting apart in separators or letter case — and a drift fails closed,
 * i.e. every build suddenly looks tampered, which is a miserable thing to debug.
 *
 * The same format is produced at build time too (`signing.gradle.kts`,
 * `signingCertSha256List`), against the whitelist embedded in
 * `BuildConfig.EXPECTED_SIGNATURE_SHA256`. That copy cannot be shared, because
 * the build script does not run on this classpath, so it has to be changed in
 * step with this one.
 */
internal fun certificateSha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
