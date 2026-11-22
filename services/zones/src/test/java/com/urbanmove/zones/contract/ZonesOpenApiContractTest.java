package com.urbanmove.zones.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.urbanmove.zones.integration.ZonesApiTestSupport;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Comprueba que las respuestas del servicio cumplen zones.yaml. */
class ZonesOpenApiContractTest extends ZonesApiTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static OpenAPI contract;
    private static SchemaRegistry registry;

    @BeforeAll
    static void loadContract() {
        Path file = findContract();
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(file.toUri().toString(), null, options);
        assertTrue(result.getMessages() == null || result.getMessages().isEmpty(), "zones.yaml: " + result.getMessages());
        contract = result.getOpenAPI();
        registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
    }

    private static Path findContract() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/contracts/openapi/zones.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("docs/contracts/openapi/zones.yaml not found");
    }

    private static void assertMatchesContract(String path, HttpResponse<String> response) throws Exception {
        String status = String.valueOf(response.statusCode());
        var operation = contract.getPaths().get(path).getGet();
        var declared = operation.getResponses().get(status);
        assertNotNull(declared, path + ": el contrato no declara la respuesta " + status);

        String contentType = response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim();
        MediaType media = declared.getContent().get(contentType);
        assertNotNull(media, path + " " + status + ": el contrato no declara " + contentType
                + " (declara " + declared.getContent().keySet() + ")");

        JsonNode schemaJson = Json31.mapper().valueToTree(media.getSchema());
        Schema schema = registry.getSchema(schemaJson);
        List<com.networknt.schema.Error> errors = schema.validate(JSON.readTree(response.body()));
        assertTrue(errors.isEmpty(), path + " " + status + " no cumple el contrato: " + errors + "\nbody: " + response.body());
    }

    @Test
    void healthMatchesTheContract() throws Exception {
        assertMatchesContract("/health", get("/health", null));
    }

    @Test
    void listZonesMatchesTheContract() throws Exception {
        assertMatchesContract("/zones", get("/zones", token("user")));
    }

    @Test
    void getZoneMatchesTheContract() throws Exception {
        assertMatchesContract("/zones/{zoneId}", get("/zones/tafira", token("user")));
    }

    @Test
    void errorsMatchTheContract() throws Exception {
        assertMatchesContract("/zones/{zoneId}", get("/zones/atlantis", token("user")));
        assertMatchesContract("/zones", get("/zones", null));
    }

    @Test
    void theContractRejectsAWrongBody() {
        Schema zone = registry.getSchema(Json31.mapper().valueToTree(
                contract.getPaths().get("/zones/{zoneId}").getGet().getResponses().get("200")
                        .getContent().get("application/json").getSchema()));

        ObjectMapper json = new ObjectMapper();
        assertFalse(zone.validate(json.createObjectNode().put("zoneId", "tafira")).isEmpty(),
                "si el validador acepta cualquier cosa, los demás tests no demuestran nada");
    }
}
