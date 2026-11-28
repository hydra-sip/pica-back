package com.hydra.pica.plataforma_pica.control.dto;

/**
 * Cuerpo del request para marcar una alerta de fraude como revisada con nota obligatoria (HU-705).
 */
public record RevisarAlertaFraudeRequest(String nota) {
}
