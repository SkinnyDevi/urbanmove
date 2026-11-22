package com.urbanmove.maintenance.infrastructure.dynamo;

import static com.urbanmove.maintenance.infrastructure.dynamo.MaintenanceTable.EXPIRES_AT;
import static com.urbanmove.maintenance.infrastructure.dynamo.MaintenanceTable.GSI1;
import static com.urbanmove.maintenance.infrastructure.dynamo.MaintenanceTable.GSI1_PK;
import static com.urbanmove.maintenance.infrastructure.dynamo.MaintenanceTable.GSI1_SK;
import static com.urbanmove.maintenance.infrastructure.dynamo.MaintenanceTable.PK;

import com.urbanmove.maintenance.application.ActiveOrderExistsException;
import com.urbanmove.maintenance.application.ConcurrentUpdateException;
import com.urbanmove.maintenance.application.DuplicateRequestException;
import com.urbanmove.maintenance.application.port.IdempotencyRecord;
import com.urbanmove.maintenance.application.port.IdempotencyStore;
import com.urbanmove.maintenance.application.port.MaintenanceOrderReader;
import com.urbanmove.maintenance.application.port.MaintenanceOrderWriter;
import com.urbanmove.maintenance.application.port.OrderPage;
import com.urbanmove.maintenance.application.port.OrderSearch;
import com.urbanmove.maintenance.application.port.OutgoingEvent;
import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.Identifiers.TechnicianId;
import com.urbanmove.maintenance.domain.Identifiers.VehicleId;
import com.urbanmove.maintenance.domain.Identifiers.ZoneId;
import com.urbanmove.maintenance.domain.MaintenanceOrder;
import com.urbanmove.maintenance.domain.MaintenanceOrderStatus;
import com.urbanmove.maintenance.domain.MaintenanceReason;
import com.urbanmove.maintenance.domain.MaintenanceWork;
import com.urbanmove.maintenance.domain.VehicleType;
import com.urbanmove.maintenance.infrastructure.events.EventEnvelopes;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Adaptador DynamoDB de los puertos de lectura, escritura e idempotencia. */
public final class DynamoMaintenanceRepository implements MaintenanceOrderReader, MaintenanceOrderWriter, IdempotencyStore {
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Pattern CURSOR = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z#mnt-[a-z0-9-]+$");
    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private final DynamoDbClient dynamo;
    private final String table;
    private final EventEnvelopes envelopes;
    private final Clock clock;

