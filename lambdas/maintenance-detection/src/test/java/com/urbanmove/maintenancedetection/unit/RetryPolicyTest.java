package com.urbanmove.maintenancedetection.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.urbanmove.maintenancedetection.infrastructure.http.RetryPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Espera exponencial con tope y jitter. */
class RetryPolicyTest {

    @Test
    void theWaitDoublesUntilTheCap() {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(200), Duration.ofSeconds(2), bound -> bound);

        assertEquals(200, policy.delayAfter(1).toMillis());
        assertEquals(400, policy.delayAfter(2).toMillis());
        assertEquals(800, policy.delayAfter(3).toMillis());
        assertEquals(2000, policy.delayAfter(5).toMillis(), "nunca más que el tope");
    }

    @Test
    void theJitterKeepsTheWaitBetweenZeroAndTheExponentialValue() {
        RetryPolicy policy = RetryPolicy.withRandomJitter(3);

        for (int i = 0; i < 200; i++) {
            long wait = policy.delayAfter(2).toMillis();
            assertTrue(wait >= 0 && wait <= 400, "fuera de rango: " + wait);
        }
    }

    @Test
    void atLeastOneAttempt() {
        assertThrows(IllegalArgumentException.class, () -> RetryPolicy.withRandomJitter(0));
    }
}
