package com.urbanmove.synthgen;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Escribe el resultado en disco:
 * <pre>
 * out/
 * ├── raw/year=YYYY/month=MM/day=DD/source=&lt;servicio&gt;/events.jsonl   como la capa raw del Data Lake
 * ├── events.jsonl                  todos los eventos en orden (para reenviarlos al bus)
 * ├── seed/zones.json, vehicles.json, users.json   datos iniciales para cargar en los servicios
 * ├── ground_truth_zone_hours.csv   foto de cada zona cada hora (para comprobar la analítica)
 * └── summary.json                  resumen de lo generado
 * </pre>
 */
public final class OutputWriter {

    /** Si una carpeta tiene este fichero, sabemos que la creó el generador y se puede sobrescribir. */
    public static final String MARKER = "summary.json";
    private static final ObjectMapper COMPACT = new ObjectMapper();
    private static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private OutputWriter() {
    }

    public static ObjectNode write(GenerationResult result, Path out) {
        try {
            prepareOutputDir(out);
            Map<Path, StringBuilder> partitions = new LinkedHashMap<>();
            StringBuilder all = new StringBuilder();
            for (ObjectNode event : result.events()) {
                String line = COMPACT.writeValueAsString(event) + "\n";
                partitions.computeIfAbsent(rawPartition(event), p -> new StringBuilder()).append(line);
                all.append(line);
            }
            for (Map.Entry<Path, StringBuilder> partition : partitions.entrySet()) {
                Path file = out.resolve(partition.getKey()).resolve("events.jsonl");
                Files.createDirectories(file.getParent());
                Files.writeString(file, partition.getValue(), StandardCharsets.UTF_8);
            }
            Files.writeString(out.resolve("events.jsonl"), all, StandardCharsets.UTF_8);

            writeSeeds(result, out.resolve("seed"));
            writeZoneHours(result.zoneHours(), out.resolve("ground_truth_zone_hours.csv"));
            ObjectNode summary = SummaryBuilder.build(result);
            PRETTY.writeValue(out.resolve(MARKER).toFile(), summary);
            return summary;
        } catch (IOException e) {
            throw new UncheckedIOException("No puedo escribir en " + out, e);
        }
    }

    /** "2026-11-09T07:15:00.000Z" + "urbanmove.rides" -> raw/year=2026/month=11/day=09/source=rides */
    public static Path rawPartition(ObjectNode event) {
        String date = event.get("timestamp").asText().substring(0, 10);
        String source = event.get("source").asText().substring("urbanmove.".length());
        return Path.of("raw", "year=" + date.substring(0, 4), "month=" + date.substring(5, 7),
                "day=" + date.substring(8, 10), "source=" + source);
    }

    /** Vacía la carpeta de salida, pero SOLO si está vacía o la creó el generador antes. */
    static void prepareOutputDir(Path out) throws IOException {
        if (Files.exists(out)) {
            boolean empty;
            try (Stream<Path> children = Files.list(out)) {
                empty = children.findAny().isEmpty();
            }
            if (!empty && !Files.isRegularFile(out.resolve(MARKER))) {
                throw new UnsafeOutputDirException("La carpeta " + out + " tiene ficheros que no son del generador. "
                        + "Elige otra con --out para no borrar nada por accidente.");
            }
            try (Stream<Path> walk = Files.walk(out)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(out);
    }

    private static void writeSeeds(GenerationResult result, Path seed) throws IOException {
        Files.createDirectories(seed);
        ArrayNode zones = PRETTY.createArrayNode();
        for (Zone z : result.zones()) {
            ObjectNode node = zones.addObject().put("zoneId", z.zoneId()).put("name", z.name());
            node.putObject("center").put("lat", z.lat()).put("lon", z.lon());
            node.put("radiusMeters", z.radiusMeters()).put("capacity", z.capacity());
        }
        Map<String, String> initialZone = new LinkedHashMap<>();
        result.events().stream()
                .filter(e -> "VehiclePurchased".equals(e.get("eventType").asText()))
                .forEach(e -> initialZone.put(e.get("payload").get("vehicleId").asText(),
                        e.get("payload").get("initialZoneId").asText()));
        ArrayNode vehicles = PRETTY.createArrayNode();
        result.vehicles().forEach(v -> vehicles.addObject().put("vehicleId", v.vehicleId)
                .put("vehicleType", v.type.name()).put("model", v.type.model)
                .put("initialZoneId", initialZone.get(v.vehicleId)));
        ArrayNode users = PRETTY.createArrayNode();
        result.users().forEach(u -> users.addObject().put("userId", u.userId).put("email", u.email)
                .put("fullName", u.fullName).put("role", u.role));
        PRETTY.writeValue(seed.resolve("zones.json").toFile(), zones);
        PRETTY.writeValue(seed.resolve("vehicles.json").toFile(), vehicles);
        PRETTY.writeValue(seed.resolve("users.json").toFile(), users);
    }

    private static void writeZoneHours(List<ZoneHour> rows, Path file) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("hourStart,zoneId,availableAtEnd,demandSignals,unmetRequests,hotspot\n");
            for (ZoneHour h : rows) {
                writer.write(String.join(",", h.hourStart(), h.zoneId(), String.valueOf(h.availableAtEnd()),
                        String.valueOf(h.demandSignals()), String.valueOf(h.unmetRequests()),
                        String.valueOf(h.hotspot())));
                writer.write("\n");
            }
        }
    }

    /** Para los tests: el JSON compacto de un evento, igual que se escribe en los .jsonl. */
    static String toJson(ObjectNode event) {
        try {
            return COMPACT.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
