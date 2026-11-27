package com.hydra.pica.plataforma_pica.torneo.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.torneo.domain.Reprogramacion;

/**
 * Una reprogramación del historial del torneo (CR-01): es pública, sale en la ficha. {@code plazoAbierto} dice si
 * todavía se puede rechazar la nueva fecha; {@code rechazos}, cuántos se bajaron por ella (CR-01-4).
 */
public record ReprogramacionDetalle(Long id, Instant fechaInicioAnterior, Instant fechaFinAnterior,
                                    Instant fechaInicioNueva, Instant fechaFinNueva, Instant cierreInscripcion,
                                    Instant rechazoHasta, String motivo, boolean plazoAbierto, int rechazos,
                                    Instant creadaEn) {

    public static ReprogramacionDetalle de(Reprogramacion r) {
        return new ReprogramacionDetalle(r.getId(), r.getFechaInicioAnterior(), r.getFechaFinAnterior(),
                r.getFechaInicioNueva(), r.getFechaFinNueva(), r.getCierreInscripcionNuevo(), r.getRechazoHasta(),
                r.getMotivo(), r.getPlazoProcesadoEn() == null, r.getRechazos(), r.getCreadaEn());
    }
}
