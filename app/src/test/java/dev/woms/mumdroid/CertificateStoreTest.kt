package dev.woms.mumdroid

import dev.woms.mumdroid.data.CertificateStore
import dev.woms.mumdroid.data.db.CertificateDao
import dev.woms.mumdroid.data.db.CertificateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pin store's address-keyed rules: a certificate several servers share must
 * not shadow another server's pin, and re-pinning an address must never leave
 * it without a pin.
 *
 * The DAO double mirrors the two guarantees the real schema provides: the unique
 * index is on `(host, port)` — not on the fingerprint — and the two insert
 * strategies are `IGNORE` (first-connection pinning) and `REPLACE` (explicit
 * "update certificate").
 */
class CertificateStoreTest {

    private class FakeCertificateDao : CertificateDao {
        private val rows = LinkedHashMap<Long, CertificateEntity>()
        private val all = MutableStateFlow<List<CertificateEntity>>(emptyList())
        private var nextId = 1L

        override fun observeAll(): Flow<List<CertificateEntity>> = all

        override suspend fun getById(id: Long): CertificateEntity? = rows[id]

        override suspend fun findByHostPort(host: String, port: Int): CertificateEntity? =
            rows.values.firstOrNull { it.host == host && it.port == port }

        override suspend fun insertIfAbsent(certificate: CertificateEntity): Long {
            if (findByHostPort(certificate.host, certificate.port) != null) return -1L
            return store(certificate)
        }

        override suspend fun replace(certificate: CertificateEntity): Long {
            rows.values
                .firstOrNull { it.host == certificate.host && it.port == certificate.port }
                ?.let { rows.remove(it.id) }
            return store(certificate)
        }

        override suspend fun delete(certificate: CertificateEntity) {
            rows.remove(certificate.id)
            publish()
        }

        override suspend fun deleteById(id: Long) {
            rows.remove(id)
            publish()
        }

        private fun store(certificate: CertificateEntity): Long {
            val id = nextId++
            rows[id] = certificate.copy(id = id)
            publish()
            return id
        }

        private fun publish() {
            all.value = rows.values.sortedByDescending { it.createdAt }
        }
    }

    private val dao = FakeCertificateDao()
    private val store = CertificateStore(dao)

    @Test
    fun record_firstConnection_pinsTheCertificate() = runBlocking {
        store.record("a.example", 64738, "FP-SHARED")
        assertEquals("FP-SHARED", store.pinnedFingerprint("a.example", 64738))
    }

    @Test
    fun record_sharedCertificate_givesEachServerItsOwnPin() = runBlocking {
        // The regression: a fingerprint already pinned elsewhere must not stop
        // this server from being pinned too.
        store.record("a.example", 64738, "FP-SHARED")
        store.record("b.example", 64738, "FP-SHARED")

        assertEquals("FP-SHARED", store.pinnedFingerprint("a.example", 64738))
        assertEquals("FP-SHARED", store.pinnedFingerprint("b.example", 64738))
    }

    @Test
    fun record_sameHostDifferentPorts_eachKeepsItsOwnPin() = runBlocking {
        store.record("a.example", 64738, "FP-ONE")
        store.record("a.example", 64739, "FP-TWO")

        assertEquals("FP-ONE", store.pinnedFingerprint("a.example", 64738))
        assertEquals("FP-TWO", store.pinnedFingerprint("a.example", 64739))
    }

    @Test
    fun record_doesNotOverwriteAnExistingPin() = runBlocking {
        store.record("a.example", 64738, "FP-OLD")
        store.record("a.example", 64738, "FP-NEW")

        assertEquals("FP-OLD", store.pinnedFingerprint("a.example", 64738))
    }

    @Test
    fun replaceForHost_pinsAnAddressThatHadNone() = runBlocking {
        store.replaceForHost("a.example", 64738, "FP-NEW")
        assertEquals("FP-NEW", store.pinnedFingerprint("a.example", 64738))
    }

    @Test
    fun replaceForHost_acceptsAFingerprintAnotherServerAlreadyUses() = runBlocking {
        // The second regression: the new fingerprint already belongs to another
        // server, so a delete-then-insert would have been ignored and left this
        // address unpinned.
        store.record("a.example", 64738, "FP-A")
        store.record("b.example", 64738, "FP-B")

        store.replaceForHost("a.example", 64738, "FP-B")

        assertEquals("FP-B", store.pinnedFingerprint("a.example", 64738))
        assertEquals("FP-B", store.pinnedFingerprint("b.example", 64738))
    }

    @Test
    fun replaceForHost_neverLeavesTheAddressUnpinned() = runBlocking {
        store.record("a.example", 64738, "FP-A")
        store.replaceForHost("a.example", 64738, "FP-A")
        assertNotNull(store.pinnedFingerprint("a.example", 64738))
    }

    @Test
    fun blankFingerprint_isIgnored() = runBlocking {
        store.record("a.example", 64738, "   ")
        store.replaceForHost("a.example", 64738, "")

        assertNull(store.pinnedFingerprint("a.example", 64738))
    }

    @Test
    fun pinnedFingerprint_isScopedToTheAddress() = runBlocking {
        store.record("a.example", 64738, "FP-A")
        assertNull(store.pinnedFingerprint("b.example", 64738))
        assertNull(store.pinnedFingerprint("a.example", 64739))
    }

    @Test
    fun delete_removesOnlyThatAddress() = runBlocking {
        store.record("a.example", 64738, "FP-A")
        store.record("b.example", 64738, "FP-A")

        val pinned = requireNotNull(dao.findByHostPort("a.example", 64738))
        store.delete(pinned)

        assertNull(store.pinnedFingerprint("a.example", 64738))
        assertEquals("FP-A", store.pinnedFingerprint("b.example", 64738))
    }
}
