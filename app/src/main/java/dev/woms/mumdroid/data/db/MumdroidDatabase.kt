package dev.woms.mumdroid.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Application Room database holding structured data (servers, certificates,
 * channel passwords) instead of the flat DataStore key/value store used previously.
 */
@Database(
    entities = [
        ServerEntity::class,
        CertificateEntity::class,
        ChannelAccessTokenEntity::class,
        ServerAccessTokenEntity::class,
        UserCertificateEntity::class,
        UserCertificateConfigEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class MumdroidDatabase : RoomDatabase() {

    abstract fun serverDao(): ServerDao
    abstract fun certificateDao(): CertificateDao
    abstract fun channelAccessTokenDao(): ChannelAccessTokenDao
    abstract fun serverAccessTokenDao(): ServerAccessTokenDao
    abstract fun userCertificateDao(): UserCertificateDao

    companion object {
        @Volatile
        private var INSTANCE: MumdroidDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS channel_access_tokens (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        server_id INTEGER NOT NULL,
                        channel_id INTEGER NOT NULL,
                        token TEXT NOT NULL,
                        FOREIGN KEY(server_id) REFERENCES servers(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_channel_access_tokens_server_id_channel_id " +
                        "ON channel_access_tokens(server_id, channel_id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_channel_access_tokens_server_id " +
                        "ON channel_access_tokens(server_id)",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN last_channel_id INTEGER")
                db.execSQL(
                    "ALTER TABLE servers ADD COLUMN last_channel_name TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * Desktop `tokens` is a free list per server. Copy remembered channel
         * passwords into that bag so reconnect still sends them.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS server_access_tokens (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        server_id INTEGER NOT NULL,
                        token TEXT NOT NULL,
                        FOREIGN KEY(server_id) REFERENCES servers(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_server_access_tokens_server_id " +
                        "ON server_access_tokens(server_id)",
                )
                db.execSQL(
                    """
                    INSERT INTO server_access_tokens (server_id, token)
                    SELECT server_id, token FROM channel_access_tokens
                    WHERE TRIM(token) != ''
                    """.trimIndent(),
                )
            }
        }

        /**
         * Desktop favorites have no unique on hostname+port, and `tokens` is
         * keyed by server identity (certificate digest), not username. Drop
         * the host+port unique so two local names/usernames can share an
         * address, and store tokens by address so they stay shared.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_servers_host_port")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_servers_host_port ON servers(host, port)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS server_access_tokens_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        token TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_server_access_tokens_host_port " +
                        "ON server_access_tokens_new(host, port)",
                )
                db.execSQL(
                    """
                    INSERT INTO server_access_tokens_new (host, port, token)
                    SELECT lower(trim(s.host)), s.port, t.token
                    FROM server_access_tokens t
                    INNER JOIN servers s ON s.id = t.server_id
                    WHERE trim(t.token) != '' AND trim(s.host) != ''
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE server_access_tokens")
                db.execSQL(
                    "ALTER TABLE server_access_tokens_new RENAME TO server_access_tokens",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS channel_access_tokens_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        host TEXT NOT NULL,
                        port INTEGER NOT NULL,
                        channel_id INTEGER NOT NULL,
                        token TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_channel_access_tokens_host_port_channel_id " +
                        "ON channel_access_tokens_new(host, port, channel_id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_channel_access_tokens_host_port " +
                        "ON channel_access_tokens_new(host, port)",
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO channel_access_tokens_new (host, port, channel_id, token)
                    SELECT lower(trim(s.host)), s.port, t.channel_id, t.token
                    FROM channel_access_tokens t
                    INNER JOIN servers s ON s.id = t.server_id
                    WHERE trim(s.host) != ''
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE channel_access_tokens")
                db.execSQL(
                    "ALTER TABLE channel_access_tokens_new RENAME TO channel_access_tokens",
                )
            }
        }

        /**
         * User (client) certificate metadata and the keystore password move out
         * of SharedPreferences into Room. The legacy JSON list is copied in at
         * runtime by [dev.woms.mumdroid.data.UserCertificateStore] once the new
         * tables exist.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS user_certificates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        subject TEXT NOT NULL,
                        fingerprint TEXT NOT NULL,
                        serial TEXT NOT NULL,
                        not_before INTEGER NOT NULL,
                        not_after INTEGER NOT NULL,
                        pem TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_user_certificates_fingerprint " +
                        "ON user_certificates(fingerprint)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS user_certificate_config (
                        id INTEGER NOT NULL,
                        selected_fingerprint TEXT,
                        keystore_password TEXT NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        fun getInstance(context: Context): MumdroidDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MumdroidDatabase::class.java,
                    "mumdroid.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                ).build()
                    .also { INSTANCE = it }
            }
    }
}
