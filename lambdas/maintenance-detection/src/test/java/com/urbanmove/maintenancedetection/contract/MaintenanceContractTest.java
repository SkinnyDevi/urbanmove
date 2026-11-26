package com.urbanmove.maintenancedetection.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.urbanmove.maintenancedetection.application.SignalMetadata;
import com.urbanmove.maintenancedetection.domain.MaintenanceReason;
import com.urbanmove.maintenancedetection.domain.MaintenanceRequest;
import com.urbanmove.maintenancedetection.domain.VehicleId;
import com.urbanmove.maintenancedetection.domain.VehicleType;
import com.urbanmove.maintenancedetection.domain.ZoneId;
import com.urbanmove.maintenancedetection.infrastructure.SignalTranslator;
import com.urbanmove.maintenancedetection.integration.FakeMaintenance;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** La Lambda entiende VehicleBatteryLow y envía a maintenance lo que pide maintenance.yaml. */
class MaintenanceContractTest {
    private static final Path CONTRACTS = findContracts();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder
                    .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
                    .resourceLoaders(loaders -> loaders.resources(MaintenanceContractTest::read)));

    private static Path findContracts() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("docs/contracts/asyncapi"))) {
                return dir.resolve("docs/contracts").normalize();
            }
        }
        throw new IllegalStateException("docs/contracts not found");
    }

    private static String read(String iri) {
        try {
            Path file = Path.of(URI.create(iri)).toAbsolutePath().normalize();
            return file.startsWith(CONTRACTS) && Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    @Test
    void theOfficialVehicleBatteryLowExampleIsValidAndUnderstood() throws Exception {
        Path example = CONTRACTS.resolve("asyncapi/examples/VehicleBatteryLow.json");
        Schema schema = REGISTRY.getSchema(SchemaLocation.of(
                CONTRACTS.resolve("asyncapi/schemas/messages/VehicleBatteryLow.message.schema.json").toUri().toString()));

        assertTrue(schema.validate(new ObjectMapper().readTree(example.toFile())).isEmpty());
        assertTrue(new SignalTranslator(new ObjectMapper()).translate(Files.readString(example)).isPresent());
    }

    @Test
    void theRequestSentToMaintenanceMatchesOpenOrderRequest() throws Exception {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        var api = new OpenAPIV3Parser().readLocation(CONTRACTS.resolve("openapi/maintenance.yaml").toUri().toString(),
                null, options).getOpenAPI();
        var operation = api.getPaths().get("/maintenance-orders").getPost();
        Schema schema = REGISTRY.getSchema(Json31.mapper().valueToTree(
                operation.getRequestBody().getContent().get("application/json").getSchema()));

        try (FakeMaintenance maintenance = new FakeMaintenance()) {
            new com.urbanmove.maintenancedetection.infrastructure.http.HttpMaintenanceOrders(
                    HttpClient.newHttpClient(), maintenance.url(), Duration.ofSeconds(1),
                    new com.urbanmove.maintenancedetection.infrastructure.http.ServiceTokenIssuer(
                            "test-only-secret-with-at-least-32-bytes!!", "svc-maintenance-detection", Clock.systemUTC()),
                    com.urbanmove.maintenancedetection.infrastructure.http.RetryPolicy.withRandomJitter(1),
                    new ObjectMapper())
                    .open(new MaintenanceRequest(new VehicleId("veh-0042"), VehicleType.SCOOTER,
                            MaintenanceReason.LOW_BATTERY, new ZoneId("mesa-y-lopez"), "Batería al 12 %"),
                            new SignalMetadata(UUID.randomUUID(), "VehicleBatteryLow", UUID.randomUUID().toString()));

            String body = maintenance.received().get(0).body();
            var errors = schema.validate(Json31.mapper().readTree(body));
            assertTrue(errors.isEmpty(), "no cumple OpenOrderRequest: " + errors + "\n" + body);
        }
    }
}
