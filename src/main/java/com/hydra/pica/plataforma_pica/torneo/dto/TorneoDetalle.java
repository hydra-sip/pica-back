package com.hydra.pica.plataforma_pica.torneo.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.GastosAdministrativos;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/**
 * La ficha pública de un torneo publicado (HU-208). Los montos son estimados con el cupo completo, como en el paso 3
 * (HU-601): {@code pozoEstimado} es el pozo neto que se reparte en premios (la recaudación menos los gastos previstos)
 * y cada premio trae su monto. Quien se inscribe paga {@code totalAPagar}: el valor más los gastos administrativos
 * sellados al publicar (PICA-283). El reglamento puede venir escrito, en PDF o los dos.
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
        BigDecimal pctGastosAdministrativos,
        BigDecimal gastosAdministrativos,
        BigDecimal totalAPagar,
        BigDecimal pozoEstimado,
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
                torneo.getPctGastosAdministrativos(),
                GastosAdministrativos.monto(torneo.getValorInscripcion(), torneo.getPctGastosAdministrativos()),
                GastosAdministrativos.total(torneo.getValorInscripcion(), torneo.getPctGastosAdministrativos()),
                pozoEstimado,
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
