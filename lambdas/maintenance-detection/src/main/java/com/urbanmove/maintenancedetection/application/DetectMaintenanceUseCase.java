package com.urbanmove.maintenancedetection.application;

import com.urbanmove.maintenancedetection.application.port.MaintenanceOrders;
import com.urbanmove.maintenancedetection.application.port.OpenOutcome;
import com.urbanmove.maintenancedetection.domain.DetectionRule;
import com.urbanmove.maintenancedetection.domain.MaintenanceRequest;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Caso de uso: evaluar una señal con las reglas y, si procede, abrir la orden en maintenance. */
public final class DetectMaintenanceUseCase {
    private static final Logger log = LoggerFactory.getLogger(DetectMaintenanceUseCase.class);

    private final DetectionRule rules;
    private final MaintenanceOrders orders;

    public DetectMaintenanceUseCase(DetectionRule rules, MaintenanceOrders orders) {
        this.rules = Objects.requireNonNull(rules);
        this.orders = Objects.requireNonNull(orders);
    }

    public DetectionOutcome execute(IncomingSignal incoming) {
        Optional<MaintenanceRequest> request = rules.evaluate(incoming.signal());
        if (request.isEmpty()) {
            log.atInfo().addKeyValue("eventId", incoming.metadata().eventId().toString())
                    .addKeyValue("vehicleId", incoming.signal().vehicleId().value())
                    .log("No maintenance needed");
            return DetectionOutcome.NO_ACTION;
        }
        OpenOutcome opened = orders.open(request.get(), incoming.metadata());
        DetectionOutcome outcome = opened == OpenOutcome.CREATED
                ? DetectionOutcome.ORDER_CREATED : DetectionOutcome.ORDER_ALREADY_OPEN;
        log.atInfo().addKeyValue("eventId", incoming.metadata().eventId().toString())
                .addKeyValue("vehicleId", request.get().vehicleId().value())
                .addKeyValue("reason", request.get().reason().name())
                .addKeyValue("outcome", outcome.name())
                .log("Maintenance order requested");
        return outcome;
    }
}
