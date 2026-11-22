package com.urbanmove.availability.infrastructure.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.availability.application.VehicleEvent;
import com.urbanmove.availability.application.port.FleetSeedSource;
import com.urbanmove.availability.domain.VehicleId;
import com.urbanmove.availability.domain.VehicleType;
import com.urbanmove.availability.domain.ZoneId;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Lee la flota inicial del fichero vehicles.json del generador sintético. */
public final class JsonFleetSeedSource implements FleetSeedSource {
    private final String location;
    private final ObjectMapper json;

    public JsonFleetSeedSource(String location, ObjectMapper json) {
        this.location = Objects.requireNonNull(location);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public List<VehicleEvent.Purchased> load() {
        JsonNode root;
        try (InputStream in = open()) {
            root = json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read fleet seed " + location, e);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Fleet seed must be a JSON array: " + location);
        }
        List<VehicleEvent.Purchased> fleet = new ArrayList<>();
        for (JsonNode node : root) {
            fleet.add(new VehicleEvent.Purchased(
                    new VehicleId(node.path("vehicleId").asText()),
                    VehicleType.valueOf(node.path("vehicleType").asText()),
                    new ZoneId(node.path("initialZoneId").asText())));
        }
        return fleet;
    }

    private InputStream open() throws IOException {
        if (location.startsWith("classpath:")) {
            String resource = location.substring("classpath:".length());
            InputStream in = JsonFleetSeedSource.class.getClassLoader().getResourceAsStream(resource);
            if (in == null) {
                throw new IOException("Not found in classpath: " + resource);
            }
            return in;
        }
        return Files.newInputStream(Path.of(location));
    }
}
