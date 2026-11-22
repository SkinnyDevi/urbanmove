package com.urbanmove.availability.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.urbanmove.availability.integration.AvailabilityTestSupport;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Comprueba que las respuestas del servicio cumplen availability.yaml. */
class AvailabilityOpenApiContractTest extends AvailabilityTestSupport {
    private static OpenAPI contract;
    private static SchemaRegistry registry;

    @BeforeAll
    static void loadContract() {
        Path file = null;
        for (Path dir = Path.of("").toAbsolutePath(); dir != null && file == null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/contracts/openapi/availability.yaml");
            if (Files.isRegularFile(candidate)) {
                file = candidate;
            }
        }
        assertNotNull(file, "availability.yaml not found");
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(file.toUri().toString(), null, options);
        assertTrue(result.getMessages() == null || result.getMessages().isEmpty(), "availability.yaml: " + result.getMessages());
        contract = result.getOpenAPI();
        registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
    }

    private static void assertMatchesContract(String path, HttpResponse<String> response) throws IOException {
        String status = String.valueOf(response.statusCode());
        var declared = contract.getPaths().get(path).getGet().getResponses().get(status);
        assertNotNull(declared, path + ": el contrato no declara la respuesta " + status + "\nbody: " + response.body());
        String contentType = response.headers().firstValue("Content-Type").orElse("").split(";")[0].trim();
        MediaType media = declared.getContent().get(contentType);
        assertNotNull(media, path + " " + status + ": el contrato no declara " + contentType);
        Schema schema = registry.getSchema(Json31.mapper().valueToTree(media.getSchema()));
        List<com.networknt.schema.Error> errors = schema.validate(Json31.mapper().readTree(response.body()));
        assertTrue(errors.isEmpty(), path + " " + status + " no cumple el contrato: " + errors + "\nbody: " + response.body());
    }

    @Test
    void successfulResponsesMatchTheContract() throws IOException {
        String staff = token("maintenance");
        assertMatchesContract("/health", get("/health", null));
        assertMatchesContract("/availability/vehicles", get("/availability/vehicles?zoneId=tafira&limit=3", token("user")));
        assertMatchesContract("/availability/vehicles/{vehicleId}", get("/availability/vehicles/veh-0004", staff));
        assertMatchesContract("/availability/zones/summary", get("/availability/zones/summary", staff));
    }

    @Test
    void errorsMatchTheContract() throws IOException {
        assertMatchesContract("/availability/vehicles", get("/availability/vehicles", null));
        assertMatchesContract("/availability/vehicles", get("/availability/vehicles?limit=0", token("user")));
        assertMatchesContract("/availability/vehicles/{vehicleId}", get("/availability/vehicles/veh-none", token("user")));
        assertMatchesContract("/availability/zones/summary", get("/availability/zones/summary", token("user")));
    }
}
