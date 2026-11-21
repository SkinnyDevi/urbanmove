package com.urbanmove.zones.infrastructure.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pool de conexiones a la base de datos propia del servicio (ADR-001) y sus migraciones. */
public final class Database {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    /** Ninguna llamada de red sin timeout (AGENTS.md 6.4). */
    public static final int TIMEOUT_SECONDS = 3;

    private Database() {
    }

    /** El pool es caro de crear y seguro entre hilos: se crea una vez en el arranque (AGENTS.md 5.1). */
    public static HikariDataSource connect(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        config.setConnectionTimeout(TIMEOUT_SECONDS * 1000L);
        config.setValidationTimeout(TIMEOUT_SECONDS * 1000L);
        config.setPoolName("zones-db");
        return new HikariDataSource(config);
    }

    /** Aplica las migraciones de src/main/resources/db/migration. */
    public static void migrate(HikariDataSource dataSource) {
        var result = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        log.atInfo()
                .addKeyValue("migrationsExecuted", result.migrationsExecuted)
                .addKeyValue("schemaVersion", result.targetSchemaVersion)
                .log("Database migrated");
    }
}
