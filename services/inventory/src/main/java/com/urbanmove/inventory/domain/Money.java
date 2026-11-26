package com.urbanmove.inventory.domain;

import java.math.BigDecimal;
import java.util.Objects;

/** Importe en euros, como en el esquema money de los contratos. */
public record Money(BigDecimal amount, String currency) {
    public static final String EUR = "EUR";

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (!EUR.equals(currency)) {
            throw new IllegalArgumentException("currency must be EUR");
        }
    }
}
