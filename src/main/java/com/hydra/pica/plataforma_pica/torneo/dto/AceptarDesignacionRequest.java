package com.hydra.pica.plataforma_pica.torneo.dto;

/**
 * Aceptar la designación (HU-807): el árbitro firma el código de imparcialidad del wireframe 49. Sin el campo o con
 * false cuenta como no aceptado: lo resuelve el servicio con su código, como el reglamento de la inscripción.
 */
public record AceptarDesignacionRequest(Boolean compromisoAceptado) {
}
