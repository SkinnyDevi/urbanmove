package com.urbanmove.zones.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.zones.domain.GeoPoint;
import com.urbanmove.zones.domain.Zone;
import com.urbanmove.zones.domain.ZoneId;
import com.urbanmove.zones.domain.ZoneReader;
import com.urbanmove.zones.domain.ZoneWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Repositorio PostgreSQL (JDBC) del catálogo de zonas. */
public final class JdbcZoneRepository implements ZoneReader, ZoneWriter {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final String COLUMNS =
            "zone_id, name, center_lat, center_lon, radius_meters, capacity, parking_restrictions";

    private final DataSource dataSource;
    private final ObjectMapper json;

    public JdbcZoneRepository(DataSource dataSource, ObjectMapper json) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public List<Zone> findAll() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, "SELECT " + COLUMNS + " FROM zones ORDER BY zone_id");
             ResultSet rs = statement.executeQuery()) {
            List<Zone> zones = new ArrayList<>();
            while (rs.next()) {
                zones.add(toZone(rs));
            }
            return zones;
        } catch (SQLException e) {
            throw new PersistenceException("Cannot list zones", e);
        }
    }

    @Override
    public Optional<Zone> findById(ZoneId id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = prepare(connection, "SELECT " + COLUMNS + " FROM zones WHERE zone_id = ?")) {
            statement.setString(1, id.value());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(toZone(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PersistenceException("Cannot read zone " + id, e);
        }
    }

    @Override
    public int saveIfAbsent(Collection<Zone> zones) {
        String sql = "INSERT INTO zones (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))"
                + " ON CONFLICT (zone_id) DO NOTHING";
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = prepare(connection, sql)) {
                int inserted = 0;
                for (Zone zone : zones) {
                    statement.setString(1, zone.id().value());
                    statement.setString(2, zone.name());
                    statement.setDouble(3, zone.center().lat());
                    statement.setDouble(4, zone.center().lon());
                    statement.setInt(5, zone.radiusMeters());
                    statement.setInt(6, zone.capacity());
                    statement.setString(7, json.writeValueAsString(zone.parkingRestrictions()));
                    inserted += statement.executeUpdate();
                }
                connection.commit();
                return inserted;
            } catch (SQLException | JsonProcessingException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new PersistenceException("Cannot save zones", e);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(Database.TIMEOUT_SECONDS);
        return statement;
    }

    private Zone toZone(ResultSet rs) throws SQLException {
        try {
            return new Zone(
                    new ZoneId(rs.getString("zone_id")),
                    rs.getString("name"),
                    new GeoPoint(rs.getDouble("center_lat"), rs.getDouble("center_lon")),
                    rs.getInt("radius_meters"),
                    rs.getInt("capacity"),
                    json.readValue(rs.getString("parking_restrictions"), STRING_LIST));
        } catch (JsonProcessingException e) {
            throw new PersistenceException("Corrupt parking_restrictions in zone " + rs.getString("zone_id"), e);
        }
    }
}
