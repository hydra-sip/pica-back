package com.hydra.pica.plataforma_pica.inscripcion.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.hydra.pica.plataforma_pica.inscripcion.domain.Inscripcion;
import com.hydra.pica.plataforma_pica.torneo.domain.GastosAdministrativos;
import com.hydra.pica.plataforma_pica.torneo.domain.Torneo;
import com.hydra.pica.plataforma_pica.user.dto.UsuarioPublico;

/**
 * Lo que va a pagar quien tiene una inscripción pendiente de pago: {@code monto} es el valor de la inscripción, que va
 * entero al torneo, y {@code total} le suma los gastos administrativos de la plataforma (PICA-283). La individual
 * lleva al {@code participante}; la de equipo lleva el {@code equipo} y no el participante: paga su capitán.
 */
public record ResumenPago(
        Long inscripcionId,
        TorneoPago torneo,
        UsuarioPublico participante,
        InscripcionDetalle.EquipoInscripto equipo,
        BigDecimal monto,
        BigDecimal gastosAdministrativos,
        BigDecimal total,
        String moneda,
        int cupoMaximo,
        long cupoDisponible,
        Instant cierreInscripcionEn) {

    /** {@code confirmadas}: las inscripciones confirmadas del torneo, que son las que ocupan cupo. */
    public static ResumenPago de(Inscripcion inscripcion, long confirmadas) {
        Torneo torneo = inscripcion.getTorneo();
        UsuarioPublico participante = null;
        InscripcionDetalle.EquipoInscripto equipo = null;
        if (inscripcion.esDeEquipo()) {
            equipo = new InscripcionDetalle.EquipoInscripto(inscripcion.getEquipo().getId(), inscripcion.getEquipo().getNombre());
        } else {
            participante = UsuarioPublico.desde(inscripcion.getUsuario());
        }
        return new ResumenPago(inscripcion.getId(), new TorneoPago(torneo.getId(), torneo.getNombre()), participante,
                equipo, torneo.getValorInscripcion(),
                GastosAdministrativos.monto(torneo.getValorInscripcion(), torneo.getPctGastosAdministrativos()),
                GastosAdministrativos.total(torneo.getValorInscripcion(), torneo.getPctGastosAdministrativos()),
                torneo.getMoneda(),
                torneo.getCupoMaximo(), Math.max(0, torneo.getCupoMaximo() - confirmadas), torneo.getCierreInscripcionEn());
    }

    public record TorneoPago(Long id, String nombre) {
    }
}
