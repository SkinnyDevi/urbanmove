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

/**
 * Diseño de la tabla única del servicio (base de datos propia, ADR-001).
 *
 * <pre>
 * pk                          | qué es                                     | gsi1pk / gsi1sk
 * ----------------------------+--------------------------------------------+-------------------------------
 * ORDER#mnt-...               | la orden                                   | STATUS#&lt;estado&gt; / &lt;createdAt&gt;#&lt;id&gt;
 * VEHICLE#veh-...#ACTIVE      | candado: el vehículo tiene una orden activa | -
 * IDEMPOTENCY#&lt;uuid&gt;          | petición ya procesada (caduca en 24 h)     | -
 * OUTBOX#&lt;eventId&gt;            | evento guardado, pendiente de publicar     | OUTBOX / &lt;occurredAt&gt;#&lt;eventId&gt;
 * </pre>
 *
 * El índice {@code gsi1} sirve para dos consultas: las órdenes de un estado ordenadas por fecha
 * (bandeja de la app) y los eventos pendientes de publicar. Es disperso (práctica 6): los
 * candados y los registros de idempotencia no tienen gsi1pk y no ocupan sitio en él.
 */
public final class MaintenanceTable {

    public static final String PK = "pk";
    public static final String GSI1 = "gsi1";
    public static final String GSI1_PK = "gsi1pk";
    public static final String GSI1_SK = "gsi1sk";
    /** Atributo TTL: DynamoDB borra solo los registros de idempotencia caducados. */
    public static final String EXPIRES_AT = "expiresAt";
    /** Valor de gsi1pk de los eventos pendientes de publicar. */
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

    /**
     * Crea la tabla si no existe. Solo en local (DYNAMODB_CREATE_TABLE=true) y en los tests:
     * en AWS la crea Terraform. Es el equivalente a las migraciones de una base de datos SQL.
     */
    public static void createIfMissing(DynamoDbClient dynamo, String tableName) {
        try {
            dynamo.describeTable(r -> r.tableName(tableName).overrideConfiguration(c -> c.apiCallTimeout(SETUP_TIMEOUT)));
            return;
        } catch (ResourceNotFoundException notFound) {
            // No existe: se crea a continuación.
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
            // Otra réplica la creó a la vez.
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
