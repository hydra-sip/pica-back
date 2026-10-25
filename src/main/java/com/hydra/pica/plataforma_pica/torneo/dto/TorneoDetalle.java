package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

public record TorneoDetalle(
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
        Integer cupoMinimo,
        Integer cupoDisponible,
        Integer tamanioEquipo,
        BigDecimal valorInscripcion,
        String moneda,
        BigDecimal pctPozoCompetidores,
        BigDecimal pctPremioRifa,
        BigDecimal pozoEstimado,
        List<PremioDetalle> premios,
        Instant cierreInscripcionEn,
        Instant fechaInicio,
        Instant fechaFin,
        String reglamentoUrl,
        String reglamentoHash,
        OrganizadorResumen organizador,
        Instant publicadoEn) {

    public static TorneoDetalle de(
            Torneo torneo,
            int cupoDisponible,
            BigDecimal pozoEstimado,
            List<PremioDetalle> premios) {
        return new TorneoDetalle(
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
                torneo.getCupoMinimo(),
                cupoDisponible,
                torneo.getTamanioEquipo(),
                torneo.getValorInscripcion(),
                torneo.getMoneda(),
                torneo.getPctPozoCompetidores(),
                torneo.getPctPremioRifa(),
                pozoEstimado,
                premios,
                torneo.getCierreInscripcionEn(),
                torneo.getFechaInicio(),
                torneo.getFechaFin(),
                torneo.getReglamentoUrl(),
                torneo.getReglamentoHash(),
                OrganizadorResumen.de(torneo.getOrganizador()),
                torneo.getPublicadoEn());
    }
}
