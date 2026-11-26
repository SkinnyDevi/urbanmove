package com.urbanmove.relocation.infrastructure.events;

import com.urbanmove.relocation.application.CreateOrderFromHotspotUseCase;
import com.urbanmove.relocation.application.DemandHotspot;
import com.urbanmove.relocation.application.HotspotOutcome;
import com.urbanmove.relocation.infrastructure.logging.JsonLogEncoder;
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

/** Consumidor de la cola SQS de relocation: convierte cada hotspot en una orden. */
public final class SqsHotspotConsumer implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(SqsHotspotConsumer.class);

    private final SqsClient sqs;
    private final String queueUrl;
    private final int waitSeconds;
    private final HotspotTranslator translator;
    private final CreateOrderFromHotspotUseCase createOrder;
    private volatile boolean running = true;

    public SqsHotspotConsumer(SqsClient sqs, String queueUrl, int waitSeconds, HotspotTranslator translator,
                              CreateOrderFromHotspotUseCase createOrder) {
        this.sqs = Objects.requireNonNull(sqs);
        this.queueUrl = Objects.requireNonNull(queueUrl);
        this.waitSeconds = waitSeconds;
        this.translator = Objects.requireNonNull(translator);
        this.createOrder = Objects.requireNonNull(createOrder);
    }

    @Override
    public void run() {
        log.atInfo().addKeyValue("queueUrl", queueUrl).log("Hotspot consumer started");
        while (running) {
            try {
                pollOnce();
            } catch (SdkException e) {
                if (running) {
                    log.warn("Cannot read from SQS, retrying in 2 s: {}", e.getMessage());
                    pause();
                }
            } catch (RuntimeException e) {
                log.error("Unexpected error in the hotspot consumer", e);
                pause();
            }
        }
        log.info("Hotspot consumer stopped");
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
            Optional<DemandHotspot> hotspot = translator.translate(message.body());
            hotspot.ifPresent(h -> MDC.put(JsonLogEncoder.CORRELATION_ID, h.correlationId()));
            HotspotOutcome outcome = hotspot.map(createOrder::execute).orElse(null);
            log.atInfo().addKeyValue("messageId", message.messageId())
                    .addKeyValue("outcome", outcome == null ? "IGNORED" : outcome.name())
                    .log("Hotspot processed");
            sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (RuntimeException e) {
            log.atWarn().addKeyValue("messageId", message.messageId())
                    .addKeyValue("attempt", message.attributes()
                            .getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "?"))
                    .log("Hotspot not processed, it will be retried or sent to the DLQ: " + e.getMessage());
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
