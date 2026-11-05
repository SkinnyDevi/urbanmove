package com.urbanmove.synthgen;

/** Un usuario (rol "user") o un técnico (rol "maintenance"). */
public final class User {

    public final String userId;
    public final String email;
    public final String fullName;
    public final String role;
    /** true mientras tiene una reserva o un viaje en curso. */
    boolean busy;

    User(String userId, String email, String fullName, String role) {
        this.userId = userId;
        this.email = email;
        this.fullName = fullName;
        this.role = role;
    }
}
