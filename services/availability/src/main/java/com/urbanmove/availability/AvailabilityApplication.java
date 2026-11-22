package com.urbanmove.availability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.availability.application.ApplyVehicleEventUseCase;
import com.urbanmove.availability.application.GetVehicleUseCase;
import com.urbanmove.availability.application.ListVehiclesUseCase;
import com.urbanmove.availability.application.SeedFleetUseCase;
import com.urbanmove.availability.application.ZoneSummaryUseCase;
import com.urbanmove.availability.infrastructure.ServiceConfig;
import com.urbanmove.availability.infrastructure.aws.AwsClients;
import com.urbanmove.availability.infrastructure.dynamo.AvailabilityTable;
import com.urbanmove.availability.infrastructure.dynamo.DynamoVehicleRepository;
import com.urbanmove.availability.infrastructure.events.EventTranslator;
import com.urbanmove.availability.infrastructure.events.SqsEventConsumer;
import com.urbanmove.availability.infrastructure.health.DynamoDbHealthCheck;
import com.urbanmove.availability.infrastructure.health.SqsHealthCheck;
import com.urbanmove.availability.infrastructure.seed.JsonFleetSeedSource;
import com.urbanmove.availability.interfaces.http.AvailabilityHttpApi;
import com.urbanmove.availability.interfaces.http.HealthCheckHandler;
import com.urbanmove.availability.interfaces.http.JwtVerifier;
import com.urbanmove.availability.interfaces.http.VehicleHandlers;
import io.javalin.Javalin;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Punto de entrada y composición del servicio. */
public final class AvailabilityApplication implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(AvailabilityApplication.class);

    private final Javalin http;
    private final SqsEventConsumer consumer;
    private final Thread consumerThread;
    private final SdkHttpClient awsHttp;
    private final SdkHttpClient sqsHttp;
    private final DynamoDbClient dynamo;
    private final SqsClient sqs;

    private AvailabilityApplication(Javalin http, SqsEventConsumer consumer, Thread consumerThread,
                                    SdkHttpClient awsHttp, SdkHttpClient sqsHttp, DynamoDbClient dynamo, SqsClient sqs) {
        this.http = http;
        this.consumer = consumer;
        this.consumerThread = consumerThread;
        this.awsHttp = awsHttp;
        this.sqsHttp = sqsHttp;
        this.dynamo = dynamo;
        this.sqs = sqs;
    }

    public static void main(String[] args) {
        AvailabilityApplication app = start(ServiceConfig.fromEnvironment(System.getenv()));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));
    }

    public static AvailabilityApplication start(ServiceConfig config) {
        log.atInfo().addKeyValue("config", config.toString()).log("Starting");
        ObjectMapper json = new ObjectMapper();
        Clock clock = Clock.systemUTC();

        SdkHttpClient awsHttp = AwsClients.httpClient();
        DynamoDbClient dynamo = AwsClients.dynamoDb(config, awsHttp);

        SdkHttpClient sqsHttp = AwsClients.longPollingHttpClient(config.pollWaitSeconds());
        SqsClient sqs = AwsClients.sqs(config, sqsHttp);
        if (config.createTable()) {
            AvailabilityTable.createIfMissing(dynamo, config.tableName());
        }
        DynamoVehicleRepository repository = new DynamoVehicleRepository(dynamo, config.tableName(), clock);

        config.fleetSeed().ifPresent(seed ->
                new SeedFleetUseCase(new JsonFleetSeedSource(seed, json), repository, clock).execute());
        ApplyVehicleEventUseCase apply = new ApplyVehicleEventUseCase(repository, repository, repository);

        Javalin http = AvailabilityHttpApi.create(
                json,
                new JwtVerifier(config.jwtSecret()),
                new HealthCheckHandler(config.serviceName(), List.of(
                        new DynamoDbHealthCheck(dynamo, config.tableName()),
                        new SqsHealthCheck(sqs, config.eventsQueueUrl()))),
                new VehicleHandlers(new ListVehiclesUseCase(repository), new GetVehicleUseCase(repository),
                        new ZoneSummaryUseCase(repository), clock));
        http.start(config.port());

        SqsEventConsumer consumer = new SqsEventConsumer(sqs, config.eventsQueueUrl(), config.pollWaitSeconds(),
                new EventTranslator(json), apply);
        Thread consumerThread = Thread.ofPlatform().name("sqs-consumer").daemon(true).start(consumer);

        log.atInfo().addKeyValue("port", http.port()).log("Availability service started");
        return new AvailabilityApplication(http, consumer, consumerThread, awsHttp, sqsHttp, dynamo, sqs);
    }

    public int port() {
        return http.port();
    }

    @Override
    public void close() {
        consumer.stop();
        http.stop();
        try {
            consumerThread.join(java.time.Duration.ofSeconds(25));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dynamo.close();
        sqs.close();
        awsHttp.close();
        sqsHttp.close();
        log.info("Availability service stopped");
    }
}
