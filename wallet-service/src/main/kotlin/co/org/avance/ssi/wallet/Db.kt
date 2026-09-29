package co.org.avance.ssi.wallet

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection

class Db(val ds: HikariDataSource) : AutoCloseable {
    fun <T> tx(block: (Connection) -> T): T = ds.connection.use { c ->
        c.autoCommit = false
        try { block(c).also { c.commit() } } catch (t: Throwable) { c.rollback(); throw t }
    }

    override fun close() = ds.close()

    companion object {
        private val MIGRATIONS = listOf("W1__wallet.sql")

        fun connect(url: String, user: String, password: String, migrate: Boolean = true): Db {
            val cfg = HikariConfig().apply {
                jdbcUrl = url; username = user; this.password = password
                maximumPoolSize = 8; connectionTimeout = 10_000; initializationFailTimeout = 30_000
            }
            return Db(HikariDataSource(cfg)).also { if (migrate) migrate(it) }
        }

        /** Comparte `schema_migrations` con el VDR (versiones distintas: V1__ del VDR, W1__ de la cartera). */
        fun migrate(db: Db) = db.tx { c ->
            c.createStatement().use { it.execute("SELECT pg_advisory_xact_lock(727275)") }
            c.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())") }
            val applied = c.createStatement().use { st -> st.executeQuery("SELECT version FROM schema_migrations").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } } }
            for (name in MIGRATIONS.filterNot { it in applied }) {
                val sql = Db::class.java.getResourceAsStream("/db/migration/$name")!!.bufferedReader().readText()
                c.createStatement().use { it.execute(sql) }
                c.prepareStatement("INSERT INTO schema_migrations(version) VALUES (?)").use { ps -> ps.setString(1, name); ps.executeUpdate() }
            }
        }
    }
}
