package com.hydra.pica.plataforma_pica.common.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fuera de dev el back no arranca sin el cliente de Google, igual que {@link JwtKeyProvider} con las
 * claves. El application.yml trae placeholders para dev, y con ellos arrancaría igual: el error
 * aparecería recién en la pantalla de Google (invalid_client).
 */
@Component
public class GoogleClientCheck {

    private static final List<String> VARIABLES = List.of("GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET");

    public GoogleClientCheck(Environment environment) {
        if (Arrays.asList(environment.getActiveProfiles()).contains("dev")) {
            return;
        }
        List<String> faltantes = new ArrayList<>();
        for (String variable : VARIABLES) {
            String valor = environment.getProperty(variable);
            if (valor == null || valor.isBlank()) {
                faltantes.add(variable);
            }
        }
        if (!faltantes.isEmpty()) {
            throw new IllegalStateException(
                    "Falta(n) la(s) variable(s) de entorno " + String.join(" y ", faltantes)
                            + " para el login con Google. La aplicación no puede iniciar fuera del perfil dev.");
        }
    }
}
