package dev.woms.mumdroid

import dev.woms.mumdroid.data.UserCertificateKeyStoreFiles
import dev.woms.mumdroid.data.UserCertificatePkcs12
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore

/**
 * The app stores its own keystores without a password, mirroring the desktop
 * client's identity blob and relying on the app sandbox instead. These exercise
 * the password-less round trip at the two layers that have to agree on it: the
 * PKCS#12 pack/open pair and the on-disk file access.
 */
class UserCertificatePasswordlessTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val empty = CharArray(0)

    @Test
    fun packWithEmptyPassword_opensWithEmptyPasswordOnly() {
        val (key, cert) = UserCertificatePkcs12.createSelfSigned("Alice")
        val ks = UserCertificatePkcs12.pack(key, arrayOf(cert), empty)
        val bytes = ByteArrayOutputStream().also { ks.store(it, empty) }.toByteArray()

        val opened = UserCertificatePkcs12.open(bytes, CharArray(0))
        assertTrue(
            "an empty password must open the app's own keystore",
            opened is UserCertificatePkcs12.OpenResult.Opened,
        )

        val wrong = UserCertificatePkcs12.open(bytes, "not-the-password".toCharArray())
        assertTrue(
            "a non-empty password must not open it",
            wrong is UserCertificatePkcs12.OpenResult.WrongPassword,
        )
    }

    @Test
    fun savedFile_roundTripsWithAnEmptyPasswordAndRejectsOthers() {
        val (key, cert) = UserCertificatePkcs12.createSelfSigned("Bob")
        val ks = UserCertificatePkcs12.pack(key, arrayOf(cert), empty)
        val files = UserCertificateKeyStoreFiles(temp.newFolder("user_certs"))

        files.save("AA:BB", ks, empty)

        val loaded = files.load("AA:BB", CharArray(0))
        assertNotNull("the password-less file must load with an empty password", loaded)
        val entry = loaded!!.getEntry(
            UserCertificatePkcs12.KEY_ALIAS,
            KeyStore.PasswordProtection(empty),
        )
        assertNotNull("the key entry must be readable with an empty password", entry)

        val failed = runCatching { files.load("AA:BB", "wrong".toCharArray()) }.isFailure
        assertTrue("a non-empty password must fail to load the file", failed)
    }

    @Test
    fun save_leavesNoStagingFileBehind() {
        val (key, cert) = UserCertificatePkcs12.createSelfSigned("Carol")
        val ks = UserCertificatePkcs12.pack(key, arrayOf(cert), empty)
        val dir = temp.newFolder("user_certs")
        val files = UserCertificateKeyStoreFiles(dir)

        files.save("AA:BB", ks, empty)

        assertTrue(File(dir, "user_cert_AABB.p12").isFile)
        assertFalse("the staging file must not survive a successful save", File(dir, "user_cert_AABB.p12.tmp").exists())
    }
}
