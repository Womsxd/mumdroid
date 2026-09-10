// ---------------------------------------------------------------------------
// Release signing logic — isolated from app/build.gradle.kts.
//
// This script is applied from app/build.gradle.kts via:
//
//     apply(from = file("signing.gradle.kts"))
//
// Kotlin DSL compiles each applied .gradle.kts into its own script, so its
// top-level classes/functions are NOT visible to the applying script. We
// therefore evaluate everything here and publish the results through the
// project's `extra` map. app/build.gradle.kts only reads those `extra` values
// and feeds them into the AGP `signingConfigs` and BuildConfig.
//
// Resolution priority (highest first):
//   1. A git-ignored `keystore.properties` file in this module directory — the
//      standard, Android-Studio-friendly convention. Configured this way,
//      `assembleRelease` (from the CLI *or* Android Studio building the
//      "release" variant) is signed by AGP and the expected digest is embedded
//      automatically, so the runtime tamper check works.
//   2. Environment variables KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS /
//      KEY_PASSWORD (used by headless CI builds).
//
// When neither source is present (e.g. a plain debug build) the digest is left
// empty and the runtime tamper check is skipped, so normal development is
// unaffected.
//
// ⚠  IMPORTANT LIMITATION — the Android Studio "Generate Signed App Bundle or
// APK" wizard signs the APK *AFTER* the Gradle build finishes using `apksigner`
// with credentials that exist only inside that dialog. Gradle can therefore
// NEVER see that keystore during configuration, so no expected digest can be
// embedded for that exact flow. To benefit from tamper detection you must sign
// through Gradle (option 1 or 2 above) instead of the GUI "Generate ..." flow.
// ---------------------------------------------------------------------------
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

/** Small holder describing a release signing keystore. */
data class SigningConfigHolder(
    val storeFile: File?,
    val storePassword: String?,
    val keyAlias: String?,
    val keyPassword: String?,
    /**
     * Extra trusted certificate digests supplied by hand, comma / semicolon /
     * newline separated. Used for certificates that are NOT in the keystore —
     * e.g. a rotated-away key, or the separate key that signed v3 while v2 was
     * signed with the keystore key.
     */
    val extraSignatures: String = "",
)

/**
 * Loads the release signing configuration from [1] `keystore.properties` or,
 * as a fallback, [2] the KEYSTORE_* / KEY_* environment variables.
 *
 * Returns a data holder with the keystore file, store password, key alias and
 * key password — all nullable when no signing config is available.
 */
fun loadReleaseSigningConfig(): SigningConfigHolder {
    // 1) keystore.properties (module dir), e.g. app/keystore.properties.
    //    Read it first so `extraSignatures` survives even when the file only
    //    declares extra digests and no keystore at all.
    val propsFile = file("keystore.properties")
    var fileExtras = ""
    if (propsFile.isFile) {
        val props = Properties()
        FileInputStream(propsFile).use { props.load(it) }
        fileExtras = props.getProperty("extraSignatures").orEmpty()
        val storeFile = props.getProperty("storeFile")?.takeIf { it.isNotBlank() }
        val storePassword = props.getProperty("storePassword")?.takeIf { it.isNotBlank() }
        if (storeFile != null && storePassword != null) {
            return SigningConfigHolder(
                storeFile = file(storeFile),
                storePassword = storePassword,
                keyAlias = props.getProperty("keyAlias"),
                keyPassword = props.getProperty("keyPassword"),
                extraSignatures = fileExtras,
            )
        }
    }
    // 2) Environment variables (headless / CI).
    val storeFile = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
    val storePassword = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    if (storeFile != null && storePassword != null) {
        return SigningConfigHolder(
            storeFile = file(storeFile),
            storePassword = storePassword,
            keyAlias = System.getenv("KEY_ALIAS"),
            keyPassword = System.getenv("KEY_PASSWORD"),
            extraSignatures = System.getenv("EXTRA_SIGNATURES")?.takeIf { it.isNotBlank() } ?: fileExtras,
        )
    }
    // No keystore, but a hand-written trusted list still makes the check
    // meaningful (e.g. signing happens outside Gradle). Environment wins over
    // the properties file so CI can override without editing the file.
    return SigningConfigHolder(
        null, null, null, null,
        extraSignatures = System.getenv("EXTRA_SIGNATURES")?.takeIf { it.isNotBlank() } ?: fileExtras,
    )
}

/**
 * Computes a comma-separated list of SHA-256 digests (uppercase, hex,
 * colon-separated) for the signing certificates the runtime check should trust.
 *
 * This is deliberately a **list**, not a single digest:
 *
 *  - Every alias present in the keystore is included, so a keystore holding
 *    both a current and a previous key (rotation) yields two entries.
 *  - An optional `extraSignatures` property in `keystore.properties` appends
 *    further digests by hand — for example the certificate of the previous
 *    release key, or the v3 key when v2 and v3 are signed with different keys
 *    (see `SignatureVerifier.expectedDigests`).
 *
 * Returns an empty string when no keystore is configured, so the runtime
 * tamper check is simply skipped and normal development is unaffected.
 */
fun signingCertSha256List(holder: SigningConfigHolder): String {
    val md = MessageDigest.getInstance("SHA-256")
    fun digestOf(cert: java.security.cert.Certificate): String =
        md.digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    val extra = holder.extraSignatures
        .split(',', ';', '\n')
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }

    val storeFile = holder.storeFile
    val storePassword = holder.storePassword
    if (storeFile == null || storePassword == null) {
        // No keystore: fall back to the hand-written list only, if any.
        return extra.joinToString(",")
    }

    return try {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        FileInputStream(storeFile).use { ks.load(it, storePassword.toCharArray()) }
        val list = mutableListOf<String>()
        ks.aliases().toList().forEach { alias ->
            val cert = ks.getCertificate(alias) ?: return@forEach
            list.add(digestOf(cert))
        }
        (list + extra).distinct().joinToString(",")
    } catch (e: Exception) {
        // A broken keystore must not silently disable the check when an
        // explicit list was configured.
        extra.joinToString(",")
    }
}

// Evaluate once and publish through `extra` so the applying script can read them.
val releaseSigning = loadReleaseSigningConfig()
extra["releaseSigningStoreFile"] = releaseSigning.storeFile?.absolutePath
extra["releaseSigningStorePassword"] = releaseSigning.storePassword
extra["releaseSigningKeyAlias"] = releaseSigning.keyAlias
extra["releaseSigningKeyPassword"] = releaseSigning.keyPassword
extra["releaseSigningSha256"] = signingCertSha256List(releaseSigning)
