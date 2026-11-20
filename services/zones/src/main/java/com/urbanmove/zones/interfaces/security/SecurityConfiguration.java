package com.urbanmove.zones.interfaces.security;

import com.urbanmove.zones.interfaces.web.Problems;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validación de los JWT emitidos por identity (AGENTS.md 3.1).
 *
 * <p>Formato provisional hasta que el bloque A lo cierre: HS256 con un secreto compartido
 * (variable de entorno JWT_SECRET, nunca en el repositorio) y los roles en el claim {@code roles}
 * (user, maintenance, admin). Si A elige claves asimétricas, solo cambia {@link #jwtDecoder}.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final String[] ALL_ROLES = {"user", "maintenance", "admin"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/zones", "/zones/*").hasAnyRole(ALL_ROLES)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesFromClaim()))
                        .authenticationEntryPoint((request, response, e) ->
                                writeProblem(response, json, HttpStatus.UNAUTHORIZED, "/problems/unauthorized",
                                        "Missing or invalid JWT"))
                        .accessDeniedHandler((request, response, e) ->
                                writeProblem(response, json, HttpStatus.FORBIDDEN, "/problems/forbidden",
                                        "Your role is not allowed to perform this operation")))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                writeProblem(response, json, HttpStatus.UNAUTHORIZED, "/problems/unauthorized",
                                        "Missing or invalid JWT"))
                        .accessDeniedHandler((request, response, e) ->
                                writeProblem(response, json, HttpStatus.FORBIDDEN, "/problems/forbidden",
                                        "Your role is not allowed to perform this operation")))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${urbanmove.security.jwt-secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes for HS256");
        }
        SecretKey key = new SecretKeySpec(bytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static JwtAuthenticationConverter rolesFromClaim() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static void writeProblem(HttpServletResponse response, JsonMapper json, HttpStatus status,
                                     String type, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(Problems.MEDIA_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(Problems.asMap(Problems.of(status, type, detail))));
    }
}
