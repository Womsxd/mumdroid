package dev.woms.mumdroid

import org.junit.Assume.assumeTrue
import java.io.InputStream

/**
 * The `apksigner` fixtures the signature tests run against.
 *
 * They live in `app/src/test/resources/apk-signatures/` and are deliberately
 * **not committed** (see `.gitignore`): `tools/make-signature-fixtures.sh` mints
 * a fresh key pair on every run, so the certificates — and the SHA-256 digests
 * derived from them — are per-machine artifacts. A test must therefore read the
 * digests it asserts on out of the fixtures instead of hardcoding them, or it
 * would only pass for whoever generated the files that happen to be on disk.
 *
 * When the fixtures have not been generated, the affected tests are skipped
 * rather than failed — a missing test input is not a regression — and the skip
 * reason names the script that creates them. The `testDebugUnitTest` task also
 * logs a warning about the missing directory, because Gradle swallows test
 * stdout and a reason that has to be dug out of the report is easy to miss.
 */
object ApkSignatureFixtures {

    private const val DIR = "/apk-signatures"

    /**
     * The certificate subjects `make-signature-fixtures.sh` signs with. The key
     * material is regenerated per run, but these names are pinned by the script,
     * so tests can identify a signer by subject.
     */
    const val DEVELOPER_SUBJECT = "CN=Original Dev"
    const val ATTACKER_SUBJECT = "CN=Attacker"

    /**
     * The fixture [name] as bytes.
     *
     * Skips the calling test (JUnit assumption) when it has not been generated.
     */
    fun load(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("$DIR/$name")
        if (stream == null) {
            assumeTrue(
                "APK signature fixture $name has not been generated; run " +
                    "tools/make-signature-fixtures.sh (its output is not committed)",
                false,
            )
        }
        return (stream as InputStream).use { it.readBytes() }
    }
}
