package com.urbanmove.inventory.infrastructure.events;

import com.urbanmove.inventory.application.ApplyInventoryEventUseCase;
import com.urbanmove.inventory.application.EventOutcome;
import com.urbanmove.inventory.application.IncomingEvent;
import com.urbanmove.inventory.infrastructure.logging.JsonLogEncoder;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/** Consumidor de la cola SQS de inventory: aplica cada evento al registro de activos. */
public final class SqsEventConsumer implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SqsEventConsumer.class);

    private final SqsClient sqs;
    private final String queueUrl;
    private final int waitSeconds;
    private final EventTranslator translator;
    private final ApplyInventoryEventUseCase apply;
    private volatile boolean running = true;

    public SqsEventConsumer(SqsClient sqs, String queueUrl, int waitSeconds, EventTranslator translator,
                            ApplyInventoryEventUseCase apply) {
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
                    pause();
                }
            } catch (RuntimeException e) {
                log.error("Unexpected error in the event consumer", e);
                pause();
            }
        }
        log.info("Event consumer stopped");
    }

    public int pollOnce() {
        Duration timeout = Duration.ofSeconds(waitSeconds + 5L);
        List<Message> messages = sqs.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(10)
                .waitTimeSeconds(waitSeconds)
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)
                .overrideConfiguration(c -> c.apiCallTimeout(timeout).apiCallAttemptTimeout(timeout)))
                .messages();
        messages.forEach(this::handle);
        return messages.size();
    }

    public void stop() {
        running = false;
    }

    private void handle(Message message) {
        try {
            Optional<IncomingEvent> event = translator.translate(message.body());
            event.ifPresent(e -> MDC.put(JsonLogEncoder.CORRELATION_ID, e.metadata().correlationId()));
            EventOutcome outcome = event.map(apply::execute).orElse(null);
            log.atInfo().addKeyValue("messageId", message.messageId())
                    .addKeyValue("outcome", outcome == null ? "IGNORED" : outcome.name())
                    .log("Event processed");
            sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (RuntimeException e) {
            log.atWarn().addKeyValue("messageId", message.messageId())
                    .addKeyValue("attempt", message.attributes()
                            .getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "?"))
                    .log("Event not processed, it will be retried or sent to the DLQ: " + e.getMessage());
        } finally {
            MDC.remove(JsonLogEncoder.CORRELATION_ID);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
