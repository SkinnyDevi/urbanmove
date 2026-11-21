package com.urbanmove.maintenance.interfaces.http;

import io.javalin.security.RouteRole;
import java.util.Optional;

/** Los tres roles oficiales del JWT (AGENTS.md 3.1). Se asignan a cada ruta al registrarla. */
public enum Role implements RouteRole {
    USER("user"),
    MAINTENANCE("maintenance"),
    ADMIN("admin");

    private final String claimValue;

    Role(String claimValue) {
        this.claimValue = claimValue;
    }

    /** Valor tal como aparece en el claim {@code roles} del JWT. */
    public String claimValue() {
        return claimValue;
    }

    public static Optional<Role> fromClaim(String value) {
        for (Role role : values()) {
            if (role.claimValue.equals(value)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
