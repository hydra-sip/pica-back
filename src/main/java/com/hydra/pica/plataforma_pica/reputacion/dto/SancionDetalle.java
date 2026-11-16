package com.hydra.pica.plataforma_pica.reputacion.dto;

import java.time.Instant;
import com.hydra.pica.plataforma_pica.reputacion.domain.Gravedad;
import com.hydra.pica.plataforma_pica.reputacion.domain.Sancion;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;

/** Una sanción como la devuelve la API: el torneo, el jugador, el partido si lo hay, la gravedad y quién la aplicó. */
public record SancionDetalle(
        Long id,
        Long torneoId,
        String torneo,
        UsuarioPublico jugador,
        Long partidoId,
        Gravedad gravedad,
        String motivo,
        UsuarioPublico aplicadaPor,
        Instant aplicadaEn,
        Instant anuladaEn) {

    public static SancionDetalle desde(Sancion sancion) {
        return new SancionDetalle(sancion.getId(), sancion.getTorneo().getId(), sancion.getTorneo().getNombre(),
                UsuarioPublico.desde(sancion.getUsuario()),
                sancion.getPartido() == null ? null : sancion.getPartido().getId(), sancion.getGravedad(),
                sancion.getMotivo(), UsuarioPublico.desde(sancion.getAplicadaPor()), sancion.getAplicadaEn(),
                sancion.getAnuladaEn());
    }
}
