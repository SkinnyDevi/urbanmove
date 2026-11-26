package com.urbanmove.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.inventory.application.ApplyInventoryEventUseCase;
import com.urbanmove.inventory.application.GetVehicleUseCase;
import com.urbanmove.inventory.application.ListVehiclesUseCase;
import com.urbanmove.inventory.application.RetireVehicleUseCase;
import com.urbanmove.inventory.application.SeedFleetUseCase;
import com.urbanmove.inventory.infrastructure.ServiceConfig;
import com.urbanmove.inventory.infrastructure.aws.AwsClients;
import com.urbanmove.inventory.infrastructure.dynamo.DynamoAssetEventWriter;
import com.urbanmove.inventory.infrastructure.dynamo.DynamoAssetSearch;
import com.urbanmove.inventory.infrastructure.dynamo.DynamoInventoryReader;
import com.urbanmove.inventory.infrastructure.dynamo.DynamoRetirementWriter;
import com.urbanmove.inventory.infrastructure.dynamo.InventoryTable;
import com.urbanmove.inventory.infrastructure.events.EventEnvelopes;
import com.urbanmove.inventory.infrastructure.events.EventTranslator;
import com.urbanmove.inventory.infrastructure.events.SnsOutboxPublisher;
import com.urbanmove.inventory.infrastructure.events.SqsEventConsumer;
import com.urbanmove.inventory.infrastructure.health.DynamoDbHealthCheck;
import com.urbanmove.inventory.infrastructure.health.SnsHealthCheck;
import com.urbanmove.inventory.infrastructure.health.SqsHealthCheck;
import com.urbanmove.inventory.infrastructure.seed.JsonFleetSeedSource;
import com.urbanmove.inventory.interfaces.http.HealthCheckHandler;
import com.urbanmove.inventory.interfaces.http.InventoryHttpApi;
import com.urbanmove.inventory.interfaces.http.JwtVerifier;
import com.urbanmove.inventory.interfaces.http.VehicleHandlers;
import io.javalin.Javalin;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Punto de entrada y composición del servicio inventory. */
public final class InventoryApplication implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(InventoryApplication.class);

    private final Javalin http;
    private final SqsEventConsumer consumer;
    private final Thread consumerThread;
    private final ScheduledExecutorService scheduler;
    private final SnsOutboxPublisher publisher;
    private final List<AutoCloseable> resources;

    private InventoryApplication(Javalin http, SqsEventConsumer consumer, Thread consumerThread,
                                 ScheduledExecutorService scheduler, SnsOutboxPublisher publisher,
                                 List<AutoCloseable> resources) {
        this.http = http;
        this.consumer = consumer;
        this.consumerThread = consumerThread;
        this.scheduler = scheduler;
        this.publisher = publisher;
        this.resources = resources;
    }

    public static void main(String[] args) {
        InventoryApplication app = start(ServiceConfig.fromEnvironment(System.getenv()));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));
    }

    public static InventoryApplication start(ServiceConfig config) {
        log.atInfo().addKeyValue("config", config.toString()).log("Starting");
        ObjectMapper json = new ObjectMapper();
        Clock clock = Clock.systemUTC();

        SdkHttpClient awsHttp = AwsClients.httpClient();
        SdkHttpClient sqsHttp = AwsClients.longPollingHttpClient(config.pollWaitSeconds());
        DynamoDbClient dynamo = AwsClients.dynamoDb(config, awsHttp);
        SnsClient sns = AwsClients.sns(config, awsHttp);
        SqsClient sqs = AwsClients.sqs(config, sqsHttp);
        if (config.createTable()) {
            InventoryTable.createIfMissing(dynamo, config.tableName());
        }
        DynamoInventoryReader reader = new DynamoInventoryReader(dynamo, config.tableName());
        DynamoAssetEventWriter eventWriter = new DynamoAssetEventWriter(dynamo, config.tableName(), clock);
        SnsOutboxPublisher publisher = new SnsOutboxPublisher(dynamo, sns, config.tableName(), config.eventsTopicArn());
        config.seedFile().ifPresent(file ->
                new SeedFleetUseCase(new JsonFleetSeedSource(file, json), eventWriter, clock).execute());

        RetireVehicleUseCase retire = new RetireVehicleUseCase(reader,
                new DynamoRetirementWriter(dynamo, config.tableName(), new EventEnvelopes(json), clock),
                reader, publisher, UUID::randomUUID, clock);
        Javalin http = InventoryHttpApi.create(json, new JwtVerifier(config.jwtSecret()),
                new HealthCheckHandler(config.serviceName(), List.of(
                        new DynamoDbHealthCheck(dynamo, config.tableName()),
                        new SnsHealthCheck(sns, config.eventsTopicArn()),
                        new SqsHealthCheck(sqs, config.eventsQueueUrl()))),
                new VehicleHandlers(new ListVehiclesUseCase(new DynamoAssetSearch(dynamo, config.tableName())),
                        new GetVehicleUseCase(reader), retire, json));
        http.start(config.port());

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().name("outbox-relay").daemon(true).unstarted(r));
        publisher.start(scheduler, config.outboxRelaySeconds());
        SqsEventConsumer consumer = new SqsEventConsumer(sqs, config.eventsQueueUrl(), config.pollWaitSeconds(),
                new EventTranslator(json), new ApplyInventoryEventUseCase(reader, eventWriter, reader));
        Thread consumerThread = Thread.ofPlatform().name("sqs-consumer").daemon(true).start(consumer);

        log.atInfo().addKeyValue("port", http.port()).log("Inventory service started");
        return new InventoryApplication(http, consumer, consumerThread, scheduler, publisher,
                List.of(dynamo, sns, sqs, awsHttp, sqsHttp));
    }

    public int port() {
        return http.port();
    }

    public int relayPendingEvents() {
        return publisher.relayPending();
    }

    @Override
    public void close() {
        consumer.stop();
        http.stop();
        scheduler.shutdownNow();
        try {
            consumerThread.join(Duration.ofSeconds(25));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                log.warn("Error closing {}: {}", resource, e.getMessage());
            }
        }
        log.info("Inventory service stopped");
    }
}
