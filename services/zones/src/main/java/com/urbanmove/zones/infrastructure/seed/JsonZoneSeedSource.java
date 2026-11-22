package com.urbanmove.zones.infrastructure.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.zones.domain.GeoPoint;
import com.urbanmove.zones.domain.InvalidZoneException;
import com.urbanmove.zones.domain.Zone;
import com.urbanmove.zones.domain.ZoneId;
import com.urbanmove.zones.domain.ZoneSeedSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lee las zonas de un fichero JSON con el formato de tools/synthetic-generator/config/zones.json.
 */
public final class JsonZoneSeedSource implements ZoneSeedSource {
    @FunctionalInterface
    public interface Opener {
        InputStream open() throws IOException;
    }

    private final String description;
    private final Opener opener;
    private final ObjectMapper json;

    public JsonZoneSeedSource(String description, Opener opener, ObjectMapper json) {
        this.description = Objects.requireNonNull(description);
        this.opener = Objects.requireNonNull(opener);
        this.json = Objects.requireNonNull(json);
    }

    public static JsonZoneSeedSource fromLocation(String location, ObjectMapper json) {
        if (location.startsWith("classpath:")) {
            String resource = location.substring("classpath:".length());
            return new JsonZoneSeedSource(location, () -> {
                InputStream in = JsonZoneSeedSource.class.getClassLoader().getResourceAsStream(resource);
                if (in == null) {
                    throw new IOException("Not found in classpath: " + resource);
                }
                return in;
            }, json);
        }
        return new JsonZoneSeedSource(location, () -> Files.newInputStream(Path.of(location)), json);
    }

    @Override
    public List<Zone> load() {
        JsonNode root;
        try (InputStream in = opener.open()) {
            root = json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read zone seed " + description, e);
        }
        if (root == null || !root.isArray()) {
            throw new InvalidZoneException("Zone seed must be a JSON array: " + description);
        }
        List<Zone> zones = new ArrayList<>();
        for (JsonNode node : root) {
            zones.add(toZone(node));
        }
        return zones;
    }

    private static Zone toZone(JsonNode node) {
        List<String> restrictions = new ArrayList<>();
        node.path("parkingRestrictions").forEach(r -> restrictions.add(r.asText()));
        JsonNode center = required(node, "center");
        return new Zone(
                new ZoneId(required(node, "zoneId").asText()),
                required(node, "name").asText(),
                new GeoPoint(required(center, "lat").asDouble(), required(center, "lon").asDouble()),
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
