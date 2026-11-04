package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.torneo.domain.AsistenteTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/**
 * Una fila de Mis torneos (PICA-201).
 *
 * @param inscriptos inscripciones confirmadas
 * @param pozo lo que va al pozo de competidores; null si todavía no tiene la parte económica o el cupo
 * @param pozoEstimado true en BORRADOR, donde el pozo se calcula con el cupo completo; si no, con los inscriptos
 * @param pasoPendiente en BORRADOR, el paso donde reabrir el asistente; si no, null
 */
public record MiTorneoResumen(
        Long id,
        String nombre,
        String disciplina,
        EstadoTorneo estado,
        Integer pasoPendiente,
        String sede,
        Integer cupoMaximo,
        long inscriptos,
        BigDecimal pozo,
        boolean pozoEstimado,
        String moneda,
        Instant fechaInicio,
        Instant modificadoEn) {

    public static MiTorneoResumen de(Torneo t, long inscriptos, BigDecimal pozo) {
        return new MiTorneoResumen(t.getId(), t.getNombre(), t.getDisciplina(), t.getEstado(),
                AsistenteTorneo.pasoPendiente(t), t.getSede(), t.getCupoMaximo(), inscriptos, pozo,
                t.getEstado() == EstadoTorneo.BORRADOR, t.getMoneda(), t.getFechaInicio(), t.getModificadoEn());
    }
}
