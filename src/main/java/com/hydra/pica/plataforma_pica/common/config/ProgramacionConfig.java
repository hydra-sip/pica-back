package com.hydra.pica.plataforma_pica.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tareas programadas: confirmar los resultados con el plazo vencido (PICA-242) y cerrar las inscripciones vencidas
 * (PICA-213, que además se apaga sola con {@code app.torneos.cierre-automatico}). Los tests las apagan para que nada
 * cambie la base por su cuenta.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.partidos.confirmacion-automatica", havingValue = "true", matchIfMissing = true)
public class ProgramacionConfig {
}
