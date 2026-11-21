package com.urbanmove.maintenance.infrastructure.events;

import static com.urbanmove.maintenance.infrastructure.dynamo.MaintenanceTable.GSI1;
import static com.urbanmove.maintenance.infrastructure.dynamo.MaintenanceTable.GSI1_PK;
import static com.urbanmove.maintenance.infrastructure.dynamo.MaintenanceTable.PK;

import com.urbanmove.maintenance.application.port.EventDispatcher;
import com.urbanmove.maintenance.application.port.OutgoingEvent;
import com.urbanmove.maintenance.infrastructure.dynamo.MaintenanceTable;
import com.urbanmove.maintenance.infrastructure.logging.JsonLogEncoder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

/**
 * Publica en el tópico SNS los eventos guardados en la tabla (patrón outbox).
 *
 * <ul>
 *   <li>{@link #dispatch}: justo después de guardar la orden, publica sus eventos.</li>
 *   <li>{@link #relayPending}: cada pocos segundos, publica los que quedaron pendientes porque SNS
 *       falló o el servicio se reinició entre guardar y publicar.</li>
 * </ul>
 *
 * Un evento solo se borra de la tabla después de que SNS confirme la publicación. Si el borrado
 * falla, el evento se publicará otra vez: SQS ya entrega "al menos una vez", así que los
 * consumidores deben ser idempotentes con el eventId (AGENTS.md 5.5).
 *
 * <p>Cada mensaje lleva el atributo {@code eventType}, que usan las suscripciones SQS para filtrar
 * solo los eventos que les interesan (como en la práctica de SNS + SQS).
 */
public final class SnsOutboxPublisher implements EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SnsOutboxPublisher.class);
    private static final Pattern CORRELATION = Pattern.compile("\"correlationId\":\"([0-9a-f-]+)\"");
    private static final int PAGE = 25;

    private final DynamoDbClient dynamo;
    private final SnsClient sns;
    private final String table;
    private final String topicArn;

    public SnsOutboxPublisher(DynamoDbClient dynamo, SnsClient sns, String table, String topicArn) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.sns = Objects.requireNonNull(sns);
        this.table = Objects.requireNonNull(table);
        this.topicArn = Objects.requireNonNull(topicArn);
    }

    @Override
    public void dispatch(List<OutgoingEvent> events) {
        for (OutgoingEvent event : events) {
            try {
                Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table).consistentRead(true)
                        .key(Map.of(PK, AttributeValue.fromS(MaintenanceTable.outboxKey(event.eventId().toString())))))
                        .item();
                if (item != null && !item.isEmpty()) {
                    publish(item);
                }
            } catch (SdkException e) {
                log.atWarn().addKeyValue("eventId", event.eventId().toString())
                        .log("Event not published yet, it will be retried: " + e.getMessage());
            }
        }
    }

    /** Publica todos los eventos pendientes. Devuelve cuántos se publicaron. */
    public int relayPending() {
        int published = 0;
        Map<String, AttributeValue> startKey = null;
        do {
            Map<String, AttributeValue> from = startKey;
            QueryResponse response = dynamo.query(r -> r.tableName(table).indexName(GSI1)
                    .keyConditionExpression(GSI1_PK + " = :outbox")
                    .expressionAttributeValues(Map.of(":outbox", AttributeValue.fromS(MaintenanceTable.OUTBOX_PARTITION)))
                    .limit(PAGE)
                    .exclusiveStartKey(from));
            for (Map<String, AttributeValue> item : response.items()) {
                if (publish(item)) {
                    published++;
                }
            }
            startKey = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                    ? response.lastEvaluatedKey() : null;
        } while (startKey != null);
        if (published > 0) {
            log.atInfo().addKeyValue("published", published).log("Pending events relayed");
        }
        return published;
    }

    /** Ejecuta {@link #relayPending} periódicamente. Un fallo se registra y se reintenta en la siguiente vuelta. */
    public void start(ScheduledExecutorService scheduler, int intervalSeconds) {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                relayPending();
            } catch (RuntimeException e) {
                log.warn("Outbox relay failed, will retry: {}", e.getMessage());
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    private boolean publish(Map<String, AttributeValue> item) {
        String eventId = item.get("eventId").s();
        String eventType = item.get("eventType").s();
        String envelope = item.get("envelope").s();
        String previousCorrelation = MDC.get(JsonLogEncoder.CORRELATION_ID);
        correlationOf(envelope).ifPresent(id -> MDC.put(JsonLogEncoder.CORRELATION_ID, id));
        try {
            String messageId = sns.publish(r -> r.topicArn(topicArn).message(envelope)
                    .messageAttributes(Map.of("eventType", MessageAttributeValue.builder()
                            .dataType("String").stringValue(eventType).build()))).messageId();
            dynamo.deleteItem(r -> r.tableName(table).key(Map.of(PK, item.get(PK))));
            log.atInfo().addKeyValue("eventId", eventId).addKeyValue("eventType", eventType)
                    .addKeyValue("snsMessageId", messageId).log("Event published");
            return true;
        } catch (SdkException e) {
            log.atWarn().addKeyValue("eventId", eventId).addKeyValue("eventType", eventType)
                    .log("Event not published yet, it will be retried: " + e.getMessage());
            return false;
        } finally {
            if (previousCorrelation == null) {
                MDC.remove(JsonLogEncoder.CORRELATION_ID);
            } else {
                MDC.put(JsonLogEncoder.CORRELATION_ID, previousCorrelation);
            }
        }
    }

    private static java.util.Optional<String> correlationOf(String envelope) {
        Matcher matcher = CORRELATION.matcher(envelope);
        return matcher.find() ? java.util.Optional.of(matcher.group(1)) : java.util.Optional.empty();
    }
}
