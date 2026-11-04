package com.hydra.pica.plataforma_pica.partido.dto;

import java.util.List;

/** @param nombre "Final", "Semifinal", "Cuartos de final", "Octavos de final" o "Ronda n" */
public record RondaBracket(int numero, String nombre, List<PartidoBracket> partidos) {

    public static String nombre(int ronda, int rondas) {
        return switch (rondas - ronda) {
            case 0 -> "Final";
            case 1 -> "Semifinal";
            case 2 -> "Cuartos de final";
            case 3 -> "Octavos de final";
            default -> "Ronda " + ronda;
        };
    }
}
