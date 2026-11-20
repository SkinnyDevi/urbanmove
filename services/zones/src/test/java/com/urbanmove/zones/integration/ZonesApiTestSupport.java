package com.urbanmove.zones.integration;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de los tests de integración y de contrato: arranca el servicio completo en un puerto
 * aleatorio contra un PostgreSQL real en Docker (Testcontainers) y le hace peticiones HTTP de verdad.
 *
 * <p>El contenedor se arranca una sola vez y lo comparten todas las clases de test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class ZonesApiTestSupport {

    /** Solo para tests. El de verdad llega por la variable de entorno JWT_SECRET. */
    protected static final String TEST_JWT_SECRET = "test-only-secret-with-at-least-32-bytes!!";

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("urbanmove.security.jwt-secret", () -> TEST_JWT_SECRET);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @LocalServerPort
    protected int port;

    protected HttpResponse<String> get(String path, String token, String correlationId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(3))
                .GET();
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
            jwt.sign(new MACSigner(TEST_JWT_SECRET.getBytes()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    protected static String token(String... roles) {
        return token(List.of(roles), Instant.now().plusSeconds(300));
    }
}
