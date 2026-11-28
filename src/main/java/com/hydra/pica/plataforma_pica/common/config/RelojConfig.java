package com.hydra.pica.plataforma_pica.common.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El reloj de la aplicación (CAL-6): los servicios y los jobs piden la hora con {@code Instant.now(clock)}, así un
 * test puede fijarla con un {@link Clock#fixed} en vez de depender de la hora real.
 */
@Configuration
public class RelojConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
