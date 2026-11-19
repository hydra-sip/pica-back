package com.hydra.pica.plataforma_pica.billetera.event;

import com.hydra.pica.plataforma_pica.billetera.domain.TipoCuenta;

/**
 * Publicado al registrar una cuenta bancaria o virtual de un usuario, ya sea validada o rechazada (HU-705).
 */
public record CuentaBancariaRegistrada(
        Long cuentaId,
        Long usuarioId,
        TipoCuenta tipo,
        String valor
) {
}
