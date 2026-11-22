package com.urbanmove.maintenance.infrastructure.dynamo;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/** Diseño y creación de la tabla DynamoDB de maintenance. */
public final class MaintenanceTable {
    public static final String PK = "pk";
    public static final String GSI1 = "gsi1";
    public static final String GSI1_PK = "gsi1pk";
    public static final String GSI1_SK = "gsi1sk";

    public static final String EXPIRES_AT = "expiresAt";

    public static final String OUTBOX_PARTITION = "OUTBOX";

    private static final Logger log = LoggerFactory.getLogger(MaintenanceTable.class);
    private static final Duration SETUP_TIMEOUT = Duration.ofSeconds(60);

    private MaintenanceTable() {
    }

    public static String orderKey(String orderId) {
        return "ORDER#" + orderId;
    }

    public static String vehicleLockKey(String vehicleId) {
        return "VEHICLE#" + vehicleId + "#ACTIVE";
    }

    public static String idempotencyKey(String key) {
        return "IDEMPOTENCY#" + key;
    }

    public static String outboxKey(String eventId) {
        return "OUTBOX#" + eventId;
    }

    public static void createIfMissing(DynamoDbClient dynamo, String tableName) {
        try {
            dynamo.describeTable(r -> r.tableName(tableName).overrideConfiguration(c -> c.apiCallTimeout(SETUP_TIMEOUT)));
            return;
        } catch (ResourceNotFoundException notFound) {
        }
        try {
            dynamo.createTable(r -> r
                    .tableName(tableName)
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(
                            attribute(PK), attribute(GSI1_PK), attribute(GSI1_SK))
                    .keySchema(KeySchemaElement.builder().attributeName(PK).keyType(KeyType.HASH).build())
                    .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                            .indexName(GSI1)
                            .keySchema(
                                    KeySchemaElement.builder().attributeName(GSI1_PK).keyType(KeyType.HASH).build(),
                                    KeySchemaElement.builder().attributeName(GSI1_SK).keyType(KeyType.RANGE).build())
                            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                            .build())
                    .overrideConfiguration(c -> c.apiCallTimeout(SETUP_TIMEOUT)));
        } catch (ResourceInUseException alreadyCreated) {
        }
        dynamo.waiter().waitUntilTableExists(r -> r.tableName(tableName));
        dynamo.updateTimeToLive(r -> r.tableName(tableName)
                .timeToLiveSpecification(t -> t.attributeName(EXPIRES_AT).enabled(true)));
        log.atInfo().addKeyValue("table", tableName).log("DynamoDB table created");
    }

    private static AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder().attributeName(name).attributeType(ScalarAttributeType.S).build();
    }
}
