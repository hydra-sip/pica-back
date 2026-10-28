package com.hydra.pica.plataforma_pica.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RifaConfig.RifaProperties.class)
public class RifaConfig {

    /**
     * @param duracionReserva lo que dura un número RESERVADO mientras se paga (HU-403); vencida, el número vuelve a
     *                        estar libre
     */
    @ConfigurationProperties(prefix = "app.rifas")
    public record RifaProperties(@DefaultValue("10m") Duration duracionReserva) {
    }
}
