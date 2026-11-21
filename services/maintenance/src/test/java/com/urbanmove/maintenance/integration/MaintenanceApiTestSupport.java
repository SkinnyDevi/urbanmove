package com.urbanmove.maintenance.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.urbanmove.maintenance.MaintenanceApplication;
import com.urbanmove.maintenance.infrastructure.ServiceConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/**
 * Base de los tests de integración y de contrato: LocalStack 4.14.0 (la versión de la práctica 4)
 * con DynamoDB, SNS y SQS, y el servicio completo arrancado contra él en un puerto libre.
 *
 * <p>Monta el mismo patrón que la práctica de mensajería: un tópico SNS y una cola SQS suscrita
 * que hace de "consumidor" (el papel de availability). Los tests leen de esa cola los eventos
 * que publica el servicio.
 *
 * <p>Contenedor y servicio se arrancan una sola vez y los comparten todas las clases de test.
 */
public abstract class MaintenanceApiTestSupport {

    /** Solo para tests. El de verdad llega por la variable de entorno JWT_SECRET. */
    protected static final String TEST_JWT_SECRET = "test-only-secret-with-at-least-32-bytes!!";
    protected static final String TABLE = "urbanmove-maintenance";
    protected static final ObjectMapper JSON = new ObjectMapper();

    /**
     * La imagen oficial tal cual, como el {@code docker run} de la práctica 4. (El LocalStackContainer
     * de Testcontainers espera a copiar un script de arranque que no llega con Docker 29 en Windows.)
     */
    protected static final GenericContainer<?> LOCALSTACK =
            new GenericContainer<>("localstack/localstack:4.14.0")
                    .withEnv("SERVICES", "dynamodb,sns,sqs")
                    .withExposedPorts(4566)
                    .waitingFor(Wait.forLogMessage(".*Ready\\.\\n", 1)
                            .withStartupTimeout(java.time.Duration.ofMinutes(2)));
    private static final String REGION = "us-east-1";
    protected static final String TOPIC_ARN;
    protected static final ServiceConfig CONFIG;
    protected static final MaintenanceApplication APP;

    private static final SqsClient SQS;
    private static final String QUEUE_URL;
    /** Eventos recibidos de la cola, agrupados por orden (las clases de test comparten la cola). */
    private static final Map<String, List<JsonNode>> EVENTS_BY_ORDER = new ConcurrentHashMap<>();

    static {
        LOCALSTACK.start();
        URI endpoint = URI.create("http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566));
        // Credenciales de LocalStack para la cadena por defecto del SDK, como harían AWS_ACCESS_KEY_ID/SECRET.
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");

        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"));
        Region region = Region.of(REGION);
        try (SnsClient sns = SnsClient.builder().endpointOverride(endpoint)
                .credentialsProvider(credentials).region(region).build()) {
            TOPIC_ARN = sns.createTopic(r -> r.name("urbanmove-domain-events")).topicArn();
            SQS = SqsClient.builder().endpointOverride(endpoint)
                    .credentialsProvider(credentials).region(region).build();
            QUEUE_URL = SQS.createQueue(r -> r.queueName("test-consumer-queue")).queueUrl();
            String queueArn = SQS.getQueueAttributes(r -> r.queueUrl(QUEUE_URL)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
            sns.subscribe(r -> r.topicArn(TOPIC_ARN).protocol("sqs").endpoint(queueArn)
                    .attributes(Map.of("RawMessageDelivery", "true")));
        }

        // Reintento periódico cada hora: los tests lo lanzan a mano (APP.relayPendingEvents()) cuando toca.
        CONFIG = new ServiceConfig("maintenance-service", 0, REGION, endpoint.toString(), TABLE, true,
                TOPIC_ARN, 3600, TEST_JWT_SECRET);
        APP = MaintenanceApplication.start(CONFIG);
        Runtime.getRuntime().addShutdownHook(new Thread(APP::close));
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    // ------------------------------------------------------------------ HTTP

    /** Petición completa. {@code idempotencyKey} y {@code body} pueden ser null. */
    protected static HttpResponse<String> request(String method, String path, String token, String idempotencyKey,
                                                  String correlationId, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + APP.port() + path))
                    .timeout(Duration.ofSeconds(10))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body instanceof String text ? text
                            : JSON.writeValueAsString(body)));
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            if (correlationId != null) {
                request.header("X-Correlation-Id", correlationId);
            }
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected static HttpResponse<String> get(String path, String token) {
        return request("GET", path, token, null, null, null);
    }

