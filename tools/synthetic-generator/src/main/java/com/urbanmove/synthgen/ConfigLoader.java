package com.urbanmove.synthgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lee config/zones.json y config/demand_profiles.json y comprueba que tienen sentido. */
public final class ConfigLoader {

    public static final int HOURS_PER_DAY = 24;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigLoader() {
    }

    public static List<Zone> loadZones(Path file) {
        List<Zone> zones = new ArrayList<>();
        for (JsonNode z : read(file)) {
            zones.add(new Zone(z.get("zoneId").asText(), z.get("name").asText(),
                    z.get("center").get("lat").asDouble(), z.get("center").get("lon").asDouble(),
                    z.get("radiusMeters").asInt(), z.get("capacity").asInt(),
                    z.get("demandProfile").asText(), z.path("popularity").asDouble(1.0)));
        }
        return List.copyOf(zones);
    }

    public static Map<String, DemandProfile> loadProfiles(Path file, List<Zone> zones) {
        Map<String, DemandProfile> profiles = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : read(file).properties()) {
            if (entry.getKey().startsWith("_")) {
                continue;   // comentarios como "_comment"
            }
            JsonNode p = entry.getValue();
            profiles.put(entry.getKey(), new DemandProfile(p.path("weekendFactor").asDouble(1.0),
                    hourly(entry.getKey(), p, "departures"), hourly(entry.getKey(), p, "arrivals")));
        }
        for (Zone zone : zones) {
            if (!profiles.containsKey(zone.profile())) {
                throw new IllegalArgumentException("La zona " + zone.zoneId() + " usa el perfil '" + zone.profile()
                        + "', que no existe en " + file.getFileName());
            }
        }
        return Map.copyOf(profiles);
    }

    private static double[] hourly(String profile, JsonNode node, String key) {
        JsonNode values = node.get(key);
        if (values == null || values.size() != HOURS_PER_DAY) {
            throw new IllegalArgumentException("El perfil '" + profile + "' debe tener 24 valores en '" + key + "'");
        }
        double[] result = new double[HOURS_PER_DAY];
        for (int h = 0; h < HOURS_PER_DAY; h++) {
            result[h] = values.get(h).asDouble();
        }
        return result;
    }

    private static JsonNode read(Path file) {
        try {
            return MAPPER.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("No puedo leer " + file, e);
        }
    }
}
