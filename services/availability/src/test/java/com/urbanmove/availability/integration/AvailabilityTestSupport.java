package com.urbanmove.availability.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.urbanmove.availability.AvailabilityApplication;
import com.urbanmove.availability.infrastructure.ServiceConfig;
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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
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

/** Base de los tests: LocalStack con DynamoDB, SNS y SQS y el servicio arrancado. */
public abstract class AvailabilityTestSupport {
    protected static final String TEST_JWT_SECRET = "test-only-secret-with-at-least-32-bytes!!";
    protected static final ObjectMapper JSON = new ObjectMapper();
    private static final String REGION = "us-east-1";

    protected static final GenericContainer<?> LOCALSTACK = new GenericContainer<>("localstack/localstack:4.14.0")
            .withEnv("SERVICES", "dynamodb,sns,sqs")
            .withExposedPorts(4566)
            .waitingFor(Wait.forLogMessage(".*Ready\\.\\n", 1).withStartupTimeout(Duration.ofMinutes(2)));

    protected static final SnsClient SNS;
    protected static final SqsClient SQS;
    protected static final String TOPIC_ARN;
    protected static final String QUEUE_URL;
    protected static final String DLQ_URL;
    protected static final AvailabilityApplication APP;
    private static final Path EXAMPLES = findExamples();

    private static final int LONG_POLL_SECONDS = 5;

    static {
        LOCALSTACK.start();
        URI endpoint = URI.create("http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566));
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));

        SNS = SnsClient.builder().endpointOverride(endpoint).credentialsProvider(credentials).region(Region.of(REGION)).build();
        SQS = SqsClient.builder().endpointOverride(endpoint).credentialsProvider(credentials).region(Region.of(REGION)).build();
        TOPIC_ARN = SNS.createTopic(r -> r.name("urbanmove-domain-events")).topicArn();
        DLQ_URL = SQS.createQueue(r -> r.queueName("availability-dlq")).queueUrl();
        String dlqArn = arn(DLQ_URL);

        QUEUE_URL = SQS.createQueue(r -> r.queueName("availability-queue").attributes(Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "1",
                QueueAttributeName.REDRIVE_POLICY,
                "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"3\"}"))).queueUrl();
        SNS.subscribe(r -> r.topicArn(TOPIC_ARN).protocol("sqs").endpoint(arn(QUEUE_URL)).attributes(Map.of(
                "RawMessageDelivery", "true",
                "FilterPolicy", "{\"eventType\":[\"VehiclePurchased\",\"VehicleReserved\",\"ReservationCancelled\","
                        + "\"ReservationExpired\",\"RideStarted\",\"RideFinished\",\"VehicleBatteryLow\","
                        + "\"VehicleWithdrawnForMaintenance\",\"VehicleReturnedToService\",\"RelocationOrderCompleted\"]}")));

        APP = AvailabilityApplication.start(new ServiceConfig("availability-service", 0, REGION, endpoint.toString(),
                "urbanmove-availability", true, QUEUE_URL, LONG_POLL_SECONDS, "classpath:seed/vehicles.json",
                TEST_JWT_SECRET));
        Runtime.getRuntime().addShutdownHook(new Thread(APP::close));
    }

    private static String arn(String queueUrl) {
        return SQS.getQueueAttributes(r -> r.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
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

    protected static ObjectNode example(String eventType, String vehicleId) {
        try {
            ObjectNode event = (ObjectNode) JSON.readTree(EXAMPLES.resolve(eventType + ".json").toFile());
            event.put("eventId", UUID.randomUUID().toString());
            event.put("correlationId", UUID.randomUUID().toString());
            event.put("timestamp", Instant.now().toString());
            ObjectNode payload = (ObjectNode) event.get("payload");
            if (payload.has("vehicleId")) {
                payload.put("vehicleId", vehicleId);
            }
            if (payload.has("vehicleIds")) {
                ArrayNode ids = payload.putArray("vehicleIds");
                ids.add(vehicleId);
            }
            return event;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static void publish(JsonNode event) {
        publishRaw(event.toString(), event.get("eventType").asText());
    }

    protected static void publishRaw(String body, String eventType) {
        SNS.publish(r -> r.topicArn(TOPIC_ARN).message(body).messageAttributes(Map.of("eventType",
                MessageAttributeValue.builder().dataType("String").stringValue(eventType).build())));
    }

    protected static String newVehicle() {
        return "veh-it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String purchase(String vehicleId, String zoneId) {
        ObjectNode event = example("VehiclePurchased", vehicleId);
        ((ObjectNode) event.get("payload")).put("initialZoneId", zoneId);
        publish(event);
        awaitVehicle(vehicleId, v -> v.get("status").asText().equals("AVAILABLE"));
        return vehicleId;
    }

    protected static JsonNode awaitVehicle(String vehicleId, Predicate<JsonNode> condition) {
        long deadline = System.currentTimeMillis() + 15_000;
        JsonNode last = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = get("/availability/vehicles/" + vehicleId, token("maintenance"));
            if (response.statusCode() == 200) {
                last = json(response);
                if (condition.test(last)) {
                    return last;
                }
            }
            pause(200);
        }
        throw new AssertionError("El vehículo " + vehicleId + " no llegó al estado esperado. Último: " + last);
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

    protected static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    protected static HttpResponse<String> get(String path, String token) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + APP.port() + path))
                    .timeout(Duration.ofSeconds(5)).GET();
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected static JsonNode json(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new AssertionError("La respuesta no es JSON: " + response.body(), e);
        }
    }

    protected static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(item.get("vehicleId").asText()));
        return ids;
    }

    protected static String token(String... roles) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("usr-test-01")
                    .claim("roles", List.of(roles))
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
