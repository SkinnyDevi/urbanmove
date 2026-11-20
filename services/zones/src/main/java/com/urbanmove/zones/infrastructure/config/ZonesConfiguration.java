package com.urbanmove.zones.infrastructure.config;

import com.urbanmove.zones.application.GetZoneUseCase;
import com.urbanmove.zones.application.ListZonesUseCase;
import com.urbanmove.zones.application.SeedZonesUseCase;
import com.urbanmove.zones.infrastructure.health.DatabaseHealthCheck;
import com.urbanmove.zones.infrastructure.persistence.JdbcZoneRepository;
import com.urbanmove.zones.infrastructure.seed.JsonZoneSeedSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Composición del servicio (AGENTS.md 4.2, DIP): aquí, y solo aquí, se crean los adaptadores
 * y se inyectan en los casos de uso por constructor. Los casos de uso y el dominio no conocen Spring.
 */
@Configuration(proxyBeanMethods = false)
public class ZonesConfiguration {

    @Bean
    JdbcZoneRepository zoneRepository(JdbcClient jdbc, TransactionTemplate transactions, JsonMapper json) {
        return new JdbcZoneRepository(jdbc, transactions, json);
    }

    @Bean
    ListZonesUseCase listZonesUseCase(JdbcZoneRepository repository) {
        return new ListZonesUseCase(repository);
    }

    @Bean
    GetZoneUseCase getZoneUseCase(JdbcZoneRepository repository) {
        return new GetZoneUseCase(repository);
    }

    @Bean
    SeedZonesUseCase seedZonesUseCase(@Value("${urbanmove.zones.seed-file}") Resource seedFile,
                                      JsonMapper json, JdbcZoneRepository repository) {
        return new SeedZonesUseCase(new JsonZoneSeedSource(seedFile, json), repository);
    }

    /** Carga la semilla al arrancar. Si falla, el servicio no arranca (mejor que servir un catálogo vacío). */
    @Bean
    ApplicationRunner seedZonesOnStartup(SeedZonesUseCase seedZones) {
        return args -> seedZones.execute();
    }

    @Bean
    DatabaseHealthCheck databaseHealthCheck(JdbcClient jdbc) {
        return new DatabaseHealthCheck(jdbc);
    }
}
