package com.hydra.pica.plataforma_pica.torneo.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.Cancha;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/**
 * Un torneo propio como lo ve su organizador en el asistente, incluso en BORRADOR (PICA-199): los datos de los
 * pasos 1 y 2, con lo que falta en null. La vista pública es otra.
 */
public record TorneoInformacion(
        Long id,
        EstadoTorneo estado,
        String nombre,
        String disciplina,
        String descripcion,
        Formato formato,
        Modalidad modalidad,
        TipoParticipante tipoParticipante,
        String sede,
        List<String> canchas,
        Instant fechaInicio,
        Instant fechaFin,
        Instant cierreInscripcionEn,
        String reglamentoTexto,
        String reglamentoUrl,
        Integer cupoMaximo,
        Integer cupoMinimo,
        Integer tamanioEquipo,
        Integer nominaMaxima,
        Instant creadoEn,
        Instant modificadoEn) {

    public static TorneoInformacion de(Torneo t) {
        List<String> canchas = t.getCanchas().stream()
                .map(Cancha::getNombre)
                .sorted(Comparator.comparing(String::toLowerCase))
                .toList();
        return new TorneoInformacion(t.getId(), t.getEstado(), t.getNombre(), t.getDisciplina(), t.getDescripcion(),
                t.getFormato(), t.getModalidad(), t.getTipoParticipante(), t.getSede(), canchas, t.getFechaInicio(),
                t.getFechaFin(), t.getCierreInscripcionEn(), t.getReglamentoTexto(), t.getReglamentoUrl(),
                t.getCupoMaximo(), t.getCupoMinimo(), t.getTamanioEquipo(), t.getNominaMaxima(), t.getCreadoEn(),
                t.getModificadoEn());
    }
}
