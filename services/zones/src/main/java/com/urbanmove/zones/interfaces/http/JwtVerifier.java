package com.urbanmove.zones.interfaces.http;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.proc.SingleKeyJWSKeySelector;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;

/**
 * Valida los JWT emitidos por identity (AGENTS.md 3.1): firma, caducidad y roles.
 *
 * <p>Formato provisional hasta que el bloque A lo cierre: HS256 con un secreto compartido
 * (variable JWT_SECRET, nunca en el repositorio), {@code sub} = userId y los roles en el claim
 * {@code roles}. Si A elige claves asimétricas, solo cambia el constructor.
 */
public final class JwtVerifier {

    /** Usuario autenticado de la petición. */
    public record AuthenticatedUser(String userId, Set<Role> roles) {
    }

    private final DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();

    public JwtVerifier(String secret) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes for HS256");
        }
        processor.setJWSKeySelector(new SingleKeyJWSKeySelector<>(JWSAlgorithm.HS256, new SecretKeySpec(key, "HmacSHA256")));
        // Exige sub y exp, y rechaza tokens caducados.
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(null, Set.of("sub", "exp")));
    }

    /** @throws ApiProblem 401 si el token falta, está mal formado, mal firmado o caducado */
    public AuthenticatedUser verify(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw ApiProblem.unauthorized();
        }
        try {
            JWTClaimsSet claims = processor.process(authorizationHeader.substring("Bearer ".length()).trim(), null);
            List<String> claimed = claims.getStringListClaim("roles");
            Set<Role> roles = EnumSet.noneOf(Role.class);
            if (claimed != null) {
                claimed.forEach(value -> Role.fromClaim(value).ifPresent(roles::add));
            }
            return new AuthenticatedUser(claims.getSubject(), roles);
        } catch (Exception e) {
            // ParseException, BadJOSEException, JOSEException...: para el cliente todos son "token no válido".
            throw ApiProblem.unauthorized();
        }
    }
}
