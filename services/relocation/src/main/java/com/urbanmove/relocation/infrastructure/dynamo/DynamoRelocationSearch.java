package com.urbanmove.relocation.infrastructure.dynamo;

import static com.urbanmove.relocation.infrastructure.dynamo.RelocationOrderItems.s;
import static com.urbanmove.relocation.infrastructure.dynamo.RelocationTable.GSI1;
import static com.urbanmove.relocation.infrastructure.dynamo.RelocationTable.GSI1_PK;
import static com.urbanmove.relocation.infrastructure.dynamo.RelocationTable.GSI1_SK;

import com.urbanmove.relocation.application.port.OrderPage;
import com.urbanmove.relocation.application.port.OrderSearch;
import com.urbanmove.relocation.application.port.RelocationOrderSearch;
import com.urbanmove.relocation.domain.RelocationOrderStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/** Bandeja de órdenes: una consulta al índice por estado, ordenadas por plazo y paginadas con cursor. */
public final class DynamoRelocationSearch implements RelocationOrderSearch {
    private static final Pattern CURSOR = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z#rel-[a-z0-9-]+$");

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoRelocationSearch(DynamoDbClient dynamo, String table) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.table = Objects.requireNonNull(table);
    }

    @Override
    public OrderPage search(OrderSearch search) {
        String after = decode(search.cursor());
        List<Map<String, AttributeValue>> found = new ArrayList<>();
        for (RelocationOrderStatus status : search.statuses()) {
            found.addAll(queryStatus(status, search, after, search.limit() + 1));
        }
        found.sort(Comparator.comparing(item -> item.get(GSI1_SK).s()));
        boolean more = found.size() > search.limit();
        List<Map<String, AttributeValue>> page = more ? found.subList(0, search.limit()) : found;
        String next = more ? encode(page.get(page.size() - 1).get(GSI1_SK).s()) : null;
        return new OrderPage(page.stream().map(RelocationOrderItems::toOrder).toList(), next);
    }

    private List<Map<String, AttributeValue>> queryStatus(RelocationOrderStatus status, OrderSearch search,
                                                          String after, int wanted) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":status", s("STATUS#" + status.name()));
        String keyCondition = GSI1_PK + " = :status";
        if (after != null) {
            keyCondition += " AND " + GSI1_SK + " > :after";
            values.put(":after", s(after));
        }
        StringJoiner filter = new StringJoiner(" AND ");
        addFilter(filter, values, "sourceZoneId", search.sourceZoneId() == null ? null : search.sourceZoneId().value());
        addFilter(filter, values, "targetZoneId", search.targetZoneId() == null ? null : search.targetZoneId().value());
        addFilter(filter, values, "assigneeId", search.assigneeId() == null ? null : search.assigneeId().value());

        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder().tableName(table).indexName(GSI1)
                    .keyConditionExpression(keyCondition).expressionAttributeValues(values)
                    .limit(wanted).exclusiveStartKey(startKey);
            if (filter.length() > 0) {
                request.filterExpression(filter.toString());
            }
            QueryResponse response = dynamo.query(request.build());
            items.addAll(response.items());
            startKey = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                    ? response.lastEvaluatedKey() : null;
        } while (startKey != null && items.size() < wanted);
        return items.size() > wanted ? items.subList(0, wanted) : items;
    }

    private static void addFilter(StringJoiner filter, Map<String, AttributeValue> values, String attribute, String value) {
        if (value != null) {
            filter.add(attribute + " = :" + attribute);
            values.put(":" + attribute, s(value));
        }
    }

    private static String encode(String sortKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sortKey.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String cursor) {
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
