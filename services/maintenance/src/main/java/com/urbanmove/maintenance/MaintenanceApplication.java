package com.urbanmove.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.maintenance.application.GetMaintenanceOrderUseCase;
import com.urbanmove.maintenance.application.ListMaintenanceOrdersUseCase;
import com.urbanmove.maintenance.application.OpenMaintenanceOrderUseCase;
import com.urbanmove.maintenance.application.PerformOrderActionUseCase;
import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.infrastructure.ServiceConfig;
import com.urbanmove.maintenance.infrastructure.aws.AwsClients;
import com.urbanmove.maintenance.infrastructure.dynamo.DynamoMaintenanceRepository;
import com.urbanmove.maintenance.infrastructure.dynamo.MaintenanceTable;
import com.urbanmove.maintenance.infrastructure.events.EventEnvelopes;
import com.urbanmove.maintenance.infrastructure.events.SnsOutboxPublisher;
import com.urbanmove.maintenance.infrastructure.health.DynamoDbHealthCheck;
import com.urbanmove.maintenance.infrastructure.health.SnsHealthCheck;
import com.urbanmove.maintenance.interfaces.http.HealthCheckHandler;
import com.urbanmove.maintenance.interfaces.http.JwtVerifier;
import com.urbanmove.maintenance.interfaces.http.MaintenanceHttpApi;
import com.urbanmove.maintenance.interfaces.http.OrderHandlers;
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

/**
 * Punto de entrada y composición del servicio (AGENTS.md 4.2, DIP).
 *
 * <p>Aquí, y solo aquí, se crean los adaptadores y se inyectan por constructor en los casos de uso.
 * No hay framework de inyección: todo el grafo de objetos se lee en {@link #start} (ADR-003).
 */
public final class MaintenanceApplication implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceApplication.class);

    private final Javalin http;
    private final ScheduledExecutorService scheduler;
    private final SdkHttpClient awsHttp;
    private final DynamoDbClient dynamo;
    private final SnsClient sns;
    private final SnsOutboxPublisher publisher;

    private MaintenanceApplication(Javalin http, ScheduledExecutorService scheduler, SdkHttpClient awsHttp,
                                   DynamoDbClient dynamo, SnsClient sns, SnsOutboxPublisher publisher) {
        this.http = http;
        this.scheduler = scheduler;
        this.awsHttp = awsHttp;
        this.dynamo = dynamo;
        this.sns = sns;
        this.publisher = publisher;
    }

    public static void main(String[] args) {
        MaintenanceApplication app = start(ServiceConfig.fromEnvironment(System.getenv()));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));
    }

    /** Arranca el servicio completo. Lo usan main y los tests de integración. */
    public static MaintenanceApplication start(ServiceConfig config) {
        log.atInfo().addKeyValue("config", config.toString()).log("Starting");
        ObjectMapper json = new ObjectMapper();
        Clock clock = Clock.systemUTC();

        // infrastructure: clientes de AWS (uno de cada, compartidos) y adaptadores
        SdkHttpClient awsHttp = AwsClients.httpClient();
        DynamoDbClient dynamo = AwsClients.dynamoDb(config, awsHttp);
        SnsClient sns = AwsClients.sns(config, awsHttp);
        if (config.createTable()) {
            MaintenanceTable.createIfMissing(dynamo, config.tableName());
        }
        DynamoMaintenanceRepository repository =
                new DynamoMaintenanceRepository(dynamo, config.tableName(), new EventEnvelopes(json), clock);
        SnsOutboxPublisher publisher = new SnsOutboxPublisher(dynamo, sns, config.tableName(), config.eventsTopicArn());

        // application
        OpenMaintenanceOrderUseCase open = new OpenMaintenanceOrderUseCase(repository, repository, repository,
                () -> new MaintenanceOrderId("mnt-" + UUID.randomUUID()), clock);
        PerformOrderActionUseCase act = new PerformOrderActionUseCase(repository, repository, repository,
                publisher, UUID::randomUUID, clock);
        GetMaintenanceOrderUseCase get = new GetMaintenanceOrderUseCase(repository);
        ListMaintenanceOrdersUseCase list = new ListMaintenanceOrdersUseCase(repository);

        // interfaces
        Javalin http = MaintenanceHttpApi.create(
                json,
                new JwtVerifier(config.jwtSecret()),
                new HealthCheckHandler(config.serviceName(), List.of(
                        new DynamoDbHealthCheck(dynamo, config.tableName()),
                        new SnsHealthCheck(sns, config.eventsTopicArn()))),
                new OrderHandlers(open, act, get, list, json));
        http.start(config.port());

        // Reintento periódico de los eventos que no se pudieron publicar
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().name("outbox-relay").daemon(true).unstarted(r));
        publisher.start(scheduler, config.outboxRelaySeconds());

        log.atInfo().addKeyValue("port", http.port()).log("Maintenance service started");
        return new MaintenanceApplication(http, scheduler, awsHttp, dynamo, sns, publisher);
    }

    /** Puerto real (útil con port = 0 en los tests). */
    public int port() {
        return http.port();
    }

    /** Publica ya los eventos pendientes, sin esperar al reintento periódico (tests). */
    public int relayPendingEvents() {
        return publisher.relayPending();
    }

    @Override
    public void close() {
        http.stop();
        scheduler.shutdownNow();
        dynamo.close();
        sns.close();
        awsHttp.close();
        log.info("Maintenance service stopped");
    }
}
