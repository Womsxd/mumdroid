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
)

/**
 * Loads the release signing configuration from [1] `keystore.properties` or,
 * as a fallback, [2] the KEYSTORE_* / KEY_* environment variables.
 *
 * Returns a data holder with the keystore file, store password, key alias and
 * key password — all nullable when no signing config is available.
 */
fun loadReleaseSigningConfig(): SigningConfigHolder {
    // 1) keystore.properties (module dir), e.g. app/keystore.properties
    val propsFile = file("keystore.properties")
    if (propsFile.isFile) {
        val props = Properties()
        FileInputStream(propsFile).use { props.load(it) }
        val storeFile = props.getProperty("storeFile")?.takeIf { it.isNotBlank() }
        val storePassword = props.getProperty("storePassword")?.takeIf { it.isNotBlank() }
        if (storeFile != null && storePassword != null) {
            return SigningConfigHolder(
                storeFile = file(storeFile),
                storePassword = storePassword,
                keyAlias = props.getProperty("keyAlias"),
                keyPassword = props.getProperty("keyPassword"),
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
        )
    }
    return SigningConfigHolder(null, null, null, null)
}

/**
 * Computes the SHA-256 digest (uppercase, hex, colon-separated) of every signing
 * certificate inside the given keystore. Empty string when no keystore is
 * configured, so the tamper check is simply skipped.
 */
fun signingCertSha256List(holder: SigningConfigHolder): String {
    val storeFile = holder.storeFile ?: return ""
    val storePassword = holder.storePassword ?: return ""
    return try {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        FileInputStream(storeFile).use { ks.load(it, storePassword.toCharArray()) }
        val md = MessageDigest.getInstance("SHA-256")
        val list = mutableListOf<String>()
        ks.aliases().toList().forEach { alias ->
            val cert = ks.getCertificate(alias) ?: return@forEach
            list.add(md.digest(cert.encoded).joinToString(":") { "%02X".format(it) })
        }
        list.joinToString(",")
    } catch (e: Exception) {
        ""
    }
}

// Evaluate once and publish through `extra` so the applying script can read them.
val releaseSigning = loadReleaseSigningConfig()
extra["releaseSigningStoreFile"] = releaseSigning.storeFile?.absolutePath
extra["releaseSigningStorePassword"] = releaseSigning.storePassword
extra["releaseSigningKeyAlias"] = releaseSigning.keyAlias
extra["releaseSigningKeyPassword"] = releaseSigning.keyPassword
extra["releaseSigningSha256"] = signingCertSha256List(releaseSigning)
