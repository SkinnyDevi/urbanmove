package com.urbanmove.maintenance.infrastructure;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Configuración del servicio, leída de variables de entorno. */
public record ServiceConfig(String serviceName, int port, String awsRegion, String awsEndpoint,
                            String tableName, boolean createTable, String eventsTopicArn,
                            int outboxRelaySeconds, String jwtSecret) {
    public ServiceConfig {
        Objects.requireNonNull(serviceName);
        Objects.requireNonNull(awsRegion);
        Objects.requireNonNull(tableName);
        Objects.requireNonNull(eventsTopicArn);
        Objects.requireNonNull(jwtSecret);
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        if (outboxRelaySeconds < 1) {
            throw new IllegalArgumentException("OUTBOX_RELAY_SECONDS must be at least 1");
        }
    }

    public static ServiceConfig fromEnvironment(Map<String, String> env) {
        return new ServiceConfig(
                "maintenance-service",
                Integer.parseInt(env.getOrDefault("PORT", "8080")),
                env.getOrDefault("AWS_REGION", "us-east-1"),
                blankToNull(env.get("AWS_ENDPOINT_URL")),
                env.getOrDefault("DYNAMODB_TABLE", "urbanmove-maintenance"),
                Boolean.parseBoolean(env.getOrDefault("DYNAMODB_CREATE_TABLE", "false")),
                required(env, "EVENTS_TOPIC_ARN"),
                Integer.parseInt(env.getOrDefault("OUTBOX_RELAY_SECONDS", "5")),
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
                + ", eventsTopicArn=" + eventsTopicArn + ", outboxRelaySeconds=" + outboxRelaySeconds + "]";
    }
}
