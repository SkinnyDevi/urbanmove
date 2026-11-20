package com.urbanmove.zones.infrastructure.persistence;

import com.urbanmove.zones.domain.GeoPoint;
import com.urbanmove.zones.domain.Zone;
import com.urbanmove.zones.domain.ZoneId;
import com.urbanmove.zones.domain.ZoneReader;
import com.urbanmove.zones.domain.ZoneWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Adaptador PostgreSQL de los puertos {@link ZoneReader} y {@link ZoneWriter}. */
public final class JdbcZoneRepository implements ZoneReader, ZoneWriter {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    public JdbcZoneRepository(JdbcClient jdbc, TransactionTemplate transactions, JsonMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = Objects.requireNonNull(transactions);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public List<Zone> findAll() {
        return jdbc.sql("""
                        SELECT zone_id, name, center_lat, center_lon, radius_meters, capacity, parking_restrictions
                        FROM zones ORDER BY zone_id""")
                .query(this::toZone)
                .list();
    }

    @Override
    public Optional<Zone> findById(ZoneId id) {
        return jdbc.sql("""
                        SELECT zone_id, name, center_lat, center_lon, radius_meters, capacity, parking_restrictions
                        FROM zones WHERE zone_id = :id""")
                .param("id", id.value())
                .query(this::toZone)
                .optional();
    }

    @Override
    public int saveIfAbsent(Collection<Zone> zones) {
        Integer inserted = transactions.execute(status -> {
            int count = 0;
            for (Zone zone : zones) {
                count += jdbc.sql("""
                                INSERT INTO zones (zone_id, name, center_lat, center_lon, radius_meters, capacity, parking_restrictions)
                                VALUES (:id, :name, :lat, :lon, :radius, :capacity, CAST(:restrictions AS jsonb))
                                ON CONFLICT (zone_id) DO NOTHING""")
                        .param("id", zone.id().value())
                        .param("name", zone.name())
                        .param("lat", zone.center().lat())
                        .param("lon", zone.center().lon())
                        .param("radius", zone.radiusMeters())
                        .param("capacity", zone.capacity())
                        .param("restrictions", json.writeValueAsString(zone.parkingRestrictions()))
                        .update();
            }
            return count;
        });
        return inserted == null ? 0 : inserted;
    }

    private Zone toZone(ResultSet rs, int row) throws SQLException {
        return new Zone(
                new ZoneId(rs.getString("zone_id")),
                rs.getString("name"),
                new GeoPoint(rs.getDouble("center_lat"), rs.getDouble("center_lon")),
                rs.getInt("radius_meters"),
                rs.getInt("capacity"),
                json.readValue(rs.getString("parking_restrictions"), STRING_LIST));
    }
}
