package com.hydra.pica.plataforma_pica.reputacion.dto;

import java.time.Instant;
import java.util.List;
import com.hydra.pica.plataforma_pica.reputacion.domain.EventoIndiceOrganizador;
import com.hydra.pica.plataforma_pica.torneo.dto.TorneoResumen;

/**
 * Perfil público de un organizador (HU-806, T806-1): identidad visible (sin datos privados), contadores de torneos
 * organizados (publicados), finalizados y cancelados, índice de confiabilidad con su historial de movimientos y sus
 * torneos próximos.
 */
public record PerfilOrganizador(
        Long id,
        String username,
        String nombreCompleto,
        String avatar,
        int torneosOrganizados,
        int torneosFinalizados,
        int torneosCancelados,
        int indice,
        Instant indiceActualizadoEn,
        List<MovimientoIndice> historialIndice,
        List<TorneoResumen> torneosProximos) {

    public record MovimientoIndice(
            Long torneoId,
            String torneo,
            boolean conGastos,
            int delta,
            int valorResultante,
            Instant creadoEn) {

        public static MovimientoIndice de(EventoIndiceOrganizador evento) {
            return new MovimientoIndice(
                    evento.getTorneo().getId(),
                    evento.getTorneo().getNombre(),
                    evento.isConGastos(),
                    evento.getDelta(),
                    evento.getValorResultante(),
                    evento.getCreadoEn());
        }
    }
}
