package com.urbanmove.inventory.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.urbanmove.inventory.domain.event.VehicleRetired;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/** Máquina de estados del activo y reglas de la baja definitiva. */
class VehicleAssetTest {
    private static final Instant AT = Instant.parse("2026-11-20T12:00:00Z");

    private static VehicleAsset asset() {
        return VehicleAsset.registerPurchased(new VehicleId("veh-0042"), VehicleType.SCOOTER, "Segway Ninebot Max G30",
                LocalDate.parse("2026-11-02"), new Money(new BigDecimal("649.00"), Money.EUR), AT);
    }

    private static VehicleAsset in(ServiceStatus status) {
        VehicleAsset asset = asset();
        if (status != ServiceStatus.IN_SERVICE) {
            asset.markWithdrawn();
        }
        if (status == ServiceStatus.RETIRED) {
            asset.retire("Fin de vida útil", AT);
            asset.pullDomainEvents();
        }
        return asset;
    }

    static Stream<Arguments> transitions() {
        Consumer<VehicleAsset> withdraw = VehicleAsset::markWithdrawn;
        Consumer<VehicleAsset> back = VehicleAsset::markReturnedToService;
        Consumer<VehicleAsset> retire = a -> a.retire("Fin de vida útil", AT);
        return Stream.of(
                Arguments.of(ServiceStatus.IN_SERVICE, "withdraw", withdraw, ServiceStatus.WITHDRAWN),
                Arguments.of(ServiceStatus.IN_SERVICE, "return", back, null),
                Arguments.of(ServiceStatus.IN_SERVICE, "retire", retire, null),
                Arguments.of(ServiceStatus.WITHDRAWN, "withdraw", withdraw, null),
                Arguments.of(ServiceStatus.WITHDRAWN, "return", back, ServiceStatus.IN_SERVICE),
                Arguments.of(ServiceStatus.WITHDRAWN, "retire", retire, ServiceStatus.RETIRED),
                Arguments.of(ServiceStatus.RETIRED, "withdraw", withdraw, null),
                Arguments.of(ServiceStatus.RETIRED, "return", back, null),
                Arguments.of(ServiceStatus.RETIRED, "retire", retire, null));
    }

    @ParameterizedTest(name = "{0} + {1} -> {3}")
    @MethodSource("transitions")
    void everyStateAcceptsOnlyItsActions(ServiceStatus from, String name, Consumer<VehicleAsset> action,
                                         ServiceStatus expected) {
        VehicleAsset asset = in(from);
        if (expected == null) {
            InvalidStateTransitionError error = assertThrows(InvalidStateTransitionError.class, () -> action.accept(asset));
            assertEquals(from, error.from());
            assertEquals(from, asset.status(), "una transición ilegal no cambia nada");
        } else {
            action.accept(asset);
            assertEquals(expected, asset.status());
        }
    }

    @Test
    void aNewAssetIsActiveAndInService() {
        VehicleAsset asset = asset();

        assertEquals(ServiceStatus.IN_SERVICE, asset.status());
        assertEquals(AdminStatus.ACTIVE, asset.adminStatus());
        assertTrue(asset.pullDomainEvents().isEmpty());
    }

    @Test
    void retiringRecordsTheReasonAndVehicleRetired() {
        VehicleAsset asset = in(ServiceStatus.WITHDRAWN);

        asset.retire("Chasis dañado", AT);

        assertEquals(AdminStatus.RETIRED, asset.adminStatus());
        assertEquals("Chasis dañado", asset.snapshot().retirementReason());
        assertEquals(AT, asset.snapshot().retiredAt());
        VehicleRetired event = assertInstanceOf(VehicleRetired.class, asset.pullDomainEvents().get(0));
        assertEquals(VehicleType.SCOOTER, event.vehicleType());
        assertTrue(asset.pullDomainEvents().isEmpty(), "los eventos se entregan una sola vez");
    }

    @Test
    void theReasonIsRequiredAndBounded() {
        VehicleAsset asset = in(ServiceStatus.WITHDRAWN);

        assertThrows(IllegalArgumentException.class, () -> asset.retire(" ", AT));
        assertThrows(IllegalArgumentException.class, () -> asset.retire("x".repeat(501), AT));
        assertEquals(ServiceStatus.WITHDRAWN, asset.status());
    }

    @Test
    void valueObjectsRejectInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new VehicleId("bike-1"));
        assertThrows(IllegalArgumentException.class, () -> new Money(new BigDecimal("-1"), Money.EUR));
        assertThrows(IllegalArgumentException.class, () -> new Money(BigDecimal.ONE, "USD"));
    }
}
