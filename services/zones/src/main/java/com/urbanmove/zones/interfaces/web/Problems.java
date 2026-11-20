package com.urbanmove.zones.interfaces.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Errores RFC 7807 con correlationId (AGENTS.md 3.1, docs/contracts/openapi/common.yaml: Problem).
 */
public final class Problems {

    public static final String MEDIA_TYPE = "application/problem+json";

    private Problems() {
    }

    public static ProblemDetail of(HttpStatus status, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("correlationId", CorrelationIdFilter.current());
        return problem;
    }

    /** Para escribir el error a mano (filtros de seguridad, donde no hay message converters). */
    public static Map<String, Object> asMap(ProblemDetail problem) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        if (problem.getDetail() != null) {
            body.put("detail", problem.getDetail());
        }
        if (problem.getInstance() != null) {
            body.put("instance", problem.getInstance().toString());
        }
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        return body;
    }
}
