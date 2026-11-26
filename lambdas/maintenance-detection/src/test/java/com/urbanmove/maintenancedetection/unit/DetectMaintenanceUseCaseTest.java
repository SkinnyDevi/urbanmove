package com.urbanmove.maintenancedetection.unit;

import static org.junit.jupiter.api.Assertions.*;

import com.urbanmove.maintenancedetection.application.DetectMaintenanceUseCase;
import com.urbanmove.maintenancedetection.application.DetectionOutcome;
import com.urbanmove.maintenancedetection.application.IncomingSignal;
import com.urbanmove.maintenancedetection.application.MaintenanceUnavailableException;
import com.urbanmove.maintenancedetection.application.SignalMetadata;
import com.urbanmove.maintenancedetection.application.port.OpenOutcome;
import com.urbanmove.maintenancedetection.domain.BatteryLowRule;
import com.urbanmove.maintenancedetection.domain.MaintenanceRequest;
import com.urbanmove.maintenancedetection.domain.VehicleId;
import com.urbanmove.maintenancedetection.domain.VehicleSignal;
import com.urbanmove.maintenancedetection.domain.VehicleType;
import com.urbanmove.maintenancedetection.domain.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Caso de uso: de la señal a la orden, sin red. */
class DetectMaintenanceUseCaseTest {
    private final List<MaintenanceRequest> opened = new ArrayList<>();

    private static IncomingSignal signal(int level) {
        return new IncomingSignal(new SignalMetadata(UUID.randomUUID(), "VehicleBatteryLow", "corr-1"),
                new VehicleSignal.BatteryLow(new VehicleId("veh-0042"), VehicleType.SCOOTER, new ZoneId("tafira"),
                        level, 15));
    }

    private DetectMaintenanceUseCase useCase(OpenOutcome answer) {
        return new DetectMaintenanceUseCase(new BatteryLowRule(), (request, trigger) -> {
            opened.add(request);
            return answer;
        });
    }

    @Test
    void aLowBatteryOpensAnOrder() {
        assertEquals(DetectionOutcome.ORDER_CREATED, useCase(OpenOutcome.CREATED).execute(signal(10)));
        assertEquals(1, opened.size());
    }

    @Test
    void anAlreadyOpenOrderIsNotAnError() {
        assertEquals(DetectionOutcome.ORDER_ALREADY_OPEN, useCase(OpenOutcome.ALREADY_OPEN).execute(signal(10)));
    }

    @Test
    void enoughBatteryDoesNotCallMaintenance() {
        assertEquals(DetectionOutcome.NO_ACTION, useCase(OpenOutcome.CREATED).execute(signal(60)));
        assertTrue(opened.isEmpty());
    }

    @Test
    void anUnavailableMaintenanceIsReported() {
        DetectMaintenanceUseCase failing = new DetectMaintenanceUseCase(new BatteryLowRule(), (request, trigger) -> {
            throw new MaintenanceUnavailableException("down", null);
        });

        assertThrows(MaintenanceUnavailableException.class, () -> failing.execute(signal(10)));
    }
}
