package com.urbanmove.inventory.infrastructure.dynamo;

import static com.urbanmove.inventory.infrastructure.dynamo.DynamoItems.s;
import static com.urbanmove.inventory.infrastructure.dynamo.InventoryTable.GSI1;
import static com.urbanmove.inventory.infrastructure.dynamo.InventoryTable.GSI1_PK;
import static com.urbanmove.inventory.infrastructure.dynamo.InventoryTable.GSI1_SK;

import com.urbanmove.inventory.application.port.AssetPage;
import com.urbanmove.inventory.application.port.AssetSearch;
import com.urbanmove.inventory.application.port.VehicleAssetSearch;
import com.urbanmove.inventory.domain.AdminStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/** Listado de activos: una consulta al índice por estado administrativo, ordenada por tipo e id. */
public final class DynamoAssetSearch implements VehicleAssetSearch {
    private static final Pattern CURSOR = Pattern.compile("^(BIKE|SCOOTER)#veh-[a-z0-9-]+$");

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoAssetSearch(DynamoDbClient dynamo, String table) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.table = Objects.requireNonNull(table);
    }

    @Override
    public AssetPage search(AssetSearch search) {
        String after = decode(search.cursor());
        Set<AdminStatus> statuses = search.adminStatus() == null
                ? EnumSet.allOf(AdminStatus.class) : EnumSet.of(search.adminStatus());
        List<Map<String, AttributeValue>> found = new ArrayList<>();
        for (AdminStatus status : statuses) {
            found.addAll(query(status, search, after, search.limit() + 1));
        }
        found.sort(Comparator.comparing(item -> item.get(GSI1_SK).s()));
        boolean more = found.size() > search.limit();
        List<Map<String, AttributeValue>> page = more ? found.subList(0, search.limit()) : found;
        String next = more ? encode(page.get(page.size() - 1).get(GSI1_SK).s()) : null;
        return new AssetPage(page.stream().map(AssetItems::toAsset).toList(), next);
    }

    private List<Map<String, AttributeValue>> query(AdminStatus status, AssetSearch search, String after, int wanted) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":status", s(InventoryTable.adminStatusKey(status.name())));
        String condition = GSI1_PK + " = :status";
        if (search.vehicleType() != null) {
            condition += " AND begins_with(" + GSI1_SK + ", :type)";
            values.put(":type", s(search.vehicleType().name() + "#"));
        }
        String filter = null;
        if (after != null) {
            filter = GSI1_SK + " > :after";
            values.put(":after", s(after));
        }
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            QueryRequest.Builder request = QueryRequest.builder().tableName(table).indexName(GSI1)
                    .keyConditionExpression(condition).expressionAttributeValues(values).exclusiveStartKey(startKey);
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
