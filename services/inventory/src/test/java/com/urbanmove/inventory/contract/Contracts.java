package com.urbanmove.inventory.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Lectura de los contratos de docs/contracts y validación de JSON contra ellos. */
final class Contracts {
    static final Path CONTRACTS = findContracts();

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder

                    .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
                    .resourceLoaders(loaders -> loaders.resources(Contracts::readContractFile)));

    private static OpenAPI inventoryApi;

    private Contracts() {
    }

    private static Path findContracts() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/contracts");
            if (Files.isDirectory(candidate.resolve("asyncapi"))) {
                return candidate.normalize();
            }
        }
        throw new IllegalStateException("docs/contracts not found");
    }

    private static String readContractFile(String iri) {
        try {
            Path file = Path.of(URI.create(iri)).toAbsolutePath().normalize();
            return file.startsWith(CONTRACTS) && Files.isRegularFile(file) ? Files.readString(file) : null;
        } catch (IllegalArgumentException | IOException e) {
            return null;
        }
    }

    static List<com.networknt.schema.Error> eventErrors(JsonNode event) {
        String type = event.path("eventType").asText();
        Path schemaFile = CONTRACTS.resolve("asyncapi/schemas/messages/" + type + ".message.schema.json");
        assertTrue(Files.isRegularFile(schemaFile), "No hay contrato para el evento " + type);
        Schema schema = REGISTRY.getSchema(SchemaLocation.of(schemaFile.toUri().toString()));
        return schema.validate(event);
    }

    static void assertEventMatchesContract(JsonNode event) {
        List<com.networknt.schema.Error> errors = eventErrors(event);
        assertTrue(errors.isEmpty(), "El evento no cumple su contrato: " + errors + "\n" + event);
    }

    private static synchronized OpenAPI inventoryApi() {
        if (inventoryApi == null) {
            ParseOptions options = new ParseOptions();
            options.setResolve(true);
            options.setResolveFully(true);
            SwaggerParseResult result = new OpenAPIV3Parser().readLocation(
                    CONTRACTS.resolve("openapi/inventory.yaml").toUri().toString(), null, options);
            assertTrue(result.getMessages() == null || result.getMessages().isEmpty(),
                    "inventory.yaml: " + result.getMessages());
            inventoryApi = result.getOpenAPI();
        }
        return inventoryApi;
    }

    static void assertResponseMatchesContract(String method, String path, HttpResponse<String> response) {
        PathItem item = inventoryApi().getPaths().get(path);
        assertNotNull(item, "El contrato no tiene la ruta " + path);
        Operation operation = item.readOperationsMap().get(PathItem.HttpMethod.valueOf(method));
        assertNotNull(operation, "El contrato no tiene " + method + " " + path);
        String status = String.valueOf(response.statusCode());
        var declared = operation.getResponses().get(status);
        assertNotNull(declared, method + " " + path + ": el contrato no declara la respuesta " + status
                + "\nbody: " + response.body());

        String contentType = response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim();
        MediaType media = declared.getContent().get(contentType);
        assertNotNull(media, method + " " + path + " " + status + ": el contrato no declara " + contentType);

        Schema schema = REGISTRY.getSchema(Json31.mapper().valueToTree(media.getSchema()));
        try {
            List<com.networknt.schema.Error> errors = schema.validate(Json31.mapper().readTree(response.body()));
            assertTrue(errors.isEmpty(), method + " " + path + " " + status + " no cumple el contrato: " + errors
                    + "\nbody: " + response.body());
        } catch (IOException e) {
            fail("Respuesta que no es JSON: " + response.body());
        }
    }
}