    /** POST con una Idempotency-Key nueva. */
    protected static HttpResponse<String> post(String path, String token, Object body) {
        return request("POST", path, token, UUID.randomUUID().toString(), null, body);
    }

    protected static JsonNode json(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new AssertionError("La respuesta no es JSON: " + response.body(), e);
        }
    }

    /** Cuerpo de POST /maintenance-orders para un vehículo. */
    protected static Map<String, Object> openBody(String vehicleId) {
        return Map.of("vehicleId", vehicleId, "vehicleType", "SCOOTER", "reason", "LOW_BATTERY",
                "zoneId", "mesa-y-lopez");
    }

    /** Un vehículo distinto en cada test, para que no choquen entre sí (una orden activa por vehículo). */
    protected static String newVehicle() {
        return "veh-it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Abre una orden y devuelve su id. */
    protected static String openOrder(String vehicleId) {
        HttpResponse<String> response = post("/maintenance-orders", token("maintenance"), openBody(vehicleId));
        if (response.statusCode() != 201) {
            throw new AssertionError("No se pudo abrir la orden: " + response.statusCode() + " " + response.body());
        }
        return json(response).get("maintenanceOrderId").asText();
    }

    // ------------------------------------------------------------------ JWT

    /** JWT firmado como lo haría identity. El técnico de los tests es usr-tech-it. */
    protected static String token(List<String> roles, String subject, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .claim("roles", roles)
                    .issueTime(new Date())
                    .expirationTime(Date.from(expiresAt))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String token(String... roles) {
        return token(List.of(roles), "usr-tech-it", Instant.now().plusSeconds(300));
    }

    // ------------------------------------------------------------------ eventos recibidos en SQS

    /**
     * Espera (hasta 15 s) a que la cola haya recibido {@code count} eventos de la orden y los
     * devuelve en orden de llegada.
     */
    protected static List<JsonNode> awaitEvents(String orderId, int count) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            List<JsonNode> events = EVENTS_BY_ORDER.getOrDefault(orderId, List.of());
            if (events.size() >= count) {
                return List.copyOf(events);
            }
            drainQueue();
        }
        throw new AssertionError("Esperaba " + count + " eventos de " + orderId + " y llegaron "
                + EVENTS_BY_ORDER.getOrDefault(orderId, List.of()).size());
    }

    /** Eventos de la orden recibidos hasta ahora (sin esperar más que una lectura). */
    protected static List<JsonNode> eventsSoFar(String orderId) {
        drainQueue();
        return List.copyOf(EVENTS_BY_ORDER.getOrDefault(orderId, List.of()));
    }

    private static synchronized void drainQueue() {
        List<Message> messages = SQS.receiveMessage(r -> r.queueUrl(QUEUE_URL).maxNumberOfMessages(10)
                .waitTimeSeconds(1)).messages();
        for (Message message : messages) {
            try {
                JsonNode event = JSON.readTree(message.body());
                String orderId = event.path("payload").path("maintenanceOrderId").asText();
                EVENTS_BY_ORDER.computeIfAbsent(orderId, k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                        .add(event);
            } catch (IOException e) {
                throw new AssertionError("Mensaje que no es JSON: " + message.body(), e);
            }
            SQS.deleteMessage(r -> r.queueUrl(QUEUE_URL).receiptHandle(message.receiptHandle()));
        }
    }
}
