package com.hydra.pica.plataforma_pica.rifa.dto;

import java.time.Instant;
import java.util.List;

import com.hydra.pica.plataforma_pica.rifa.domain.Rifa;

/**
 * Todo lo que hace falta para recalcular el sorteo de una rifa (HU-504) en el portal de verificación: la lista sellada
 * y su desglose ({@code listaHash}), el compromiso ({@code SHA-256(listaHash:fechaSorteo)}) publicado antes de la
 * semilla, la semilla y los premios con el número ganador y quién lo compró. La semilla sale de drand (HU-503):
 * {@code semillaUrl} es donde se consulta su ronda para comparar la semilla y la firma. Ronda, firma y URL son null en
 * las rifas sorteadas con la semilla del sistema, antes de PICA-264.
 */
public record SorteoRifaDetalle(
        Long id,
        String titulo,
        String torneoNombre,
        Instant fechaSorteo,
        String listaHash,
        Instant listaSelladaEn,
        List<LineaListaSellada> lista,
        String compromisoHash,
        Instant compromisoPublicadoEn,
        String semilla,
        String semillaFirma,
        Long semillaRonda,
        String semillaUrl,
        Instant semillaObtenidaEn,
        List<GanadorRifa> premios) {

    public static SorteoRifaDetalle de(Rifa rifa, List<LineaListaSellada> lista, List<GanadorRifa> premios,
                                       String semillaUrl) {
        return new SorteoRifaDetalle(rifa.getId(), rifa.getTitulo(),
                rifa.getTorneo() == null ? null : rifa.getTorneo().getNombre(), rifa.getSorteadaEn(),
                rifa.getListaHash(), rifa.getListaSelladaEn(), lista, rifa.getCompromisoHash(),
                rifa.getCompromisoPublicadoEn(), rifa.getSemilla(), rifa.getSemillaFirma(), rifa.getSemillaRonda(),
                semillaUrl, rifa.getSemillaObtenidaEn(), premios);
    }
}
