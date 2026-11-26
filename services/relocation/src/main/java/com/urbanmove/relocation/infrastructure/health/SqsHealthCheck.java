package com.urbanmove.relocation.infrastructure.health;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Comprueba que la cola SQS de eventos responde (dependencia no crítica). */
public final class SqsHealthCheck implements DependencyHealthCheck {
    private static final Logger log = LoggerFactory.getLogger(SqsHealthCheck.class);

    private final SqsClient sqs;
    private final String queueUrl;

    public SqsHealthCheck(SqsClient sqs, String queueUrl) {
        this.sqs = Objects.requireNonNull(sqs);
        this.queueUrl = Objects.requireNonNull(queueUrl);
    }

    @Override
    public String name() {
        return "sqs";
    }

    @Override
    public boolean critical() {
        return false;
    }

    @Override
    public boolean isUp() {
        try {
            sqs.getQueueAttributes(r -> r.queueUrl(queueUrl));
            return true;
        } catch (SdkException e) {
            log.warn("SQS health check failed: {}", e.getMessage());
            return false;
        }
    }
}
