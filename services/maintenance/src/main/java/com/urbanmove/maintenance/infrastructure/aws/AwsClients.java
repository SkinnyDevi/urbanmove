package com.urbanmove.maintenance.infrastructure.aws;

import com.urbanmove.maintenance.infrastructure.ServiceConfig;
import java.net.URI;
import java.time.Duration;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Clientes del AWS SDK v2. Son caros de crear y seguros entre hilos: se crean una vez en el
 * arranque y se comparten (AGENTS.md 5.1).
 *
 * <p>Toda llamada tiene timeout explícito (AGENTS.md 6.4): 2 s por intento y 3 s en total. Los
 * reintentos los hace el SDK con espera exponencial y jitter (su estrategia estándar).
 */
public final class AwsClients {

    public static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(2);
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(3);

    private AwsClients() {
    }

    public static DynamoDbClient dynamoDb(ServiceConfig config, SdkHttpClient http) {
        return configure(DynamoDbClient.builder(), config).httpClient(http).build();
    }

    public static SnsClient sns(ServiceConfig config, SdkHttpClient http) {
        return configure(SnsClient.builder(), config).httpClient(http).build();
    }

    /** Un único cliente HTTP compartido por todos los clientes de AWS. */
    public static SdkHttpClient httpClient() {
        return UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(2))
                .socketTimeout(CALL_TIMEOUT)
                .build();
    }

    private static <B extends AwsClientBuilder<B, ?>> B configure(B builder, ServiceConfig config) {
        builder.region(Region.of(config.awsRegion()))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(ATTEMPT_TIMEOUT)
                        .apiCallTimeout(CALL_TIMEOUT)
                        .build());
        config.endpoint().ifPresent(endpoint -> builder.endpointOverride(URI.create(endpoint)));
        return builder;
    }
}
