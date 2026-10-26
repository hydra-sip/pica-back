package com.hydra.pica.plataforma_pica.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PartidoConfig.PartidoProperties.class)
public class PartidoConfig {

    /** @param plazoObjecion lo que tiene el rival para aceptar u objetar un resultado cargado (HU-301) */
    @ConfigurationProperties(prefix = "app.partidos")
    public record PartidoProperties(@DefaultValue("15m") Duration plazoObjecion) {
    }
}
