package com.urbanmove.zones.interfaces.web;

import com.urbanmove.zones.infrastructure.logging.UrbanMoveLogFormatter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Trazabilidad de extremo a extremo (AGENTS.md 6.1).
 *
 * <p>Lee la cabecera X-Correlation-Id o, si falta o no es un UUID, genera uno. Lo deja en el MDC
 * para que salga en todos los logs de la petición y lo devuelve en la respuesta. Va antes que
 * cualquier otro filtro (incluida la seguridad) para que hasta un 401 lleve su correlationId.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = validOrNew(request.getHeader(HEADER));
        MDC.put(UrbanMoveLogFormatter.CORRELATION_ID, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(UrbanMoveLogFormatter.CORRELATION_ID);
        }
    }

    /** El correlationId de la petición en curso. */
    public static String current() {
        String id = MDC.get(UrbanMoveLogFormatter.CORRELATION_ID);
        return id != null ? id : UUID.randomUUID().toString();
    }

    private static String validOrNew(String header) {
        if (header != null) {
            try {
                return UUID.fromString(header.trim()).toString();
            } catch (IllegalArgumentException ignored) {
                // Cabecera mal formada: se genera una nueva en lugar de propagar basura a los logs.
            }
        }
        return UUID.randomUUID().toString();
    }
}
