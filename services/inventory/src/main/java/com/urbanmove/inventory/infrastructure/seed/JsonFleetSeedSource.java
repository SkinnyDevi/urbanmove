package com.urbanmove.inventory.infrastructure.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.inventory.application.InventoryEvent;
import com.urbanmove.inventory.application.port.FleetSeedSource;
import com.urbanmove.inventory.domain.Money;
import com.urbanmove.inventory.domain.VehicleId;
import com.urbanmove.inventory.domain.VehicleType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Lee la flota inicial del entorno local (los mismos vehículos que la semilla de availability). */
public final class JsonFleetSeedSource implements FleetSeedSource {
    private final String location;
    private final ObjectMapper json;

    public JsonFleetSeedSource(String location, ObjectMapper json) {
        this.location = Objects.requireNonNull(location);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public List<InventoryEvent.Purchased> load() {
        JsonNode root;
        try (InputStream in = open()) {
            root = json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read fleet seed " + location, e);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Fleet seed must be a JSON array: " + location);
        }
        List<InventoryEvent.Purchased> fleet = new ArrayList<>();
        for (JsonNode node : root) {
            fleet.add(new InventoryEvent.Purchased(
                    new VehicleId(node.path("vehicleId").asText()),
                    VehicleType.valueOf(node.path("vehicleType").asText()),
                    node.path("model").asText(),
                    LocalDate.parse(node.path("purchaseDate").asText()),
                    new Money(new BigDecimal(node.path("purchasePrice").asText()), Money.EUR)));
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
