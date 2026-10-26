package com.hydra.pica.plataforma_pica.torneo.dto;

import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;

/**
 * Lo que necesita el paso 1 del asistente para armar sus opciones (PICA-199).
 *
 * @param disciplinas sugeridas, con sus valores por defecto; el organizador puede escribir otra
 * @param cupoMaximo el máximo de participantes que admite la plataforma
 * @param canchas las que ya existen en la sede pedida (vacío si no se pidió ninguna)
 */
public record OpcionesTorneo(
        List<Disciplina> disciplinas,
        List<FormatoOpcion> formatos,
        List<Modalidad> modalidades,
        int cupoMaximo,
        List<String> canchas) {

    /**
     * Valores con los que el front completa el formulario al elegir la disciplina. En los torneos individuales
     * el tamaño y la nómina van en null.
     */
    public record Disciplina(
            String nombre,
            TipoParticipante tipoParticipante,
            Modalidad modalidad,
            Integer tamanioEquipo,
            Integer nominaMaxima,
            String reglas) {
    }

    /** {@code disponible} en false: se muestra como "próximamente" y no se puede elegir. */
    public record FormatoOpcion(String codigo, String nombre, boolean disponible) {
    }
}
