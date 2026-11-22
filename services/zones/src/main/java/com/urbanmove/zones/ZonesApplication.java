package com.urbanmove.zones;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.zones.application.GetZoneUseCase;
import com.urbanmove.zones.application.ListZonesUseCase;
import com.urbanmove.zones.application.SeedZonesUseCase;
import com.urbanmove.zones.infrastructure.ServiceConfig;
import com.urbanmove.zones.infrastructure.health.DatabaseHealthCheck;
import com.urbanmove.zones.infrastructure.persistence.Database;
import com.urbanmove.zones.infrastructure.persistence.JdbcZoneRepository;
import com.urbanmove.zones.infrastructure.seed.JsonZoneSeedSource;
import com.urbanmove.zones.interfaces.http.HealthCheckHandler;
import com.urbanmove.zones.interfaces.http.JwtVerifier;
import com.urbanmove.zones.interfaces.http.ZoneHandlers;
import com.urbanmove.zones.interfaces.http.ZonesHttpApi;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Punto de entrada y composición del servicio. */
public final class ZonesApplication implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ZonesApplication.class);

    private final HikariDataSource dataSource;
    private final Javalin http;

    private ZonesApplication(HikariDataSource dataSource, Javalin http) {
        this.dataSource = dataSource;
        this.http = http;
    }

    public static void main(String[] args) {
        ZonesApplication app = start(ServiceConfig.fromEnvironment(System.getenv()));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "shutdown"));
    }

    public static ZonesApplication start(ServiceConfig config) {
        log.atInfo().addKeyValue("config", config.toString()).log("Starting");
        ObjectMapper json = new ObjectMapper();

        HikariDataSource dataSource = Database.connect(config.dbUrl(), config.dbUser(), config.dbPassword());
        try {
            Database.migrate(dataSource);
            JdbcZoneRepository repository = new JdbcZoneRepository(dataSource, json);

            new SeedZonesUseCase(JsonZoneSeedSource.fromLocation(config.seedFile(), json), repository).execute();
            ListZonesUseCase listZones = new ListZonesUseCase(repository);
            GetZoneUseCase getZone = new GetZoneUseCase(repository);

            Javalin http = ZonesHttpApi.create(
                    json,
                    new JwtVerifier(config.jwtSecret()),
                    new HealthCheckHandler(config.serviceName(), List.of(new DatabaseHealthCheck(dataSource))),
                    new ZoneHandlers(listZones, getZone));
            http.start(config.port());
            log.atInfo().addKeyValue("port", http.port()).log("Zones service started");
            return new ZonesApplication(dataSource, http);
        } catch (RuntimeException e) {
            dataSource.close();
            throw e;
        }
    }

    public int port() {
        return http.port();
    }

    @Override
    public void close() {
        http.stop();
        dataSource.close();
        log.info("Zones service stopped");
    }
}
