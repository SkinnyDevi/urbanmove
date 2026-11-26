package com.urbanmove.maintenancedetection.interfaces;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.urbanmove.maintenancedetection.infrastructure.LambdaComponents;
import com.urbanmove.maintenancedetection.infrastructure.LambdaConfig;
import com.urbanmove.maintenancedetection.integration.FakeMaintenance;
import com.urbanmove.maintenancedetection.integration.FakeMaintenance.Reply;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** El handler completo: un lote de SQS con mensajes buenos, ignorados, malos y con maintenance caído. */
class MaintenanceDetectionHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final FakeMaintenance maintenance = new FakeMaintenance();

    @AfterEach
    void stop() {
        maintenance.close();
    }

    private MaintenanceDetectionHandler handler(URI url) {
        return new MaintenanceDetectionHandler(LambdaComponents.create(new LambdaConfig(url,
                "test-only-secret-with-at-least-32-bytes!!", Duration.ofMillis(500), 2, Duration.ofSeconds(5))));
    }

    static String example(String eventType, int batteryLevel) throws Exception {
        Path dir = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(dir.resolve("docs/contracts/asyncapi/examples"))) {
            dir = dir.getParent();
        }
        ObjectNode event = (ObjectNode) JSON.readTree(dir.resolve("docs/contracts/asyncapi/examples/" + eventType + ".json")
                .toFile());
        event.put("eventId", UUID.randomUUID().toString());
        if (event.get("payload").has("batteryLevel")) {
            ((ObjectNode) event.get("payload")).put("batteryLevel", batteryLevel);
        }
        return event.toString();
    }

    private static SQSEvent batch(String... bodies) {
        SQSEvent event = new SQSEvent();
        List<SQSEvent.SQSMessage> messages = new java.util.ArrayList<>();
        for (int i = 0; i < bodies.length; i++) {
            SQSEvent.SQSMessage message = new SQSEvent.SQSMessage();
            message.setMessageId("msg-" + i);
            message.setBody(bodies[i]);
            messages.add(message);
        }
        event.setRecords(messages);
        return event;
    }

    private static List<String> failed(SQSBatchResponse response) {
        return response.getBatchItemFailures().stream().map(SQSBatchResponse.BatchItemFailure::getItemIdentifier)
                .toList();
    }

    @Test
    void onlyTheMessagesThatCouldNotBeProcessedAreReturnedToTheQueue() throws Exception {
        SQSBatchResponse response = handler(maintenance.url()).handleRequest(batch(
                example("VehicleBatteryLow", 12),
                example("RideStarted", 0),
                "{not json",
                example("VehicleBatteryLow", 80)), new FakeContext(60_000));

        assertEquals(List.of("msg-2"), failed(response), "solo el mensaje mal formado");
        assertEquals(1, maintenance.received().size(), "solo la batería baja llama a maintenance");
    }

    @Test
    void withMaintenanceDownTheMessageGoesBackToTheQueue() throws Exception {
        maintenance.always(Reply.of(503, ""));

        SQSBatchResponse response = handler(maintenance.url()).handleRequest(batch(example("VehicleBatteryLow", 5)),
                new FakeContext(60_000));

        assertEquals(List.of("msg-0"), failed(response));
    }

    @Test
    void withoutTimeLeftTheRestOfTheBatchIsReturnedUnprocessed() throws Exception {
        SQSBatchResponse response = handler(maintenance.url()).handleRequest(batch(example("VehicleBatteryLow", 5),
                example("VehicleBatteryLow", 6)), new FakeContext(1_000));

        assertEquals(List.of("msg-0", "msg-1"), failed(response));
        assertTrue(maintenance.received().isEmpty());
    }

    private record FakeContext(int remainingMillis) implements Context {
        public String getAwsRequestId() { return "req-1"; }

        public String getLogGroupName() { return "/aws/lambda/maintenance-detection"; }

        public String getLogStreamName() { return "test"; }

        public String getFunctionName() { return "maintenance-detection"; }

        public String getFunctionVersion() { return "$LATEST"; }

        public String getInvokedFunctionArn() { return "arn:aws:lambda:us-east-1:000000000000:function:maintenance-detection"; }

        public CognitoIdentity getIdentity() { return null; }

        public ClientContext getClientContext() { return null; }

        public int getRemainingTimeInMillis() { return remainingMillis; }

        public int getMemoryLimitInMB() { return 256; }

        public LambdaLogger getLogger() { return null; }
    }
}
