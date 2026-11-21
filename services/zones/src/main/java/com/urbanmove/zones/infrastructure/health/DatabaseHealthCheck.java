package com.urbanmove.zones.infrastructure.health;

import com.urbanmove.zones.infrastructure.persistence.Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** La base de datos acepta conexiones y responde en menos de 3 s. */
public final class DatabaseHealthCheck implements DependencyHealthCheck {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthCheck.class);

    private final DataSource dataSource;

    public DatabaseHealthCheck(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public String name() {
        return "database";
    }

    @Override
    public boolean isUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(Database.TIMEOUT_SECONDS);
        } catch (SQLException | RuntimeException e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return false;
        }
    }
}
