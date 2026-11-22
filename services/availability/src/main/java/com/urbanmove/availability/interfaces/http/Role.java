package com.urbanmove.availability.interfaces.http;

import io.javalin.security.RouteRole;
import java.util.Optional;

/** Los tres roles oficiales del JWT. */
public enum Role implements RouteRole {
    USER("user"),
    MAINTENANCE("maintenance"),
    ADMIN("admin");

    private final String claimValue;

    Role(String claimValue) {
        this.claimValue = claimValue;
    }

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
