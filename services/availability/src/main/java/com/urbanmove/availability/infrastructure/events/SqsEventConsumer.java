package com.urbanmove.availability.infrastructure.events;

import com.urbanmove.availability.application.ApplyVehicleEventUseCase;
import com.urbanmove.availability.application.EventOutcome;
import com.urbanmove.availability.application.IncomingEvent;
import com.urbanmove.availability.infrastructure.logging.JsonLogEncoder;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/** Consumidor de la cola SQS de availability. */
public final class SqsEventConsumer implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SqsEventConsumer.class);
    private static final Pattern CORRELATION = Pattern.compile("\"correlationId\"\\s*:\\s*\"([0-9a-fA-F-]+)\"");

    private final SqsClient sqs;
    private final String queueUrl;
    private final int waitSeconds;
    private final EventTranslator translator;
    private final ApplyVehicleEventUseCase apply;
    private volatile boolean running = true;

    public SqsEventConsumer(SqsClient sqs, String queueUrl, int waitSeconds, EventTranslator translator,
                            ApplyVehicleEventUseCase apply) {
        this.sqs = Objects.requireNonNull(sqs);
        this.queueUrl = Objects.requireNonNull(queueUrl);
        this.waitSeconds = waitSeconds;
        this.translator = Objects.requireNonNull(translator);
        this.apply = Objects.requireNonNull(apply);
    }

    @Override
    public void run() {
        log.atInfo().addKeyValue("queueUrl", queueUrl).log("Event consumer started");
        while (running) {
            try {
                pollOnce();
            } catch (SdkException e) {
                if (running) {
                    log.warn("Cannot read from SQS, retrying in 2 s: {}", e.getMessage());
                    sleep(Duration.ofSeconds(2));
                }
            } catch (RuntimeException e) {
                log.error("Unexpected error in the event consumer", e);
                sleep(Duration.ofSeconds(2));
            }
        }
        log.info("Event consumer stopped");
    }

    public int pollOnce() {
        List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl)
                .maxNumberOfMessages(10)
                .waitTimeSeconds(waitSeconds)
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(waitSeconds + 5L))
                        .apiCallAttemptTimeout(Duration.ofSeconds(waitSeconds + 5L))))
                .messages();
        for (Message message : messages) {
            handle(message);
        }
        return messages.size();
    }

    private void handle(Message message) {
        correlationOf(message.body()).ifPresent(id -> MDC.put(JsonLogEncoder.CORRELATION_ID, id));
        String attempt = message.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "?");
        try {
            Optional<IncomingEvent> event = translator.translate(message.body());
            if (event.isEmpty()) {
                log.atInfo().addKeyValue("messageId", message.messageId()).log("Event not consumed by availability, ignored");
            } else {
                EventOutcome outcome = apply.execute(event.get());
                log.atDebug().addKeyValue("outcome", outcome.name()).log("Event processed");
            }
            sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (RuntimeException e) {
            log.atWarn()
                    .addKeyValue("messageId", message.messageId())
                    .addKeyValue("attempt", attempt)
                    .addKeyValue("error", e.getClass().getSimpleName())
                    .log("Event not processed, it will be retried or sent to the DLQ: " + e.getMessage());
        } finally {
            MDC.remove(JsonLogEncoder.CORRELATION_ID);
        }
    }

    public void stop() {
        running = false;
    }

    private static Optional<String> correlationOf(String body) {
        Matcher matcher = CORRELATION.matcher(body);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
