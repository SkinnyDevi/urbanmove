package com.urbanmove.availability.infrastructure.dynamo;

import static com.urbanmove.availability.infrastructure.dynamo.AvailabilityTable.EXPIRES_AT;
import static com.urbanmove.availability.infrastructure.dynamo.AvailabilityTable.GSI1;
import static com.urbanmove.availability.infrastructure.dynamo.AvailabilityTable.GSI1_PK;
import static com.urbanmove.availability.infrastructure.dynamo.AvailabilityTable.GSI1_SK;
import static com.urbanmove.availability.infrastructure.dynamo.AvailabilityTable.PK;

import com.urbanmove.availability.application.ConcurrentUpdateException;
import com.urbanmove.availability.application.DuplicateEventException;
import com.urbanmove.availability.application.EventMetadata;
import com.urbanmove.availability.application.EventOutcome;
import com.urbanmove.availability.application.port.ProcessedEvents;
import com.urbanmove.availability.application.port.VehicleChange;
import com.urbanmove.availability.application.port.VehiclePage;
import com.urbanmove.availability.application.port.VehicleReader;
import com.urbanmove.availability.application.port.VehicleSearch;
import com.urbanmove.availability.application.port.VehicleWriter;
import com.urbanmove.availability.application.port.ZoneSummary;
import com.urbanmove.availability.domain.VehicleAvailability;
import com.urbanmove.availability.domain.VehicleId;
import com.urbanmove.availability.domain.VehicleStatus;
import com.urbanmove.availability.domain.VehicleType;
import com.urbanmove.availability.domain.ZoneId;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** Adaptador DynamoDB de los puertos de lectura, escritura y eventos procesados. */
public final class DynamoVehicleRepository implements VehicleReader, VehicleWriter, ProcessedEvents {
    private static final Duration PROCESSED_TTL = Duration.ofDays(7);
    private static final Pattern CURSOR = Pattern.compile("^[a-z0-9-]+#(BIKE|SCOOTER)#veh-[a-z0-9-]+$");
    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private final DynamoDbClient dynamo;
    private final String table;
    private final Clock clock;

