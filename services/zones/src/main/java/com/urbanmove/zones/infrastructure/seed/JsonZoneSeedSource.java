package com.urbanmove.zones.infrastructure.seed;

import com.urbanmove.zones.domain.GeoPoint;
import com.urbanmove.zones.domain.InvalidZoneException;
import com.urbanmove.zones.domain.Zone;
import com.urbanmove.zones.domain.ZoneId;
import com.urbanmove.zones.domain.ZoneSeedSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.core.io.Resource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lee las zonas de un fichero JSON con el formato de tools/synthetic-generator/config/zones.json.
 * Los campos que solo usa el generador (demandProfile, popularity) se ignoran.
 */
public final class JsonZoneSeedSource implements ZoneSeedSource {

    private final Resource file;
    private final JsonMapper json;

    public JsonZoneSeedSource(Resource file, JsonMapper json) {
        this.file = Objects.requireNonNull(file);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public List<Zone> load() {
        JsonNode root;
        try (InputStream in = file.getInputStream()) {
            root = json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read zone seed " + file.getDescription(), e);
        }
        if (!root.isArray()) {
            throw new InvalidZoneException("Zone seed must be a JSON array: " + file.getDescription());
        }
        List<Zone> zones = new ArrayList<>();
        for (JsonNode node : root) {
            zones.add(toZone(node));
        }
        return zones;
    }

    private static Zone toZone(JsonNode node) {
        List<String> restrictions = new ArrayList<>();
        node.path("parkingRestrictions").forEach(r -> restrictions.add(r.asString()));
        return new Zone(
                new ZoneId(required(node, "zoneId").asString()),
                required(node, "name").asString(),
                new GeoPoint(required(node.path("center"), "lat").asDouble(), required(node.path("center"), "lon").asDouble()),
                required(node, "radiusMeters").asInt(),
                required(node, "capacity").asInt(),
                restrictions);
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new InvalidZoneException("Zone seed entry is missing '" + field + "': " + node);
        }
        return value;
    }
}
