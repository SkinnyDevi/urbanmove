package com.urbanmove.maintenancedetection.infrastructure.http;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/** JWT de servicio (rol admin) con el que la Lambda llama a maintenance; se reutiliza mientras no caduque. */
public final class ServiceTokenIssuer {
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Duration RENEW_BEFORE = Duration.ofMinutes(1);

    private final MACSigner signer;
    private final String subject;
    private final Clock clock;
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public ServiceTokenIssuer(String secret, String subject, Clock clock) {
        try {
            this.signer = new MACSigner(secret.getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException e) {
            throw new IllegalArgumentException("JWT_SECRET must have at least 32 bytes", e);
        }
        this.subject = Objects.requireNonNull(subject);
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized String bearer() {
        Instant now = clock.instant();
        if (now.isAfter(expiresAt.minus(RENEW_BEFORE))) {
            expiresAt = now.plus(TTL);
            token = sign(now);
        }
        return "Bearer " + token;
    }

    private String sign(Instant now) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(subject).claim("roles", List.of("admin"))
                .issueTime(Date.from(now)).expirationTime(Date.from(expiresAt)).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the service token", e);
        }
        return jwt.serialize();
    }
}
