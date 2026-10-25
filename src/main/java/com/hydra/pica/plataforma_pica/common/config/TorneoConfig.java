package com.hydra.pica.plataforma_pica.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TorneoConfig.TorneoProperties.class)
public class TorneoConfig {

    /** {@code cupoMaximo}: el máximo de participantes que admite la plataforma en un torneo (HU-201). */
    @ConfigurationProperties(prefix = "app.torneos")
    public record TorneoProperties(@DefaultValue("64") int cupoMaximo) {
    }
}
