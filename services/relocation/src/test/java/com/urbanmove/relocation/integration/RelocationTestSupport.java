package com.urbanmove.relocation.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.urbanmove.relocation.RelocationApplication;
import com.urbanmove.relocation.infrastructure.ServiceConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/** Base de los tests: LocalStack con DynamoDB, SNS y SQS, las colas del bus y el servicio arrancado. */
public abstract class RelocationTestSupport {
    protected static final String TEST_JWT_SECRET = "test-only-secret-with-at-least-32-bytes!!";
    protected static final ObjectMapper JSON = new ObjectMapper();
    private static final String REGION = "us-east-1";

    private static final GenericContainer<?> LOCALSTACK = new GenericContainer<>("localstack/localstack:4.14.0")
            .withEnv("SERVICES", "dynamodb,sns,sqs")
            .withExposedPorts(4566)
            .waitingFor(Wait.forLogMessage(".*Ready\\.\\n", 1).withStartupTimeout(Duration.ofMinutes(2)));

    protected static final SnsClient SNS;
    protected static final SqsClient SQS;
    protected static final String TOPIC_ARN;
    protected static final String DLQ_URL;
    private static final String OBSERVER_URL;
    protected static final RelocationApplication APP;
    private static final Path EXAMPLES = findExamples();
    private static final Map<String, List<JsonNode>> EVENTS_BY_ORDER = new ConcurrentHashMap<>();

    static {
        LOCALSTACK.start();
        URI endpoint = URI.create("http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566));
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        SNS = SnsClient.builder().endpointOverride(endpoint).credentialsProvider(credentials).region(Region.of(REGION)).build();
        SQS = SqsClient.builder().endpointOverride(endpoint).credentialsProvider(credentials).region(Region.of(REGION)).build();
        TOPIC_ARN = SNS.createTopic(r -> r.name("urbanmove-domain-events")).topicArn();

        DLQ_URL = SQS.createQueue(r -> r.queueName("relocation-dlq")).queueUrl();
        String queueUrl = SQS.createQueue(r -> r.queueName("relocation-queue").attributes(Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "1",
                QueueAttributeName.REDRIVE_POLICY,
                "{\"deadLetterTargetArn\":\"" + arn(DLQ_URL) + "\",\"maxReceiveCount\":\"3\"}"))).queueUrl();
        subscribe(queueUrl, "{\"eventType\":[\"DemandHotspotDetected\"]}");
        OBSERVER_URL = SQS.createQueue(r -> r.queueName("observer-queue")).queueUrl();
        subscribe(OBSERVER_URL, "{\"eventType\":[\"RelocationOrderCreated\",\"RelocationOrderCompleted\"]}");

        APP = RelocationApplication.start(new ServiceConfig("relocation-service", 0, REGION, endpoint.toString(),
                "urbanmove-relocation", true, TOPIC_ARN, queueUrl, 5, 3600, Duration.ofHours(2), TEST_JWT_SECRET));
        Runtime.getRuntime().addShutdownHook(new Thread(APP::close));
    }

    private static String arn(String queueUrl) {
        return SQS.getQueueAttributes(r -> r.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    private static void subscribe(String queueUrl, String filterPolicy) {
        SNS.subscribe(r -> r.topicArn(TOPIC_ARN).protocol("sqs").endpoint(arn(queueUrl))
                .attributes(Map.of("RawMessageDelivery", "true", "FilterPolicy", filterPolicy)));
    }

    private static Path findExamples() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/contracts/asyncapi/examples");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("docs/contracts/asyncapi/examples not found");
    }

    protected static String newZone() {
        return "zona-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static ObjectNode hotspot(String deficitZone, String sourceZone, int quantity) {
        try {
            ObjectNode event = (ObjectNode) JSON.readTree(EXAMPLES.resolve("DemandHotspotDetected.json").toFile());
            event.put("eventId", UUID.randomUUID().toString());
            event.put("correlationId", UUID.randomUUID().toString());
            ObjectNode payload = (ObjectNode) event.get("payload");
            payload.put("zoneId", deficitZone);
            payload.put("suggestedSourceZoneId", sourceZone);
            payload.put("suggestedQuantity", quantity);
            return event;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static void publish(String body, String eventType) {
        SNS.publish(r -> r.topicArn(TOPIC_ARN).message(body).messageAttributes(Map.of("eventType",
                MessageAttributeValue.builder().dataType("String").stringValue(eventType).build())));
    }

    protected static void publish(JsonNode event) {
        publish(event.toString(), event.get("eventType").asText());
    }

    protected static List<JsonNode> ordersTo(String targetZone) {
        return items(json(get("/relocation-orders?targetZoneId=" + targetZone, token("maintenance"))));
    }

    protected static JsonNode awaitOrderTo(String targetZone) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            List<JsonNode> orders = ordersTo(targetZone);
            if (!orders.isEmpty()) {
                return orders.get(0);
            }
            pause(200);
        }
        throw new AssertionError("No se creó ninguna orden hacia " + targetZone);
    }

    protected static List<JsonNode> awaitEvents(String orderId, int count) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            List<JsonNode> events = EVENTS_BY_ORDER.getOrDefault(orderId, List.of());
            if (events.size() >= count) {
                return List.copyOf(events);
            }
            drainObserver();
        }
        throw new AssertionError("Esperaba " + count + " eventos de " + orderId);
    }

    protected static boolean awaitInDlq(String text) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            List<Message> messages = SQS.receiveMessage(r -> r.queueUrl(DLQ_URL).maxNumberOfMessages(10)
                    .waitTimeSeconds(1).visibilityTimeout(0)).messages();
            if (messages.stream().anyMatch(m -> m.body().contains(text))) {
                return true;
            }
        }
        return false;
    }

    private static synchronized void drainObserver() {
        for (Message message : SQS.receiveMessage(r -> r.queueUrl(OBSERVER_URL).maxNumberOfMessages(10)
                .waitTimeSeconds(1)).messages()) {
            try {
                JsonNode event = JSON.readTree(message.body());
                EVENTS_BY_ORDER.computeIfAbsent(event.at("/payload/relocationOrderId").asText(),
                        k -> Collections.synchronizedList(new ArrayList<>())).add(event);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            SQS.deleteMessage(r -> r.queueUrl(OBSERVER_URL).receiptHandle(message.receiptHandle()));
        }
    }

    protected static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    protected static HttpResponse<String> request(String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + APP.port() + path))
                    .timeout(Duration.ofSeconds(10))
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            if ("POST".equals(method)) {
                request.header("Idempotency-Key", UUID.randomUUID().toString());
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
        return request("GET", path, token, null);
    }

    protected static HttpResponse<String> post(String path, String token, Object body) {
        return request("POST", path, token, body);
    }

    protected static JsonNode json(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new AssertionError("La respuesta no es JSON: " + response.body(), e);
        }
    }

    private static List<JsonNode> items(JsonNode page) {
        List<JsonNode> items = new ArrayList<>();
        page.get("items").forEach(items::add);
        return items;
    }

    protected static String token(String role, String subject) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(subject).claim("roles", List.of(role))
                    .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300))).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String token(String role) {
        return token(role, "usr-tech-it");
    }
}
