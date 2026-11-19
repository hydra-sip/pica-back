package com.hydra.pica.plataforma_pica.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TorneoConfig.TorneoProperties.class)
public class TorneoConfig {

    /**
     * @param cupoMaximo       el máximo de participantes que admite la plataforma en un torneo (HU-201)
     * @param sinValidarFechas solo para el ambiente de prueba (PICA-448): deja crear, editar y publicar un torneo con
     *                         fechas pasadas y programar partidos en cualquier fecha; el orden entre las fechas se sigue
     *                         exigiendo
     */
    @ConfigurationProperties(prefix = "app.torneos")
    public record TorneoProperties(@DefaultValue("64") int cupoMaximo, @DefaultValue("false") boolean sinValidarFechas) {
    }
}
