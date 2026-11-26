package com.urbanmove.relocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.relocation.application.CreateOrderFromHotspotUseCase;
import com.urbanmove.relocation.application.GetRelocationOrderUseCase;
import com.urbanmove.relocation.application.ListRelocationOrdersUseCase;
import com.urbanmove.relocation.application.PerformRelocationActionUseCase;
import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.infrastructure.ServiceConfig;
import com.urbanmove.relocation.infrastructure.aws.AwsClients;
import com.urbanmove.relocation.infrastructure.dynamo.DynamoRelocationRepository;
import com.urbanmove.relocation.infrastructure.dynamo.DynamoRelocationSearch;
import com.urbanmove.relocation.infrastructure.dynamo.RelocationTable;
import com.urbanmove.relocation.infrastructure.events.EventEnvelopes;
import com.urbanmove.relocation.infrastructure.events.HotspotTranslator;
import com.urbanmove.relocation.infrastructure.events.SnsOutboxPublisher;
import com.urbanmove.relocation.infrastructure.events.SqsHotspotConsumer;
import com.urbanmove.relocation.infrastructure.health.DynamoDbHealthCheck;
import com.urbanmove.relocation.infrastructure.health.SnsHealthCheck;
import com.urbanmove.relocation.infrastructure.health.SqsHealthCheck;
import com.urbanmove.relocation.interfaces.http.ActionHandlers;
import com.urbanmove.relocation.interfaces.http.HealthCheckHandler;
import com.urbanmove.relocation.interfaces.http.JwtVerifier;
import com.urbanmove.relocation.interfaces.http.QueryHandlers;
import com.urbanmove.relocation.interfaces.http.RelocationHttpApi;
import io.javalin.Javalin;
import java.time.Clock;
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

/** Punto de entrada y composición del servicio relocation. */
public final class RelocationApplication implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RelocationApplication.class);

    private final Javalin http;
    private final SqsHotspotConsumer consumer;
    private final Thread consumerThread;
    private final ScheduledExecutorService scheduler;
    private final SnsOutboxPublisher publisher;
    private final List<AutoCloseable> resources;

    private RelocationApplication(Javalin http, SqsHotspotConsumer consumer, Thread consumerThread,
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
        RelocationApplication app = start(ServiceConfig.fromEnvironment(System.getenv()));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));
    }

    public static RelocationApplication start(ServiceConfig config) {
        log.atInfo().addKeyValue("config", config.toString()).log("Starting");
        ObjectMapper json = new ObjectMapper();
        Clock clock = Clock.systemUTC();

        SdkHttpClient awsHttp = AwsClients.httpClient();
        SdkHttpClient sqsHttp = AwsClients.longPollingHttpClient(config.pollWaitSeconds());
        DynamoDbClient dynamo = AwsClients.dynamoDb(config, awsHttp);
        SnsClient sns = AwsClients.sns(config, awsHttp);
        SqsClient sqs = AwsClients.sqs(config, sqsHttp);
        if (config.createTable()) {
            RelocationTable.createIfMissing(dynamo, config.tableName());
        }
        DynamoRelocationRepository repository =
                new DynamoRelocationRepository(dynamo, config.tableName(), new EventEnvelopes(json), clock);
        SnsOutboxPublisher publisher = new SnsOutboxPublisher(dynamo, sns, config.tableName(), config.eventsTopicArn());

        CreateOrderFromHotspotUseCase createOrder = new CreateOrderFromHotspotUseCase(repository, repository,
                repository, publisher, () -> new RelocationOrderId("rel-" + UUID.randomUUID()), UUID::randomUUID,
                clock, config.orderDeadline());
        PerformRelocationActionUseCase act = new PerformRelocationActionUseCase(repository, repository, repository,
                publisher, UUID::randomUUID, clock);

        Javalin http = RelocationHttpApi.create(json, new JwtVerifier(config.jwtSecret()),
                new HealthCheckHandler(config.serviceName(), List.of(
                        new DynamoDbHealthCheck(dynamo, config.tableName()),
                        new SnsHealthCheck(sns, config.eventsTopicArn()),
                        new SqsHealthCheck(sqs, config.hotspotQueueUrl()))),
                new QueryHandlers(new ListRelocationOrdersUseCase(new DynamoRelocationSearch(dynamo, config.tableName())),
                        new GetRelocationOrderUseCase(repository)),
                new ActionHandlers(act, json));
        http.start(config.port());

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().name("outbox-relay").daemon(true).unstarted(r));
        publisher.start(scheduler, config.outboxRelaySeconds());
        SqsHotspotConsumer consumer = new SqsHotspotConsumer(sqs, config.hotspotQueueUrl(), config.pollWaitSeconds(),
                new HotspotTranslator(json), createOrder);
        Thread consumerThread = Thread.ofPlatform().name("sqs-consumer").daemon(true).start(consumer);

        log.atInfo().addKeyValue("port", http.port()).log("Relocation service started");
        return new RelocationApplication(http, consumer, consumerThread, scheduler, publisher,
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
            consumerThread.join(java.time.Duration.ofSeconds(25));
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
        log.info("Relocation service stopped");
    }
}
