package dev.woms.mumdroid

import dev.woms.mumdroid.data.CertificateVault
import dev.woms.mumdroid.data.UserCertificateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The backup switch works by relocating the PKCS#12 files between a backed-up
 * base directory and a no-backup one, since Android's backup rules are static.
 * [CertificateVault] is that pure file-system logic; exercised here with temp
 * directories, no Android or Room involved.
 */
class CertificateVaultBackupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var backedUpBase: File
    private lateinit var noBackupBase: File
    private lateinit var vault: CertificateVault

    @Before
    fun setUp() {
        backedUpBase = temp.newFolder("files")
        noBackupBase = temp.newFolder("no_backup")
        vault = CertificateVault(backedUpBase, noBackupBase)
    }

    private fun certName(id: String) = "${UserCertificateCodec.CERT_FILE_PREFIX}$id.p12"

    @Test
    fun dir_followsTheBackupPreferenceAndCreatesTheDirectory() {
        val on = vault.dir(backupEnabled = true)
        val off = vault.dir(backupEnabled = false)

        assertEquals(File(backedUpBase, CertificateVault.VAULT_DIR), on)
        assertEquals(File(noBackupBase, CertificateVault.VAULT_DIR), off)
        assertTrue("the backed-up vault must exist", on.isDirectory)
        assertTrue("the no-backup vault must exist", off.isDirectory)
    }

    @Test
    fun relocate_movesCertFilesAndLeavesEverythingElse() {
        val source = temp.newFolder("legacy")
        File(source, certName("AA")).writeText("key")
        File(source, "unrelated.txt").writeText("keep")
        val target = vault.dir(backupEnabled = true)

        val failed = vault.relocate(target, listOf(source))

        assertEquals("all files must have moved", 0, failed)
        assertTrue(File(target, certName("AA")).isFile)
        assertFalse("the source copy must be gone", File(source, certName("AA")).exists())
        assertTrue("non-certificate files must stay put", File(source, "unrelated.txt").isFile)
    }

    @Test
    fun relocate_keepsTheTargetCopyWhenBothExist() {
        val source = temp.newFolder("legacy")
        val target = vault.dir(backupEnabled = true)
        File(source, certName("AA")).writeText("old")
        File(target, certName("AA")).writeText("new")

        vault.relocate(target, listOf(source))

        assertEquals("new", File(target, certName("AA")).readText())
        assertFalse(File(source, certName("AA")).exists())
    }

    @Test
    fun relocate_ignoresTheTargetWhenAlsoPassedAsSource() {
        val target = vault.dir(backupEnabled = true)
        File(target, certName("AA")).writeText("key")

        vault.relocate(target, listOf(target))

        assertEquals("key", File(target, certName("AA")).readText())
    }

    @Test
    fun certFiles_onlyReturnsMatchingRegularFiles() {
        val dir = vault.dir(backupEnabled = true)
        File(dir, certName("AA")).writeText("key")
        File(dir, "notes.txt").writeText("x")
        File(dir, "user_cert_subdir").mkdir()

        assertEquals(listOf(certName("AA")), vault.certFiles(dir).map { it.name })
    }

    @Test
    fun certFiles_ignoresStagingFiles() {
        val dir = vault.dir(backupEnabled = true)
        File(dir, certName("AA")).writeText("key")
        File(dir, certName("AA") + ".tmp").writeText("half written")

        assertEquals(listOf(certName("AA")), vault.certFiles(dir).map { it.name })
    }
}
