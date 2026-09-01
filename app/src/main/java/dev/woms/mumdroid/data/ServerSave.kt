package dev.woms.mumdroid.data

/**
 * How to persist a favorite without dropping its Room identity.
 *
 * Desktop `servers` has no unique on hostname+port, so two cards may share an
 * address and differ only by local name/username. Editing updates that row;
 * adding always inserts. Access tokens are stored by address, not by this id.
 */
object ServerSave {
    data class Plan(
        /** Existing row to UPDATE; 0 means INSERT a new favorite. */
        val updateId: Long = 0,
    ) {
        val isInsert: Boolean get() = updateId <= 0L
    }

    fun plan(editingId: Long): Plan {
        return if (editingId > 0L) Plan(updateId = editingId) else Plan()
    }
}
