package com.hydra.pica.plataforma_pica.partido.dto;

import java.util.List;

/**
 * El bracket de un torneo (HU-207): las rondas con sus partidos, de la primera a la final. Sin generar, las rondas
 * vienen vacías.
 *
 * @param semilla   con la que se sorteó (ver {@code SorteoBracket}); null sin generar
 * @param listaHash SHA-256 de la lista de inscriptos, sellada al cerrar la inscripción
 */
public record BracketTorneo(
        Long torneoId,
        String semilla,
        String listaHash,
        List<RondaBracket> rondas) {
}
