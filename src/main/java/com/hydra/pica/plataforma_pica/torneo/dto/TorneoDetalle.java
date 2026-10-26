package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/**
 * La ficha pública de un torneo publicado (HU-208). Los montos son estimados con el cupo completo, como en el paso 3
 * (HU-601): {@code pozoEstimado} es el pozo neto que se reparte en premios (competidores menos gastos) y cada premio
 * trae su monto. El reglamento puede venir escrito, en PDF o los dos.
 */
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
        BigDecimal premioRifaEstimado,
        List<PremioDetalle> premios,
        Instant cierreInscripcionEn,
        Instant fechaInicio,
        Instant fechaFin,
        String reglamentoTexto,
        String reglamentoUrl,
        String reglamentoHash,
        OrganizadorResumen organizador,
        Instant publicadoEn) {

    public static TorneoDetalle de(
            Torneo torneo,
            int cupoDisponible,
            BigDecimal pozoEstimado,
            BigDecimal premioRifaEstimado,
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
                premioRifaEstimado,
                premios,
                torneo.getCierreInscripcionEn(),
                torneo.getFechaInicio(),
                torneo.getFechaFin(),
                torneo.getReglamentoTexto(),
                torneo.getReglamentoUrl(),
                torneo.getReglamentoHash(),
                OrganizadorResumen.de(torneo.getOrganizador()),
                torneo.getPublicadoEn());
    }
}
