package com.urbanmove.maintenancedetection.infrastructure.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.maintenancedetection.application.MaintenanceUnavailableException;
import com.urbanmove.maintenancedetection.application.OrderRejectedException;
import com.urbanmove.maintenancedetection.application.SignalMetadata;
import com.urbanmove.maintenancedetection.application.port.MaintenanceOrders;
import com.urbanmove.maintenancedetection.application.port.OpenOutcome;
import com.urbanmove.maintenancedetection.domain.MaintenanceRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adaptador HTTP de POST /maintenance-orders, con timeout, reintentos y la Idempotency-Key del evento. */
public final class HttpMaintenanceOrders implements MaintenanceOrders {
    private static final Logger log = LoggerFactory.getLogger(HttpMaintenanceOrders.class);
    private static final String ACTIVE_ORDER = "/problems/active-order-exists";
    private static final String CONCURRENT_UPDATE = "/problems/concurrent-update";

    private final HttpClient http;
    private final URI ordersUri;
    private final Duration timeout;
    private final ServiceTokenIssuer tokens;
    private final RetryPolicy retry;
    private final ObjectMapper json;

    public HttpMaintenanceOrders(HttpClient http, URI maintenanceUrl, Duration timeout, ServiceTokenIssuer tokens,
                                 RetryPolicy retry, ObjectMapper json) {
        this.http = Objects.requireNonNull(http);
        this.ordersUri = maintenanceUrl.resolve("/maintenance-orders");
        this.timeout = Objects.requireNonNull(timeout);
        this.tokens = Objects.requireNonNull(tokens);
        this.retry = Objects.requireNonNull(retry);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public OpenOutcome open(MaintenanceRequest request, SignalMetadata trigger) {
        String body = body(request, trigger);
        String lastProblem = "no attempts";
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            try {
                Optional<OpenOutcome> outcome = interpret(send(body, trigger));
                if (outcome.isPresent()) {
                    return outcome.get();
                }
                lastProblem = "temporary error from maintenance";
            } catch (IOException e) {
                lastProblem = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            if (attempt < retry.maxAttempts()) {
                Duration wait = retry.delayAfter(attempt);
                log.atWarn().addKeyValue("attempt", attempt).addKeyValue("waitMs", wait.toMillis())
                        .log("maintenance call failed, retrying: " + lastProblem);
                pause(wait);
            }
        }
        throw new MaintenanceUnavailableException("maintenance unavailable after " + retry.maxAttempts()
                + " attempts: " + lastProblem, null);
    }

    private HttpResponse<String> send(String body, SignalMetadata trigger) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(ordersUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", tokens.bearer())
                .header("Idempotency-Key", trigger.eventId().toString())
                .header("X-Correlation-Id", trigger.correlationId())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private Optional<OpenOutcome> interpret(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == 201) {
            return Optional.of(OpenOutcome.CREATED);
        }
        if (status == 200) {
            return Optional.of(OpenOutcome.ALREADY_OPEN);
        }
        if (status == 409) {
            String type = problemType(response.body());
            if (ACTIVE_ORDER.equals(type)) {
                return Optional.of(OpenOutcome.ALREADY_OPEN);
            }
            if (CONCURRENT_UPDATE.equals(type)) {
                return Optional.empty();
            }
            throw new OrderRejectedException("maintenance answered 409 " + type);
        }
        if (status == 429 || status >= 500) {
            return Optional.empty();
        }
        if (status == 401 || status == 403) {
            throw new MaintenanceUnavailableException("maintenance refused the service token (" + status
                    + "): check JWT_SECRET", null);
        }
        throw new OrderRejectedException("maintenance rejected the order (" + status + "): " + response.body());
    }

    private String body(MaintenanceRequest request, SignalMetadata trigger) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vehicleId", request.vehicleId().value());
        body.put("vehicleType", request.vehicleType().name());
        body.put("reason", request.reason().name());
        body.put("zoneId", request.zoneId().value());
        if (request.notes() != null) {
            body.put("notes", request.notes());
        }
        body.put("triggeredByEventId", trigger.eventId().toString());
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String problemType(String body) {
        try {
            JsonNode node = json.readTree(body);
            return node.path("type").asText("");
        } catch (IOException e) {
            return "";
        }
    }

    private static void pause(Duration wait) {
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