    public DynamoMaintenanceRepository(DynamoDbClient dynamo, String table, EventEnvelopes envelopes, Clock clock) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.table = Objects.requireNonNull(table);
        this.envelopes = Objects.requireNonNull(envelopes);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<MaintenanceOrder> findById(MaintenanceOrderId id) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table).consistentRead(true)
                .key(Map.of(PK, s(MaintenanceTable.orderKey(id.value()))))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(toOrder(item));
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table).consistentRead(true)
                .key(Map.of(PK, s(MaintenanceTable.idempotencyKey(key))))).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new IdempotencyRecord(key, item.get("operation").s(),
                new MaintenanceOrderId(item.get("orderId").s())));
    }

    @Override
    public OrderPage search(OrderSearch search) {
        String after = decodeCursor(search.cursor());
        List<Map<String, AttributeValue>> found = new ArrayList<>();
        for (MaintenanceOrderStatus status : search.statuses()) {
            found.addAll(queryStatus(status, search, after, search.limit() + 1));
        }
        found.sort(Comparator.comparing(item -> item.get(GSI1_SK).s()));
        boolean more = found.size() > search.limit();
        List<Map<String, AttributeValue>> page = more ? found.subList(0, search.limit()) : found;
        String next = more ? encodeCursor(page.get(page.size() - 1).get(GSI1_SK).s()) : null;
        return new OrderPage(page.stream().map(DynamoMaintenanceRepository::toOrder).toList(), next);
    }

    private List<Map<String, AttributeValue>> queryStatus(MaintenanceOrderStatus status, OrderSearch search,
                                                          String after, int wanted) {
        Map<String, AttributeValue> values = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        values.put(":status", s("STATUS#" + status.name()));
        String keyCondition = GSI1_PK + " = :status";
        if (after != null) {
            keyCondition += " AND " + GSI1_SK + " > :after";
            values.put(":after", s(after));
        }
        StringJoiner filter = new StringJoiner(" AND ");
        if (search.zoneId() != null) {
            filter.add("zoneId = :zone");
            values.put(":zone", s(search.zoneId().value()));
        }
        if (search.vehicleId() != null) {
            filter.add("vehicleId = :vehicle");
            values.put(":vehicle", s(search.vehicleId().value()));
        }
        if (search.technicianId() != null) {
            filter.add("technicianId = :tech");
            values.put(":tech", s(search.technicianId().value()));
        }

        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(table).indexName(GSI1)
                    .keyConditionExpression(keyCondition)
                    .expressionAttributeValues(values)
                    .limit(wanted)
                    .exclusiveStartKey(startKey);
            if (filter.length() > 0) {
                request.filterExpression(filter.toString());
            }
            if (!names.isEmpty()) {
                request.expressionAttributeNames(names);
            }
            QueryResponse response = dynamo.query(request.build());
            items.addAll(response.items());
            startKey = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                    ? response.lastEvaluatedKey() : null;
        } while (startKey != null && items.size() < wanted);
        return items.size() > wanted ? items.subList(0, wanted) : items;
    }

    @Override
    public void create(MaintenanceOrder order, IdempotencyRecord idempotency, List<OutgoingEvent> events) {
        String vehicleId = order.vehicleId().value();
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(put(toItem(order.snapshot(), 0), "attribute_not_exists(" + PK + ")", null));
        items.add(put(Map.of(PK, s(MaintenanceTable.vehicleLockKey(vehicleId)),
                        "orderId", s(order.orderId().value())),
                "attribute_not_exists(" + PK + ")", null));
        items.add(put(idempotencyItem(idempotency), "attribute_not_exists(" + PK + ")", null));
        events.forEach(event -> items.add(put(outboxItem(event), null, null)));

        try {
            dynamo.transactWriteItems(r -> r.transactItems(items));
        } catch (TransactionCanceledException e) {
            List<CancellationReason> reasons = e.cancellationReasons();
            if (failed(reasons, 2)) {
                throw new DuplicateRequestException(idempotency.key());
            }
            if (failed(reasons, 1)) {
                throw new ActiveOrderExistsException(order.vehicleId(), activeOrderOf(vehicleId));
            }
            throw e;
        }
    }

    @Override
    public void update(MaintenanceOrder order, long expectedVersion, IdempotencyRecord idempotency,
                       List<OutgoingEvent> events) {
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(put(toItem(order.snapshot(), expectedVersion + 1), "rowVersion = :expected",
                Map.of(":expected", n(expectedVersion))));
        items.add(put(idempotencyItem(idempotency), "attribute_not_exists(" + PK + ")", null));
        if (!order.isActive()) {
            items.add(TransactWriteItem.builder().delete(d -> d.tableName(table)
                    .key(Map.of(PK, s(MaintenanceTable.vehicleLockKey(order.vehicleId().value()))))
                    .conditionExpression("attribute_not_exists(" + PK + ") OR orderId = :order")
                    .expressionAttributeValues(Map.of(":order", s(order.orderId().value())))).build());
        }
        events.forEach(event -> items.add(put(outboxItem(event), null, null)));

        try {
            dynamo.transactWriteItems(r -> r.transactItems(items));
        } catch (TransactionCanceledException e) {
            List<CancellationReason> reasons = e.cancellationReasons();
            if (failed(reasons, 1)) {
                throw new DuplicateRequestException(idempotency.key());
            }
            if (failed(reasons, 0)) {
                throw new ConcurrentUpdateException(order.orderId());
            }
            throw e;
        }
    }

    private MaintenanceOrderId activeOrderOf(String vehicleId) {
        Map<String, AttributeValue> lock = dynamo.getItem(r -> r.tableName(table).consistentRead(true)
                .key(Map.of(PK, s(MaintenanceTable.vehicleLockKey(vehicleId))))).item();
        return lock == null || lock.isEmpty() ? null : new MaintenanceOrderId(lock.get("orderId").s());
    }

    private static boolean failed(List<CancellationReason> reasons, int index) {
        return reasons != null && reasons.size() > index
                && CONDITIONAL_CHECK_FAILED.equals(reasons.get(index).code());
    }

    private TransactWriteItem put(Map<String, AttributeValue> item, String condition,
                                  Map<String, AttributeValue> values) {
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

    private Map<String, AttributeValue> idempotencyItem(IdempotencyRecord record) {
        long expiresAt = clock.instant().plus(IDEMPOTENCY_TTL).getEpochSecond();
        return Map.of(
                PK, s(MaintenanceTable.idempotencyKey(record.key())),
                "operation", s(record.operation()),
                "orderId", s(record.orderId().value()),
                EXPIRES_AT, n(expiresAt));
    }

    private Map<String, AttributeValue> outboxItem(OutgoingEvent event) {
        String occurredAt = EventEnvelopes.timestamp(event.event().occurredAt());
        return Map.of(
                PK, s(MaintenanceTable.outboxKey(event.eventId().toString())),
                GSI1_PK, s(MaintenanceTable.OUTBOX_PARTITION),
                GSI1_SK, s(occurredAt + "#" + event.eventId()),
                "eventId", s(event.eventId().toString()),
                "eventType", s(event.event().eventType()),
                "envelope", s(envelopes.toJson(event)));
    }

    static String sortKey(MaintenanceOrder.Snapshot order) {
        return EventEnvelopes.timestamp(order.createdAt()) + "#" + order.orderId().value();
    }

    private static Map<String, AttributeValue> toItem(MaintenanceOrder.Snapshot o, long version) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(MaintenanceTable.orderKey(o.orderId().value())));
        item.put(GSI1_PK, s("STATUS#" + o.status().name()));
        item.put(GSI1_SK, s(sortKey(o)));
        item.put("orderId", s(o.orderId().value()));
        item.put("vehicleId", s(o.vehicleId().value()));
        item.put("vehicleType", s(o.vehicleType().name()));
        item.put("reason", s(o.reason().name()));
        item.put("zoneId", s(o.zoneId().value()));
        item.put("status", s(o.status().name()));
        item.put("workPerformed", AttributeValue.fromL(o.workPerformed().stream()
                .sorted().map(w -> s(w.name())).toList()));
        item.put("createdAt", s(o.createdAt().toString()));
        item.put("rowVersion", n(version));
        putIfPresent(item, "technicianId", o.technicianId() == null ? null : o.technicianId().value());
        putIfPresent(item, "notes", o.notes());
        putIfPresent(item, "triggeredByEventId", o.triggeredByEventId() == null ? null : o.triggeredByEventId().toString());
        putIfPresent(item, "withdrawnAt", o.withdrawnAt() == null ? null : o.withdrawnAt().toString());
        putIfPresent(item, "returnedAt", o.returnedAt() == null ? null : o.returnedAt().toString());
        putIfPresent(item, "returnZoneId", o.returnZoneId() == null ? null : o.returnZoneId().value());
        putIfPresent(item, "cancellationReason", o.cancellationReason());
        return item;
    }

    private static MaintenanceOrder toOrder(Map<String, AttributeValue> item) {
        Set<MaintenanceWork> work = EnumSet.noneOf(MaintenanceWork.class);
        if (item.containsKey("workPerformed")) {
            item.get("workPerformed").l().forEach(w -> work.add(MaintenanceWork.valueOf(w.s())));
        }
        return MaintenanceOrder.rehydrate(new MaintenanceOrder.Snapshot(
                new MaintenanceOrderId(item.get("orderId").s()),
                new VehicleId(item.get("vehicleId").s()),
                VehicleType.valueOf(item.get("vehicleType").s()),
                MaintenanceReason.valueOf(item.get("reason").s()),
                new ZoneId(item.get("zoneId").s()),
                MaintenanceOrderStatus.valueOf(item.get("status").s()),
                optional(item, "technicianId") == null ? null : new TechnicianId(optional(item, "technicianId")),
                work,
                optional(item, "notes"),
                optional(item, "triggeredByEventId") == null ? null : UUID.fromString(optional(item, "triggeredByEventId")),
                Instant.parse(item.get("createdAt").s()),
                instant(item, "withdrawnAt"),
                instant(item, "returnedAt"),
                optional(item, "returnZoneId") == null ? null : new ZoneId(optional(item, "returnZoneId")),
                optional(item, "cancellationReason"),
                Long.parseLong(item.get("rowVersion").n())));
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String name, String value) {
        if (value != null) {
            item.put(name, s(value));
        }
    }

    private static String optional(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    private static Instant instant(Map<String, AttributeValue> item, String name) {
        String value = optional(item, name);
        return value == null ? null : Instant.parse(value);
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    private static String encodeCursor(String sortKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sortKey.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String sortKey = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (CURSOR.matcher(sortKey).matches()) {
                return sortKey;
            }
        } catch (IllegalArgumentException ignored) {
        }
        throw new IllegalArgumentException("Invalid cursor");
    }
}
