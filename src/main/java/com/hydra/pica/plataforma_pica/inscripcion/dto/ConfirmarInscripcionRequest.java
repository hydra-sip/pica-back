package com.hydra.pica.plataforma_pica.inscripcion.dto;

/** Sin el campo o con false el reglamento cuenta como no aceptado: lo resuelve el servicio con su código. */
public record ConfirmarInscripcionRequest(Boolean reglamentoAceptado) {
}
