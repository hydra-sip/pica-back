package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

public record TorneoResumen(
        Long id,
        String slug,
        String nombre,
        String descripcion,
        String disciplina,
        Formato formato,
        Modalidad modalidad,
        TipoParticipante tipoParticipante,
        String sede,
        EstadoTorneo estado,
        Integer cupoMaximo,
        Integer cupoDisponible,
        BigDecimal valorInscripcion,
        String moneda,
        BigDecimal pozoEstimado,
        Instant cierreInscripcionEn,
        Instant fechaInicio,
        Instant fechaFin) {

    public static TorneoResumen de(Torneo torneo, int cupoDisponible, BigDecimal pozoEstimado) {
        return new TorneoResumen(
                torneo.getId(),
                torneo.getSlug(),
                torneo.getNombre(),
                torneo.getDescripcion(),
                torneo.getDisciplina(),
                torneo.getFormato(),
                torneo.getModalidad(),
                torneo.getTipoParticipante(),
                torneo.getSede(),
                torneo.getEstado(),
                torneo.getCupoMaximo(),
                cupoDisponible,
                torneo.getValorInscripcion(),
                torneo.getMoneda(),
                pozoEstimado,
                torneo.getCierreInscripcionEn(),
                torneo.getFechaInicio(),
                torneo.getFechaFin());
    }
}
