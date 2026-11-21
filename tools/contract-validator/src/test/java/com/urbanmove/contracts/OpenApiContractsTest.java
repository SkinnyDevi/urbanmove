package com.urbanmove.contracts;

import static org.junit.jupiter.api.Assertions.*;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests de los contratos REST de docs/contracts/openapi/.
 *
 * <p>Comprueban que cada fichero es OpenAPI 3.1 válido con todas las referencias resueltas, y que
 * cumple las reglas del AGENTS.md: GET /health sin autenticación (6.3), JWT en el resto (3.1),
 * cabecera X-Correlation-Id (6.1), errores en RFC 7807 (3.1) e Idempotency-Key en los POST (5.5).
 */
class OpenApiContractsTest {

    private static final Path OPENAPI_DIR = ContractsLocator.findRepoRoot().resolve("docs/contracts/openapi");

    static Stream<Path> serviceContracts() throws IOException {
        try (Stream<Path> files = Files.list(OPENAPI_DIR)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                    .filter(p -> !p.getFileName().toString().equals("common.yaml"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }

    private static OpenAPI parse(Path file) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult result = new OpenAPIV3Parser().readLocation(file.toUri().toString(), null, options);
        List<String> messages = result.getMessages() == null ? List.of() : result.getMessages();
        assertTrue(messages.isEmpty(), file.getFileName() + " tiene errores: " + messages);
        assertNotNull(result.getOpenAPI(), file.getFileName() + " no se pudo leer");
        return result.getOpenAPI();
    }

    private record Op(String label, String method, String path, Operation operation) {
    }

    private static List<Op> operations(OpenAPI api) {
        List<Op> ops = new ArrayList<>();
        for (Map.Entry<String, PathItem> entry : api.getPaths().entrySet()) {
            entry.getValue().readOperationsMap().forEach((method, op) ->
                    ops.add(new Op(method + " " + entry.getKey(), method.name(), entry.getKey(), op)));
        }
        return ops;
    }

    private static boolean hasHeader(Operation op, String name) {
        List<Parameter> params = op.getParameters() == null ? List.of() : op.getParameters();
        return params.stream().anyMatch(p -> "header".equals(p.getIn()) && name.equalsIgnoreCase(p.getName()));
    }

    @Test
    void thereAreContractsForTheMinimumBlockBServices() {
        List<String> names = assertDoesNotThrow(() -> serviceContracts().map(p -> p.getFileName().toString()).toList());
        for (String required : List.of("inventory.yaml", "availability.yaml", "maintenance.yaml", "relocation.yaml")) {
            assertTrue(names.contains(required), "falta " + required);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serviceContracts")
    void isValidOpenApi31(Path file) {
        OpenAPI api = parse(file);
        assertTrue(api.getOpenapi().startsWith("3.1"), "debe ser OpenAPI 3.1");
        assertNotNull(api.getInfo().getVersion());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serviceContracts")
    void exposesAPublicHealthCheck(Path file) {
        OpenAPI api = parse(file);
        PathItem health = api.getPaths().get("/health");
        assertNotNull(health, "falta GET /health");
        Operation get = health.getGet();
        assertNotNull(get, "falta GET /health");
        assertNotNull(get.getSecurity(), "/health no debe pedir JWT");
        assertTrue(get.getSecurity().isEmpty(), "/health no debe pedir JWT");
        assertNotNull(get.getResponses().get("200"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serviceContracts")
    void everyOtherOperationRequiresJwtAndCorrelationId(Path file) {
        OpenAPI api = parse(file);
        assertNotNull(api.getSecurity(), "falta la seguridad global bearerJwt");
        assertTrue(api.getSecurity().stream().anyMatch(s -> s.containsKey("bearerJwt")), "falta bearerJwt");

        for (Op op : operations(api)) {
            if (op.path().equals("/health")) {
                continue;
            }
            assertTrue(hasHeader(op.operation(), "X-Correlation-Id"), op.label() + ": falta X-Correlation-Id");
            assertNotNull(op.operation().getResponses().get("401"), op.label() + ": falta la respuesta 401");
            assertNotNull(op.operation().getOperationId(), op.label() + ": falta operationId");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serviceContracts")
    void postOperationsRequireAnIdempotencyKey(Path file) {
        for (Op op : operations(parse(file))) {
            if (op.method().equals("POST")) {
                assertTrue(hasHeader(op.operation(), "Idempotency-Key"), op.label() + ": falta Idempotency-Key");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serviceContracts")
    void errorsUseProblemDetailsWithCorrelationId(Path file) {
        for (Op op : operations(parse(file))) {
            if (op.path().equals("/health")) {
                continue;
            }
            for (Map.Entry<String, ApiResponse> response : op.operation().getResponses().entrySet()) {
                int status = Integer.parseInt(response.getKey());
                if (status < 400) {
                    continue;
                }
                String where = op.label() + " " + status;
                var content = response.getValue().getContent();
                assertNotNull(content, where + ": sin cuerpo");
                var media = content.get("application/problem+json");
                assertNotNull(media, where + ": debe ser application/problem+json (RFC 7807)");
                assertTrue(requiredFields(media.getSchema()).contains("correlationId"),
                        where + ": el Problem debe exigir correlationId");
            }
        }
    }

    /** Campos obligatorios de un esquema, incluyendo los de sus allOf. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<String> requiredFields(io.swagger.v3.oas.models.media.Schema schema) {
        List<String> required = new ArrayList<>();
        if (schema == null) {
            return required;
        }
        if (schema.getRequired() != null) {
            required.addAll(schema.getRequired());
        }
        if (schema.getAllOf() != null) {
            for (Object part : schema.getAllOf()) {
                required.addAll(requiredFields((io.swagger.v3.oas.models.media.Schema) part));
            }
        }
        // anyOf / oneOf: un campo es obligatorio si lo es en todas las alternativas.
        for (List alternatives : new List[] {schema.getAnyOf(), schema.getOneOf()}) {
            if (alternatives != null && !alternatives.isEmpty()) {
                List<String> common = null;
                for (Object alternative : alternatives) {
                    List<String> fields = requiredFields((io.swagger.v3.oas.models.media.Schema) alternative);
                    if (common == null) {
                        common = new ArrayList<>(fields);
                    } else {
                        common.retainAll(fields);
                    }
                }
                required.addAll(common);
            }
        }
        return required;
    }
}
