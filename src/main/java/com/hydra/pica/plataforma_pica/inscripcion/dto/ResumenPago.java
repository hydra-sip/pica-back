package com.hydra.pica.plataforma_pica.inscripcion.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;

/**
 * Lo que va a pagar quien tiene una inscripción pendiente de pago, sin comisiones. En una inscripción
 * individual el pagador es el participante; la de equipo suma el equipo (ticket 05).
 */
public record ResumenPago(
        Long inscripcionId,
        TorneoPago torneo,
        UsuarioPublico participante,
        BigDecimal monto,
        String moneda,
        int cupoMaximo,
        long cupoDisponible,
        Instant cierreInscripcionEn) {

    /** {@code confirmadas}: las inscripciones confirmadas del torneo, que son las que ocupan cupo. */
    public static ResumenPago de(Inscripcion inscripcion, long confirmadas) {
        Torneo torneo = inscripcion.getTorneo();
        return new ResumenPago(inscripcion.getId(), new TorneoPago(torneo.getId(), torneo.getNombre()),
                UsuarioPublico.desde(inscripcion.getUsuario()), torneo.getValorInscripcion(), torneo.getMoneda(),
                torneo.getCupoMaximo(), Math.max(0, torneo.getCupoMaximo() - confirmadas), torneo.getCierreInscripcionEn());
    }

    public record TorneoPago(Long id, String nombre) {
    }
}
