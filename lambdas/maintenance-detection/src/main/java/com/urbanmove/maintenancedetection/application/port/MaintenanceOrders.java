package com.urbanmove.maintenancedetection.application.port;

import com.urbanmove.maintenancedetection.application.SignalMetadata;
import com.urbanmove.maintenancedetection.domain.MaintenanceRequest;

/** Puerto hacia el servicio maintenance; el eventId del disparador hace la llamada idempotente. */
public interface MaintenanceOrders {
    OpenOutcome open(MaintenanceRequest request, SignalMetadata trigger);
}
