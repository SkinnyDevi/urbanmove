package com.urbanmove.contracts;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Valida eventos de UrbanMove contra los contratos
 */
public final class ContractValidator {

    private static final String ROOT = "<raíz>";

    private final Path contractsDir;
    private final Path schemasDir;
    private final SchemaRegistry registry;
    private final Schema envelopeSchema;
    private final List<String> eventTypes;
    private final Map<String, Schema> messageSchemas = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public ContractValidator(Path contractsDir) {
        this.contractsDir = contractsDir.toAbsolutePath().normalize();
        this.schemasDir = this.contractsDir.resolve("schemas");
        if (!Files.isRegularFile(schemasDir.resolve("envelope.schema.json"))) {
            throw new IllegalArgumentException("No hay contratos en " + this.contractsDir);
        }
        // formatAssertionsEnabled: comprueba de verdad fechas, UUID y emails (por defecto solo se anotan).
        SchemaRegistryConfig config = SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build();

        this.registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(config)
                        .resourceLoaders(loaders -> loaders.resources(this::readContractFile)));
        this.envelopeSchema = loadSchema(schemasDir.resolve("envelope.schema.json"));
        this.eventTypes = readEventTypes();
    }

    /** Busca los contratos automáticamente */
    public static ContractValidator fromDefaultLocation() {
        return new ContractValidator(ContractsLocator.findContractsDir());
    }

    public Path contractsDir() {
        return contractsDir;
    }

    /** Los 13 tipos de evento del catálogo cerrado, tal como los define el envelope. */
    public List<String> knownEventTypes() {
        return eventTypes;
    }

    /** Devuelve los errores del evento; lista vacía si es válido. */
    public List<String> validate(JsonNode event) {
        List<String> errors = format(envelopeSchema.validate(event));
        if (!errors.isEmpty()) {
            return errors;
        }
        String eventType = event.get("eventType").asText();
        Schema message = messageSchemas.computeIfAbsent(eventType,
                type -> loadSchema(schemasDir.resolve("messages").resolve(type + ".message.schema.json")));
        return format(message.validate(event));
    }

    /** Igual que {@link #validate(JsonNode)}, pero a partir del texto JSON. */
    public List<String> validate(String json) {
        try {
            return validate(mapper.readTree(json));
        } catch (JsonProcessingException e) {
            return List.of(ROOT + ": JSON mal formado: " + e.getOriginalMessage());
        }
    }


    private Schema loadSchema(Path file) {
        return registry.getSchema(SchemaLocation.of(file.toUri().toString()));
    }

    private String readContractFile(String iri) {
        try {
            Path file = Path.of(URI.create(iri)).toAbsolutePath().normalize();
            if (!file.startsWith(contractsDir) || !Files.isRegularFile(file)) {
                return null;
            }
            return Files.readString(file);
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    private List<String> readEventTypes() {
        try {
            JsonNode envelope = mapper.readTree(schemasDir.resolve("envelope.schema.json").toFile());
            List<String> types = new ArrayList<>();
            envelope.path("properties").path("eventType").path("enum").forEach(node -> types.add(node.asText()));
            return List.copyOf(types);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> format(List<com.networknt.schema.Error> errors) {
        return errors.stream()
                .map(error -> {
                    String where = error.getInstanceLocation().toString().replaceFirst("^/", "");
                    return (where.isEmpty() ? ROOT : where) + ": " + error.getMessage();
                })
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
