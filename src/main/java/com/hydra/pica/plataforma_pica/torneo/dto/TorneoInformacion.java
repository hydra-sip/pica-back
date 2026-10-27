package com.hydra.pica.plataforma_pica.torneo.dto;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.hydra.pica.plataforma_pica.torneo.domain.AsistenteTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Cancha;
import com.hydra.pica.plataforma_pica.torneo.domain.EstadoTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.Formato;
import com.hydra.pica.plataforma_pica.torneo.domain.Modalidad;
import com.hydra.pica.plataforma_pica.torneo.domain.Premio;
import com.hydra.pica.plataforma_pica.torneo.domain.SelloTorneo;
import com.hydra.pica.plataforma_pica.torneo.domain.TipoParticipante;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;

/**
 * Un torneo propio como lo ve su organizador en el asistente, incluso en BORRADOR (PICA-199): los datos de los
 * tres pasos, con lo que falta en null. La vista pública es otra.
 *
 * @param pasoPendiente en BORRADOR, el paso donde reabrir el asistente (ver {@link AsistenteTorneo}); si no, null
 * @param economia el paso 3 (PICA-203)
 * @param slug el enlace público; se genera al publicar (PICA-204)
 * @param reglamentoUrl el PDF, que sirve {@code GET /torneos/{id}/reglamento} (PICA-200)
 * @param reglamentoHash SHA-256 del PDF desde que se sube; sin PDF, el del texto al publicar
 * @param esquemaHash SHA-256 del esquema económico, sellado al publicar (ver {@link SelloTorneo})
 * @param inscripcionCerradaEn cuándo se cerró la inscripción (HU-206); null mientras no se cerró
 * @param listaHash SHA-256 de la lista de inscriptos, sellada al cerrar la inscripción (ver {@link SelloTorneo})
 * @param camposBloqueados los de {@code InformacionTorneoRequest} que ya no se pueden cambiar (HU-202); vacío en
 *                         BORRADOR
 */
public record TorneoInformacion(
        Long id,
        EstadoTorneo estado,
        Integer pasoPendiente,
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
        String reglamentoHash,
        Integer cupoMaximo,
        Integer cupoMinimo,
        Integer tamanioEquipo,
        Integer nominaMaxima,
        EconomiaTorneo economia,
        String slug,
        String esquemaHash,
        Instant publicadoEn,
        Instant inscripcionCerradaEn,
        String listaHash,
        Instant creadoEn,
        Instant modificadoEn,
        List<String> camposBloqueados) {

    public static TorneoInformacion de(Torneo t, List<Premio> premios, List<String> camposBloqueados) {
        List<String> canchas = t.getCanchas().stream()
                .map(Cancha::getNombre)
                .sorted(Comparator.comparing(String::toLowerCase))
                .toList();
        return new TorneoInformacion(t.getId(), t.getEstado(), AsistenteTorneo.pasoPendiente(t), t.getNombre(),
                t.getDisciplina(), t.getDescripcion(), t.getFormato(), t.getModalidad(), t.getTipoParticipante(),
                t.getSede(), canchas, t.getFechaInicio(), t.getFechaFin(), t.getCierreInscripcionEn(),
                t.getReglamentoTexto(), t.getReglamentoUrl(), t.getReglamentoHash(), t.getCupoMaximo(), t.getCupoMinimo(),
                t.getTamanioEquipo(), t.getNominaMaxima(), EconomiaTorneo.de(t, premios), t.getSlug(),
                t.getEsquemaHash(), t.getPublicadoEn(), t.getListaSelladaEn(), t.getListaHash(), t.getCreadoEn(), t.getModificadoEn(), camposBloqueados);
    }
}