    public DynamoVehicleRepository(DynamoDbClient dynamo, String table, Clock clock) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.table = Objects.requireNonNull(table);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<VehicleAvailability> findById(VehicleId id) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table).consistentRead(true)
                .key(Map.of(PK, s(AvailabilityTable.vehicleKey(id.value()))))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(toVehicle(item));
    }

    @Override
    public boolean isProcessed(UUID eventId) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table).consistentRead(true)
                .key(Map.of(PK, s(AvailabilityTable.processedKey(eventId.toString()))))).item();
        return item != null && !item.isEmpty();
    }

    @Override
    public VehiclePage search(VehicleSearch search) {
        String after = decodeCursor(search.cursor());
        List<Map<String, AttributeValue>> items = query(search.status(), search.zoneId(), search.vehicleType(),
                after, search.limit() + 1);
        boolean more = items.size() > search.limit();
        List<Map<String, AttributeValue>> page = more ? items.subList(0, search.limit()) : items;
        String next = more ? encodeCursor(page.get(page.size() - 1).get(GSI1_SK).s()) : null;
        return new VehiclePage(page.stream().map(DynamoVehicleRepository::toVehicle).toList(), next);
    }

    @Override
    public List<ZoneSummary> summary(ZoneId zoneId, VehicleType vehicleType) {
        Map<String, Map<VehicleStatus, Integer>> counts = new TreeMap<>();
        for (VehicleStatus status : VehicleStatus.values()) {
            for (Map<String, AttributeValue> item : query(status, zoneId, vehicleType, null, Integer.MAX_VALUE)) {
                String key = item.get("zoneId").s() + "#" + item.get("vehicleType").s();
                counts.computeIfAbsent(key, k -> new EnumMap<>(VehicleStatus.class)).merge(status, 1, Integer::sum);
            }
        }
        List<ZoneSummary> result = new ArrayList<>();
        counts.forEach((key, byStatus) -> {
            String[] parts = key.split("#");
            result.add(new ZoneSummary(new ZoneId(parts[0]), VehicleType.valueOf(parts[1]), byStatus));
        });
        return result;
    }

    private List<Map<String, AttributeValue>> query(VehicleStatus status, ZoneId zoneId, VehicleType vehicleType,
                                                    String after, int wanted) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":status", s(AvailabilityTable.statusKey(status.name())));
        String keyCondition = GSI1_PK + " = :status";
        String filter = null;
        if (zoneId != null) {
            String prefix = zoneId.value() + "#" + (vehicleType == null ? "" : vehicleType.name() + "#");
            keyCondition += " AND begins_with(" + GSI1_SK + ", :prefix)";
            values.put(":prefix", s(prefix));
        } else if (vehicleType != null) {
            filter = "vehicleType = :type";
            values.put(":type", s(vehicleType.name()));
        }

        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> startKey = after == null ? null : Map.of(
                PK, s(AvailabilityTable.vehicleKey(after.substring(after.lastIndexOf('#') + 1))),
                GSI1_PK, s(AvailabilityTable.statusKey(status.name())),
                GSI1_SK, s(after));
        do {
            QueryRequest.Builder request = QueryRequest.builder()
                    .tableName(table).indexName(GSI1)
                    .keyConditionExpression(keyCondition)
                    .expressionAttributeValues(values)
                    .limit(Math.min(wanted, 500))
                    .exclusiveStartKey(startKey);
            if (filter != null) {
                request.filterExpression(filter);
            }
            QueryResponse response = dynamo.query(request.build());
            items.addAll(response.items());
            startKey = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                    ? response.lastEvaluatedKey() : null;
        } while (startKey != null && items.size() < wanted);
        return items.size() > wanted ? items.subList(0, wanted) : items;
    }

    @Override
    public void apply(List<VehicleChange> changes, EventMetadata event) {
        List<TransactWriteItem> items = new ArrayList<>();
        items.add(processedItem(event, EventOutcome.APPLIED));
        for (VehicleChange change : changes) {
            items.add(change.expectedVersion() == null
                    ? put(toItem(change.vehicle().snapshot(), event.occurredAt(), 0),
                            "attribute_not_exists(" + PK + ")", null)
                    : put(toItem(change.vehicle().snapshot(), event.occurredAt(), change.expectedVersion() + 1),
                            "rowVersion = :expected", Map.of(":expected", n(change.expectedVersion()))));
        }
        try {
            dynamo.transactWriteItems(r -> r.transactItems(items));
        } catch (TransactionCanceledException e) {
            List<CancellationReason> reasons = e.cancellationReasons();
            if (failed(reasons, 0)) {
                throw new DuplicateEventException(event.eventId());
            }
            for (int i = 1; i < items.size(); i++) {
                if (failed(reasons, i)) {
                    throw new ConcurrentUpdateException("Vehicle " + changes.get(i - 1).vehicle().vehicleId()
                            + " changed while applying event " + event.eventId());
                }
            }
            throw e;
        }
    }

    @Override
    public void markProcessed(EventMetadata event, EventOutcome outcome) {
        try {
            dynamo.putItem(r -> r.tableName(table).item(processedItem(event, outcome).put().item())
                    .conditionExpression("attribute_not_exists(" + PK + ")"));
        } catch (ConditionalCheckFailedException alreadyMarked) {
        }
    }

    @Override
    public boolean registerIfAbsent(VehicleAvailability vehicle) {
        VehicleAvailability.Snapshot s = vehicle.snapshot();
        try {
            dynamo.putItem(r -> r.tableName(table).item(toItem(s, s.updatedAt(), 0))
                    .conditionExpression("attribute_not_exists(" + PK + ")"));
            return true;
        } catch (ConditionalCheckFailedException exists) {
            return false;
        }
    }

    private TransactWriteItem processedItem(EventMetadata event, EventOutcome outcome) {
        long expiresAt = clock.instant().plus(PROCESSED_TTL).getEpochSecond();
        return put(Map.of(
                        PK, s(AvailabilityTable.processedKey(event.eventId().toString())),
                        "eventType", s(event.eventType()),
                        "outcome", s(outcome.name()),
                        "correlationId", s(event.correlationId()),
                        "processedAt", s(clock.instant().toString()),
                        EXPIRES_AT, n(expiresAt)),
                "attribute_not_exists(" + PK + ")", null);
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

    private static boolean failed(List<CancellationReason> reasons, int index) {
        return reasons != null && reasons.size() > index
                && CONDITIONAL_CHECK_FAILED.equals(reasons.get(index).code());
    }

    private static Map<String, AttributeValue> toItem(VehicleAvailability.Snapshot v, Instant updatedAt, long version) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(AvailabilityTable.vehicleKey(v.vehicleId().value())));
        item.put(GSI1_PK, s(AvailabilityTable.statusKey(v.status().name())));
        item.put(GSI1_SK, s(AvailabilityTable.locationKey(v.zoneId().value(), v.vehicleType().name(), v.vehicleId().value())));
        item.put("vehicleId", s(v.vehicleId().value()));
        item.put("vehicleType", s(v.vehicleType().name()));
        item.put("zoneId", s(v.zoneId().value()));
        item.put("status", s(v.status().name()));
        item.put("batteryLow", AttributeValue.fromBool(v.batteryLow()));
        item.put("updatedAt", s(updatedAt.toString()));
        item.put("rowVersion", n(version));
        return item;
    }

    private static VehicleAvailability toVehicle(Map<String, AttributeValue> item) {
        return VehicleAvailability.rehydrate(new VehicleAvailability.Snapshot(
                new VehicleId(item.get("vehicleId").s()),
                VehicleType.valueOf(item.get("vehicleType").s()),
                new ZoneId(item.get("zoneId").s()),
                VehicleStatus.valueOf(item.get("status").s()),
                item.containsKey("batteryLow") && item.get("batteryLow").bool(),
                Instant.parse(item.get("updatedAt").s()),
                Long.parseLong(item.get("rowVersion").n())));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue n(long value) {
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
