package com.urbanmove.relocation.infrastructure.dynamo;

import static com.urbanmove.relocation.infrastructure.dynamo.RelocationOrderItems.n;
import static com.urbanmove.relocation.infrastructure.dynamo.RelocationOrderItems.s;
import static com.urbanmove.relocation.infrastructure.dynamo.RelocationTable.EXPIRES_AT;
import static com.urbanmove.relocation.infrastructure.dynamo.RelocationTable.GSI1_PK;
import static com.urbanmove.relocation.infrastructure.dynamo.RelocationTable.GSI1_SK;
import static com.urbanmove.relocation.infrastructure.dynamo.RelocationTable.PK;

import com.urbanmove.relocation.application.ActiveRouteOrderExistsException;
import com.urbanmove.relocation.application.ConcurrentUpdateException;
import com.urbanmove.relocation.application.DuplicateRequestException;
import com.urbanmove.relocation.application.port.IdempotencyRecord;
import com.urbanmove.relocation.application.port.IdempotencyStore;
import com.urbanmove.relocation.application.port.OutgoingEvent;
import com.urbanmove.relocation.application.port.RelocationOrderReader;
import com.urbanmove.relocation.application.port.RelocationOrderWriter;
import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.RelocationOrder;
import com.urbanmove.relocation.infrastructure.events.EventEnvelopes;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Lectura y escritura transaccional de órdenes de recolocación en DynamoDB. */
public final class DynamoRelocationRepository implements RelocationOrderReader, RelocationOrderWriter, IdempotencyStore {
    private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(7);
    private static final String NOT_EXISTS = "attribute_not_exists(" + PK + ")";

    private final DynamoDbClient dynamo;
    private final String table;
    private final EventEnvelopes envelopes;
    private final Clock clock;

    public DynamoRelocationRepository(DynamoDbClient dynamo, String table, EventEnvelopes envelopes, Clock clock) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.table = Objects.requireNonNull(table);
        this.envelopes = Objects.requireNonNull(envelopes);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<RelocationOrder> findById(RelocationOrderId id) {
        return get(RelocationTable.orderKey(id.value())).map(RelocationOrderItems::toOrder);
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        return get(RelocationTable.idempotencyKey(key)).map(item -> new IdempotencyRecord(key,
                item.get("operation").s(), new RelocationOrderId(item.get("orderId").s())));
    }

    @Override
    public void create(RelocationOrder order, IdempotencyRecord idempotency, List<OutgoingEvent> events) {
        String lock = routeLock(order);
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(put(RelocationOrderItems.toItem(order.snapshot(), 0), NOT_EXISTS, null));
        items.add(put(Map.of(PK, s(lock), "orderId", s(order.orderId().value())), NOT_EXISTS, null));
        items.add(put(idempotencyItem(idempotency), NOT_EXISTS, null));
        events.forEach(event -> items.add(put(outboxItem(event), null, null)));
        try {
            dynamo.transactWriteItems(r -> r.transactItems(items));
        } catch (TransactionCanceledException e) {
            if (failed(e, 2)) {
                throw new DuplicateRequestException(idempotency.key());
            }
            if (failed(e, 1)) {
                throw new ActiveRouteOrderExistsException(order.route(), get(lock)
                        .map(item -> new RelocationOrderId(item.get("orderId").s())).orElse(null));
            }
            throw e;
        }
    }

    @Override
    public void update(RelocationOrder order, long expectedVersion, IdempotencyRecord idempotency,
                       List<OutgoingEvent> events) {
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(put(RelocationOrderItems.toItem(order.snapshot(), expectedVersion + 1), "rowVersion = :expected",
                Map.of(":expected", n(expectedVersion))));
        items.add(put(idempotencyItem(idempotency), NOT_EXISTS, null));
        if (!order.isActive()) {
            items.add(TransactWriteItem.builder().delete(d -> d.tableName(table)
                    .key(Map.of(PK, s(routeLock(order))))
                    .conditionExpression(NOT_EXISTS + " OR orderId = :order")
                    .expressionAttributeValues(Map.of(":order", s(order.orderId().value())))).build());
        }
        events.forEach(event -> items.add(put(outboxItem(event), null, null)));
        try {
            dynamo.transactWriteItems(r -> r.transactItems(items));
        } catch (TransactionCanceledException e) {
            if (failed(e, 1)) {
                throw new DuplicateRequestException(idempotency.key());
            }
            if (failed(e, 0)) {
                throw new ConcurrentUpdateException(order.orderId());
            }
            throw e;
        }
    }

    private Optional<Map<String, AttributeValue>> get(String key) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table).consistentRead(true)
                .key(Map.of(PK, s(key)))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(item);
    }

    private static String routeLock(RelocationOrder order) {
        return RelocationTable.routeLockKey(order.route().target().value(), order.route().vehicleType().name());
    }

    private Map<String, AttributeValue> idempotencyItem(IdempotencyRecord record) {
        return Map.of(PK, s(RelocationTable.idempotencyKey(record.key())), "operation", s(record.operation()),
                "orderId", s(record.orderId().value()),
                EXPIRES_AT, n(clock.instant().plus(IDEMPOTENCY_TTL).getEpochSecond()));
    }

    private Map<String, AttributeValue> outboxItem(OutgoingEvent event) {
        return Map.of(PK, s(RelocationTable.outboxKey(event.eventId().toString())),
                GSI1_PK, s(RelocationTable.OUTBOX_PARTITION),
                GSI1_SK, s(EventEnvelopes.timestamp(event.event().occurredAt()) + "#" + event.eventId()),
                "eventId", s(event.eventId().toString()),
                "eventType", s(event.event().eventType()),
                "envelope", s(envelopes.toJson(event)));
    }

    private TransactWriteItem put(Map<String, AttributeValue> item, String condition, Map<String, AttributeValue> values) {
        return TransactWriteItem.builder().put(p -> {
            p.tableName(table).item(item);
            if (condition != null) {
                p.conditionExpression(condition);
            }
            if (values != null) {
                p.expressionAttributeValues(values);
            }
        }).build();
    }

    private static boolean failed(TransactionCanceledException e, int index) {
        List<CancellationReason> reasons = e.cancellationReasons();
        return reasons != null && reasons.size() > index && "ConditionalCheckFailed".equals(reasons.get(index).code());
    }
}
