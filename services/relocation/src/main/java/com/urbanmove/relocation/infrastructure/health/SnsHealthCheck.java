package com.urbanmove.relocation.infrastructure.health;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sns.SnsClient;

/** Comprueba que el tópico SNS de eventos responde (dependencia no crítica). */
public final class SnsHealthCheck implements DependencyHealthCheck {
    private static final Logger log = LoggerFactory.getLogger(SnsHealthCheck.class);

    private final SnsClient sns;
    private final String topicArn;

    public SnsHealthCheck(SnsClient sns, String topicArn) {
        this.sns = Objects.requireNonNull(sns);
        this.topicArn = Objects.requireNonNull(topicArn);
    }

    @Override
    public String name() {
        return "sns";
    }

    @Override
    public boolean critical() {
        return false;
    }

    @Override
    public boolean isUp() {
        try {
            sns.getTopicAttributes(r -> r.topicArn(topicArn));
            return true;
        } catch (SdkException e) {
            log.warn("SNS health check failed: {}", e.getMessage());
            return false;
        }
    }
}
