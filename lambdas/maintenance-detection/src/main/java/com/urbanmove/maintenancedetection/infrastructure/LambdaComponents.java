package com.urbanmove.maintenancedetection.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.maintenancedetection.application.DetectMaintenanceUseCase;
import com.urbanmove.maintenancedetection.domain.BatteryLowRule;
import com.urbanmove.maintenancedetection.domain.DetectionRules;
import com.urbanmove.maintenancedetection.infrastructure.http.HttpMaintenanceOrders;
import com.urbanmove.maintenancedetection.infrastructure.http.RetryPolicy;
import com.urbanmove.maintenancedetection.infrastructure.http.ServiceTokenIssuer;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Composición de la Lambda: crea una vez por contenedor el cliente HTTP, el token y el caso de uso. */
public record LambdaComponents(SignalTranslator translator, DetectMaintenanceUseCase useCase, Duration safetyMargin) {

    public static LambdaComponents create(LambdaConfig config) {
        ObjectMapper json = new ObjectMapper();
        HttpClient http = HttpClient.newBuilder().connectTimeout(config.httpTimeout()).build();
        HttpMaintenanceOrders orders = new HttpMaintenanceOrders(http, config.maintenanceUrl(), config.httpTimeout(),
                new ServiceTokenIssuer(config.jwtSecret(), LambdaConfig.SERVICE_SUBJECT, Clock.systemUTC()),
                RetryPolicy.withRandomJitter(config.maxAttempts()), json);
        DetectionRules rules = new DetectionRules(List.of(new BatteryLowRule()));
        return new LambdaComponents(new SignalTranslator(json), new DetectMaintenanceUseCase(rules, orders),
                config.safetyMargin());
    }
}
