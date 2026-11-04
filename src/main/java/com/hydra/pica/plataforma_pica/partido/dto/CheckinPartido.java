package com.hydra.pica.plataforma_pica.partido.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.partido.domain.Lado;

/**
 * La ventana de check-in de un partido y el check-in de cada lado (HU-308).
 *
 * @param ventana       sin la tolerancia; null si el torneo no la configuró o el partido no tiene fecha
 * @param estadoVentana con la tolerancia aplicada
 * @param visitante     null hasta que se sabe quién juega
 * @param miLado        el lado del que es capitán quien consulta, si juega el partido
 */
public record CheckinPartido(
        VentanaCheckin ventana,
        EstadoVentana estadoVentana,
        LadoCheckin local,
        LadoCheckin visitante,
        Lado miLado) {

    public enum EstadoVentana {
        SIN_VENTANA,
        ANTES,
        ABIERTA,
        CERRADA
    }

    public record VentanaCheckin(Instant abreEn, Instant cierraEn) {
    }

    /** @param nombre el del equipo o, si se inscribe un jugador, su nombre y apellido */
    public record LadoCheckin(String nombre, Instant checkinEn) {
    }
}
