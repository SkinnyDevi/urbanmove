package com.urbanmove.zones.integration;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.urbanmove.zones.ZonesApplication;
import com.urbanmove.zones.infrastructure.ServiceConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de los tests de integración y de contrato: arranca el servicio completo
 * ({@link ZonesApplication#start}) en un puerto libre contra un PostgreSQL real en Docker
 * (Testcontainers) y le hace peticiones HTTP de verdad.
 *
 * <p>Contenedor y servicio se arrancan una sola vez y los comparten todas las clases de test.
 */
public abstract class ZonesApiTestSupport {

    /** Solo para tests. El de verdad llega por la variable de entorno JWT_SECRET. */
    protected static final String TEST_JWT_SECRET = "test-only-secret-with-at-least-32-bytes!!";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    protected static final ZonesApplication APP;

    static {
        POSTGRES.start();
        APP = ZonesApplication.start(new ServiceConfig("zones-service", 0, POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword(), TEST_JWT_SECRET, "classpath:seed/zones.json"));
        Runtime.getRuntime().addShutdownHook(new Thread(APP::close));
    }

    /** Para tests que necesitan hablar con la base de datos directamente. */
    protected static ServiceConfig databaseConfig() {
        return new ServiceConfig("zones-service", 0, POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), TEST_JWT_SECRET, "classpath:seed/zones.json");
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    protected HttpResponse<String> request(String method, String path, String token, String correlationId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + APP.port() + path))
                .timeout(Duration.ofSeconds(3))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected HttpResponse<String> get(String path, String token, String correlationId) {
        return request("GET", path, token, correlationId);
    }

    protected HttpResponse<String> get(String path, String token) {
        return get(path, token, null);
    }

    /** JWT firmado como lo haría identity: sub = userId, roles = lista de roles. */
    protected static String token(List<String> roles, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("usr-test-01")
                    .claim("roles", roles)
                    .issueTime(new Date())
                    .expirationTime(Date.from(expiresAt))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String token(String... roles) {
        return token(List.of(roles), Instant.now().plusSeconds(300));
    }
}
