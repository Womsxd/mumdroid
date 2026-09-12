package dev.woms.mumdroid.data

import java.io.File

/**
 * Locates the directory holding the user-certificate PKCS#12 files and moves
 * them between the two storage areas.
 *
 * Android's Auto Backup rules are static XML and cannot follow a runtime
 * setting, so the backup switch is implemented through file location instead:
 * everything under `filesDir` is included in cloud backup / device transfer,
 * while `noBackupFilesDir` is excluded from both. Only the private-key files
 * are moved; the certificate metadata keeps living in Room.
 *
 * Pure file-system logic, so it can be unit-tested without Android.
 */
internal class CertificateVault(
    private val backedUpBase: File,
    private val noBackupBase: File,
) {

    companion object {
        /** Subdirectory of either base that holds the PKCS#12 files. */
        const val VAULT_DIR = "user_certs"
    }

    /** The PKCS#12 directory for [backupEnabled], created if it does not exist. */
    fun dir(backupEnabled: Boolean): File {
        val base = if (backupEnabled) backedUpBase else noBackupBase
        val dir = File(base, VAULT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** The stored PKCS#12 files of [dir], ignoring everything else. */
    fun certFiles(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter {
                it.isFile &&
                    it.name.startsWith(UserCertificateCodec.CERT_FILE_PREFIX) &&
                    it.name.endsWith(UserCertificateCodec.CERT_FILE_SUFFIX)
            }
    }

    /**
     * Moves every PKCS#12 file from [sources] into [target], collapsing both the
     * legacy layout (files directly in the `filesDir` root) and the other vault
     * into one place. A file already present in [target] wins and the source
     * copy is deleted, so a relocation never overwrites a newer certificate.
     *
     * @return the number of files that could not be moved; callers must not
     *   treat such a fingerprint as gone, or they would drop a still-present
     *   certificate.
     */
    fun relocate(target: File, sources: List<File>): Int {
        var failed = 0
        for (source in sources) {
            if (source == target) continue
            for (file in certFiles(source)) {
                val destination = File(target, file.name)
                val done = if (destination.exists()) file.delete() else file.renameTo(destination)
                if (!done) failed++
            }
        }
        return failed
    }
}
