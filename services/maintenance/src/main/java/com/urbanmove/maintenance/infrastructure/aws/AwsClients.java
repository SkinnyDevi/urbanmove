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

/** Clientes del AWS SDK con timeouts explícitos y reintentos. */
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
