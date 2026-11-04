package com.hydra.pica.plataforma_pica.user.dto;

import java.time.Instant;

import com.hydra.pica.plataforma_pica.control.domain.Bloqueo;

/** El bloqueo vigente de un usuario, en su detalle. {@code hasta} null es indefinido. */
public record BloqueoDatos(String motivo, Instant desde, Instant hasta) {

    public static BloqueoDatos desde(Bloqueo bloqueo) {
        return new BloqueoDatos(bloqueo.getMotivo(), bloqueo.getDesde(), bloqueo.getHasta());
    }
}
