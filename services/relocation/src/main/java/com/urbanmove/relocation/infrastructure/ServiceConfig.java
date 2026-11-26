package com.urbanmove.relocation.infrastructure;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Configuración del servicio, leída de variables de entorno. */
public record ServiceConfig(String serviceName, int port, String awsRegion, String awsEndpoint, String tableName,
                            boolean createTable, String eventsTopicArn, String hotspotQueueUrl, int pollWaitSeconds,
                            int outboxRelaySeconds, Duration orderDeadline, String jwtSecret) {

    public ServiceConfig {
        Objects.requireNonNull(serviceName);
        Objects.requireNonNull(awsRegion);
        Objects.requireNonNull(tableName);
        Objects.requireNonNull(eventsTopicArn);
        Objects.requireNonNull(hotspotQueueUrl);
        Objects.requireNonNull(orderDeadline);
        Objects.requireNonNull(jwtSecret);
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        if (pollWaitSeconds < 0 || pollWaitSeconds > 20) {
            throw new IllegalArgumentException("SQS_WAIT_SECONDS must be between 0 and 20");
        }
        if (outboxRelaySeconds < 1) {
            throw new IllegalArgumentException("OUTBOX_RELAY_SECONDS must be at least 1");
        }
    }

    public static ServiceConfig fromEnvironment(Map<String, String> env) {
        return new ServiceConfig(
                "relocation-service",
                Integer.parseInt(env.getOrDefault("PORT", "8080")),
                env.getOrDefault("AWS_REGION", "us-east-1"),
                blankToNull(env.get("AWS_ENDPOINT_URL")),
                env.getOrDefault("DYNAMODB_TABLE", "urbanmove-relocation"),
                Boolean.parseBoolean(env.getOrDefault("DYNAMODB_CREATE_TABLE", "false")),
                required(env, "EVENTS_TOPIC_ARN"),
                required(env, "HOTSPOT_QUEUE_URL"),
                Integer.parseInt(env.getOrDefault("SQS_WAIT_SECONDS", "20")),
                Integer.parseInt(env.getOrDefault("OUTBOX_RELAY_SECONDS", "5")),
                Duration.ofMinutes(Long.parseLong(env.getOrDefault("ORDER_DEADLINE_MINUTES", "120"))),
                required(env, "JWT_SECRET"));
    }

    public Optional<String> endpoint() {
        return Optional.ofNullable(awsEndpoint);
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    public String toString() {
        return "ServiceConfig[serviceName=" + serviceName + ", port=" + port + ", awsRegion=" + awsRegion
                + ", awsEndpoint=" + awsEndpoint + ", tableName=" + tableName + ", createTable=" + createTable
                + ", eventsTopicArn=" + eventsTopicArn + ", hotspotQueueUrl=" + hotspotQueueUrl
                + ", orderDeadline=" + orderDeadline + "]";
    }
}
