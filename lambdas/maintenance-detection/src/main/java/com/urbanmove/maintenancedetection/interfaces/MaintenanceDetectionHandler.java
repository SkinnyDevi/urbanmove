package com.urbanmove.maintenancedetection.interfaces;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.urbanmove.maintenancedetection.application.DetectionOutcome;
import com.urbanmove.maintenancedetection.application.IncomingSignal;
import com.urbanmove.maintenancedetection.application.MaintenanceUnavailableException;
import com.urbanmove.maintenancedetection.infrastructure.LambdaComponents;
import com.urbanmove.maintenancedetection.infrastructure.LambdaConfig;
import com.urbanmove.maintenancedetection.infrastructure.logging.JsonLogEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Punto de entrada de la Lambda: procesa un lote de SQS y devuelve solo los mensajes que hay que reintentar. */
public final class MaintenanceDetectionHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceDetectionHandler.class);

    private static final class Shared {
        static final LambdaComponents INSTANCE = LambdaComponents.create(LambdaConfig.fromEnvironment(System.getenv()));
    }

    private final LambdaComponents components;

    public MaintenanceDetectionHandler() {
        this(Shared.INSTANCE);
    }

    MaintenanceDetectionHandler(LambdaComponents components) {
        this.components = Objects.requireNonNull(components);
    }

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();
        for (SQSEvent.SQSMessage message : event.getRecords()) {
            if (context.getRemainingTimeInMillis() < components.safetyMargin().toMillis()) {
                log.atWarn().addKeyValue("messageId", message.getMessageId())
                        .log("Not enough time left, the message goes back to the queue");
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
                continue;
            }
            if (!handle(message)) {
                failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
            }
        }
        return new SQSBatchResponse(failures);
    }

    private boolean handle(SQSEvent.SQSMessage message) {
        try {
            Optional<IncomingSignal> signal = components.translator().translate(message.getBody());
            if (signal.isEmpty()) {
                log.atInfo().addKeyValue("messageId", message.getMessageId()).log("Event ignored");
                return true;
            }
            MDC.put(JsonLogEncoder.CORRELATION_ID, signal.get().metadata().correlationId());
            DetectionOutcome outcome = components.useCase().execute(signal.get());
            log.atInfo().addKeyValue("messageId", message.getMessageId()).addKeyValue("outcome", outcome.name())
                    .log("Event processed");
            return true;
        } catch (MaintenanceUnavailableException e) {
            log.atWarn().addKeyValue("messageId", message.getMessageId())
                    .log("Event not processed, SQS will retry it: " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.atError().addKeyValue("messageId", message.getMessageId())
                    .log("Event cannot be processed, it will end in the DLQ: " + e.getMessage());
            return false;
        } finally {
            MDC.remove(JsonLogEncoder.CORRELATION_ID);
        }
    }
}
