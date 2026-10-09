package com.hydra.pica.plataforma_pica.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SesionConfig.SesionProperties.class)
public class SesionConfig {

    /**
     * Duraciones de la sesión (CE2-8), configurables sin tocar código (RF-002).
     *
     * accessTtl: vida del access token.
     * refreshTtl: vida de cada refresh token. Como rota en cada uso, funciona como el tiempo máximo de
     * inactividad: si no se usa en ese lapso, hay que volver a iniciar sesión.
     * sesionMaxima: tope absoluto desde el login, aunque la sesión se use todo el tiempo.
     *
     * Los valores que faltan toman el default, así un entorno sin configurar sigue andando.
     */
    @ConfigurationProperties(prefix = "app.auth")
    public record SesionProperties(Duration accessTtl, Duration refreshTtl, Duration sesionMaxima) {

        public static final Duration ACCESS_TTL_POR_DEFECTO = Duration.ofMinutes(15);
        public static final Duration REFRESH_TTL_POR_DEFECTO = Duration.ofHours(24);
        public static final Duration SESION_MAXIMA_POR_DEFECTO = Duration.ofDays(7);

        public SesionProperties {
            accessTtl = accessTtl != null ? accessTtl : ACCESS_TTL_POR_DEFECTO;
            refreshTtl = refreshTtl != null ? refreshTtl : REFRESH_TTL_POR_DEFECTO;
            sesionMaxima = sesionMaxima != null ? sesionMaxima : SESION_MAXIMA_POR_DEFECTO;
        }

        /** Los defaults: para tests y para quien construye los servicios a mano. */
        public static SesionProperties porDefecto() {
            return new SesionProperties(null, null, null);
        }
    }
}
