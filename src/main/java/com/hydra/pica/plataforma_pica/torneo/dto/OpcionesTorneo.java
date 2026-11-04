package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;

/**
 * Lo que necesita el asistente para armar sus opciones (PICA-199): el paso 1 y los gastos administrativos del paso 3.
 *
 * @param disciplinas sugeridas, con sus valores por defecto; el organizador puede escribir otra
 * @param cupoMaximo el máximo de participantes que admite la plataforma
 * @param pctGastosAdministrativos el % vigente que paga aparte quien se inscribe (PICA-283); al publicar queda fijo
 * @param canchas las que ya existen en la sede pedida (vacío si no se pidió ninguna)
 */
public record OpcionesTorneo(
        List<Disciplina> disciplinas,
        List<FormatoOpcion> formatos,
        List<Modalidad> modalidades,
        int cupoMaximo,
        BigDecimal pctGastosAdministrativos,
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
