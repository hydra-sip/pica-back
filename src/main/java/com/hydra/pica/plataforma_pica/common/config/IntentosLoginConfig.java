package com.hydra.pica.plataforma_pica.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IntentosLoginConfig.IntentosLoginProperties.class)
public class IntentosLoginConfig {

    /**
     * Límite de logins fallidos (RNF-SEG-06): con {@code maximo} fallidos de un mismo identificador dentro de
     * {@code ventana}, el siguiente intento responde 429 hasta que el más viejo salga de la ventana.
     */
    @ConfigurationProperties(prefix = "app.auth.intentos-login")
    public record IntentosLoginProperties(@DefaultValue("5") int maximo, @DefaultValue("15m") Duration ventana) {
    }
}
