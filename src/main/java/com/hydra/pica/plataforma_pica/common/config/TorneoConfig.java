package com.hydra.pica.plataforma_pica.common.config;

<<<<<<< HEAD
import java.math.BigDecimal;

=======
>>>>>>> origin/dev
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TorneoConfig.TorneoProperties.class)
public class TorneoConfig {

<<<<<<< HEAD
    /**
     * @param cupoMaximo el máximo de participantes que admite la plataforma en un torneo (HU-201)
     * @param comisionMaxima el tope del % de comisión de la plataforma que puede fijar un organizador (HU-601)
     */
    @ConfigurationProperties(prefix = "app.torneos")
    public record TorneoProperties(@DefaultValue("64") int cupoMaximo, @DefaultValue("10") BigDecimal comisionMaxima) {
=======
    /** {@code cupoMaximo}: el máximo de participantes que admite la plataforma en un torneo (HU-201). */
    @ConfigurationProperties(prefix = "app.torneos")
    public record TorneoProperties(@DefaultValue("64") int cupoMaximo) {
>>>>>>> origin/dev
    }
}
